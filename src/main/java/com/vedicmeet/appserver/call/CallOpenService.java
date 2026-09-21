package com.vedicmeet.appserver.call;

import org.bson.Document;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * ⚠ SHADOW-ONLY / REAL-TIME. FAITHFUL port of the Node {@code CallManager.openCall} body
 * (utils/classes/call.js L28-165), migrated as-is (plain class, unwired). This is the "all gates passed
 * → open the call" path that {@link CallLifecycleService} references as a seam.
 *
 * <p>Sequence: consultant-scoped ownership claim → busy re-check →
 * {@code redis.del} then {@code hmset(status:'initiated')} + 4h expiry → load user/consultant devices →
 * build the two {@code incoming_call} payloads → {@code callInitated.create} → FCM to every user token +
 * user VOIP + FCM to every consultant token + consultant VOIP → waitlist {@code 'initiated'} log →
 * register the 60s missed-call timer ({@code timer:<roomId>}) → return the stored hash.</p>
 */
public class CallOpenService {

    private static final String DEFAULT_IMAGE =
            "https://cdn.pixabay.com/photo/2015/10/05/22/37/blank-profile-picture-973460_1280.png";

    private final CallOpenStore store;

    public CallOpenService(CallOpenStore store) {
        this.store = store;
    }

    public Map<String, String> openCall(String callBy, String userId, String consultantId,
                                        String roomId, String callMode) {
        return openCall(callBy, userId, consultantId, roomId, callMode, false);
    }

    public Map<String, String> openCall(String callBy, String userId, String consultantId,
                                        String roomId, String callMode, boolean needLatency) {
        if (!store.claimConsultantCall(consultantId, roomId, 4 * 60 * 60L)) {
            throw new IllegalStateException("Consultant is busy");
        }

        try {
            if (store.findBusyWaitlist(userId, consultantId, roomId) != null) {
                throw new IllegalStateException("Consultant is busy");
            }

            store.deleteCallHash(roomId);

            Map<String, Object> hash = new LinkedHashMap<>();
            hash.put("userId", userId);
            hash.put("consultantId", consultantId);
            hash.put("roomId", roomId);
            hash.put("status", "initiated");
            hash.put("timestamp", String.valueOf(System.currentTimeMillis()));
            hash.put("owner", "java");
            store.writeCallHash(roomId, hash, 4 * 60 * 60L);

            Document user = store.findUserForCall(userId);
            Document consultant = store.findConsultantForCall(consultantId);

            String consName = str(consultant, "accountName", "Consultant");
            String userName = str(user, "name", "User");
            long sentAt = System.currentTimeMillis();

            Map<String, Object> callUserData = new LinkedHashMap<>();
            callUserData.put("sentAt", sentAt);
            callUserData.put("type", "incoming_call");
            callUserData.put("callId", roomId);
            callUserData.put("callerName", consName);
            callUserData.put("callerImage", strRaw(consultant, "profileImage", DEFAULT_IMAGE));
            callUserData.put("sessionId", roomId);
            callUserData.put("callMode", callMode);
            callUserData.put("autoAccept", "user".equals(callBy) ? "true" : "false");

            Map<String, Object> callConsultantData = new LinkedHashMap<>();
            callConsultantData.put("sentAt", sentAt);
            callConsultantData.put("type", "incoming_call");
            callConsultantData.put("callId", roomId);
            callConsultantData.put("callerName", userName);
            callConsultantData.put("callerImage", strRaw(user, "profileImage", DEFAULT_IMAGE));
            callConsultantData.put("sessionId", roomId);
            callConsultantData.put("callMode", callMode);
            callConsultantData.put("userId", user == null ? null : user.get("userId"));
            callConsultantData.put("autoAccept", "cons".equals(callBy) ? "true" : "false");

            store.createCallInitated(userId, consultantId, callUserData, callConsultantData);

            for (String token : fcmTokens(user)) {
                store.sendFcm(token, "Incoming Call", consName + " is calling you", "user", callUserData);
            }
            String userVoip = voipToken(user);
            if (userVoip != null) {
                store.sendVoip(userVoip, "user", "Incoming Call", consName + " is calling you", consName, callUserData);
            }

            Runnable consultantNotify = () -> {
                for (String token : fcmTokens(consultant)) {
                    store.sendFcm(token, "Incoming Call", userName + " is calling you", "cons", callConsultantData);
                }
                String consVoip = voipToken(consultant);
                if (consVoip != null && !fcmTokens(consultant).isEmpty()) {
                    store.sendVoip(consVoip, "cons", "Incoming Call", userName + " is calling you", userName, callConsultantData);
                }
            };
            if (needLatency) CompletableFuture.delayedExecutor(7, TimeUnit.SECONDS).execute(consultantNotify);
            else consultantNotify.run();

            store.pushInitiatedLog(roomId, callBy);
            store.registerMissedTimer(roomId, 60);

            return store.readCallHash(roomId);
        } catch (RuntimeException error) {
            store.releaseConsultantCall(consultantId, roomId);
            throw error;
        }
    }

    /** {@code doc.field || fallback} for a display name (falsy/empty → fallback). */
    private String str(Document d, String field, String fallback) {
        String v = d == null ? null : d.getString(field);
        return (v == null || v.isEmpty()) ? fallback : v;
    }

    private Object strRaw(Document d, String field, String fallback) {
        String v = d == null ? null : d.getString(field);
        return (v == null || v.isEmpty()) ? fallback : v;
    }

    @SuppressWarnings("unchecked")
    private List<String> fcmTokens(Document d) {
        if (d == null) return List.of();
        Object device = d.get("device");
        if (!(device instanceof Document)) return List.of();
        Object t = ((Document) device).get("fcmToken");
        return t instanceof List ? (List<String>) t : List.of();
    }

    private String voipToken(Document d) {
        if (d == null) return null;
        Object device = d.get("device");
        if (!(device instanceof Document)) return null;
        Object t = ((Document) device).get("voipToken");
        return t == null ? null : t.toString();
    }
}
