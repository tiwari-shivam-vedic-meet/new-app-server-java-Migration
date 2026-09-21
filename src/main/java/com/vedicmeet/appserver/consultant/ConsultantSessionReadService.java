package com.vedicmeet.appserver.consultant;

import com.vedicmeet.appserver.config.AppConstants.Collections;
import com.vedicmeet.appserver.support.ChatServerClient;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read/query half of production {@code rest-apis/modules/consultant/session.js}. */
@Service
public class ConsultantSessionReadService {

    private final MongoTemplate mongo;
    private final ChatServerClient chat;

    public ConsultantSessionReadService(MongoTemplate mongo, ChatServerClient chat) {
        this.mongo = mongo;
        this.chat = chat;
    }

    /** Node GET /cons/session/init creates/reuses an admin chat thread and returns seed settings. */
    public Map<String, Object> initialize(Document actor) {
        Document settings = mongo.getCollection(Collections.SEED_MASTERS)
                .find(new Document("for", "consultantMasterSettings")).first();
        Map<String, Object> consultant = new LinkedHashMap<>();
        consultant.put("_id", actor.get("_id"));
        consultant.put("name", actor.get("accountName"));
        consultant.put("profileImage", actor.get("profileImage"));
        Map<String, Object> admin = new LinkedHashMap<>();
        admin.put("_id", "000000000000000000000000");
        admin.put("name", "Vedicmeet Admin");
        admin.put("profileImage", "https://vedicmeet.com/statics/vedic-meet_title-logo.webp");
        Map<String, Object> response = chat.createThreadAdmin(Map.of("user", consultant, "admin", admin));
        if (!Boolean.TRUE.equals(response.get("success"))) {
            throw new IllegalStateException("Failed to create thread!");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("threadId", response.get("data"));
        result.put("consultantMasterSettings", settings == null ? null : settings.get("data"));
        return result;
    }

    public Object liveEvent(Document actor, String eventId) {
        if (!blank(eventId)) {
            Document event = mongo.getCollection(Collections.CONSULTANT_LIVE_EVENTS)
                    .find(new Document("_id", id(eventId))).first();
            if (event == null) throw new IllegalArgumentException("Event not found");
            return event;
        }
        return mongo.getCollection(Collections.CONSULTANT_LIVE_EVENTS)
                .find(new Document("consultant_id", actor.get("_id"))).into(new ArrayList<>());
    }

    public List<Document> waitlistHistory(Document actor, String rawType, String rawConsultationType) {
        String consultantId = text(actor.get("_id"));
        String consultationType = List.of("private_call", "session", "query").contains(rawConsultationType)
                ? rawConsultationType : "private_call";
        String type = blank(rawType) ? "all" : rawType;
        Instant since = Instant.now().minus("query".equals(consultationType) ? 24 : 7 * 24, ChronoUnit.HOURS);
        Document match = new Document("createdAt", new Document("$gte", Date.from(since)));
        if ("boost".equals(type)) match.put("session_info.isBoosted", true);
        else if ("consultation".equals(type)) match.put("session_info.isBoosted", new Document("$ne", true));

        String collection = Collections.WAITLISTS;
        switch (consultationType) {
            case "query" -> match.append("used_for", "query")
                    .append("consultant_id", new Document("$in", Arrays.asList(null, consultantId)))
                    .append("status", "completed");
            case "session" -> {
                collection = Collections.FIXED_SESSION_WAITLISTS;
                match.append("for", "fixed_session")
                        .append("consultant_id", new Document("$in", Arrays.asList(null, actor.get("_id"))))
                        .append("status", "waiting");
            }
            default -> match.append("used_for", "private_call").append("consultant_id", consultantId)
                    .append("status", new Document("$in", List.of("completed", "canceled", "blocked")));
        }
        return mongo.getCollection(collection).aggregate(List.of(
                new Document("$match", match),
                lookupUser(), lookupRelation(),
                new Document("$addFields", new Document("isUserDeleted", firstOr("$userDetails.isDeleted", false))
                        .append("isBlocked", firstOr("$userConsRel.forCons.isUserBlocked", false))),
                new Document("$project", new Document("userConsRel", 0).append("userDetails", 0)),
                new Document("$sort", new Document("createdAt", -1))
        )).into(new ArrayList<>());
    }

    public List<Document> activeWaitlist(Document actor) {
        String consultantId = text(actor.get("_id"));
        return mongo.getCollection(Collections.WAITLISTS).aggregate(List.of(
                new Document("$match", new Document("consultant_id", consultantId)
                        .append("status", new Document("$in", List.of("waiting", "progress", "missed")))),
                lookupRelation(),
                new Document("$addFields", new Document("isBlocked",
                        firstOr("$userConsRel.forCons.isUserBlocked", false))),
                new Document("$project", new Document("userConsRel", 0)),
                new Document("$sort", new Document("createdAt", 1))
        )).into(new ArrayList<>());
    }

    private Document lookupUser() {
        return new Document("$lookup", new Document("from", Collections.USERS)
                .append("let", new Document("userId", convertObjectId("$user_id")))
                .append("pipeline", List.of(new Document("$match", new Document("$expr",
                        new Document("$eq", List.of("$_id", "$$userId"))))))
                .append("as", "userDetails"));
    }

    private Document lookupRelation() {
        return new Document("$lookup", new Document("from", Collections.USER_CONS_RELS)
                .append("let", new Document("userId", convertObjectId("$user_id"))
                        .append("consultantId", convertObjectId("$consultant_id")))
                .append("pipeline", List.of(new Document("$match", new Document("$expr",
                        new Document("$and", List.of(
                                new Document("$eq", List.of("$userId", "$$userId")),
                                new Document("$eq", List.of("$consId", "$$consultantId"))))))))
                .append("as", "userConsRel"));
    }

    private Document convertObjectId(String input) {
        return new Document("$convert", new Document("input", input).append("to", "objectId")
                .append("onError", null).append("onNull", null));
    }

    private Document firstOr(String path, Object fallback) {
        return new Document("$cond", new Document("if", new Document("$gt", List.of(
                new Document("$size", new Document("$ifNull", List.of(
                        path.substring(0, path.indexOf('.', 1)), List.of()))), 0)))
                .append("then", new Document("$arrayElemAt", List.of(path, 0)))
                .append("else", fallback));
    }

    private Object id(String value) { return ObjectId.isValid(value) ? new ObjectId(value) : value; }
    private String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private boolean blank(String value) { return value == null || value.isBlank(); }
}
