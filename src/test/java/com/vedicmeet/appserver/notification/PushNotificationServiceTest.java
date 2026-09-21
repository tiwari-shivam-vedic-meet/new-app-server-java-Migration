package com.vedicmeet.appserver.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Pins the faithful FCM v1 payload of {@link PushNotificationService} (Node sendNotificationAndCons). */
class PushNotificationServiceTest {

    private final PushNotificationService svc = new PushNotificationService(new ObjectMapper());

    @SuppressWarnings("unchecked")
    private Map<String, Object> msg(Map<String, Object> payload) {
        return (Map<String, Object>) payload.get("message");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sub(Map<String, Object> m, String k) {
        return (Map<String, Object>) m.get(k);
    }

    @Test
    void basicMessage_hasNotificationAndDataBlock_noAndroidApns() {
        var data = new LinkedHashMap<String, Object>();
        data.put("foo", "bar");
        var payload = svc.buildFcmMessage("tok", "hello", "Vedic Meet", "", data);
        var m = msg(payload);
        assertEquals("tok", m.get("token"));
        assertEquals("hello", sub(m, "notification").get("body"));
        assertFalse(m.containsKey("android"));
        assertFalse(m.containsKey("apns"));
        var d = sub(m, "data");
        assertEquals("default", d.get("sound"));
        assertEquals("", d.get("type"));
        assertEquals("{\"foo\":\"bar\"}", d.get("customData"), "customData = JSON.stringify(data)");
    }

    @Test
    void sessionStart_addsCriticalApnsAndAndroidChannel() {
        var payload = svc.buildFcmMessage("tok", "Session starting", "Vedic Meet", "session_start", Map.of());
        var m = msg(payload);
        var aps = sub(sub(sub(m, "apns"), "payload"), "aps");
        assertEquals("session_start.mp3", aps.get("sound"));
        assertEquals("critical", aps.get("interruption-level"));
        assertEquals(1, aps.get("badge"));
        assertEquals("session_start", sub(sub(m, "android"), "notification").get("channel_id"));
        assertEquals("session_start.mp3", sub(m, "data").get("sound"));
    }

    @Test
    void sessionStart_useDefaultSound_switchesApsSoundToDefault() {
        var payload = svc.buildFcmMessage("tok", "x", "Vedic Meet", "session_start", Map.of("useDefaultSound", true));
        var aps = sub(sub(sub(msg(payload), "apns"), "payload"), "aps");
        assertEquals("default", aps.get("sound"));
    }

    @Test
    void task_addsAlarmChannelAndSound() {
        var payload = svc.buildFcmMessage("tok", "task!", "Vedic Meet", "task", Map.of());
        var m = msg(payload);
        assertEquals("vedic_alarm", sub(sub(m, "android"), "notification").get("channel_id"));
        assertEquals("session_morning.mp3", sub(sub(sub(m, "apns"), "payload"), "aps").get("sound"));
    }

    @Test
    void isConsAvailable_addsFlagAsStringTrue() {
        var payload = svc.buildFcmMessage("tok", "x", "Vedic Meet", "", Map.of("isConsAvailable", true));
        assertEquals("true", sub(msg(payload), "data").get("isConsAvailable"));
    }

    @Test
    void directDataOnlyMessage_omitsVisibleNotificationAndStringifiesNestedData() {
        var payload = svc.buildDirectFcmMessage("tok", "Call Ended", "x",
                Map.of("type", "session_ended", "params", Map.of("id", "1"), "sentAt", 10L),
                null, false, Map.of("ttl", "45000ms"));
        var m = msg(payload);
        assertFalse(m.containsKey("notification"));
        assertEquals("session_ended", sub(m, "data").get("type"));
        assertEquals("{\"id\":\"1\"}", sub(m, "data").get("params"));
        assertEquals("45000ms", sub(m, "android").get("ttl"));
        assertFalse(sub(sub(sub(m, "apns"), "payload"), "aps").containsKey("alert"));
    }
}
