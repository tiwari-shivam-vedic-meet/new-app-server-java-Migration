package com.vedicmeet.appserver.realtime;

import com.vedicmeet.appserver.security.Role;
import org.bson.Document;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Actor-scoped port of the non-call handlers in Node {@code sockets/namespaces/app.js}.
 * Payload names remain client-compatible; known authorization and concurrency holes are closed.
 */
@Service
public class AppSocketSessionService {

    public record Dispatch(List<String> targets, Map<String, Object> payload) {}

    private final AppSocketSessionStore store;

    public AppSocketSessionService(AppSocketSessionStore store) {
        this.store = store;
    }

    public Dispatch sessionSwitchPermission(String role, String actorId, String waitlistId,
                                            String channel, String type) {
        if (!List.of("request", "accepted", "rejected").contains(type)) {
            throw new IllegalArgumentException("Invalid switch permission type");
        }
        AppSocketSessionStore.Participants p = requireParticipants(waitlistId, role, actorId);
        List<String> targets = "request".equals(type)
                ? List.of(counterparty(p, role)) : List.of(p.consultantId(), p.userId());
        return new Dispatch(targets, map("type", type, "channel", channel));
    }

    public boolean sessionSwitchLog(String role, String actorId, String waitlistId, String channel) {
        requireText(channel, "channel");
        return store.appendSessionSwitchLog(requireText(waitlistId, "waitlistId"), role, actorId, channel);
    }

    public Document consultantDetails(String role, String actorId, String waitlistId) {
        AppSocketSessionStore.Participants p = requireParticipants(waitlistId, role, actorId);
        Document details = store.consultantDetails(p.consultantId());
        if (details == null) throw new IllegalStateException("Failed to get consultant details");
        return details;
    }

    public Map<String, Object> sessionDetails(String role, String actorId, String waitlistId) {
        AppSocketSessionStore.Participants p = requireParticipants(waitlistId, role, actorId);
        Document user = store.sessionUser(p.userId());
        Document consultant = store.sessionConsultant(p.consultantId());
        if (user == null || consultant == null) throw new IllegalStateException("Failed to get session details");
        return map("userDetails", user, "consultantDetails", consultant,
                "sessionDetails", p.waitlist());
    }

    public List<Document> blockedChatRanges(String role, String actorId, String waitlistId) {
        AppSocketSessionStore.Participants p = requireParticipants(waitlistId, role, actorId);
        return store.blockedChatRanges(p.userId(), p.consultantId());
    }

    public Dispatch ongoingActivity(String role, String actorId, String waitlistId,
                                    String requestedTarget, String type, String message) {
        AppSocketSessionStore.Participants p = waitlistId == null || waitlistId.isBlank()
                ? store.activeParticipants(role, actorId, requireText(requestedTarget, "to"))
                : requireParticipants(waitlistId, role, actorId);
        if (p == null) throw new SecurityException("No active session with socket target");
        String target = counterparty(p, role);
        if (requestedTarget != null && !requestedTarget.isBlank() && !target.equals(requestedTarget)) {
            throw new SecurityException("Socket target is not the active counterparty");
        }
        return new Dispatch(List.of(target), map("type", type, "message", message));
    }

    public Dispatch lastCallMode(String role, String actorId, String waitlistId,
                                 String roomId, String currentChannel) {
        AppSocketSessionStore.Participants p = requireParticipants(waitlistId, role, actorId);
        return new Dispatch(List.of(counterparty(p, role)),
                map("roomId", roomId, "currentChannel", currentChannel));
    }

    public Map<String, Object> waitlistStatus(String role, String actorId) {
        if (Role.USER.equals(role)) {
            return map("entry", store.userWaitlists(actorId),
                    "progressingEntry", store.progressingUserWaitlist(actorId),
                    "fixedSessionEntry", store.fixedSessionWaitlists(actorId));
        }
        if (Role.CONSULTANT.equals(role)) {
            return map("entry", null, "progressingEntry", store.progressingConsultantWaitlist(actorId));
        }
        throw new SecurityException("Unsupported socket role");
    }

    public Object cancelSession(String role, String actorId, String waitlistId,
                                String reason, String usedFor) {
        requireRole(role, Role.USER);
        Document canceled = "fixed_session".equals(usedFor)
                ? store.cancelFixedSession(requireText(waitlistId, "waitlistId"), actorId, reason)
                : store.cancelWaitlist(requireText(waitlistId, "waitlistId"), actorId, reason);
        if (canceled == null) throw new IllegalStateException("Failed to cancel session");
        return "fixed_session".equals(usedFor) ? List.of() : store.userWaitlists(actorId);
    }

    public List<Document> rejoinWaitlist(String role, String actorId, String consultantId,
                                         String waitlistId) {
        requireRole(role, Role.USER);
        Document rejoined = store.rejoinWaitlist(requireText(waitlistId, "roomId"),
                requireText(consultantId, "consultantId"), actorId);
        if (rejoined == null) throw new IllegalStateException("Failed to join waitlist");
        return store.userWaitlists(actorId);
    }

    public Map<String, Object> canSendMessage(String role, String actorId, String waitlistId,
                                              String message) {
        AppSocketSessionStore.MessageClaim claim = store.claimWaitlistMessage(
                requireText(waitlistId, "waitlistId"), role, actorId, message);
        return claim == null ? permission(false, "Message limit reached")
                : permission(true, "Message sent successfully");
    }

    public Map<String, Object> canSendLeaveMessage(String role, String actorId,
                                                   String consultantId, String userId) {
        AppSocketSessionStore.MessageClaim claim = store.claimLeaveMessage(
                requireText(consultantId, "consultantId"), requireText(userId, "userId"), role, actorId);
        return claim == null ? permission(false, "Message limit reached")
                : permission(true, "Message sent successfully");
    }

    public void presence(String role, String actorId, String status, double seconds) {
        try { store.updatePresence(role, actorId, status, seconds); }
        catch (RuntimeException ignored) {
            // Presence is telemetry. A temporary Mongo outage must never reject/disconnect a socket.
        }
    }

    private AppSocketSessionStore.Participants requireParticipants(String waitlistId,
                                                                    String role, String actorId) {
        AppSocketSessionStore.Participants p = store.participants(
                requireText(waitlistId, "waitlistId"), role, actorId);
        if (p == null) throw new SecurityException("Waitlist not found for authenticated actor");
        return p;
    }

    private String counterparty(AppSocketSessionStore.Participants p, String role) {
        if (Role.USER.equals(role)) return p.consultantId();
        if (Role.CONSULTANT.equals(role)) return p.userId();
        throw new SecurityException("Unsupported socket role");
    }

    private void requireRole(String actual, String required) {
        if (!required.equals(actual)) throw new SecurityException("Unsupported socket role");
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value;
    }

    private Map<String, Object> permission(boolean allowed, String message) {
        return map("canSend", allowed, "message", message);
    }

    private Map<String, Object> map(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i + 1 < values.length; i += 2) {
            result.put(String.valueOf(values[i]), values[i + 1]);
        }
        return result;
    }
}
