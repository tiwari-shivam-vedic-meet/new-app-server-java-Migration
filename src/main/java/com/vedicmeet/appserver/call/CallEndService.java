package com.vedicmeet.appserver.call;

import org.bson.Document;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ⚠ SHADOW-ONLY / REAL-TIME. FAITHFUL port of Node {@code CallManager.endCall} (call.js L1224),
 * the user-pressed-END path, migrated as-is.
 *
 * <p>Sequence (exactly as Node): stop the billing clock → redis {@code status='ended'} →
 * waitlist push {@code {callStatus:'ended'}} log → emit {@code call_ended} + {@code leave_call_room}
 * to both parties → (if consultantId) FCM + {@code saveNotification} to the consultant → return true.</p>
 *
 * <p>Node quirk preserved: {@code redis.hgetall} returns {@code {}} (truthy) when the room is gone, so
 * {@code if (callData)} is <b>always</b> true and the end sequence runs even for a ghost room — unlike
 * {@link CallTimeoutSettlementService} which gates on {@code status === 'accepted'}. Here only a genuinely
 * {@code null} hash returns false. Also note: endCall does NOT settle — settlement is the timeout path.</p>
 */
public class CallEndService {

    public enum EndOutcome { ENDED, NO_DATA }

    private final CallEndStore store;

    public CallEndService(CallEndStore store) {
        this.store = store;
    }

    public EndOutcome endCall(String roomId, String userId, String consultantId) {
        Map<String, String> callData = store.getCallHash(roomId);
        if (callData == null) {
            return EndOutcome.NO_DATA; // Node: hgetall returns {} (truthy) in practice, so this rarely hits.
        }

        store.cancelDurationTimer(roomId);
        store.setStatus(roomId, "ended");

        Document waitlist = store.pushEndedLog(roomId);
        String waitlistId = waitlist == null ? null : String.valueOf(waitlist.get("_id"));
        Object threadId = waitlist == null ? null : waitlist.get("threadId");

        Map<String, Object> ended = new LinkedHashMap<>();
        ended.put("roomId", roomId);
        ended.put("status", "ended");
        store.emit(userId, "call_ended", ended);
        store.emit(consultantId, "call_ended", ended);

        Map<String, Object> leave = new LinkedHashMap<>();
        leave.put("waitlistId", waitlistId);
        leave.put("roomId", threadId);
        store.emit(userId, "leave_call_room", leave);
        store.emit(consultantId, "leave_call_room", leave);

        if (consultantId != null) {
            store.notifyConsultantCallEnded(consultantId);
        }
        return EndOutcome.ENDED;
    }
}
