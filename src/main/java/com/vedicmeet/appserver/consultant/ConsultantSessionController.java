package com.vedicmeet.appserver.consultant;

import com.vedicmeet.appserver.migration.MigrationWrite;
import com.vedicmeet.appserver.security.AuthPrincipal;
import com.vedicmeet.appserver.security.AuthUserService;
import com.vedicmeet.appserver.security.CurrentUser;
import com.vedicmeet.appserver.security.RequireRole;
import com.vedicmeet.appserver.security.Role;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Exact consultant/session route family, exposed behind the migration /v2 prefix. */
@RestController
@RequestMapping("/v2/cons/session")
@RequireRole(Role.CONSULTANT)
public class ConsultantSessionController {

    private final AuthUserService users;
    private final ConsultantSessionReadService reads;
    private final ConsultantSessionCallService calls;
    private final ConsultantLiveEventCommandService liveEvents;
    private final ConsultantSessionAssignmentService assignments;
    private final ConsultantRefundService refunds;
    private final ConsultantRecordingService recordings;
    private final boolean sessionEnabled;
    private final boolean callEnabled;
    private final boolean immediateRefundEnabled;

    public ConsultantSessionController(AuthUserService users, ConsultantSessionReadService reads,
                                       ConsultantSessionCallService calls,
                                       ConsultantLiveEventCommandService liveEvents,
                                       ConsultantSessionAssignmentService assignments,
                                       ConsultantRefundService refunds,
                                       ConsultantRecordingService recordings,
                                       @Value("${vedicmeet.consultant-session.execution-enabled:false}")
                                       boolean sessionEnabled,
                                       @Value("${vedicmeet.call.execution-enabled:false}") boolean callEnabled,
                                       @Value("${vedicmeet.consultant-session.immediate-refund-enabled:false}")
                                       boolean immediateRefundEnabled) {
        this.users = users;
        this.reads = reads;
        this.calls = calls;
        this.liveEvents = liveEvents;
        this.assignments = assignments;
        this.refunds = refunds;
        this.recordings = recordings;
        this.sessionEnabled = sessionEnabled;
        this.callEnabled = callEnabled;
        this.immediateRefundEnabled = immediateRefundEnabled;
    }

    /** This GET creates/reuses a chat-server thread and is therefore correctly write-gated. */
    @GetMapping("/init")
    @MigrationWrite
    public Map<String, Object> initialize(@CurrentUser AuthPrincipal principal) {
        if (!sessionEnabled) return disabled();
        return execute("Successfully initialized!", () -> reads.initialize(actor(principal)));
    }

    @PostMapping("/call_status")
    @MigrationWrite
    public Map<String, Object> callStatus(@CurrentUser AuthPrincipal principal,
                                          @RequestBody(required = false) Map<String, Object> body) {
        if (!callEnabled) return failure("Java call execution is disabled");
        Map<String, Object> input = map(body);
        return execute("cancel".equals(text(input.get("type"))) || "pick".equals(text(input.get("type")))
                        ? "Call status deleted successfully" : "Successfully fetched!", () -> {
                    calls.callStatus(actor(principal), text(input.get("type")), text(input.get("appState")));
                    return null;
                });
    }

    @PostMapping("/create_live_event")
    @MigrationWrite
    public Map<String, Object> createLiveEvent(@CurrentUser AuthPrincipal principal,
                                               @RequestBody(required = false) Map<String, Object> body) {
        if (!sessionEnabled) return disabled();
        return execute("Successfully created!", () -> liveEvents.create(actor(principal), map(body)));
    }

    @PostMapping("/get_live_event")
    public Map<String, Object> getLiveEvent(@CurrentUser AuthPrincipal principal,
                                            @RequestBody(required = false) Map<String, Object> body) {
        return execute("Successfully fetched!", () ->
                reads.liveEvent(actor(principal), text(map(body).get("event_id"))));
    }

    @PostMapping("/close_live_event")
    @MigrationWrite
    public Map<String, Object> closeLiveEvent(@CurrentUser AuthPrincipal principal,
                                              @RequestBody(required = false) Map<String, Object> body) {
        if (!sessionEnabled) return disabled();
        return execute("Successfully fetched!", () ->
                liveEvents.close(actor(principal), text(map(body).get("event_id"))));
    }

    @PostMapping("/accept_incoming_session_request")
    @MigrationWrite
    public Map<String, Object> acceptIncoming(@CurrentUser AuthPrincipal principal,
                                              @RequestBody(required = false) Map<String, Object> body) {
        if (!callEnabled) return failure("Java call execution is disabled");
        Map<String, Object> input = map(body);
        return execute("Successfully fetched!", () -> calls.acceptIncoming(actor(principal),
                text(input.get("roomId")), nullableText(input.get("type"))));
    }

    @PostMapping("/accept-fixed-session-request")
    @MigrationWrite
    public Map<String, Object> acceptFixed(@CurrentUser AuthPrincipal principal,
                                           @RequestBody(required = false) Map<String, Object> body) {
        if (!sessionEnabled) return disabled();
        try {
            return assignment(assignments.acceptFixedSession(actor(principal),
                    text(map(body).get("waitlistId"))));
        } catch (RuntimeException failure) { return failure(message(failure)); }
    }

    @GetMapping("/waitlist_history")
    public Map<String, Object> history(@CurrentUser AuthPrincipal principal,
                                      @RequestParam(required = false) String type,
                                      @RequestParam(required = false) String consultationType) {
        return execute("Successfully fetched!", () ->
                reads.waitlistHistory(actor(principal), type, consultationType));
    }

    @GetMapping("/active_waitlist")
    public Map<String, Object> active(@CurrentUser AuthPrincipal principal) {
        return execute("Successfully fetched!", () -> reads.activeWaitlist(actor(principal)));
    }

    @PostMapping("/block_waitlist")
    @MigrationWrite
    public Map<String, Object> block(@CurrentUser AuthPrincipal principal,
                                     @RequestBody(required = false) Map<String, Object> body) {
        if (!sessionEnabled) return disabled();
        Map<String, Object> input = map(body);
        return execute("Blocked Request Sent Successfully!", () -> {
            calls.requestBlock(actor(principal), text(input.get("waitlistId")), text(input.get("reason")));
            return null;
        });
    }

    @PostMapping("/refund_waitlist_request")
    @MigrationWrite
    public Map<String, Object> requestRefund(@CurrentUser AuthPrincipal principal,
                                             @RequestBody(required = false) Map<String, Object> body) {
        if (!sessionEnabled) return disabled();
        return execute("Successfully request submitted!", () ->
                refunds.requestRefund(actor(principal), text(map(body).get("waitlistId"))));
    }

    @PostMapping("/refund_waitlist_immediate")
    @MigrationWrite
    public Map<String, Object> immediateRefund(@CurrentUser AuthPrincipal principal,
                                               @RequestBody(required = false) Map<String, Object> body) {
        if (!sessionEnabled || !immediateRefundEnabled) {
            return failure("Java immediate refund execution is disabled");
        }
        return execute("Successfully refunded!", () ->
                refunds.refundImmediately(actor(principal), text(map(body).get("waitlistId"))));
    }

    @PostMapping("/notify_user_to_connect")
    @MigrationWrite
    public Map<String, Object> notifyUser(@CurrentUser AuthPrincipal principal,
                                          @RequestBody(required = false) Map<String, Object> body) {
        if (!sessionEnabled || !callEnabled) return failure("Java call execution is disabled");
        return execute("Successfully notified user to connect!", () ->
                calls.notifyUserToConnect(actor(principal), text(map(body).get("waitlistId"))));
    }

    @PostMapping("/take_offline_session")
    @MigrationWrite
    public Map<String, Object> takeOffline(@CurrentUser AuthPrincipal principal,
                                           @RequestBody(required = false) Map<String, Object> body) {
        if (!sessionEnabled || !callEnabled) return failure("Java call execution is disabled");
        Map<String, Object> input = map(body);
        // Preserve Node's existing (misleading) message so mobile behavior does not drift.
        return execute("Successfully refunded!", () -> calls.takeOfflineSession(actor(principal),
                text(input.get("waitlistId")), Boolean.TRUE.equals(input.get("isQuickCallConnect"))));
    }

    @PostMapping("/accept-query")
    @MigrationWrite
    public Map<String, Object> acceptQuery(@CurrentUser AuthPrincipal principal,
                                           @RequestBody(required = false) Map<String, Object> body) {
        if (!sessionEnabled) return disabled();
        try {
            return assignment(assignments.acceptQuery(actor(principal), text(map(body).get("waitlistId"))));
        } catch (RuntimeException failure) { return failure(message(failure)); }
    }

    @PostMapping("/generate_token_1to1")
    @MigrationWrite
    public Map<String, Object> token(@CurrentUser AuthPrincipal principal,
                                     @RequestBody(required = false) Map<String, Object> body) {
        if (!sessionEnabled) return disabled();
        return execute("Successfully created!", () -> recordings.token(actor(principal), map(body)));
    }

    @PostMapping("/start-recording")
    @MigrationWrite
    public Map<String, Object> startRecording(@CurrentUser AuthPrincipal principal,
                                              @RequestBody(required = false) Map<String, Object> body) {
        if (!sessionEnabled) return disabled();
        return execute("Recording started successfully", () -> recordings.start(actor(principal), map(body)));
    }

    @PostMapping("/stop-recording")
    @MigrationWrite
    public Map<String, Object> stopRecording(@CurrentUser AuthPrincipal principal,
                                             @RequestBody(required = false) Map<String, Object> body) {
        if (!sessionEnabled) return disabled();
        try {
            ConsultantRecordingService.StopResult result = recordings.stop(actor(principal), map(body));
            return success(result.message(), result.data());
        } catch (RuntimeException failure) { return failure(message(failure)); }
    }

    private Document actor(AuthPrincipal principal) {
        Document actor = users.load(principal);
        if (actor == null) throw new IllegalStateException("Consultant not found");
        return actor;
    }

    private Map<String, Object> assignment(ConsultantSessionAssignmentService.AssignmentResult result) {
        Map<String, Object> response = result.success() ? success(result.message(), result.data())
                : failure(result.message());
        if (result.code() != null) response.put("code", result.code());
        if (!result.success() && result.data() != null) response.put("data", result.data());
        return response;
    }

    private Map<String, Object> execute(String message, Work work) {
        try { return success(message, work.run()); }
        catch (RuntimeException failure) { return failure(message(failure)); }
    }
    private Map<String, Object> success(String message, Object data) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        if (message != null) response.put("message", message);
        if (data != null) response.put("data", data);
        return response;
    }
    private Map<String, Object> failure(String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", false); response.put("message", message); return response;
    }
    private Map<String, Object> disabled() { return failure("Java consultant-session execution is disabled"); }
    private Map<String, Object> map(Map<String, Object> input) {
        return input == null ? Collections.emptyMap() : input;
    }
    private String nullableText(Object value) { return value == null ? null : String.valueOf(value); }
    private String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private String message(Throwable error) {
        return error.getMessage() == null ? "Internal server error" : error.getMessage();
    }
    @FunctionalInterface private interface Work { Object run(); }
}
