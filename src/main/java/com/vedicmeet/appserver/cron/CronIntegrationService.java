package com.vedicmeet.appserver.cron;

import com.vedicmeet.appserver.call.CallIntegrationOutboxService;
import com.vedicmeet.appserver.integrations.InteraktClient;
import com.vedicmeet.appserver.integrations.PabblyClient;
import org.bson.Document;
import org.springframework.stereotype.Service;

import java.util.Map;

/** Executes retryable provider work created by the legacy cron port. */
@Service
public class CronIntegrationService {

    private final CallIntegrationOutboxService outbox;
    private final PabblyClient pabbly;
    private final InteraktClient interakt;

    public CronIntegrationService(CallIntegrationOutboxService outbox, PabblyClient pabbly,
                                  InteraktClient interakt) {
        this.outbox = outbox;
        this.pabbly = pabbly;
        this.interakt = interakt;
    }

    public void pabbly(CallIntegrationOutboxService.ClaimedJob job) {
        if (outbox.stepCompleted(job, "pabbly")) return;
        pabbly.send(map(job.payload().get("data")));
        outbox.markStep(job.id(), "pabbly");
    }

    public void interakt(CallIntegrationOutboxService.ClaimedJob job) {
        if (outbox.stepCompleted(job, "interakt")) return;
        Document payload = job.payload();
        interakt.queueInteraktEvent(required(payload, "eventName"),
                required(payload, "userId"), text(payload.get("phone")),
                map(payload.get("eventProperties")), map(payload.get("traits")));
        outbox.markStep(job.id(), "interakt");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        if (value instanceof Document document) return (Map<String, Object>) (Map<?, ?>) document;
        return value instanceof Map<?, ?> raw ? (Map<String, Object>) raw : Map.of();
    }

    private String required(Document source, String key) {
        String value = text(source.get(key));
        if (value.isBlank()) throw new IllegalStateException("CRON_JOB_MISSING_" + key.toUpperCase());
        return value;
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
