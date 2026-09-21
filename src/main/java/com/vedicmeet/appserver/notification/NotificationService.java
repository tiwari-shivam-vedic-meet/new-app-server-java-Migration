package com.vedicmeet.appserver.notification;

import com.vedicmeet.appserver.config.AppConstants.Collections;
import com.mongodb.client.result.UpdateResult;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Faithful port of Node NotificationService.listUser + countUser
 * (utils/classes/notification.js), served by GET /v1/notification and /count
 * (authed: user OR consultant).
 *
 * countUser reproduces a Node quirk: the query's `receiverId` clause uses `user.id`
 * (undefined in Node) which Mongoose strips, so the $or effectively matches all in the
 * date range that the user hasn't read. Preserved intentionally (identical logic).
 */
@Service
public class NotificationService {

    private final MongoTemplate mongo;

    public NotificationService(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    // ---- Prompt C writes (modules/notification.js mark_read + mark_all_read). Diff-pending. ----

    /**
     * markRead (utils/classes/notification.js L543) — findOne by _id; if it exists, $push the
     * caller into readByReceiver. Returns the PRE-update document (Node returns isReadExit, the
     * pre-image, even though it uses findOneAndUpdate). Idempotency: none in Node — repeated calls
     * push the same id again (duplicates in readByReceiver); preserved (no guard added).
     */
    public Document markRead(Document user, String notificationId) {
        Document isReadExit = mongo.getCollection(Collections.NOTIFICATIONS)
                .find(new Document("_id", new ObjectId(notificationId))).first();
        if (isReadExit != null) {
            mongo.getCollection(Collections.NOTIFICATIONS).updateOne(
                    new Document("_id", new ObjectId(notificationId)),
                    new Document("$push", new Document("readByReceiver", user.getObjectId("_id"))));
        }
        return isReadExit;
    }

    /**
     * markAllAsRead (utils/classes/notification.js L352) — updateMany $addToSet (idempotent by
     * construction) over notifications the caller receives and hasn't read. Returns the driver
     * update result mirrored to Mongoose's {acknowledged, matchedCount, modifiedCount, ...} shape.
     */
    public Map<String, Object> markAllAsRead(Document user) {
        ObjectId userId = user.getObjectId("_id");
        UpdateResult result = mongo.getCollection(Collections.NOTIFICATIONS).updateMany(
                new Document("$or", Arrays.asList(
                        new Document("receiverId", userId),
                        new Document("adminReceiverId", new Document("$in", Arrays.asList(userId)))))
                        .append("readByReceiver", new Document("$nin", Arrays.asList(userId))),
                new Document("$addToSet", new Document("readByReceiver", userId)));

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("acknowledged", result.wasAcknowledged());
        res.put("matchedCount", result.getMatchedCount());
        res.put("modifiedCount", result.getModifiedCount());
        res.put("upsertedId", result.getUpsertedId() == null ? null : result.getUpsertedId());
        res.put("upsertedCount", 0);
        return res;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> listUser(Document user, Integer pageParam, Integer limitParam, String search) {
        int page = pageParam == null ? 1 : pageParam;
        int limit = limitParam == null ? 10 : limitParam;
        int skipIndex = (page - 1) * limit;
        ObjectId userId = user.getObjectId("_id");

        Date startDate = user.get("createdAt") instanceof Date
                ? (Date) user.get("createdAt")
                : new Date(System.currentTimeMillis() - 24L * 60 * 60 * 1000);
        Date endDate = new Date(System.currentTimeMillis() + 24L * 60 * 60 * 1000);

        List<Document> pipeline = Arrays.asList(
                new Document("$match", new Document("createdAt",
                        new Document("$gte", startDate).append("$lte", endDate))),
                new Document("$match", new Document("$or", Arrays.asList(
                        new Document("receiverId", userId),
                        new Document("adminReceiverId", new Document("$in", Arrays.asList(userId)))))),
                new Document("$addFields", new Document("isRead", new Document("$cond",
                        new Document("if", new Document("$in", Arrays.asList(userId, "$readByReceiver")))
                                .append("then", true).append("else", false)))),
                new Document("$project", new Document("title", 1).append("message", 1)
                        .append("createdAt", 1).append("isRead", 1).append("data", 1)),
                new Document("$facet", new Document()
                        .append("list", Arrays.asList(
                                new Document("$sort", new Document("createdAt", -1)),
                                new Document("$skip", skipIndex),
                                new Document("$limit", limit)))
                        .append("count", Arrays.asList(new Document("$count", "total")))));

        List<Document> res = mongo.getCollection(Collections.NOTIFICATIONS).aggregate(pipeline).into(new ArrayList<>());
        List<Document> list = new ArrayList<>();
        long total = 0;
        if (!res.isEmpty()) {
            List<Document> l = (List<Document>) res.get(0).get("list");
            if (l != null) list = l;
            List<Document> count = (List<Document>) res.get(0).get("count");
            if (count != null && !count.isEmpty()) {
                Number t = count.get(0).get("total", Number.class);
                total = t == null ? 0 : t.longValue();
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", list);
        result.put("total", total);
        return result;
    }

    public long countUser(Document user) {
        ObjectId userId = user.getObjectId("_id");
        Date startDate = user.get("createdAt") instanceof Date ? (Date) user.get("createdAt")
                : (user.get("created_at") instanceof Date ? (Date) user.get("created_at") : new Date(0));
        Date endDate = new Date(System.currentTimeMillis() + 24L * 60 * 60 * 1000);

        // Node's receiverId clause is `user.id` (undefined) -> stripped -> {} matches all.
        Document query = new Document("createdAt", new Document("$gte", startDate).append("$lte", endDate))
                .append("$and", Arrays.asList(
                        new Document("$or", Arrays.asList(
                                new Document(),                                   // {} (Node's undefined receiverId)
                                new Document("adminReceiverId", new Document("$in", Arrays.asList(userId))))),
                        new Document("readByReceiver", new Document("$nin", Arrays.asList(userId)))));
        return mongo.getCollection(Collections.NOTIFICATIONS).countDocuments(query);
    }
}
