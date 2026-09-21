package com.vedicmeet.appserver.auth.service;

import com.vedicmeet.appserver.integrations.CleverTapClient;
import com.vedicmeet.appserver.integrations.InteraktClient;
import com.vedicmeet.appserver.notification.PushNotificationService;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/** Best-effort registration side effects; failures never roll back the Mongo account. */
@Service
public class RegistrationIntegrationService {

    private static final Logger log = LoggerFactory.getLogger(RegistrationIntegrationService.class);
    private final CleverTapClient cleverTap;
    private final InteraktClient interakt;
    private final PushNotificationService notifications;

    public RegistrationIntegrationService(CleverTapClient cleverTap, InteraktClient interakt,
                                          PushNotificationService notifications) {
        this.cleverTap = cleverTap;
        this.interakt = interakt;
        this.notifications = notifications;
    }

    public void userRegistered(Document user, String fcmToken, Map<String, Object> tracking) {
        Document details = user.get("details") instanceof Document d ? d : new Document();
        String phone = string(details.get("phone"));
        String id = string(user.get("_id"));
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("Name", user.get("name"));
        profile.put("Email", details.get("email"));
        profile.put("Phone", "+" + string(details.get("phonePrefix")) + phone);
        profile.put("Gender", details.get("gender"));
        profile.put("DOB", details.get("dob"));
        profile.put("Problems", details.get("problems"));
        profile.put("UserId", user.get("userId"));
        profile.put("DeviceType", user.get("deviceType"));
        profile.put("IsActive", "online");
        profile.put("IsVerified", true);
        profile.put("Zodiac", user.get("zodiac"));
        if (tracking != null) profile.putAll(tracking);

        safely("CleverTap", () -> {
            cleverTap.uploadProfile(phone, profile);
            cleverTap.uploadEvent(phone, "Registration Completed", profile);
        });
        safely("Interakt", () -> interakt.queueInteraktEvent(
                "registration_completed", id, phone, profile, profile));
        if (fcmToken != null && !fcmToken.isBlank()) {
            safely("FCM", () -> notifications.sendNotificationAndCons(
                    "user", fcmToken, "Welcome to Vedic Meet", Map.of(), "Vedic Meet", "user"));
        }
    }

    public void consultantRegistered(Document consultant, String fcmToken) {
        if (fcmToken == null || fcmToken.isBlank()) return;
        safely("FCM", () -> notifications.sendNotificationAndCons(
                "cons", fcmToken, "Registration completed. Approval is pending.",
                Map.of(), "Vedic Meet", "cons"));
    }

    private void safely(String integration, Runnable action) {
        try { action.run(); }
        catch (RuntimeException e) { log.warn("{} registration side effect failed: {}", integration, e.getMessage()); }
    }

    private String string(Object value) { return value == null ? "" : String.valueOf(value); }
}
