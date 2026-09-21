package com.vedicmeet.appserver.session;

import com.vedicmeet.appserver.support.ChatServerClient;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SessionBookingServiceTest {

    @Test
    void instantBookingBuildsCompatibleWaitlistAndUsesChatThread() {
        Fixture f = fixture("", true);
        Map<String, Object> body = request("chat");
        body.put("fcmToken", "new-token");
        body.put("question", "career");

        Document result = f.service.book(f.store.user, body).data();

        assertEquals("waiting", result.getString("status"));
        assertEquals("private_call", result.getString("used_for"));
        assertEquals("chat-thread-1", result.getString("threadId"));
        assertEquals(f.store.user.get("_id").toString(), result.getString("user_id"));
        assertEquals(f.store.consultant.get("_id").toString(), result.getString("consultant_id"));
        assertEquals("career", result.get("request_form", Document.class).get("question"));
        assertFalse(result.get("request_form", Document.class).containsKey("sessionMeta"));
        assertEquals("9000000001", result.get("request_form", Document.class).get("phoneNumber"));
        assertEquals(List.of("old-2", "old-3", "new-token"), f.store.updatedTokens);
        assertTrue(result.getBoolean("isConsultantJustReadyToConnect"));
        verify(f.chat).feedFirstMessageForm(anyMap());
        assertTrue(f.store.released.size() == 2);
    }

    @Test
    void offlineConsultantIsRejectedBeforeThreadOrDatabaseWrite() {
        Fixture f = fixture("", false);
        assertThrows(SessionBookingService.ConsultantUnavailableException.class,
                () -> f.service.book(f.store.user, request("chat")));
        assertNull(f.store.persisted);
        verify(f.chat, never()).createThread(anyMap());
    }

    @Test
    void existingActiveWaitlistRejectsBeforePricing() {
        Fixture f = fixture("", true);
        f.store.active = true;
        assertEquals("You are already in waitlist!", assertThrows(IllegalStateException.class,
                () -> f.service.book(f.store.user, request("chat"))).getMessage());
        verifyNoInteractions(f.pricing);
    }

    @Test
    void scheduledCrossMidnightSessionDebitsDiscountedFifteenMinuteAmount() {
        Fixture f = fixture("", true);
        Map<String, Object> body = request("session-book");
        ((Map<String, Object>) body.get("sessionMeta")).put("slotDay", "MONDAY");
        ((Map<String, Object>) body.get("sessionMeta")).put("slotTime",
                Map.of("from", "23:45", "to", "00:00"));

        Document result = f.service.book(f.store.user, body).data();

        assertNotNull(f.store.scheduled);
        assertEquals(15, f.store.scheduled.minutes());
        assertEquals(20, f.store.scheduled.discountPercentage());
        assertEquals(120, f.store.scheduled.amount());
        Document info = result.get("session_info", Document.class);
        assertEquals(8, ((Number) info.get("price")).doubleValue());
        assertEquals(120, ((Number) info.get("holdAmount")).doubleValue());
        assertEquals("session", result.getString("used_for"));
        assertFalse(result.getBoolean("isConsultantJustReadyToConnect"));
    }

    @Test
    void failedChatFormFeedCreatesDurableRetryWithoutLosingBooking() {
        Fixture f = fixture("", true);
        when(f.chat.feedFirstMessageForm(anyMap())).thenThrow(new RuntimeException("down"));
        Document result = f.service.book(f.store.user, request("chat")).data();
        assertNotNull(result);
        assertEquals("chat-thread-1", f.store.retryThread);
        assertNotNull(f.store.retryForm);
    }

    @Test
    void exotelKeepsCallInitiationButClientReadyFlagFalse() {
        Fixture f = fixture("exotel", true);
        Document result = f.service.book(f.store.user, request("audio")).data();
        assertEquals("chat", result.get("session_info", Document.class).getString("mode"));
        assertEquals("audio", result.get("session_info", Document.class).getString("requestedMode"));
        assertFalse(result.getBoolean("isConsultantJustReadyToConnect"));
    }

    @SuppressWarnings("unchecked")
    private Fixture fixture(String courier, boolean live) {
        FakeStore store = new FakeStore(live);
        SessionBookingPricingService pricing = mock(SessionBookingPricingService.class);
        when(pricing.quote(anyString(), any(Document.class), any(Document.class), anyBoolean(), anyString()))
                .thenReturn(new SessionBookingPricingService.Quote(10, 10, false, null,
                        false, 50, 600, null, null));
        // Mockito does not match null with anyString().
        when(pricing.quote(anyString(), any(Document.class), any(Document.class), anyBoolean(), isNull()))
                .thenReturn(new SessionBookingPricingService.Quote(10, 10, false, null,
                        false, 50, 600, null, null));
        ChatServerClient chat = mock(ChatServerClient.class);
        when(chat.createThread(anyMap())).thenReturn(Map.of("success", true, "data", "chat-thread-1"));
        when(chat.feedFirstMessageForm(anyMap())).thenReturn(Map.of("success", true));
        return new Fixture(new SessionBookingService(store, pricing, chat, courier), store, pricing, chat);
    }

    private Map<String, Object> request(String mode) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("consultantId", CONSULTANT_ID);
        meta.put("mode", mode);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("sessionMeta", meta);
        return request;
    }

    private static final String CONSULTANT_ID = "64b000000000000000000002";

    private static class FakeStore implements SessionBookingStore {
        final Document user;
        final Document consultant;
        boolean active;
        List<String> updatedTokens;
        Document persisted;
        ScheduledCharge scheduled;
        String retryThread;
        Document retryForm;
        final List<Lease> released = new ArrayList<>();

        FakeStore(boolean live) {
            user = new Document("_id", new ObjectId("64b000000000000000000001"))
                    .append("name", "Test User").append("wallet", 200).append("subscription", "none")
                    .append("details", new Document("phone", "9000000001"))
                    .append("device", new Document("fcmToken", List.of("old-1", "old-2", "old-3"))
                            .append("uuid", List.of("device-1")));
            consultant = new Document("_id", new ObjectId(CONSULTANT_ID)).append("accountName", "Astro")
                    .append("price", new Document("default", 10).append("platformShare", 60))
                    .append("sessionsStatus", new Document("isChatLive", live).append("isVoiceLive", live)
                            .append("isVideoLive", live).append("canEndTheCall", false));
        }
        @Override public Document loadUser(Object userId) { return user; }
        @Override public Document loadConsultant(String consultantId) { return consultant; }
        @Override public boolean isUserBlocked(Object userId, Object consultantId) { return false; }
        @Override public boolean hasActiveWaitlist(String userId) { return active; }
        @Override public void updateFcmTokens(Object userId, List<String> tokens) { updatedTokens = tokens; }
        @Override public String nextOrderId() { return "VM26SEP081"; }
        @Override public Lease acquire(String key, Duration ttl) { return new Lease(key, key + "-token"); }
        @Override public void release(Lease lease) { if (lease != null) released.add(lease); }
        @Override public PersistResult persist(Document waitlist, SessionBookingPricingService.Quote quote,
                                               ScheduledCharge charge, boolean modeLive,
                                               String userName, String consultantName) {
            persisted = waitlist; scheduled = charge;
            Document result = new Document(waitlist).append("isConsultantJustReadyToConnect", charge == null);
            return new PersistResult(result, 0, charge == null);
        }
        @Override public void enqueueChatFormRetry(String waitlistId, String threadId, Document form) {
            retryThread = threadId; retryForm = form;
        }
    }

    private record Fixture(SessionBookingService service, FakeStore store,
                           SessionBookingPricingService pricing, ChatServerClient chat) {}
}
