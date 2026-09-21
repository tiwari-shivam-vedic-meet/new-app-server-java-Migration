package com.vedicmeet.appserver.session;

import com.vedicmeet.appserver.support.ChatServerClient;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Safe port of Node POST /v1/user/session/book. Unlike Node, this does not insert an orphan waitlist
 * before rejecting an offline consultant and does not use a detached promise with a stale decision.
 */
@Service
public class SessionBookingService {

    public record BookingResult(Document data, boolean instantCallRequested) {}

    public static class ConsultantUnavailableException extends RuntimeException {
        public ConsultantUnavailableException(String message) { super(message); }
    }

    private static final Set<String> MODES = Set.of("chat", "audio", "call", "video", "session-book");
    private static final Set<String> RESERVED_BODY_FIELDS = Set.of(
            "sessionMeta", "fcmToken", "isOfferApply", "couponCode", "trafficSource");

    private final SessionBookingStore store;
    private final SessionBookingPricingService pricing;
    private final ChatServerClient chat;
    private final String audioCourier;

    public SessionBookingService(SessionBookingStore store, SessionBookingPricingService pricing,
                                 ChatServerClient chat,
                                 @Value("${vedicmeet.session.audio-courier:}") String audioCourier) {
        this.store = store;
        this.pricing = pricing;
        this.chat = chat;
        this.audioCourier = audioCourier == null ? "" : audioCourier;
    }

    public BookingResult book(Document authenticatedUser, Map<String, Object> request) {
        if (authenticatedUser == null || authenticatedUser.get("_id") == null) {
            throw new IllegalStateException("Unauthorized");
        }
        Document input = document(request);
        Document sessionMeta = doc(input.get("sessionMeta"));
        String consultantId = requiredText(sessionMeta, "consultantId");
        if (!ObjectId.isValid(consultantId)) throw new IllegalArgumentException("Invalid consultantId");
        String requestedMode = text(sessionMeta.get("mode"));
        if (requestedMode.isBlank()) requestedMode = "chat";
        if (!MODES.contains(requestedMode)) throw new IllegalArgumentException("Invalid session mode");

        String userId = text(authenticatedUser.get("_id"));
        SessionBookingStore.Lease userLease = store.acquire("user:" + userId, Duration.ofMinutes(2));
        if (userLease == null) throw new IllegalStateException("Your booking is already being processed. Please wait.");
        SessionBookingStore.Lease consultantLease = null;
        try {
            consultantLease = store.acquire("consultant:" + consultantId, Duration.ofMinutes(2));
            if (consultantLease == null) throw new IllegalStateException("Consultant booking is busy. Please retry.");

            Document user = store.loadUser(authenticatedUser.get("_id"));
            if (user == null) throw new IllegalStateException("User not found!");
            Document consultant = store.loadConsultant(consultantId);
            if (consultant == null) throw new IllegalStateException("Consultant not found!");
            if (store.isUserBlocked(user.get("_id"), consultant.get("_id"))) {
                throw new IllegalStateException("Consultant is blocked for you!");
            }
            if (store.hasActiveWaitlist(userId)) throw new IllegalStateException("You are already in waitlist!");

            normalizeFcm(user, text(input.get("fcmToken")));
            boolean scheduled = "session-book".equals(requestedMode);
            boolean applyOffer = !scheduled && bool(input.get("isOfferApply"));
            SessionBookingPricingService.Quote quote = pricing.quote(requestedMode, user, consultant,
                    applyOffer, text(input.get("couponCode")));
            if (!scheduled && quote.minimumBalanceMissing()) {
                throw new IllegalStateException("Insufficient balance! Minimum balance required is "
                        + money(quote.requiredBalance()) + " coins");
            }

            boolean modeLive = scheduled || modeLive(consultant, requestedMode);
            if (!modeLive) {
                throw new ConsultantUnavailableException(
                        "Consultant is not available  right now. we will call you as soon as they are online.");
            }

            SessionBookingStore.ScheduledCharge scheduledCharge = scheduled
                    ? scheduledCharge(sessionMeta, quote.basePrice(), number(user.get("wallet"))) : null;
            Document form = bookingForm(input, user);
            String threadId = createThread(user, consultant);
            Document waitlist = waitlist(user, consultant, sessionMeta, requestedMode, quote,
                    scheduledCharge, form, threadId, input.get("trafficSource"));

            SessionBookingStore.PersistResult persisted = store.persist(waitlist, quote, scheduledCharge,
                    modeLive, text(user.get("name")), text(consultant.get("accountName")));
            try {
                Map<String, Object> feed = new LinkedHashMap<>();
                feed.put("threadId", threadId);
                feed.put("form", form);
                Map<String, Object> response = chat.feedFirstMessageForm(feed);
                if (!Boolean.TRUE.equals(response.get("success"))) throw new IllegalStateException("CHAT_FORM_REJECTED");
            } catch (RuntimeException integrationFailure) {
                // Node leaves an inserted waitlist and returns failure here. A durable retry preserves
                // the booking and makes the external chat write eventually consistent instead.
                store.enqueueChatFormRetry(text(waitlist.get("_id")), threadId, form);
            }

            boolean clientReady = persisted.callInitiationQueued() && !"exotel".equalsIgnoreCase(audioCourier);
            persisted.waitlist().put("isConsultantJustReadyToConnect", clientReady);
            return new BookingResult(persisted.waitlist(), !scheduled);
        } finally {
            store.release(consultantLease);
            store.release(userLease);
        }
    }

    public boolean chatServerReady() { return chat.isReady(); }

    private String createThread(Document user, Document consultant) {
        Map<String, Object> cons = new LinkedHashMap<>();
        cons.put("_id", text(consultant.get("_id")));
        cons.put("profileImage", consultant.get("profileImage"));
        cons.put("name", consultant.get("accountName"));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("user", user);
        payload.put("cons", cons);
        Map<String, Object> response = chat.createThread(payload);
        Object data = response == null ? null : response.get("data");
        if (response == null || !Boolean.TRUE.equals(response.get("success")) || data == null
                || text(data).isBlank()) throw new IllegalStateException("Failed to create thread!");
        return text(data);
    }

    private Document waitlist(Document user, Document consultant, Document sessionMeta,
                              String requestedMode, SessionBookingPricingService.Quote quote,
                              SessionBookingStore.ScheduledCharge scheduled, Document form,
                              String threadId, Object trafficSource) {
        boolean isScheduled = scheduled != null;
        String normalizedMode = "call".equals(requestedMode) ? "audio" : requestedMode;
        String storedMode = isScheduled || ("audio".equals(requestedMode)
                && "exotel".equalsIgnoreCase(audioCourier)) ? "chat" : normalizedMode;
        Document price = doc(consultant.get("price"));
        Document session = new Document("price", isScheduled
                ? scheduledPrice(quote.basePrice(), scheduled.discountPercentage()) : quote.price())
                .append("basePrice", quote.basePrice()).append("platformShare", price.get("platformShare"))
                .append("mode", storedMode).append("requestedMode", normalizedMode)
                .append("callModeCourier", "audio".equals(requestedMode) ? blankToNull(audioCourier) : null)
                .append("canConsultantAccessHistory", true).append("messageLimit", 3)
                .append("isSessionExtended", false)
                .append("holdAmount", isScheduled ? scheduled.amount() : quote.requiredBalance())
                .append("bookType", quote.bookType()).append("isTriedAgainAfterMissed", false)
                .append("makeConsultantNotToCancel", canConsultantCancel(consultant, quote))
                .append("isBoosted", boosted(consultant, normalizedMode));
        if (isScheduled) {
            session.append("sessionMeta", new Document("isUserCancelled", false)
                    .append("isConsultantCancelled", false).append("slotDay", scheduled.slotDay())
                    .append("timeFrom", scheduled.slotTime().get("from"))
                    .append("timeTo", scheduled.slotTime().get("to"))
                    .append("sessionTimeInMinutes", scheduled.minutes())
                    .append("discountPercentage", scheduled.discountPercentage()));
        }

        Object uuid = last(doc(user.get("device")).get("uuid"));
        int priority = "none".equals(text(user.get("subscription"))) ? 1
                : "platinum".equals(text(user.get("subscription"))) ? 2 : 3;
        long requestedSeconds = isScheduled ? scheduled.minutes() * 60L : quote.maximumTimeSeconds();
        return new Document("_id", new ObjectId()).append("orderId", store.nextOrderId())
                .append("deviceUsedToken", uuid).append("user_id", text(user.get("_id")))
                .append("consultant_id", text(consultant.get("_id")))
                .append("requested_time", requestedSeconds).append("request_form", form)
                .append("coupon", quote.couponSnapshot()).append("session_info", session)
                .append("used_for", isScheduled ? "session" : "private_call")
                .append("status", "waiting").append("logs", new ArrayList<>())
                .append("priority", priority).append("threadId", threadId)
                .append("trafficSource", asDocumentOrValue(trafficSource))
                .append("createdAt", new java.util.Date()).append("updatedAt", new java.util.Date());
    }

    private SessionBookingStore.ScheduledCharge scheduledCharge(Document meta, double basePrice, double wallet) {
        String slotDay = requiredText(meta, "slotDay");
        Document slot = doc(meta.get("slotTime"));
        int from = seconds(requiredText(slot, "from"));
        int to = seconds(requiredText(slot, "to"));
        if (to < from) to += 24 * 60 * 60;
        int total = to - from;
        if (total <= 0 || total % 60 != 0) throw new IllegalArgumentException("Invalid session slot duration");
        int minutes = total / 60;
        int discount = minutes == 15 ? 20 : minutes == 30 ? 15 : minutes == 60 ? 10 : 0;
        double amount = basePrice * minutes * (100 - discount) / 100.0;
        if (wallet < amount) throw new IllegalStateException("Insufficient balance! Required balance for "
                + minutes + " minutes session is " + money(amount) + " coins");
        return new SessionBookingStore.ScheduledCharge(amount, minutes, discount, slotDay, slot);
    }

    private Document bookingForm(Document input, Document user) {
        Document form = new Document();
        input.forEach((key, value) -> { if (!RESERVED_BODY_FIELDS.contains(key)) form.put(key, value); });
        form.put("phoneNumber", doc(user.get("details")).get("phone"));
        return form;
    }

    private void normalizeFcm(Document user, String incoming) {
        List<String> before = strings(doc(user.get("device")).get("fcmToken"));
        LinkedHashSet<String> clean = new LinkedHashSet<>();
        before.stream().filter(v -> v != null && !v.isBlank()).forEach(clean::add);
        if (incoming != null && !incoming.isBlank()) {
            clean.remove(incoming);
            clean.add(incoming);
        }
        List<String> finalTokens = new ArrayList<>(clean);
        if (finalTokens.size() > 3) finalTokens = new ArrayList<>(finalTokens.subList(finalTokens.size() - 3, finalTokens.size()));
        if (!before.equals(finalTokens)) store.updateFcmTokens(user.get("_id"), finalTokens);
        doc(user.computeIfAbsent("device", ignored -> new Document())).put("fcmToken", finalTokens);
    }

    private boolean modeLive(Document consultant, String requestedMode) {
        Document status = doc(consultant.get("sessionsStatus"));
        return switch (requestedMode) {
            case "chat" -> bool(status.get("isChatLive"));
            case "audio", "call" -> bool(status.get("isVoiceLive"));
            case "video" -> bool(status.get("isVideoLive"));
            default -> false;
        };
    }

    private boolean canConsultantCancel(Document consultant, SessionBookingPricingService.Quote quote) {
        return bool(doc(consultant.get("sessionsStatus")).get("canEndTheCall"))
                || (quote.couponSnapshot() != null
                    && "first_purchase".equals(quote.couponSnapshot().getString("type")))
                || "exotel".equalsIgnoreCase(audioCourier);
    }

    private boolean boosted(Document consultant, String mode) {
        Document boost = doc(consultant.get("boostSessionsStatus"));
        return switch (mode) {
            case "chat" -> bool(boost.get("isChatLive"));
            case "audio" -> bool(boost.get("isVoiceLive"));
            case "video" -> bool(boost.get("isVideoLive"));
            default -> false;
        };
    }

    private int seconds(String hhmm) {
        String[] parts = hhmm.split(":", -1);
        if (parts.length != 2) throw new IllegalArgumentException("Invalid slot time");
        try {
            int hour = Integer.parseInt(parts[0]);
            int minute = Integer.parseInt(parts[1]);
            if (hour < 0 || hour > 23 || minute < 0 || minute > 59) throw new NumberFormatException();
            return hour * 3600 + minute * 60;
        } catch (NumberFormatException invalid) { throw new IllegalArgumentException("Invalid slot time"); }
    }

    private double scheduledPrice(double base, int discount) { return base * (100 - discount) / 100.0; }
    private String requiredText(Document document, String key) {
        String value = text(document.get(key));
        if (value.isBlank()) throw new IllegalArgumentException(key + " is required");
        return value;
    }
    private Object last(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) return null;
        return list.get(list.size() - 1);
    }
    private Object asDocumentOrValue(Object value) { return value instanceof Map<?, ?> map ? document(map) : value; }
    private List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) return new ArrayList<>();
        return list.stream().filter(v -> v != null).map(String::valueOf).toList();
    }
    private Document document(Map<?, ?> map) {
        Document out = new Document();
        if (map != null) map.forEach((key, value) -> out.put(String.valueOf(key), convert(value)));
        return out;
    }
    private Object convert(Object value) {
        if (value instanceof Map<?, ?> map) return document(map);
        if (value instanceof List<?> list) return list.stream().map(this::convert).toList();
        return value;
    }
    private Document doc(Object value) {
        if (value instanceof Document d) return d;
        if (value instanceof Map<?, ?> map) return document(map);
        return new Document();
    }
    private boolean bool(Object value) { return value instanceof Boolean b ? b : Boolean.parseBoolean(text(value)); }
    private double number(Object value) {
        if (value instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(text(value)); } catch (NumberFormatException ignored) { return 0; }
    }
    private String money(double value) {
        if (value == Math.rint(value)) return String.valueOf((long) value);
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }
    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value; }
    private String text(Object value) { return value == null ? "" : String.valueOf(value); }
}
