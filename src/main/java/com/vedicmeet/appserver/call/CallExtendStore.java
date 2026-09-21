package com.vedicmeet.appserver.call;

import java.util.Map;

/**
 * Port seam for {@link CallExtendService} (Node extendCallDuration). Redis/Socket.IO effects behind an
 * interface so the transition is unit-testable and stays unwired until review.
 */
public interface CallExtendStore {

    /** {@code redis.hgetall(roomId)} — the call hash (never null in Node; {@code {}} when absent). */
    Map<String, String> getCallHash(String roomId);

    /** {@code extendTimer(timerKey, additionalSeconds)} — true if the (still-live) timer was extended. */
    boolean extendTimer(String timerKey, long additionalSeconds);

    /** {@code redis.ttl(timerKey)} — remaining seconds. */
    long ttlSeconds(String timerKey);

    /** {@code socket.emit(room, event, payload)} on the /app namespace. */
    void emit(String room, String event, Object payload);
}
