package com.vedicmeet.appserver.call;

import com.mongodb.client.AggregateIterable;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import com.vedicmeet.appserver.realtime.SocketEventPublisher;
import com.vedicmeet.appserver.support.ChatServerClient;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Ports Node BusinessLogic.callToNextConsultantAsItFree with retry-safe checkpoints. */
@Service
public class FreeConsultationContinuationService {

    private final MongoTemplate mongo;
    private final CallIntegrationOutboxService outbox;
    private final FreeConsultationMongoService transaction;
    private final ChatServerClient chat;
    private final CallLifecycleService lifecycle;
    private final SocketEventPublisher events;

    public FreeConsultationContinuationService(MongoTemplate mongo,
                                               CallIntegrationOutboxService outbox,
                                               FreeConsultationMongoService transaction,
                                               ChatServerClient chat,
                                               CallLifecycleService lifecycle,
                                               SocketEventPublisher events) {
        this.mongo = mongo;
        this.outbox = outbox;
        this.transaction = transaction;
        this.chat = chat;
        this.lifecycle = lifecycle;
        this.events = events;
    }

    public void process(CallIntegrationOutboxService.ClaimedJob job) {
        Document old = byId(Collections.WAITLISTS, job.payload().get("_id"));
        if (old == null) throw new IllegalStateException("FREE_CONTINUATION_WAITLIST_NOT_FOUND");
        String userId = text(old.get("user_id"));
        if (rapidCallLimitReached(userId)) return;

        Document work = doc(job.raw().get("work"));
        String consultantId = text(work.get("consultantId"));
        Document selected = consultantId.isBlank() ? selectConsultant(userId) : byId(Collections.CONSULTANTS, consultantId);
        if (selected == null) return;
        consultantId = text(selected.get("_id"));
        if (text(work.get("consultantId")).isBlank()) outbox.updateWork(job.id(), "consultantId", consultantId);

        Document user = byId(Collections.USERS, userId);
        if (user == null) throw new IllegalStateException("FREE_CONTINUATION_USER_NOT_FOUND");

        String newRoomId = text(work.get("newWaitlistId"));
        if (newRoomId.isBlank()) {
            newRoomId = new ObjectId().toHexString();
            outbox.updateWork(job.id(), "newWaitlistId", newRoomId);
        }

        String threadId = text(work.get("threadId"));
        if (threadId.isBlank()) {
            Map<String, Object> response = chat.createThread(createThreadPayload(user, selected));
            if (!Boolean.TRUE.equals(response.get("success"))) {
                throw new IllegalStateException("FREE_CONTINUATION_THREAD_CREATE_FAILED");
            }
            threadId = responseData(response);
            if (threadId.isBlank()) throw new IllegalStateException("FREE_CONTINUATION_THREAD_ID_MISSING");
            outbox.updateWork(job.id(), "threadId", threadId);
        }

        Document replacement = transaction.createReplacement(text(old.get("_id")), newRoomId,
                consultantId, threadId);
        if (!outbox.stepCompleted(job, "first-message-form")) {
            chat.feedFirstMessageForm(Map.of("threadId", threadId,
                    "form", value(replacement.get("request_form"), Map.of())));
            outbox.markStep(job.id(), "first-message-form");
        }
        if (!outbox.stepCompleted(job, "replacement-call")) {
            events.emitToRoom(userId, "call_missed_calling_next_consultant", Map.of());
            lifecycle.initiateCall(userId, consultantId, newRoomId,
                    doc(replacement.get("session_info")).getString("mode"));
            outbox.markStep(job.id(), "replacement-call");
        }
    }

    private boolean rapidCallLimitReached(String userId) {
        ZoneId zone = ZoneId.systemDefault();
        Date start = Date.from(LocalDate.now(zone).atStartOfDay(zone).toInstant());
        Date end = Date.from(LocalDate.now(zone).plusDays(1).atStartOfDay(zone).minusNanos(1).toInstant());
        List<Document> calls = mongo.getCollection(Collections.WAITLISTS)
                .find(new Document("user_id", userId)
                        .append("createdAt", new Document("$gte", start).append("$lte", end)))
                .sort(new Document("createdAt", 1)).into(new ArrayList<>());
        for (int i = 0; i + 2 < calls.size(); i++) {
            Date one = calls.get(i).getDate("createdAt");
            Date two = calls.get(i + 1).getDate("createdAt");
            Date three = calls.get(i + 2).getDate("createdAt");
            if (one != null && two != null && three != null
                    && two.getTime() - one.getTime() < 120_000
                    && three.getTime() - two.getTime() < 120_000) return true;
        }
        return false;
    }

    private Document selectConsultant(String userId) {
        List<String> priorIds = mongo.getCollection(Collections.WAITLISTS)
                .find(new Document("user_id", userId))
                .projection(new Document("consultant_id", 1))
                .map(d -> text(d.get("consultant_id"))).into(new ArrayList<>());
        List<Object> excluded = priorIds.stream().filter(ObjectId::isValid).map(ObjectId::new).map(v -> (Object) v).toList();

        List<Document> pipeline = new ArrayList<>();
        Document match = new Document("sessionsStatus.isChatLive", true).append("status", true);
        if (!excluded.isEmpty()) match.append("_id", new Document("$nin", excluded));
        pipeline.add(new Document("$match", match));
        pipeline.add(new Document("$lookup", new Document("from", Collections.WAITLISTS)
                .append("let", new Document("consultantId", new Document("$toString", "$_id")))
                .append("pipeline", List.of(new Document("$match", new Document("$expr",
                        new Document("$eq", List.of("$consultant_id", "$$consultantId"))))))
                .append("as", "waitlistEntries")));
        pipeline.add(new Document("$match", new Document("waitlistEntries",
                new Document("$not", new Document("$elemMatch", new Document("status", "progress"))))));
        pipeline.add(new Document("$addFields", new Document("waitlistCount",
                new Document("$size", "$waitlistEntries"))));
        pipeline.add(new Document("$sort", new Document("waitlistCount", 1)));
        pipeline.add(new Document("$limit", 1));
        AggregateIterable<Document> result = mongo.getCollection(Collections.CONSULTANTS).aggregate(pipeline);
        return result.first();
    }

    private Map<String, Object> createThreadPayload(Document user, Document consultant) {
        Map<String, Object> u = new LinkedHashMap<>();
        u.put("_id", text(user.get("_id")));
        u.put("profileImage", user.get("profileImage"));
        u.put("name", user.get("name"));
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("_id", text(consultant.get("_id")));
        c.put("profileImage", consultant.get("profileImage"));
        c.put("name", consultant.get("accountName"));
        return Map.of("user", u, "cons", c);
    }

    @SuppressWarnings("unchecked")
    private String responseData(Map<String, Object> response) {
        Object data = response.get("data");
        if (data instanceof Map<?, ?> map && map.get("threadId") != null) return text(map.get("threadId"));
        return text(data);
    }

    private Document byId(String collection, Object raw) {
        Object key = raw;
        String value = text(raw);
        if (!(raw instanceof ObjectId) && ObjectId.isValid(value)) key = new ObjectId(value);
        return mongo.getCollection(collection).find(new Document("_id", key)).first();
    }

    private Document doc(Object value) { return value instanceof Document d ? d : new Document(); }
    private Object value(Object first, Object fallback) { return first == null ? fallback : first; }
    private String text(Object value) { return value == null ? "" : String.valueOf(value); }
}
