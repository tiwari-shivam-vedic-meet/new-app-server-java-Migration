package com.vedicmeet.appserver.call;

import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Pins the faithful {@link CallQueueService} (Node sendCallNotificationToNextUser L341). */
class CallQueueServiceTest {

    @Test
    void noLiveMode_returnsNull() {
        Fake s = new Fake();
        Document cons = new Document("_id", "C").append("sessionsStatus", new Document("isChatLive", false));
        assertNull(new CallQueueService(s).sendCallNotificationToNextUser(cons));
        assertNull(s.askedModes, "no aggregation when no mode live");
    }

    @Test
    void chatLive_derivesModes_andReturnsFirstEntryAsNextCaller() {
        Fake s = new Fake();
        s.entries = List.of(new Document("_id", "W1").append("user_id", "U1")
                .append("session_info", new Document("mode", "chat")));
        Document cons = new Document("_id", "C")
                .append("sessionsStatus", new Document("isChatLive", true).append("isVideoLive", true));

        CallQueueService.NextCaller next = new CallQueueService(s).sendCallNotificationToNextUser(cons);

        assertNotNull(next);
        assertEquals("cons", next.callBy);
        assertEquals("U1", next.userId);
        assertEquals("C", next.consultantId);
        assertEquals("W1", next.roomId);
        assertEquals("chat", next.callMode);
        assertTrue(s.askedModes.contains("chat") && s.askedModes.contains("video"));
        assertFalse(s.askedModes.contains("audio"), "voice not live → no audio mode");
    }

    @Test
    void liveButEmptyQueue_returnsNull() {
        Fake s = new Fake();
        s.entries = List.of();
        Document cons = new Document("_id", "C").append("sessionsStatus", new Document("isVoiceLive", true));
        assertNull(new CallQueueService(s).sendCallNotificationToNextUser(cons));
        assertTrue(s.askedModes.contains("audio"));
    }

    static class Fake implements CallQueueStore {
        List<Document> entries;
        List<String> askedModes;

        @Override public List<Document> findWaitingPrivateCalls(String consultantId, List<String> activeModes) {
            askedModes = activeModes;
            return entries;
        }
    }
}
