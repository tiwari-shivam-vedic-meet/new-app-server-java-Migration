package com.vedicmeet.appserver.session;

import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.vedicmeet.appserver.call.CallAcceptService;
import com.vedicmeet.appserver.call.CallCancelService;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mobile HTTP bridge to the same atomic call state machine used by Socket.IO.
 * Ports user/session.js {@code call_status} and {@code accept_incoming_session_request}.
 */
@Service
public class UserSessionCallService {
    private static final String COUPON_A = "67d195c835f0c73d7a488f7d";
    private static final String COUPON_B = "680218a0e65f3c49bcc45cc2";

    private final MongoTemplate mongo;
    private final CallAcceptService accept;
    private final CallCancelService cancel;

    public UserSessionCallService(MongoTemplate mongo, CallAcceptService accept, CallCancelService cancel) {
        this.mongo = mongo;
        this.accept = accept;
        this.cancel = cancel;
    }

    public Document callStatus(String userId, String type, String appState) {
        if ("cancel".equals(type)) {
            Document initiated = mongo.getCollection(Collections.CALL_INITIATED).findOneAndDelete(
                    new Document("userId", userId).append("isAcceptedByUser", false));
            String roomId = roomId(initiated);
            if (roomId != null) cancel.cancelCall(roomId, userId, null);
            return null;
        }

        if ("pick".equals(type)) {
            Document initiated = mongo.getCollection(Collections.CALL_INITIATED).findOneAndUpdate(
                    new Document("userId", userId).append("isAcceptedByUser", false),
                    new Document("$set", new Document("isAcceptedByUser", true)),
                    new FindOneAndUpdateOptions().returnDocument(ReturnDocument.BEFORE));
            String roomId = roomId(initiated);
            if (roomId != null) accept.acceptCall(roomId, userId, null, null);
            return initiated;
        }

        if ("voip_accepted".equals(type)) {
            Document initiated = mongo.getCollection(Collections.CALL_INITIATED).findOneAndUpdate(
                    new Document("userId", userId).append("isAcceptedByUserForVOIP", false),
                    new Document("$set", new Document("isAcceptedByUserForVOIP", true)),
                    new FindOneAndUpdateOptions().returnDocument(ReturnDocument.BEFORE));
            String roomId = roomId(initiated);
            if ("opened".equals(appState) && roomId != null) accept.acceptCall(roomId, userId, null, null);
            return initiated;
        }
        throw new IllegalArgumentException("Invalid call status type");
    }

    public Map<String, Object> acceptIncoming(String userId, String roomId, String type) {
        if (roomId == null || roomId.isBlank()) throw new IllegalArgumentException("roomId is required");
        CallAcceptService.AcceptOutcome outcome = accept.acceptCall(roomId, userId, null, type);
        if (outcome != CallAcceptService.AcceptOutcome.PROGRESS_STARTED
                && outcome != CallAcceptService.AcceptOutcome.ALREADY_ACCEPTED) return null;
        Document waitlist = mongo.getCollection(Collections.WAITLISTS)
                .find(new Document("_id", id(roomId)).append("user_id", userId)).first();
        return waitlist == null ? null : joinPayload(waitlist,
                outcome == CallAcceptService.AcceptOutcome.PROGRESS_STARTED);
    }

    private Map<String, Object> joinPayload(Document waitlist, boolean phaseTwo) {
        Document info = waitlist.get("session_info", Document.class);
        String mode = info == null || info.get("mode") == null ? null : info.get("mode").toString();
        String coupon = couponId(waitlist);
        List<String> access = new ArrayList<>(List.of("chat"));
        if (COUPON_A.equals(coupon)) {
            if (phaseTwo) access.add("audio");
        } else if ("session".equals(waitlist.getString("used_for")) || "video".equals(mode)) {
            access.add("audio"); access.add("video");
        } else if ("audio".equals(mode) || COUPON_B.equals(coupon) || "chat".equals(mode)) {
            access.add("audio");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("waitlistId", String.valueOf(waitlist.get("_id")));
        payload.put("roomId", waitlist.get("threadId"));
        payload.put("channel", mode);
        payload.put("accessbilities", access);
        return payload;
    }

    private String couponId(Document waitlist) {
        Object coupon = waitlist.get("coupon");
        if (!(coupon instanceof Document document) || document.get("_id") == null) return null;
        return document.get("_id").toString();
    }

    private String roomId(Document initiated) {
        if (initiated == null) return null;
        Object payload = initiated.get("userPayload");
        Object sessionId = payload instanceof Document d ? d.get("sessionId")
                : payload instanceof Map<?, ?> m ? m.get("sessionId") : initiated.get("roomId");
        return sessionId == null ? null : sessionId.toString();
    }

    private Object id(String value) { return ObjectId.isValid(value) ? new ObjectId(value) : value; }
}
