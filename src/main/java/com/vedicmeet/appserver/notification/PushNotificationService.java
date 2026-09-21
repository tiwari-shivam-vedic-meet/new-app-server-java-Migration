package com.vedicmeet.appserver.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * FCM push, ported from Node sendNotificationAndCons (utils/functions/firebase.js L91-189).
 *
 * The FCM HTTP v1 message payload is ported faithfully by buildFcmMessage. Transport is provided by
 * {@link FcmPushClient}; iOS PushKit delivery is provided by {@link ApnsVoipPushClient}. Both load
 * credentials only from configured external files and remain disabled by default.
 */
@Service
public class PushNotificationService {

    private static final Logger log = LoggerFactory.getLogger(PushNotificationService.class);

    private final ObjectMapper mapper;
    private final FcmPushClient fcm;
    private final ApnsVoipPushClient apns;

    /** Pure-payload constructor retained for isolated unit tests. */
    public PushNotificationService(ObjectMapper mapper) {
        this(mapper, null, null);
    }

    @Autowired
    public PushNotificationService(ObjectMapper mapper, FcmPushClient fcm,
                                   ApnsVoipPushClient apns) {
        this.mapper = mapper;
        this.fcm = fcm;
        this.apns = apns;
    }

    /** FAITHFUL port of the sendNotificationAndCons FCM v1 message body. Pure + testable. */
    public Map<String, Object> buildFcmMessage(String token, String message, String title,
                                               String type, Map<String, Object> data) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("token", token);

        Map<String, Object> notification = new LinkedHashMap<>();
        notification.put("title", title);
        notification.put("body", message);
        msg.put("notification", notification);

        if ("task".equals(type)) {
            Map<String, Object> android = new LinkedHashMap<>();
            android.put("priority", "high");
            android.put("notification", mapOf("sound", "session_morning.mp3", "channel_id", "vedic_alarm"));
            msg.put("android", android);
            Map<String, Object> aps = new LinkedHashMap<>();
            aps.put("sound", "session_morning.mp3");
            aps.put("contentAvailable", true);
            msg.put("apns", mapOf("payload", mapOf("aps", aps)));
        }

        if ("session_start".equals(type)) {
            boolean useDefaultSound = data != null && Boolean.TRUE.equals(data.get("useDefaultSound"));
            Map<String, Object> aps = new LinkedHashMap<>();
            aps.put("sound", useDefaultSound ? "default" : "session_start.mp3");
            aps.put("contentAvailable", true);
            aps.put("mutable-content", 1);
            aps.put("interruption-level", "critical");
            aps.put("badge", 1);
            aps.put("alert", mapOf("title", title, "body", message));
            msg.put("apns", mapOf("payload", mapOf("aps", aps)));
            Map<String, Object> android = new LinkedHashMap<>();
            android.put("priority", "high");
            android.put("notification", mapOf("sound", "session_start.mp3", "channel_id", "session_start"));
            msg.put("android", android);
        }

        Map<String, Object> dataBlock = new LinkedHashMap<>();
        if (data != null && truthy(data.get("isConsAvailable"))) {
            dataBlock.put("isConsAvailable", "true");
        }
        dataBlock.put("customData", stringify(data));
        dataBlock.put("sound", "session_start".equals(type) ? "session_start.mp3" : "default");
        dataBlock.put("title", title);
        dataBlock.put("body", message);
        dataBlock.put("type", type == null ? "" : type);
        msg.put("data", dataBlock);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("message", msg);
        return payload;
    }

    /** FAITHFUL sendNotificationAndCons(userType, token, message, data, title, type). */
    public boolean sendNotificationAndCons(String userType, String token, String message,
                                           Map<String, Object> data, String title, String type) {
        try {
            Map<String, Object> payload = buildFcmMessage(token == null ? null : token.toString(),
                    message, title == null ? "Vedic Meet" : title, type == null ? "" : type, data);
            if (fcm == null || token == null || token.isBlank()) return false;
            return fcm.send(userType, payload);
        } catch (RuntimeException e) {
            log.warn("FCM call failed userType={} type={}", userType, type);
            return false; // Node: catch -> return false
        }
    }

    /**
     * Port of {@code utils/classes/notification.js send}. Unlike sendNotificationAndCons this
     * supports data-only delivery, which the call preflight and call-ended paths depend on.
     */
    public boolean send(String userType, String token, String title, String body,
                        Map<String, Object> data, String channel, boolean includeNotification,
                        Map<String, Object> androidOptions) {
        try {
            if (fcm == null || token == null || token.isBlank()) return false;
            return fcm.send(userType, buildDirectFcmMessage(token, title, body, data, channel,
                    includeNotification, androidOptions));
        } catch (RuntimeException error) {
            log.warn("direct FCM call failed userType={}", userType);
            return false;
        }
    }

    public Map<String, Object> buildDirectFcmMessage(String token, String title, String body,
                                                      Map<String, Object> data, String channel,
                                                      boolean includeNotification,
                                                      Map<String, Object> androidOptions) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        if (data != null) normalized.putAll(data);
        if (normalized.get("screen") != null && normalized.get("params") != null
                && !(normalized.get("params") instanceof String)) {
            normalized.put("params", stringifyValue(normalized.get("params")));
        }

        Map<String, String> fcmData = new LinkedHashMap<>();
        normalized.forEach((key, value) -> fcmData.put(key,
                value instanceof Map || value instanceof Iterable
                        ? stringifyValue(value) : String.valueOf(value)));

        Map<String, Object> android = new LinkedHashMap<>();
        android.put("priority", "high");
        if (androidOptions != null) android.putAll(androidOptions);
        if (channel != null && !channel.isBlank()) {
            android.put("notification", mapOf("channel_id", channel));
        }

        Map<String, Object> aps = new LinkedHashMap<>();
        aps.put("sound", "session_start".equals(normalized.get("type"))
                ? "session_start.mp3" : "default");
        aps.put("badge", 1);
        if (includeNotification) aps.put("alert", mapOf("title", title, "body", body));
        Map<String, Object> apnsPayload = new LinkedHashMap<>();
        apnsPayload.put("aps", aps);
        apnsPayload.putAll(normalized);

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("data", fcmData);
        message.put("token", token);
        message.put("android", android);
        message.put("apns", mapOf("payload", apnsPayload,
                "headers", mapOf("apns-priority", "10")));
        if (includeNotification) {
            message.put("notification", mapOf("title", title, "body", body));
        }
        return mapOf("message", message);
    }

    /** Faithful APNs VoIP call used by the consultation lifecycle. */
    public boolean sendVoip(String voipToken, String userType, Map<String, Object> payload) {
        try {
            return apns != null && apns.send(voipToken, userType, payload);
        } catch (RuntimeException error) {
            log.warn("APNs VoIP call failed userType={}", userType);
            return false;
        }
    }

    /** Startup gate: both Firebase projects and APNs PushKit must be configured for call ownership. */
    public boolean callTransportReady() {
        return fcm != null && apns != null && fcm.isReady() && apns.isReady();
    }

    // ----- backward-compatible overloads used by existing callers -----

    public void sendNotificationAndCons(String type, String token, String message, String category) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (category != null) data.put("category", category);
        sendNotificationAndCons(type, token, message, data, "Vedic Meet", "");
    }

    public void sendNotificationAndCons(String type, String token, String message) {
        sendNotificationAndCons(type, token, message, null);
    }

    private String stringify(Map<String, Object> data) {
        try {
            return mapper.writeValueAsString(data == null ? "" : data);
        } catch (Exception e) {
            return "";
        }
    }

    private String stringifyValue(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception error) {
            return "";
        }
    }

    private boolean truthy(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) return !((String) v).isEmpty();
        return true;
    }

    private Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }
}
