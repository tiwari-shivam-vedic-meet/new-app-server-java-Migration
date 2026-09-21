package com.vedicmeet.appserver.session;

import com.vedicmeet.appserver.call.CallExtendService;
import com.vedicmeet.appserver.call.CallIntegrationOutboxService;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import com.vedicmeet.appserver.notification.PushNotificationService;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/** Retryable external work emitted by user-session commands. */
@Service
public class UserSessionIntegrationService {

    private final MongoTemplate mongo;
    private final CallIntegrationOutboxService outbox;
    private final CallExtendService calls;
    private final PushNotificationService push;

    public UserSessionIntegrationService(MongoTemplate mongo, CallIntegrationOutboxService outbox,
                                         CallExtendService calls, PushNotificationService push) {
        this.mongo = mongo;
        this.outbox = outbox;
        this.calls = calls;
        this.push = push;
    }

    public void extendTimer(CallIntegrationOutboxService.ClaimedJob job) {
        if (outbox.stepCompleted(job, "timer")) return;
        String waitlistId = required(job.payload(), "waitlistId");
        long seconds = number(job.payload().get("additionalSeconds"));
        if (seconds <= 0 || !calls.extendCall(waitlistId, seconds)) {
            throw new IllegalStateException("CALL_TIMER_EXTENSION_FAILED");
        }
        outbox.markStep(job.id(), "timer");
    }

    public void queryCreated(CallIntegrationOutboxService.ClaimedJob job) {
        if (outbox.stepCompleted(job, "consultant-fanout")) return;
        Document payload = job.payload();
        String queryId = required(payload, "waitlistId");
        String userName = required(payload, "userName");
        String query = required(payload, "queryText");
        String styledTitle = "User raised daily query accept it soon & convert it and generate revenue baby";
        String styledBody = "Name : " + userName + "\nConcern : " + query;
        for (Document consultant : mongo.getCollection(Collections.CONSULTANTS).find(
                new Document("sessionsStatus.isChatLive", true).append("isDeleted", false).append("status", true))) {
            String consultantId = text(consultant.get("_id"));
            for (String token : tokens(consultant)) {
                push.send("cons", token, "Styled test (fallback title)", "Styled test (fallback body)",
                        Map.of("type", "styled_test", "styledTitle", styledTitle,
                                "styledSubtitle", "&#129395; QUICK QUERY", "styledBody", styledBody,
                                "styledColor", "#4caf50",
                                "styledActionPrimary", "<b>Accept the query</b> &#128111;",
                                "styledActionSecondary",
                                "<p style=\"color: #f44336;\"><b>Ignore (Dont have time)</b> &#128557;</p>",
                                "consultantId", consultantId, "queryId", queryId),
                        null, false, Map.of());
            }
        }
        outbox.markStep(job.id(), "consultant-fanout");
    }

    private List<String> tokens(Document actor) {
        Object value = doc(actor.get("device")).get("fcmToken");
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().filter(item -> item != null && !text(item).isBlank())
                .map(String::valueOf).toList();
    }
    private String required(Document payload, String key) {
        String value = text(payload.get(key));
        if (value.isBlank()) throw new IllegalStateException("USER_SESSION_JOB_MISSING_" + key.toUpperCase());
        return value;
    }
    private long number(Object value) {
        if (value instanceof Number n) return n.longValue();
        try { return value == null ? 0 : Long.parseLong(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return 0; }
    }
    private Document doc(Object value) { return value instanceof Document d ? d : new Document(); }
    private String text(Object value) { return value == null ? "" : String.valueOf(value); }
}
