package com.vedicmeet.appserver.call;

import org.bson.Document;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ⚠ SHADOW-ONLY / REAL-TIME. Port of the current production Node
 * {@code CallManager.cancelCall} (utils/classes/call.js L1223-1321), plain and unwired.
 *
 * <p>The current production timer behavior is preserved:</p>
 * <ul>
 *   <li>both {@code timer:<roomId>} and {@code <roomId>:duration} are cancelled, preventing a
 *       cancelled call from later firing either the missed-call or settlement callback.</li>
 *   <li>When both ids are present, {@code cancelledBy = callData.callerId === userId ? 'user':'consultant'}
 *       — but the call hash never stores {@code callerId}, so it is undefined → always {@code 'consultant'}.</li>
 *   <li>In the user-cancel branch BOTH the consultant and the user are looked up by
 *       {@code receiverIdToNotify} (the consultant id).</li>
 * </ul>
 */
public class CallCancelService {

    public enum CancelOutcome { CANCELLED, NO_DATA }

    public static final class NextCaller {
        public String userId, consultantId, roomId, callMode;
    }

    private final CallCancelStore store;

    public CallCancelService(CallCancelStore store) {
        this.store = store;
    }

    public CancelOutcome cancelCall(String roomId, String userId, String consultantId) {
        Map<String, String> callData = store.getCallHash(roomId);
        if (callData == null) {
            return CancelOutcome.NO_DATA; // Node: hgetall returns {} (truthy), so this rarely hits.
        }

        store.setStatusCancelled(roomId);
        store.cancelMissedTimer(roomId);
        store.cancelDurationTimer(roomId);

        String cancelledBy = "unknown";
        if (userId != null && consultantId != null) {
            // callData has no 'callerId' field → undefined === userId is false → 'consultant'
            cancelledBy = userId.equals(callData.get("callerId")) ? "user" : "consultant";
        } else if (userId != null) {
            cancelledBy = "user";
        } else if (consultantId != null) {
            cancelledBy = "consultant";
            store.consultantCancelledCall(consultantId, userId, roomId);
            Map<String, Object> appAction = new LinkedHashMap<>();
            appAction.put("action", "call_missed");
            appAction.put("data", new LinkedHashMap<>());
            store.emit(consultantId, "app_action", appAction);
        }

        String callerIdToNotify = userId != null ? userId : callData.get("userId");
        String receiverIdToNotify = consultantId != null ? consultantId : callData.get("consultantId");

        Map<String, Object> canceled = new LinkedHashMap<>();
        canceled.put("roomId", roomId);
        canceled.put("status", "cancelled");
        canceled.put("cancelledBy", cancelledBy);
        store.emit(callerIdToNotify, "call_canceled", canceled);
        store.emit(receiverIdToNotify, "call_canceled", canceled);

        Document updatedEntry = store.pushCancelledLog(roomId, cancelledBy);

        if (receiverIdToNotify != null && "user".equals(cancelledBy)) {
            Document consultant = store.findConsultant(receiverIdToNotify);
            Document user = store.findUser(receiverIdToNotify); // faithful: also by receiverIdToNotify
            if (hasFcmTokens(consultant)) {
                store.notifyUserMissedCall(consultant, user == null ? null : user.getString("name"));
            }
        }

        if (receiverIdToNotify != null && "consultant".equals(cancelledBy)) {
            if (updatedEntry != null && couponTypeIsFirstPurchase(updatedEntry)) {
                NextCaller nextCaller = store.callToNextConsultantAsItFree(roomId);
                if (nextCaller != null) {
                    store.emit(userId, "call_missed_calling_next_consultant", new LinkedHashMap<>());
                    store.scheduleInitiateCall(nextCaller);
                }
            }
        }

        return CancelOutcome.CANCELLED;
    }

    private boolean hasFcmTokens(Document consultant) {
        if (consultant == null) return false;
        Object device = consultant.get("device");
        if (!(device instanceof Document)) return false;
        Object tokens = ((Document) device).get("fcmToken");
        return tokens instanceof java.util.List && !((java.util.List<?>) tokens).isEmpty();
    }

    private boolean couponTypeIsFirstPurchase(Document entry) {
        Object coupon = entry.get("coupon");
        if (!(coupon instanceof Document)) return false;
        return "first_purchase".equals(((Document) coupon).getString("type"));
    }
}
