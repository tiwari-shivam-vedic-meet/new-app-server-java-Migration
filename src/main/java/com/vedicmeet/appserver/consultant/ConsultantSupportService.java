package com.vedicmeet.appserver.consultant;

import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.UpdateOptions;
import com.vedicmeet.appserver.call.CallIntegrationOutboxService;
import com.vedicmeet.appserver.config.AppConstants;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import com.vedicmeet.appserver.support.ChatServerClient;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Consultant leave-message and quick-note APIs from consultant/support.js. */
@Service
public class ConsultantSupportService {

    private final MongoTemplate mongo;
    private final ChatServerClient chat;
    private final CallIntegrationOutboxService outbox;
    private final AppConstants constants;

    public ConsultantSupportService(MongoTemplate mongo, ChatServerClient chat,
                                    CallIntegrationOutboxService outbox, AppConstants constants) {
        this.mongo = mongo;
        this.chat = chat;
        this.outbox = outbox;
        this.constants = constants;
    }

    @Transactional(transactionManager = "mongoTransactionManager")
    public String openLeaveMessage(Document consultant, String userId, boolean notify) {
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("userId is required");
        String consultantId = id(consultant);
        Document completed = mongo.getCollection(Collections.WAITLISTS).find(
                new Document("user_id", userId).append("consultant_id", consultantId)
                        .append("status", "completed")).sort(Sorts.descending("createdAt")).first();
        if (completed == null) {
            throw new IllegalStateException("You have not completed your first consultation with this consultant. Please complete your first consultation to leave a message.");
        }
        Document user = findById(Collections.USERS, userId);
        if (user == null) throw new IllegalArgumentException("User not found");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("cons", Map.of(
                "_id", consultantId,
                "name", text(consultant.get("accountName")),
                "profileImage", text(consultant.get("profileImage"))));
        payload.put("user", Map.of(
                "_id", userId,
                "name", text(user.get("name")),
                "profileImage", media(user.get("profileImage"))));
        Map<String, Object> response = chat.createThreadLeaveMessage(payload);
        String threadId = text(response.get("data"));
        if (threadId.isBlank()) throw new IllegalStateException("CHAT_THREAD_NOT_CREATED");

        Date now = new Date();
        Document naturalKey = new Document("user_id", userId).append("consultant_id", consultantId)
                .append("isDeleted", false);
        Document insert = new Document(naturalKey).append("userMessageLimit", 5)
                .append("consMessageLimit", 5).append("isActive", true).append("createdAt", now);
        Document mapping = mongo.getCollection(Collections.CONSULTANT_LEAVE_MESSAGE_MAPPINGS)
                .findOneAndUpdate(naturalKey,
                        new Document("$setOnInsert", insert)
                                .append("$set", new Document("thread_id", threadId)
                                        .append("waitlist_id", text(completed.get("_id")))
                                        .append("updatedAt", now)),
                        new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER));

        if (notify) {
            String title = "New message from " + text(consultant.get("accountName"));
            String body = text(consultant.get("accountName")) + " wants to leave you a message.";
            outbox.enqueue("CONSULTANT_LEAVE_MESSAGE:" + text(mapping.get("_id")) + ":" + now.getTime(),
                    "APP_PUSH_NOTIFICATION",
                    new Document("targetType", "user").append("targetId", userId)
                            .append("title", title).append("body", body)
                            .append("data", new Document("screen", "post-consultation-chat")
                                    .append("waitlistId", text(completed.get("_id")))
                                    .append("status", "completed").append("roomId", threadId)
                                    .append("consultantId", consultantId)));
        }
        return threadId;
    }

    public List<Document> quickNotes(Document consultant) {
        return mongo.getCollection(Collections.CONSULTANT_QUICK_NOTES_MESSAGES)
                .find(new Document("consultant_id", id(consultant))
                        .append("isDeleted", new Document("$ne", true)))
                .sort(Sorts.descending("createdAt")).into(new ArrayList<>());
    }

    public Document createQuickNote(Document consultant, String note) {
        String normalized = note == null ? "" : note.trim();
        if (normalized.isBlank()) throw new IllegalArgumentException("Note content is required");
        Date now = new Date();
        Document value = new Document("consultant_id", id(consultant)).append("note", normalized)
                .append("isDeleted", false).append("createdAt", now).append("updatedAt", now);
        mongo.getCollection(Collections.CONSULTANT_QUICK_NOTES_MESSAGES).insertOne(value);
        return value;
    }

    public Document updateQuickNote(Document consultant, String noteId, String note) {
        String normalized = note == null ? "" : note.trim();
        if (normalized.isBlank()) throw new IllegalArgumentException("Note content is required");
        Document updated = mongo.getCollection(Collections.CONSULTANT_QUICK_NOTES_MESSAGES)
                .findOneAndUpdate(noteFilter(consultant, noteId),
                        new Document("$set", new Document("note", normalized).append("updatedAt", new Date())),
                        new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        if (updated == null) throw new IllegalArgumentException("Note not found");
        return updated;
    }

    public void deleteQuickNote(Document consultant, String noteId) {
        Document updated = mongo.getCollection(Collections.CONSULTANT_QUICK_NOTES_MESSAGES)
                .findOneAndUpdate(noteFilter(consultant, noteId),
                        new Document("$set", new Document("isDeleted", true).append("updatedAt", new Date())),
                        new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        if (updated == null) throw new IllegalArgumentException("Note not found");
    }

    private Document noteFilter(Document consultant, String noteId) {
        if (!ObjectId.isValid(noteId)) return new Document("_id", null);
        return new Document("_id", new ObjectId(noteId)).append("consultant_id", id(consultant))
                .append("isDeleted", new Document("$ne", true));
    }

    private Document findById(String collection, String value) {
        return mongo.getCollection(collection).find(new Document("_id", objectIdOrString(value))).first();
    }
    private Object objectIdOrString(String value) {
        return ObjectId.isValid(value) ? new ObjectId(value) : value;
    }
    private String media(Object value) {
        String path = text(value);
        return path.isBlank() || path.startsWith("http") ? path : constants.mediaUrl + path;
    }
    private String id(Document actor) {
        Object value = actor.get("_id");
        return value instanceof ObjectId id ? id.toHexString() : text(value);
    }
    private String text(Object value) { return value == null ? "" : String.valueOf(value); }
}
