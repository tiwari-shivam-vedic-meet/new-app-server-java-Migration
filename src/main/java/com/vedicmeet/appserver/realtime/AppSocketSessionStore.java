package com.vedicmeet.appserver.realtime;

import org.bson.Document;

import java.util.List;

/**
 * Persistence boundary for the non-call event handlers in Node {@code sockets/namespaces/app.js}.
 * The interface keeps the socket contract testable without a real MongoDB or Redis connection.
 */
public interface AppSocketSessionStore {

    record Participants(Document waitlist, String userId, String consultantId) {}
    record MessageClaim(Document source, int remaining) {}

    Participants participants(String waitlistId, String role, String actorId);

    Participants activeParticipants(String role, String actorId, String targetId);

    Document consultantDetails(String consultantId);

    Document sessionUser(String userId);

    Document sessionConsultant(String consultantId);

    List<Document> blockedChatRanges(String userId, String consultantId);

    List<Document> userWaitlists(String userId);

    Document progressingUserWaitlist(String userId);

    Document progressingConsultantWaitlist(String consultantId);

    List<Document> fixedSessionWaitlists(String userId);

    boolean appendSessionSwitchLog(String waitlistId, String role, String actorId, String channel);

    Document cancelWaitlist(String waitlistId, String userId, String reason);

    Document cancelFixedSession(String waitlistId, String userId, String reason);

    Document rejoinWaitlist(String waitlistId, String consultantId, String userId);

    MessageClaim claimWaitlistMessage(String waitlistId, String role, String actorId, String message);

    MessageClaim claimLeaveMessage(String consultantId, String userId, String role, String actorId);

    void updatePresence(String role, String actorId, String status, double connectionSeconds);
}
