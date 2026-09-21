package com.vedicmeet.appserver.call;

import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Call-initiate state-machine contract for {@link CallLifecycleService}, mirrored from the Jest
 * "initiateCall — validation gates" suite (tests-integration/call/call-lifecycle.test.js). Uses an
 * in-memory {@link FakeCallGuardStore} so each transition + its cancel/miss log string is pinned
 * without Redis/Socket.IO/pricing. Shadow-only / real-time human-review + harness gate.
 */
class CallLifecycleServiceTest {

    private Document consultant(boolean chat, boolean voice, boolean video) {
        return new Document("_id", "C").append("accountName", "Guru")
                .append("sessionsStatus", new Document("isChatLive", chat).append("isVoiceLive", voice).append("isVideoLive", video));
    }

    @Test
    void userNotFound_cancelsWaitlist_andCallsNext() {
        FakeCallGuardStore s = new FakeCallGuardStore();
        s.user = null;
        s.consultant = consultant(true, true, true);
        s.waitlistStatus = "waiting";

        CallLifecycleService.InitiateOutcome out =
                new CallLifecycleService(s).initiateCall("U", "C", "R", "chat");

        assertEquals(CallLifecycleService.InitiateOutcome.CANCELED_PARTY_NOT_FOUND, out);
        assertEquals("cancelled - user not found", s.cancelLog);
        assertEquals(1, s.callToNextCount, "consultant present → advance the queue");
        assertFalse(s.openCalled);
    }

    @Test
    void insufficientBalance_cancelsWaitlist() {
        FakeCallGuardStore s = new FakeCallGuardStore();
        s.user = new Document("_id", "U").append("wallet", 10);
        s.consultant = consultant(true, true, true);
        s.waitlistStatus = "waiting";
        s.minBalance = false;

        CallLifecycleService.InitiateOutcome out =
                new CallLifecycleService(s).initiateCall("U", "C", "R", "chat");

        assertEquals(CallLifecycleService.InitiateOutcome.CANCELED_INSUFFICIENT_BALANCE, out);
        assertEquals("cancelled due to insufficient balance", s.cancelLog);
    }

    @Test
    void alreadyCanceledWaitlist_isHardNoOp() {
        FakeCallGuardStore s = new FakeCallGuardStore();
        s.user = new Document("_id", "U");
        s.consultant = consultant(true, true, true);
        s.waitlistStatus = "canceled";

        CallLifecycleService.InitiateOutcome out =
                new CallLifecycleService(s).initiateCall("U", "C", "R", "chat");

        assertEquals(CallLifecycleService.InitiateOutcome.NOOP_ALREADY_CLOSED, out);
        assertNull(s.cancelLog, "no resurrection / re-cancel");
        assertNull(s.missedLog);
        assertFalse(s.openCalled);
    }

    @Test
    void consultantModeOff_cancelsWithNoLongerAvailable() {
        FakeCallGuardStore s = new FakeCallGuardStore();
        s.user = new Document("_id", "U");
        s.consultant = consultant(true, false, true); // voice OFF
        s.waitlistStatus = "waiting";
        s.minBalance = true;

        CallLifecycleService.InitiateOutcome out =
                new CallLifecycleService(s).initiateCall("U", "C", "R", "audio");

        assertEquals(CallLifecycleService.InitiateOutcome.CANCELED_MODE_UNAVAILABLE, out);
        assertEquals("cancelled - consultant no longer available for this mode", s.cancelLog);
    }

    @Test
    void partyBusy_defersToMissed() {
        FakeCallGuardStore s = new FakeCallGuardStore();
        s.user = new Document("_id", "U");
        s.consultant = consultant(true, true, true);
        s.waitlistStatus = "waiting";
        s.minBalance = true;
        s.busy = "consultant";

        CallLifecycleService.InitiateOutcome out =
                new CallLifecycleService(s).initiateCall("U", "C", "R", "chat");

        assertEquals(CallLifecycleService.InitiateOutcome.DEFERRED_MISSED, out);
        assertEquals("call deferred - consultant is busy", s.missedLog);
        assertFalse(s.openCalled);
    }

    @Test
    void allGatesPass_opensCall() {
        FakeCallGuardStore s = new FakeCallGuardStore();
        s.user = new Document("_id", "U");
        s.consultant = consultant(true, true, true);
        s.waitlistStatus = "waiting";
        s.minBalance = true;
        s.busy = null;

        CallLifecycleService.InitiateOutcome out =
                new CallLifecycleService(s).initiateCall("U", "C", "R", "chat");

        assertEquals(CallLifecycleService.InitiateOutcome.INITIATED, out);
        assertTrue(s.openCalled);
        assertNull(s.cancelLog);
        assertNull(s.missedLog);
    }

    @Test
    void failedFcmPreflight_cancelsWithoutOpeningCall() {
        FakeCallGuardStore s = new FakeCallGuardStore();
        s.user = new Document("_id", "U");
        s.consultant = consultant(true, true, true);
        s.waitlistStatus = "waiting";
        s.unreachable = "user uninstalled";

        CallLifecycleService.InitiateOutcome out =
                new CallLifecycleService(s).initiateCall("U", "C", "R", "chat");

        assertEquals(CallLifecycleService.InitiateOutcome.CANCELED_USER_UNREACHABLE, out);
        assertFalse(s.openCalled);
    }

    @Test
    void alreadyActiveWaitlist_isRetrySafeNoOp() {
        for (String activeStatus : List.of("initiated", "partially_accepted", "progress")) {
            FakeCallGuardStore s = new FakeCallGuardStore();
            s.user = new Document("_id", "U");
            s.consultant = consultant(true, true, true);
            s.waitlistStatus = activeStatus;

            CallLifecycleService.InitiateOutcome out =
                    new CallLifecycleService(s).initiateCall("U", "C", "R", "chat");

            assertEquals(CallLifecycleService.InitiateOutcome.NOOP_ALREADY_ACTIVE, out);
            assertFalse(s.openCalled, activeStatus);
            assertNull(s.cancelLog, activeStatus);
            assertNull(s.missedLog, activeStatus);
        }
    }

    static class FakeCallGuardStore implements CallGuardStore {
        Document user;
        Document consultant;
        String waitlistStatus;
        boolean minBalance = true;
        String busy = null;
        String unreachable = null;

        String cancelLog;
        String missedLog;
        boolean openCalled = false;
        int callToNextCount = 0;
        final List<String> log = new CopyOnWriteArrayList<>();

        @Override public Document loadUser(String userId) { return user; }
        @Override public Document loadConsultant(String consultantId) { return consultant; }
        @Override public String loadWaitlistStatus(String roomId) { return waitlistStatus; }
        @Override public boolean hasMinimumBalance(Document u, Document c, String m, String r) { return minBalance; }
        @Override public String busyParty(String userId, String consultantId) { return busy; }
        @Override public String validateUserReachability(Document u, Document c, String userId,
                                                         String consultantId, String roomId) {
            return unreachable;
        }
        @Override public void cancelWaitlist(String roomId, String logMessage) { cancelLog = logMessage; }
        @Override public void setMissed(String roomId, String logMessage) { missedLog = logMessage; }
        @Override public void callToNextUser(String consultantId, String reason) { callToNextCount++; }
        @Override public void openCall(String roomId, String userId, String consultantId, String callMode) { openCalled = true; }
    }
}
