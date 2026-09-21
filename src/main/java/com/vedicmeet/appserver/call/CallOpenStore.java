package com.vedicmeet.appserver.call;

import org.bson.Document;

import java.util.Map;

/** Port seam for {@link CallOpenService} (Node openCall). All Redis/Mongo/FCM/VOIP/timer effects here. */
public interface CallOpenStore {

    /** Consultant-scoped single-call ownership lock; idempotent for the same room. */
    default boolean claimConsultantCall(String consultantId, String roomId, long ttlSeconds) { return true; }

    /** Token-checked release of the consultant-scoped ownership lock. */
    default void releaseConsultantCall(String consultantId, String roomId) { }

    /** {@code await new Promise(r => setTimeout(r, ms))}. */
    void sleep(long ms);

    /** waitlist {@code findOne($or user/consultant status:'progress')} — non-null ⇒ busy ⇒ throw. */
    Document findBusyWaitlist(String userId, String consultantId);

    default Document findBusyWaitlist(String userId, String consultantId, String currentRoomId) {
        return findBusyWaitlist(userId, consultantId);
    }

    void deleteCallHash(String roomId);

    /** {@code redis.hmset(roomId, hash)} + {@code redis.expire(roomId, ttlSeconds)}. */
    void writeCallHash(String roomId, Map<String, Object> hash, long ttlSeconds);

    Document findUserForCall(String userId);

    Document findConsultantForCall(String consultantId);

    /** {@code callInitated.create({... isAcceptedBy*:false})}. */
    void createCallInitated(String userId, String consultantId,
                            Map<String, Object> userPayload, Map<String, Object> consultantPayload);

    /** {@code notification.send({token,title,body,userType,data})} — per token. */
    void sendFcm(String token, String title, String body, String userType, Map<String, Object> data);

    /** {@code notification.sendVOIPNotificationCall({...})}. */
    void sendVoip(String voipToken, String userType, String title, String body, String name, Map<String, Object> payload);

    /** waitlist push {@code {callBy, callStatus:'initiated'}}. */
    void pushInitiatedLog(String roomId, String callBy);

    /** {@code registerTimerWithCallback(`timer:${roomId}`, seconds, handleMissedCall)}. */
    void registerMissedTimer(String roomId, long seconds);

    Map<String, String> readCallHash(String roomId);
}
