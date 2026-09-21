package com.vedicmeet.appserver.call;

import org.bson.Document;

import java.util.Map;

/**
 * Port seam for {@link CallEndService} (Node endCall). All Redis / Mongo / Socket.IO / FCM effects
 * live behind this interface so the transition is unit-testable and stays unwired until human review.
 */
public interface CallEndStore {

    /** {@code redis.hgetall(roomId)} — Node returns {@code {}} (never null) when the key is gone. */
    Map<String, String> getCallHash(String roomId);

    /** {@code cancelTimer(`${roomId}:duration`)} — stop the billing clock. */
    void cancelDurationTimer(String roomId);

    /** {@code redis.hset(roomId, 'status', <status>)}. */
    void setStatus(String roomId, String status);

    /** waitlist {@code findOneAndUpdate} pushing {@code {callStatus:'ended'}}; returns the (pre-update) doc. */
    Document pushEndedLog(String roomId);

    /** {@code socket.emit(room, event, payload)} on the /app namespace. */
    void emit(String room, String event, Object payload);

    /** consultant FCM push + {@code saveNotification} for the call-ended template. */
    void notifyConsultantCallEnded(String consultantId);
}
