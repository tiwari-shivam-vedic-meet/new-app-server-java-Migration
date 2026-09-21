package com.vedicmeet.appserver.call;

import com.vedicmeet.appserver.session.BookingIntegrationService;
import com.vedicmeet.appserver.realtime.AppSocketIntegrationService;
import com.vedicmeet.appserver.consultant.ConsultantIntegrationService;
import com.vedicmeet.appserver.session.UserSessionIntegrationService;
import com.vedicmeet.appserver.cron.CronIntegrationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Executes Java-owned call integration jobs. Disabled until both write and worker gates are on. */
@Component
@EnableScheduling
@ConditionalOnExpression("'${vedicmeet.call.outbox-worker-enabled:false}' == 'true' and "
        + "'${vedicmeet.migration.writes-enabled:false}' == 'true'")
public class CallIntegrationOutboxWorker {

    private static final Logger log = LoggerFactory.getLogger(CallIntegrationOutboxWorker.class);
    private static final int MAX_BATCH = 20;
    private static final int MAX_ATTEMPTS = 8;

    private final CallIntegrationOutboxService outbox;
    private final CallPostProcessingService postCall;
    private final FreeConsultationContinuationService freeContinuation;
    private final BookingIntegrationService booking;
    private final AppSocketIntegrationService appSockets;
    private final ConsultantIntegrationService consultants;
    private final UserSessionIntegrationService userSessions;
    private final CronIntegrationService cronIntegrations;

    @Autowired
    public CallIntegrationOutboxWorker(CallIntegrationOutboxService outbox,
                                       CallPostProcessingService postCall,
                                       FreeConsultationContinuationService freeContinuation,
                                       BookingIntegrationService booking,
                                       AppSocketIntegrationService appSockets,
                                       ConsultantIntegrationService consultants,
                                       UserSessionIntegrationService userSessions,
                                       CronIntegrationService cronIntegrations) {
        this.outbox = outbox;
        this.postCall = postCall;
        this.freeContinuation = freeContinuation;
        this.booking = booking;
        this.appSockets = appSockets;
        this.consultants = consultants;
        this.userSessions = userSessions;
        this.cronIntegrations = cronIntegrations;
    }

    /** Compatibility constructor retained for isolated tests written before user-session jobs existed. */
    CallIntegrationOutboxWorker(CallIntegrationOutboxService outbox,
                                CallPostProcessingService postCall,
                                FreeConsultationContinuationService freeContinuation,
                                BookingIntegrationService booking,
                                AppSocketIntegrationService appSockets,
                                ConsultantIntegrationService consultants) {
        this(outbox, postCall, freeContinuation, booking, appSockets, consultants, null, null);
    }

    /** Compatibility constructor retained for tests that exercise user-session outbox jobs. */
    CallIntegrationOutboxWorker(CallIntegrationOutboxService outbox,
                                CallPostProcessingService postCall,
                                FreeConsultationContinuationService freeContinuation,
                                BookingIntegrationService booking,
                                AppSocketIntegrationService appSockets,
                                ConsultantIntegrationService consultants,
                                UserSessionIntegrationService userSessions) {
        this(outbox, postCall, freeContinuation, booking, appSockets, consultants,
                userSessions, null);
    }

    @Scheduled(fixedDelayString = "${vedicmeet.call.outbox-poll-ms:1000}")
    public void poll() {
        for (int i = 0; i < MAX_BATCH; i++) {
            CallIntegrationOutboxService.ClaimedJob job = outbox.claimNextDue();
            if (job == null) return;
            try {
                switch (job.type()) {
                    case "CALL_POST_PROCESSING" -> postCall.process(job);
                    case "NEXT_FREE_CONSULTANT" -> freeContinuation.process(job);
                    case "CALL_INITIATE" -> booking.initiate(job);
                    case "BOOKING_POST_PROCESSING" -> booking.postBooking(job);
                    case "BOOKING_CHAT_FORM" -> booking.feedChatForm(job);
                    case "APP_PUSH_NOTIFICATION" -> appSockets.push(job);
                    case "APP_INTERAKT_EVENT" -> appSockets.interakt(job);
                    case "CONSULTANT_AVAILABILITY_CHANGED" -> consultants.availabilityChanged(job);
                    case "CONSULTANT_OFFER_ACTIVATED" -> consultants.offerActivated(job);
                    case "CONSULTANT_EVENT_CREATED" -> consultants.eventCreated(job);
                    case "CONSULTANT_EXPLORE_CREATED" -> consultants.exploreCreated(job);
                    case "CONSULTANT_QUERY_ACCEPTED" -> consultants.queryAccepted(job);
                    case "CONSULTANT_REFUND_NOTIFY" -> consultants.refundNotified(job);
                    case "CALL_TIMER_EXTEND" -> requiredUserSessions().extendTimer(job);
                    case "USER_QUERY_CREATED" -> requiredUserSessions().queryCreated(job);
                    case "CRON_PABBLY_EVENT" -> requiredCronIntegrations().pabbly(job);
                    case "CRON_INTERAKT_EVENT" -> requiredCronIntegrations().interakt(job);
                    default -> throw new IllegalStateException("UNKNOWN_CALL_OUTBOX_TYPE");
                }
                outbox.markCompleted(job.id());
            } catch (RuntimeException error) {
                log.error("call integration job failed type={} id={} attempt={}",
                        job.type(), job.id(), job.attempts(), error);
                outbox.retryOrFail(job, error, MAX_ATTEMPTS);
            }
        }
    }

    private UserSessionIntegrationService requiredUserSessions() {
        if (userSessions == null) throw new IllegalStateException("USER_SESSION_INTEGRATION_NOT_WIRED");
        return userSessions;
    }

    private CronIntegrationService requiredCronIntegrations() {
        if (cronIntegrations == null) throw new IllegalStateException("CRON_INTEGRATION_NOT_WIRED");
        return cronIntegrations;
    }
}
