package com.vedicmeet.appserver.cron;

import com.vedicmeet.appserver.call.CallIntegrationOutboxService;
import com.vedicmeet.appserver.integrations.InteraktClient;
import com.vedicmeet.appserver.integrations.PabblyClient;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.*;

class CronIntegrationServiceTest {

    @Test
    void pabblyJobIsStepIdempotent() {
        CallIntegrationOutboxService outbox = mock(CallIntegrationOutboxService.class);
        PabblyClient pabbly = mock(PabblyClient.class);
        CallIntegrationOutboxService.ClaimedJob job = job(
                new Document("data", new Document("Name", "A")));
        when(outbox.stepCompleted(job, "pabbly")).thenReturn(false);

        new CronIntegrationService(outbox, pabbly, mock(InteraktClient.class)).pabbly(job);

        verify(pabbly).send(argThat(data -> "A".equals(data.get("Name"))));
        verify(outbox).markStep("J", "pabbly");
    }

    @Test
    void completedInteraktStepDoesNotCallProviderAgain() {
        CallIntegrationOutboxService outbox = mock(CallIntegrationOutboxService.class);
        InteraktClient interakt = mock(InteraktClient.class);
        CallIntegrationOutboxService.ClaimedJob job = job(new Document());
        when(outbox.stepCompleted(job, "interakt")).thenReturn(true);

        new CronIntegrationService(outbox, mock(PabblyClient.class), interakt).interakt(job);

        verifyNoInteractions(interakt);
        verify(outbox, never()).markStep(anyString(), anyString());
    }

    private CallIntegrationOutboxService.ClaimedJob job(Document payload) {
        return new CallIntegrationOutboxService.ClaimedJob("J", "CRON", payload, 1,
                List.of(), new Document("_id", "J"));
    }
}
