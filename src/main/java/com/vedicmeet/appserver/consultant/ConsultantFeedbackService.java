package com.vedicmeet.appserver.consultant;

import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

/** Consultant-facing feedback list/flag/reply API from consultant/feedback.js. */
@Service
public class ConsultantFeedbackService {

    private final MongoTemplate mongo;

    public ConsultantFeedbackService(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    public List<Document> all(Document actor) {
        List<Document> pipeline = List.of(
                new Document("$match", new Document("consultant_id", actor.get("_id"))
                        .append("isActive", true).append("isDeleted", false)),
                new Document("$lookup", new Document("from", Collections.USERS)
                        .append("localField", "user_id").append("foreignField", "_id").append("as", "user")),
                new Document("$unwind", "$user"),
                new Document("$project", new Document("_id", 1).append("rating", 1)
                        .append("review", 1).append("consultant_id", 1).append("isFlagged", 1)
                        .append("replies", 1).append("user_id", 1).append("user.name", 1)
                        .append("feedback_type", 1).append("createdAt", 1)));
        return mongo.getCollection(Collections.FEEDBACKS).aggregate(pipeline).into(new ArrayList<>());
    }

    @Transactional(transactionManager = "mongoTransactionManager")
    public Document submitFlag(Document actor, String feedbackId, String reason) {
        if (!ObjectId.isValid(feedbackId)) throw new IllegalArgumentException("Feedback not found");
        ObjectId id = new ObjectId(feedbackId);
        Document ownership = new Document("_id", id).append("consultant_id", actor.get("_id"));
        Document feedback = mongo.getCollection(Collections.FEEDBACKS).find(ownership).first();
        if (feedback == null) throw new IllegalArgumentException("Feedback not found");

        String waitlistId = nestedText(feedback, "feedback_data", "waitlistId");
        boolean firstPurchase = firstPurchase(waitlistId);
        if (!firstPurchase && Boolean.TRUE.equals(feedback.get("isFlagged"))) {
            throw new IllegalStateException("Feedback already flagged");
        }

        Document claimed = mongo.getCollection(Collections.FEEDBACKS).findOneAndUpdate(
                firstPurchase ? ownership : new Document(ownership).append("isFlagged", new Document("$ne", true)),
                new Document("$set", new Document("isFlagged", true).append("updatedAt", new Date())
                        .append("isActive", firstPurchase ? false : feedback.get("isActive"))),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        if (claimed == null) throw new IllegalStateException("Feedback already flagged");

        Date now = new Date();
        Document temporary = new Document("feedback_id", feedbackId);
        if (firstPurchase) temporary.append("coupon", "first purchase coupon used!");
        Document flag = new Document("consultant_id", actor.get("_id"))
                .append("flag_type", "negative_feedback")
                .append("waitlist_id", waitlistId.isBlank() ? null : objectIdOrString(waitlistId))
                .append("temporary_data", temporary)
                .append("flag_status", firstPurchase ? "resolved" : "pending")
                .append("flag_reason", reason).append("createdAt", now).append("updatedAt", now);
        mongo.getCollection(Collections.FLAG_LOGS).insertOne(flag);
        if (firstPurchase) {
            mongo.getCollection(Collections.CONSULTANTS).updateOne(
                    new Document("_id", actor.get("_id")),
                    new Document("$inc", new Document("limit.flags.current", 1)));
        }
        return flag;
    }

    @Transactional(transactionManager = "mongoTransactionManager")
    public Document submitReply(Document actor, String feedbackId, String reply) {
        if (!ObjectId.isValid(feedbackId)) throw new IllegalArgumentException("Feedback not found");
        if (reply == null || reply.isBlank()) throw new IllegalArgumentException("Reply is required");
        ObjectId id = new ObjectId(feedbackId);
        Document ownership = new Document("_id", id).append("consultant_id", actor.get("_id"));
        Document feedback = mongo.getCollection(Collections.FEEDBACKS).find(ownership).first();
        if (feedback == null) throw new IllegalArgumentException("Feedback not found");
        String waitlistId = nestedText(feedback, "feedback_data", "waitlistId");
        if (firstPurchase(waitlistId)) {
            throw new IllegalStateException("Unpaid session feedbacks are not allowed!");
        }
        Document item = new Document("by", "consultant").append("reply", reply).append("createdAt", new Date());
        Document updated = mongo.getCollection(Collections.FEEDBACKS).findOneAndUpdate(ownership,
                new Document("$push", new Document("replies", item))
                        .append("$set", new Document("updatedAt", new Date())),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        if (updated == null) throw new IllegalArgumentException("Feedback not found");
        return updated;
    }

    private boolean firstPurchase(String waitlistId) {
        if (waitlistId == null || waitlistId.isBlank()) return false;
        Document waitlist = mongo.getCollection(Collections.WAITLISTS)
                .find(new Document("_id", objectIdOrString(waitlistId))).first();
        return waitlist != null && "first_purchase".equals(doc(waitlist.get("coupon")).getString("type"));
    }

    private String nestedText(Document root, String parent, String child) {
        return text(doc(root.get(parent)).get(child));
    }
    private Object objectIdOrString(String value) {
        return ObjectId.isValid(value) ? new ObjectId(value) : value;
    }
    private Document doc(Object value) {
        if (value instanceof Document d) return d;
        if (value instanceof Map<?, ?> map) return new Document((Map<String, Object>) map);
        return new Document();
    }
    private String text(Object value) { return value == null ? "" : String.valueOf(value); }
}
