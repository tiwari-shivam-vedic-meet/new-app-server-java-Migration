package com.vedicmeet.appserver.call;

import com.vedicmeet.appserver.config.AppConstants.Collections;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;

/** Mongo transaction for the free-consultation handoff after chat-thread creation succeeds. */
@Service
public class FreeConsultationMongoService {

    private final MongoTemplate mongo;

    public FreeConsultationMongoService(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Transactional(transactionManager = "mongoTransactionManager")
    public Document createReplacement(String oldRoomId, String newRoomId,
                                      String consultantId, String threadId) {
        Object newId = id(newRoomId);
        Document existing = mongo.getCollection(Collections.WAITLISTS)
                .find(new Document("_id", newId)).first();
        if (existing != null) return existing;

        Document old = mongo.getCollection(Collections.WAITLISTS)
                .find(new Document("_id", id(oldRoomId))).first();
        if (old == null) throw new IllegalStateException("FREE_CONTINUATION_WAITLIST_NOT_FOUND");

        Date now = new Date();
        mongo.getCollection(Collections.WAITLISTS).updateOne(
                new Document("_id", id(oldRoomId)),
                new Document("$set", new Document("status", "canceled").append("updatedAt", now))
                        .append("$push", new Document("logs", new Document("callBy", "system")
                                .append("callStatus", "canceled due to consultant unavailability, calling next consultant and they are busy")
                                .append("timestamp", now))));

        Document replacement = new Document(old);
        replacement.put("_id", newId);
        replacement.put("status", "waiting");
        replacement.put("threadId", threadId);
        replacement.put("consultant_id", consultantId);
        replacement.put("logs", List.of(
                new Document("callBy", "system").append("callStatus", "initiated").append("timestamp", now),
                new Document("callBy", "system-free-chat-continuation")
                        .append("callStatus", "initiated").append("timestamp", now)));
        replacement.put("createdAt", now);
        replacement.put("updatedAt", now);
        mongo.getCollection(Collections.WAITLISTS).insertOne(replacement);
        return replacement;
    }

    private Object id(String value) {
        return ObjectId.isValid(value) ? new ObjectId(value) : value;
    }
}
