package com.vedicmeet.appserver.realtime;

import org.bson.Document;

import java.util.Map;

/**
 * Port seam for {@link CallSocketDispatcher}. Wraps every Redis / Mongo / Socket.IO / CallManager effect
 * used by the app-namespace call handlers (sockets/namespaces/app.js) so the dispatcher's routing logic
 * is unit-testable and stays unwired until the Socket.IO transport is enabled + human-reviewed.
 */
public interface CallSocketBackend {

    /** {@code CallManager.acceptCall({roomId, userId, consultantId, type})}. */
    void acceptCall(String roomId, String userId, String consultantId, String type);

    /** {@code CallManager.cancelCall({roomId, userId?/consultantId?})}. */
    void cancelCall(String roomId, String userId, String consultantId);

    /** cancel_call session branch guard: waitlist {used_for:'session', status:'progress', consultant_id}. */
    Document findProgressSessionForConsultant(String roomId, String consultantId);

    /** end_call guard: progress waitlist scoped to the acting user/consultant. */
    Document findProgressWaitlistForActor(String roomId, String userType, String actorId);

    /** {@code redis.hset(roomId, 'status', <status>)}. */
    void hsetStatus(String roomId, String status);

    /** {@code CallManager.handleCallTimeout({...})}; returns the settlement result payload (may be null). */
    Object handleCallTimeout(String roomId, String userId, String consultantId, String callStatus, String callEndedBy);

    /** {@code CallManager.initiateSessionCallToConsultant({...})} — the consultant-retry path. */
    void initiateSessionCallToConsultant(Document waitlist);

    /** {@code redis.hgetall(roomId)} / {@code CallManager.getCallStatus(roomId)}. */
    Map<String, String> getCallData(String roomId);

    /** True when the waitlist row is already completed. */
    boolean isWaitlistCompleted(String roomId);

    /** {@code redis.del(roomId)} after a completed waitlist is observed. */
    void deleteCallData(String roomId);

    /** Completed waitlist projection used by {@code room_remaining_time}. */
    Document findCompletedWaitlistTime(String roomId);

    /** Waitlist timeLap projection used to calculate elapsed call time. */
    Document findWaitlistTime(String roomId);

    boolean isTimerActive(String timerKey);

    long getRemainingTime(String timerKey);

    /** {@code CallManager.extendCallDuration(roomId, additionalSeconds)}. */
    boolean extendCallDuration(String roomId, long additionalSeconds);

    void joinRoom(String roomId);

    void leaveRoom(String roomId);

    /** {@code socket.emit('call_error', {...})} / {@code 'extend_call_failed'}. */
    void emit(String event, Object payload);
}
