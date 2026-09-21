package com.vedicmeet.appserver.realtime;

import com.vedicmeet.appserver.call.CallOpenStore;
import com.vedicmeet.appserver.call.JavaCallTimerService;
import com.vedicmeet.appserver.security.Role;
import com.vedicmeet.appserver.session.SessionBookingStore;
import com.vedicmeet.appserver.support.ChatServerClient;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Business logic behind the production /live_event Socket.IO wire contract. */
@Service
public class LiveEventService {

    public record JoinResult(String action, Document waitlist, String roomId, Document user) {}

    private final LiveEventStore store;
    private final LiveEventChatService chatBox;
    private final LiveEventReplayTimerService replayTimers;
    private final SessionBookingStore leases;
    private final CallOpenStore callRuntime;
    private final JavaCallTimerService callTimers;
    private final ChatServerClient chatServer;

    public LiveEventService(LiveEventStore store, LiveEventChatService chatBox,
                            LiveEventReplayTimerService replayTimers, SessionBookingStore leases,
                            CallOpenStore callRuntime, JavaCallTimerService callTimers,
                            ChatServerClient chatServer) {
        this.store = store;
        this.chatBox = chatBox;
        this.replayTimers = replayTimers;
        this.leases = leases;
        this.callRuntime = callRuntime;
        this.callTimers = callTimers;
        this.chatServer = chatServer;
    }

    public List<Document> events() { return store.events(); }

    public List<Document> joinEvent(String eventId, String actorId, String role, boolean writesEnabled) {
        required(eventId, "Event not found");
        if (store.event(eventId) == null) throw new IllegalStateException("Event not found");
        if (writesEnabled && Role.CONSULTANT.equals(role)) store.setConsultantLive(actorId, true);
        return chatBox.getMessages(eventId);
    }

    public void leaveEvent(String eventId, String actorId, String role, boolean writesEnabled) {
        if (writesEnabled && Role.CONSULTANT.equals(role)) store.setConsultantLive(actorId, false);
    }

    public Document toggleBlock(String eventId, String userId, String consultantId,
                                String role, boolean block) {
        requireRole(role, Role.CONSULTANT);
        required(userId, "User is required");
        return store.toggleBlock(eventId, userId, consultantId, block);
    }

    public List<String> blockedUsers(String eventId) { return store.blockedUsers(eventId); }

    public Document addMessage(String eventId, String message, String name, String userId) {
        Document value = chatBox.addMessage(eventId, message, name, userId);
        replayTimers.scheduleInactivity(eventId);
        return value;
    }

    public JoinResult joinWaitlist(String actorId, String role, String eventId, Object formData) {
        requireRole(role, Role.USER);
        required(eventId, "Event not found");
        SessionBookingStore.Lease lease = leases.acquire("live-event:user:" + actorId,
                Duration.ofSeconds(20));
        if (lease == null) throw new IllegalStateException("Waitlist request is already processing");
        try {
            Document event = store.event(eventId);
            if (event == null) throw new IllegalStateException("Event not found");
            String consultantId = text(event.get("consultant_id"));
            Document user = store.user(actorId);
            Document consultant = store.consultant(consultantId);
            if (user == null) throw new IllegalStateException("User not found");
            if (consultant == null) throw new IllegalStateException("Consultant not found");
            if (store.isBlocked(eventId, actorId, consultantId)) {
                throw new IllegalStateException("You are blocked from this event");
            }
            if (store.activeUserWaitlist(actorId, consultantId) != null) {
                throw new IllegalStateException("You are already in waitlist with this consultant!");
            }

            Document price = doc(consultant.get("price"));
            double rate = number(price.get("privateCallOnLive"));
            if (rate <= 0) throw new IllegalStateException("Live-event private call is unavailable");
            double wallet = number(user.get("wallet"));
            if (wallet < rate * 5) {
                throw new IllegalStateException("Insufficient balance! Minimum balance required is "
                        + money(rate * 5) + " coins");
            }
            String threadId = createThread(user, consultant);
            // Recheck after the remote call while the user lease is still held.
            if (store.activeUserWaitlist(actorId, consultantId) != null) {
                throw new IllegalStateException("You are already in waitlist with this consultant!");
            }

            Date now = new Date();
            Document waitlist = new Document("_id", new ObjectId()).append("user_id", actorId)
                    .append("consultant_id", consultantId)
                    .append("requested_time", Math.floor((wallet / rate) * 60))
                    .append("request_form", doc(formData)).append("used_for", "live_event")
                    .append("session_info", new Document("price", rate)
                            .append("basePrice", rate)
                            .append("platformShare", number(price.get("platformShare")))
                            .append("mode", "chat").append("canConsultantAccessHistory", true)
                            .append("eventId", eventId))
                    .append("status", "waiting").append("priority", priority(user))
                    .append("threadId", threadId).append("logs", List.of())
                    .append("createdAt", now).append("updatedAt", now);
            store.insertWaitlist(waitlist);

            boolean started = startSpecific(waitlist, eventId);
            return new JoinResult(started ? "connect_now" : "waitlist_created",
                    waitlist, threadId, user);
        } finally {
            leases.release(lease);
        }
    }

    public JoinResult callNext(String consultantId, String role, String eventId) {
        requireRole(role, Role.CONSULTANT);
        Document event = store.event(eventId);
        if (event == null) throw new IllegalStateException("Event not found");
        if (!consultantId.equals(text(event.get("consultant_id")))) {
            throw new IllegalStateException("Not allowed for this event");
        }
        SessionBookingStore.Lease dispatch = leases.acquire(
                "live-event:dispatch:" + consultantId + ":" + eventId, Duration.ofSeconds(15));
        if (dispatch == null) throw new IllegalStateException("Next user is already processing");
        try {
            if (store.consultantBusy(consultantId, eventId, null)) {
                throw new IllegalStateException("Consultant is already busy");
            }
            Document next = store.nextWaiting(consultantId, eventId);
            if (next == null) throw new IllegalStateException("No user in waitlist");
            if (!startClaimed(next, eventId)) throw new IllegalStateException("Unable to start next user");
            Document user = store.user(text(next.get("user_id")));
            return new JoinResult("connect_now", next, text(next.get("threadId")), user);
        } finally {
            leases.release(dispatch);
        }
    }

    public List<Document> waitlist(String consultantId, String role, String eventId) {
        requireRole(role, Role.CONSULTANT);
        Document event = store.event(eventId);
        if (event == null || !consultantId.equals(text(event.get("consultant_id")))) {
            throw new IllegalStateException("Not allowed for this event");
        }
        return store.waitlist(consultantId, eventId);
    }

    public boolean chatServerReady() { return chatServer.isReady(); }

    private boolean startSpecific(Document waitlist, String eventId) {
        String consultantId = text(waitlist.get("consultant_id"));
        SessionBookingStore.Lease dispatch = leases.acquire(
                "live-event:dispatch:" + consultantId + ":" + eventId, Duration.ofSeconds(15));
        if (dispatch == null) return false;
        try {
            if (store.consultantBusy(consultantId, eventId, text(waitlist.get("_id")))) return false;
            return startClaimed(waitlist, eventId);
        } finally {
            leases.release(dispatch);
        }
    }

    private boolean startClaimed(Document waitlist, String eventId) {
        String waitlistId = text(waitlist.get("_id"));
        String userId = text(waitlist.get("user_id"));
        String consultantId = text(waitlist.get("consultant_id"));
        long duration = Math.max(1, (long) number(waitlist.get("requested_time")));
        if (!callRuntime.claimConsultantCall(consultantId, waitlistId, duration + 300)) return false;
        Document progressed = store.claimProgress(waitlistId);
        if (progressed == null) {
            callRuntime.releaseConsultantCall(consultantId, waitlistId);
            return false;
        }
        try {
            callRuntime.deleteCallHash(waitlistId);
            callRuntime.writeCallHash(waitlistId, Map.of(
                    "userId", userId, "consultantId", consultantId, "status", "accepted",
                    "callMode", "chat", "usedFor", "live_event", "eventId", eventId,
                    "lastUpdatedAt", System.currentTimeMillis()), duration + 300);
            callTimers.scheduleTimeout(waitlistId, userId, consultantId, duration);
            waitlist.put("status", "progress");
            waitlist.put("timeLap", progressed.get("timeLap"));
            return true;
        } catch (RuntimeException failure) {
            try { callRuntime.deleteCallHash(waitlistId); } catch (RuntimeException ignored) { }
            callRuntime.releaseConsultantCall(consultantId, waitlistId);
            store.revertProgress(waitlistId, "live event runtime failed to arm");
            throw new IllegalStateException("Unable to start live-event session", failure);
        }
    }

    private String createThread(Document user, Document consultant) {
        Map<String, Object> cons = new LinkedHashMap<>();
        cons.put("_id", text(consultant.get("_id")));
        cons.put("profileImage", consultant.get("profileImage"));
        cons.put("name", consultant.get("accountName"));
        Map<String, Object> response = chatServer.createThread(Map.of("user", user, "cons", cons));
        if (!Boolean.TRUE.equals(response.get("success")) || text(response.get("data")).isBlank()) {
            throw new IllegalStateException("Failed to create thread!");
        }
        return text(response.get("data"));
    }

    private int priority(Document user) {
        String subscription = text(user.get("subscription"));
        if (subscription.isBlank() || "none".equals(subscription)) return 1;
        return "platinum".equals(subscription) ? 2 : 3;
    }

    private void requireRole(String actual, String expected) {
        if (!expected.equals(actual)) throw new IllegalStateException("Not allowed");
    }
    private void required(String value, String error) {
        if (value == null || value.isBlank()) throw new IllegalStateException(error);
    }
    private Document doc(Object value) {
        if (value instanceof Document d) return new Document(d);
        if (value instanceof Map<?, ?> map) {
            Document d = new Document();
            map.forEach((key, item) -> d.put(String.valueOf(key), item));
            return d;
        }
        return new Document();
    }
    private String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private double number(Object value) {
        if (value instanceof Number n) return n.doubleValue();
        try { return value == null ? 0 : Double.parseDouble(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return 0; }
    }
    private String money(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }
}
