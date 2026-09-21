package com.vedicmeet.appserver.call;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ⚠ SHADOW-ONLY / REAL-TIME. Safe fix-forward port of Node
 * {@code CallManager.extendCallDuration} (utils/classes/call.js L2190-2223), plain and unwired.
 *
 * <p>Intentional parity deviation: production Node currently extends {@code timer:<roomId>}, the
 * missed-call timer that is cancelled once both parties connect. Java extends the actual billing clock
 * {@code <roomId>:duration}; otherwise a successful recharge cannot extend a live call. This deviation
 * is pinned by tests and must be included in the contract sign-off.</p>
 */
public class CallExtendService {

    private final CallExtendStore store;

    public CallExtendService(CallExtendStore store) {
        this.store = store;
    }

    public boolean extendCall(String roomId, long additionalSeconds) {
        try {
            Map<String, String> callData = store.getCallHash(roomId);
            String timerKey = roomId + ":duration";

            if (callData != null && "accepted".equals(callData.get("status"))) {
                boolean success = store.extendTimer(timerKey, additionalSeconds);
                if (success) {
                    long remainingTime = store.ttlSeconds(timerKey);
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("roomId", roomId);
                    payload.put("additionalSeconds", additionalSeconds);
                    payload.put("remainingTime", remainingTime);
                    store.emit(callData.get("userId"), "call_extended", payload);
                    store.emit(callData.get("consultantId"), "call_extended", payload);
                    return true;
                }
            }
            return false;
        } catch (RuntimeException e) {
            return false; // Node catches and returns false
        }
    }
}
