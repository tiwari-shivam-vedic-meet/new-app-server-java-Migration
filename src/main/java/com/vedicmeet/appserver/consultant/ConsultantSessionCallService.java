package com.vedicmeet.appserver.consultant;

import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.UpdateOptions;
import com.vedicmeet.appserver.call.CallAcceptService;
import com.vedicmeet.appserver.call.CallIntegrationOutboxService;
import com.vedicmeet.appserver.config.AppConstants.Collections;
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
import java.util.UUID;

/**
 * Consultant-owned call controls from production {@code consultant/session.js}.
 * All state-changing waitlist queries are actor-scoped; the Node routes for notify/offline are not.
 */
@Service
public class ConsultantSessionCallService {

    private static final String COUPON_A = "67d195c835f0c73d7a488f7d";
    private static final String COUPON_B = "680218a0e65f3c49bcc45cc2";

    private final MongoTemplate mongo;
    private final CallAcceptService accept;
    private final CallIntegrationOutboxService outbox;

    public ConsultantSessionCallService(MongoTemplate mongo, CallAcceptService accept,
                                        CallIntegrationOutboxService outbox) {
        this.mongo = mongo;
        this.accept = accept;
        this.outbox = outbox;
    }

    /**
     * Node currently returns before executing both cancel and pick branches. Preserve that observable
     * contract rather than silently enabling previously-dead behavior during the migration.
     */
    public void callStatus(Document actor, String type, String appState) {
        requiredActor(actor);
        if ("cancel".equals(type) || "pick".equals(type)) return;
        if (!"voip_accepted".equals(type)) throw new IllegalArgumentException("Invalid call status type");

        String consultantId = text(actor.get("_id"));
        Document initiated = mongo.getCollection(Collections.CALL_INITIATED).findOneAndUpdate(
                new Document("consultantId", new Document("$in", idVariants(consultantId)))
                        .append("isAcceptedByConsultantForVOIP", false),
                new Document("$set", new Document("isAcceptedByConsultantForVOIP", true)),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.BEFORE));
        String roomId = roomId(initiated);
        if ("opened".equals(appState) && roomId != null) {
            accept.acceptCall(roomId, null, consultantId, null);
        }
    }

    public Map<String, Object> acceptIncoming(Document actor, String roomId, String type) {
        String consultantId = requiredActor(actor);
        if (blank(roomId)) throw new IllegalArgumentException("roomId is required");
        CallAcceptService.AcceptOutcome outcome = accept.acceptCall(roomId, null, consultantId, type);
        if (outcome != CallAcceptService.AcceptOutcome.PROGRESS_STARTED
                && outcome != CallAcceptService.AcceptOutcome.ALREADY_ACCEPTED) return null;
        Document waitlist = mongo.getCollection(Collections.WAITLISTS)
                .find(actorWaitlist(roomId, consultantId)).first();
        return waitlist == null ? null : joinPayload(waitlist,
                outcome == CallAcceptService.AcceptOutcome.PROGRESS_STARTED);
    }

    /** Node calls this "block" but it submits an admin-review request rather than blocking now. */
    public void requestBlock(Document actor, String waitlistId, String reason) {
        String consultantId = requiredActor(actor);
        Document waitlist = mongo.getCollection(Collections.WAITLISTS)
                .find(actorWaitlist(waitlistId, consultantId)).first();
        if (waitlist == null) throw new IllegalArgumentException("Waitlist not found");
        Object userId = MongoIds.id(waitlist.get("user_id"));
        Object consId = MongoIds.id(consultantId);
        Date now = new Date();
        mongo.getCollection(Collections.USER_CONS_RELS).updateOne(
                new Document("userId", userId).append("consId", consId),
                new Document("$set", new Document("forCons.isUserBlocked", false)
                        .append("forCons.isAdminVerify", false)
                        .append("forCons.blockReason", reason).append("updatedAt", now))
                        .append("$setOnInsert", new Document("createdAt", now)),
                new UpdateOptions().upsert(true));
    }

    @Transactional(transactionManager = "mongoTransactionManager")
    public Document notifyUserToConnect(Document actor, String waitlistId) {
        String consultantId = requiredActor(actor);
        Document waitlist = mongo.getCollection(Collections.WAITLISTS).findOneAndUpdate(
                actorWaitlist(waitlistId, consultantId),
                new Document("$push", new Document("logs", new Document("callStatus",
                        "consultant notified user to connect").append("timestamp", System.currentTimeMillis()))),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        if (waitlist == null) throw new IllegalArgumentException("Waitlist not found");
        enqueueInitiate(waitlist, consultantId, "notify");
        return waitlist;
    }

    @Transactional(transactionManager = "mongoTransactionManager")
    public Document takeOfflineSession(Document actor, String waitlistId, boolean quickConnect) {
        String consultantId = requiredActor(actor);
        Document waitlist = mongo.getCollection(Collections.WAITLISTS).findOneAndUpdate(
                actorWaitlist(waitlistId, consultantId),
                new Document("$set", new Document("session_info.isOfflineSession", true)
                        .append("session_info.isQuickCallConnect", quickConnect))
                        .append("$push", new Document("logs", new Document("callStatus",
                                "offline session tried").append("timestamp", System.currentTimeMillis()))),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        if (waitlist == null) throw new IllegalArgumentException("Waitlist not found");

        String mode = text(doc(waitlist.get("session_info")).get("mode"));
        String liveField = switch (mode) {
            case "chat" -> "sessionsStatus.isChatLive";
            case "audio" -> "sessionsStatus.isVoiceLive";
            case "video" -> "sessionsStatus.isVideoLive";
            default -> null;
        };
        if (liveField != null) {
            mongo.getCollection(Collections.CONSULTANTS).updateOne(
                    new Document("_id", actor.get("_id")),
                    new Document("$set", new Document(liveField, true)));
        }
        enqueueInitiate(waitlist, consultantId, "offline");
        return waitlist;
    }

    private void enqueueInitiate(Document waitlist, String consultantId, String source) {
        String waitlistId = text(waitlist.get("_id"));
        Document info = doc(waitlist.get("session_info"));
        outbox.enqueue("CALL_INITIATE:" + source + ":" + waitlistId + ":" + UUID.randomUUID(),
                "CALL_INITIATE", new Document("waitlistId", waitlistId)
                        .append("userId", text(waitlist.get("user_id")))
                        .append("consultantId", consultantId)
                        .append("callMode", text(info.get("mode"))));
    }

    private Map<String, Object> joinPayload(Document waitlist, boolean phaseTwo) {
        Document info = doc(waitlist.get("session_info"));
        String mode = text(info.get("mode"));
        String coupon = couponId(waitlist);
        List<String> access = new ArrayList<>(List.of("chat"));
        if (COUPON_A.equals(coupon)) {
            if (phaseTwo) access.add("audio");
        } else if ("session".equals(text(waitlist.get("used_for"))) || "video".equals(mode)) {
            access.add("audio");
            access.add("video");
        } else if ("audio".equals(mode) || COUPON_B.equals(coupon) || "chat".equals(mode)) {
            access.add("audio");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("waitlistId", text(waitlist.get("_id")));
        payload.put("roomId", waitlist.get("threadId"));
        payload.put("channel", mode);
        payload.put("accessbilities", access); // Node/mobile spelling is contractual.
        return payload;
    }

    private String couponId(Document waitlist) {
        Document coupon = doc(waitlist.get("coupon"));
        return coupon.get("_id") == null ? null : text(coupon.get("_id"));
    }

    private Document actorWaitlist(String waitlistId, String consultantId) {
        if (blank(waitlistId) || !ObjectId.isValid(waitlistId)) {
            throw new IllegalArgumentException("Valid waitlistId is required");
        }
        return new Document("_id", new ObjectId(waitlistId))
                .append("consultant_id", new Document("$in", idVariants(consultantId)));
    }

    private List<Object> idVariants(String value) {
        List<Object> variants = new ArrayList<>();
        variants.add(value);
        if (ObjectId.isValid(value)) variants.add(new ObjectId(value));
        return variants;
    }

    private String roomId(Document initiated) {
        if (initiated == null) return null;
        Document consultantPayload = doc(initiated.get("consultantPayload"));
        Document userPayload = doc(initiated.get("userPayload"));
        Object value = consultantPayload.get("sessionId");
        if (value == null) value = userPayload.get("sessionId");
        if (value == null) value = initiated.get("roomId");
        return value == null ? null : text(value);
    }

    private String requiredActor(Document actor) {
        String id = actor == null ? "" : text(actor.get("_id"));
        if (blank(id)) throw new IllegalStateException("Consultant not found");
        return id;
    }
    private static Document doc(Object value) {
        if (value instanceof Document d) return d;
        if (value instanceof Map<?, ?> map) return new Document((Map<String, Object>) map);
        return new Document();
    }
    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
