package com.vedicmeet.appserver.call;

import com.vedicmeet.appserver.session.BookingIntegrationService;
import com.vedicmeet.appserver.realtime.AppSocketIntegrationService;
import com.vedicmeet.appserver.consultant.ConsultantIntegrationService;
import com.vedicmeet.appserver.cron.CronIntegrationService;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.*;

class CallIntegrationOutboxWorkerTest {

    @Test
    void postCallJob_isDispatchedAndCompleted() {
        CallIntegrationOutboxService outbox = mock(CallIntegrationOutboxService.class);
        CallPostProcessingService post = mock(CallPostProcessingService.class);
        FreeConsultationContinuationService free = mock(FreeConsultationContinuationService.class);
        BookingIntegrationService booking = mock(BookingIntegrationService.class);
        CallIntegrationOutboxService.ClaimedJob job = job("CALL_POST_PROCESSING");
        when(outbox.claimNextDue()).thenReturn(job, null);

        new CallIntegrationOutboxWorker(outbox, post, free, booking,
                mock(AppSocketIntegrationService.class), mock(ConsultantIntegrationService.class)).poll();

        verify(post).process(job);
        verify(free, never()).process(any());
        verify(outbox).markCompleted("J");
        verify(outbox, never()).retryOrFail(any(), any(), anyInt());
    }

    @Test
    void freeContinuationFailure_isRetriedAndNotMarkedCompleted() {
        CallIntegrationOutboxService outbox = mock(CallIntegrationOutboxService.class);
        CallPostProcessingService post = mock(CallPostProcessingService.class);
        FreeConsultationContinuationService free = mock(FreeConsultationContinuationService.class);
        BookingIntegrationService booking = mock(BookingIntegrationService.class);
        CallIntegrationOutboxService.ClaimedJob job = job("NEXT_FREE_CONSULTANT");
        RuntimeException failure = new RuntimeException("chat unavailable");
        when(outbox.claimNextDue()).thenReturn(job, null);
        doThrow(failure).when(free).process(job);

        new CallIntegrationOutboxWorker(outbox, post, free, booking,
                mock(AppSocketIntegrationService.class), mock(ConsultantIntegrationService.class)).poll();

        verify(outbox).retryOrFail(job, failure, 8);
        verify(outbox, never()).markCompleted(anyString());
    }

    @Test
    void unknownJobType_isFailedRatherThanSilentlyDiscarded() {
        CallIntegrationOutboxService outbox = mock(CallIntegrationOutboxService.class);
        CallIntegrationOutboxService.ClaimedJob job = job("UNKNOWN");
        when(outbox.claimNextDue()).thenReturn(job, null);

        new CallIntegrationOutboxWorker(outbox, mock(CallPostProcessingService.class),
                mock(FreeConsultationContinuationService.class), mock(BookingIntegrationService.class),
                mock(AppSocketIntegrationService.class), mock(ConsultantIntegrationService.class)).poll();

        verify(outbox).retryOrFail(eq(job), any(IllegalStateException.class), eq(8));
        verify(outbox, never()).markCompleted(anyString());
    }

    @Test
    void bookingInitiation_isDurablyDispatched() {
        CallIntegrationOutboxService outbox = mock(CallIntegrationOutboxService.class);
        BookingIntegrationService booking = mock(BookingIntegrationService.class);
        CallIntegrationOutboxService.ClaimedJob job = job("CALL_INITIATE");
        when(outbox.claimNextDue()).thenReturn(job, null);

        new CallIntegrationOutboxWorker(outbox, mock(CallPostProcessingService.class),
                mock(FreeConsultationContinuationService.class), booking,
                mock(AppSocketIntegrationService.class), mock(ConsultantIntegrationService.class)).poll();

        verify(booking).initiate(job);
        verify(outbox).markCompleted("J");
    }

    @Test
    void socketPush_isDurablyDispatched() {
        CallIntegrationOutboxService outbox = mock(CallIntegrationOutboxService.class);
        AppSocketIntegrationService sockets = mock(AppSocketIntegrationService.class);
        CallIntegrationOutboxService.ClaimedJob job = job("APP_PUSH_NOTIFICATION");
        when(outbox.claimNextDue()).thenReturn(job, null);

        new CallIntegrationOutboxWorker(outbox, mock(CallPostProcessingService.class),
                mock(FreeConsultationContinuationService.class), mock(BookingIntegrationService.class),
                sockets, mock(ConsultantIntegrationService.class)).poll();

        verify(sockets).push(job);
        verify(outbox).markCompleted("J");
    }

    @Test
    void consultantAvailability_isDurablyDispatched() {
        CallIntegrationOutboxService outbox = mock(CallIntegrationOutboxService.class);
        ConsultantIntegrationService consultants = mock(ConsultantIntegrationService.class);
        CallIntegrationOutboxService.ClaimedJob job = job("CONSULTANT_AVAILABILITY_CHANGED");
        when(outbox.claimNextDue()).thenReturn(job, null);

        new CallIntegrationOutboxWorker(outbox, mock(CallPostProcessingService.class),
                mock(FreeConsultationContinuationService.class), mock(BookingIntegrationService.class),
                mock(AppSocketIntegrationService.class), consultants).poll();

        verify(consultants).availabilityChanged(job);
        verify(outbox).markCompleted("J");
    }

    @Test
    void consultantOffer_isDurablyDispatched() {
        CallIntegrationOutboxService outbox = mock(CallIntegrationOutboxService.class);
        ConsultantIntegrationService consultants = mock(ConsultantIntegrationService.class);
        CallIntegrationOutboxService.ClaimedJob job = job("CONSULTANT_OFFER_ACTIVATED");
        when(outbox.claimNextDue()).thenReturn(job, null);

        new CallIntegrationOutboxWorker(outbox, mock(CallPostProcessingService.class),
                mock(FreeConsultationContinuationService.class), mock(BookingIntegrationService.class),
                mock(AppSocketIntegrationService.class), consultants).poll();

        verify(consultants).offerActivated(job);
        verify(outbox).markCompleted("J");
    }

    @Test
    void consultantEventNotification_isDurablyDispatched() {
        CallIntegrationOutboxService outbox = mock(CallIntegrationOutboxService.class);
        ConsultantIntegrationService consultants = mock(ConsultantIntegrationService.class);
        CallIntegrationOutboxService.ClaimedJob job = job("CONSULTANT_EVENT_CREATED");
        when(outbox.claimNextDue()).thenReturn(job, null);

        new CallIntegrationOutboxWorker(outbox, mock(CallPostProcessingService.class),
                mock(FreeConsultationContinuationService.class), mock(BookingIntegrationService.class),
                mock(AppSocketIntegrationService.class), consultants).poll();

        verify(consultants).eventCreated(job);
        verify(outbox).markCompleted("J");
    }

    @Test
    void consultantExploreNotification_isDurablyDispatched() {
        CallIntegrationOutboxService outbox = mock(CallIntegrationOutboxService.class);
        ConsultantIntegrationService consultants = mock(ConsultantIntegrationService.class);
        CallIntegrationOutboxService.ClaimedJob job = job("CONSULTANT_EXPLORE_CREATED");
        when(outbox.claimNextDue()).thenReturn(job, null);

        new CallIntegrationOutboxWorker(outbox, mock(CallPostProcessingService.class),
                mock(FreeConsultationContinuationService.class), mock(BookingIntegrationService.class),
                mock(AppSocketIntegrationService.class), consultants).poll();

        verify(consultants).exploreCreated(job);
        verify(outbox).markCompleted("J");
    }

    @Test
    void consultantQueryAccepted_isDurablyDispatched() {
        CallIntegrationOutboxService outbox = mock(CallIntegrationOutboxService.class);
        ConsultantIntegrationService consultants = mock(ConsultantIntegrationService.class);
        CallIntegrationOutboxService.ClaimedJob job = job("CONSULTANT_QUERY_ACCEPTED");
        when(outbox.claimNextDue()).thenReturn(job, null);

        new CallIntegrationOutboxWorker(outbox, mock(CallPostProcessingService.class),
                mock(FreeConsultationContinuationService.class), mock(BookingIntegrationService.class),
                mock(AppSocketIntegrationService.class), consultants).poll();

        verify(consultants).queryAccepted(job);
        verify(outbox).markCompleted("J");
    }

    @Test
    void consultantRefundNotification_isDurablyDispatched() {
        CallIntegrationOutboxService outbox = mock(CallIntegrationOutboxService.class);
        ConsultantIntegrationService consultants = mock(ConsultantIntegrationService.class);
        CallIntegrationOutboxService.ClaimedJob job = job("CONSULTANT_REFUND_NOTIFY");
        when(outbox.claimNextDue()).thenReturn(job, null);

        new CallIntegrationOutboxWorker(outbox, mock(CallPostProcessingService.class),
                mock(FreeConsultationContinuationService.class), mock(BookingIntegrationService.class),
                mock(AppSocketIntegrationService.class), consultants).poll();

        verify(consultants).refundNotified(job);
        verify(outbox).markCompleted("J");
    }

    @Test
    void cronProviderJob_isDurablyDispatched() {
        CallIntegrationOutboxService outbox = mock(CallIntegrationOutboxService.class);
        CronIntegrationService cron = mock(CronIntegrationService.class);
        CallIntegrationOutboxService.ClaimedJob job = job("CRON_PABBLY_EVENT");
        when(outbox.claimNextDue()).thenReturn(job, null);

        new CallIntegrationOutboxWorker(outbox, mock(CallPostProcessingService.class),
                mock(FreeConsultationContinuationService.class), mock(BookingIntegrationService.class),
                mock(AppSocketIntegrationService.class), mock(ConsultantIntegrationService.class),
                null, cron).poll();

        verify(cron).pabbly(job);
        verify(outbox).markCompleted("J");
    }

    private CallIntegrationOutboxService.ClaimedJob job(String type) {
        return new CallIntegrationOutboxService.ClaimedJob("J", type, new Document(), 1,
                List.of(), new Document("_id", "J"));
    }
}
