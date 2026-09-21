package com.vedicmeet.appserver.call;

import org.bson.Document;

import java.util.Map;

/** Port seam for {@link CallCancelService} (Node cancelCall). All effects behind an interface. */
public interface CallCancelStore {

    Map<String, String> getCallHash(String roomId);

    void setStatusCancelled(String roomId);

    /** Cancel {@code timer:<roomId>} — the 60-second missed-call clock. */
    void cancelMissedTimer(String roomId);

    /** Cancel {@code <roomId>:duration} — the active billing clock, when present. */
    void cancelDurationTimer(String roomId);

    /** {@code businessLogics.consultantCancelledCall(consultantId, userId, roomId)}. */
    void consultantCancelledCall(String consultantId, String userId, String roomId);

    void emit(String room, String event, Object payload);

    /** waitlist {@code findOneAndUpdate(push {callStatus:'cancelled',cancelledBy}, {new:true})}. */
    Document pushCancelledLog(String roomId, String cancelledBy);

    Document findConsultant(String id);

    Document findUser(String id);

    /** consultant FCM (USER_MISSED_CALL) + {@code saveNotification}. */
    void notifyUserMissedCall(Document consultant, String userName);

    /** {@code businessLogics.callToNextConsultantAsItFree(roomId)} → next caller or null. */
    CallCancelService.NextCaller callToNextConsultantAsItFree(String roomId);

    /** {@code setTimeout(() => CallManager.initiateCall(nextCaller), 3000)}. */
    void scheduleInitiateCall(CallCancelService.NextCaller nextCaller);
}
