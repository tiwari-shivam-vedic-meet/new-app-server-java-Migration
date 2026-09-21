package com.vedicmeet.appserver.session;

import com.vedicmeet.appserver.security.AuthPrincipal;
import com.vedicmeet.appserver.security.AuthUserService;
import com.vedicmeet.appserver.security.CurrentUser;
import com.vedicmeet.appserver.security.RequireRole;
import com.vedicmeet.appserver.security.Role;
import com.vedicmeet.appserver.migration.MigrationWrite;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.time.LocalDate;
import java.time.ZoneOffset;

/** Shadow-safe mobile session reads; route suffixes match the production Node API. */
@RestController
@RequestMapping("/v2/v1/user/session")
@RequireRole(Role.USER)
public class UserSessionController {

    private final UserSessionReadService reads;
    private final AuthUserService users;
    private final SessionBookingService bookings;
    private final UserSessionCouponService coupons;
    private final UserFixedSessionBookingService fixedBookings;
    private final UserSessionCommandService commands;
    private final boolean bookingEnabled;
    private final boolean commandsEnabled;
    private final boolean fixedBookingEnabled;
    private final boolean callExecutionEnabled;
    private final boolean socketEnabled;
    private final boolean timerWorkerEnabled;
    private final boolean outboxWorkerEnabled;

    public UserSessionController(UserSessionReadService reads, AuthUserService users,
                                 SessionBookingService bookings, UserSessionCouponService coupons,
                                 UserFixedSessionBookingService fixedBookings,
                                 UserSessionCommandService commands,
                                 @Value("${vedicmeet.session.booking-enabled:false}") boolean bookingEnabled,
                                 @Value("${vedicmeet.session.commands-enabled:false}") boolean commandsEnabled,
                                 @Value("${vedicmeet.session.fixed-booking-enabled:false}") boolean fixedBookingEnabled,
                                 @Value("${vedicmeet.call.execution-enabled:false}") boolean callExecutionEnabled,
                                 @Value("${vedicmeet.socket.enabled:false}") boolean socketEnabled,
                                 @Value("${vedicmeet.call.timer-worker-enabled:false}") boolean timerWorkerEnabled,
                                 @Value("${vedicmeet.call.outbox-worker-enabled:false}") boolean outboxWorkerEnabled) {
        this.reads = reads;
        this.users = users;
        this.bookings = bookings;
        this.coupons = coupons;
        this.fixedBookings = fixedBookings;
        this.commands = commands;
        this.bookingEnabled = bookingEnabled;
        this.commandsEnabled = commandsEnabled;
        this.fixedBookingEnabled = fixedBookingEnabled;
        this.callExecutionEnabled = callExecutionEnabled;
        this.socketEnabled = socketEnabled;
        this.timerWorkerEnabled = timerWorkerEnabled;
        this.outboxWorkerEnabled = outboxWorkerEnabled;
    }

    /** Node POST /coupon-status is read-only and can be contract-shadowed while writes remain off. */
    @PostMapping("/coupon-status")
    public Map<String, Object> couponStatus(@CurrentUser AuthPrincipal principal,
                                             @RequestBody(required = false) Map<String, Object> body) {
        try {
            UserSessionCouponService.CouponResult result = coupons.validate(requireUser(principal), body);
            Map<String, Object> response = success(result.data());
            response.put("message", result.message());
            response.put("version", result.version());
            return response;
        } catch (Exception error) { return failure(message(error)); }
    }

    /** Node POST /api/v1/user/session/book, independently gated until TEST contract/soak sign-off. */
    @PostMapping("/book")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> book(@CurrentUser AuthPrincipal principal,
                                                     @RequestBody(required = false) Map<String, Object> body) {
        if (!bookingEnabled) return ResponseEntity.ok(failure("Java session booking is disabled"));
        if (!outboxWorkerEnabled || !bookings.chatServerReady()) {
            return ResponseEntity.ok(failure("Java booking integrations are not ready"));
        }
        boolean scheduled = body != null && body.get("sessionMeta") instanceof Map<?, ?> meta
                && "session-book".equals(String.valueOf(meta.get("mode")));
        if (!scheduled && (!callExecutionEnabled || !socketEnabled || !timerWorkerEnabled)) {
            return ResponseEntity.ok(failure("Java call execution is disabled"));
        }
        try {
            SessionBookingService.BookingResult result = bookings.book(requireUser(principal), body);
            return ResponseEntity.ok(success(result.data()));
        } catch (SessionBookingService.ConsultantUnavailableException unavailable) {
            Map<String, Object> response = failure(message(unavailable));
            response.put("data", null);
            return ResponseEntity.badRequest().body(response);
        } catch (Exception error) {
            return ResponseEntity.ok(failure(message(error)));
        }
    }

    @PostMapping("/book-fixed-session")
    @MigrationWrite
    public Map<String, Object> bookFixedSession(@CurrentUser AuthPrincipal principal,
                                                 @RequestBody(required = false) Map<String, Object> body) {
        if (!fixedBookingEnabled) return failure("Java fixed-session booking is disabled");
        if (!outboxWorkerEnabled) return failure("Java booking integrations are not ready");
        try { return success(fixedBookings.book(requireUser(principal), body)); }
        catch (Exception error) { return failure(message(error)); }
    }

    @PostMapping("/block-chats-to-consultant")
    @MigrationWrite
    public Map<String, Object> blockConsultantHistory(@CurrentUser AuthPrincipal principal,
                                                       @RequestBody(required = false) Map<String, Object> body) {
        if (!commandsEnabled) return failure("Java user-session commands are disabled");
        try {
            commands.setConsultantHistoryAccess(requireUser(principal), body);
            Map<String, Object> response = success(null);
            response.remove("data");
            response.put("message", "Chats blocked to consultant");
            return response;
        } catch (Exception error) { return failure(message(error)); }
    }

    @PostMapping("/recharge-status")
    @MigrationWrite
    public Map<String, Object> rechargeStatus(@CurrentUser AuthPrincipal principal,
                                               @RequestBody(required = false) Map<String, Object> body) {
        if (!commandsEnabled || !callExecutionEnabled || !timerWorkerEnabled || !outboxWorkerEnabled) {
            return failure("Java call extension is disabled");
        }
        try { return success(commands.rechargeStatus(requireUser(principal), body)); }
        catch (Exception error) { return failure(message(error)); }
    }

    @PostMapping(value = "/upload-playstore-screenshot", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @MigrationWrite
    public Map<String, Object> uploadPlaystoreScreenshot(@CurrentUser AuthPrincipal principal,
                                                          @RequestPart(value = "type", required = false) String type,
                                                          @RequestPart(value = "file", required = false)
                                                          MultipartFile file) {
        if (!commandsEnabled) return failure("Java user-session commands are disabled");
        try {
            Map<String, Object> response = success(
                    commands.uploadPlaystoreScreenshot(requireUser(principal), type, file));
            response.put("message", "Screenshot uploaded successfully");
            return response;
        } catch (Exception error) { return failure(message(error)); }
    }

    @PostMapping("/create-query")
    @MigrationWrite
    public Map<String, Object> createQuery(@CurrentUser AuthPrincipal principal,
                                           @RequestBody(required = false) Map<String, Object> body) {
        if (!commandsEnabled) return failure("Java user-session commands are disabled");
        if (!outboxWorkerEnabled) return failure("Java query integrations are not ready");
        try {
            UserSessionCommandService.QuickQueryResult result =
                    commands.createQuickQuery(requireUser(principal), body);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", result.id()); data.put("dayKey", result.dayKey()); data.put("status", result.status());
            Map<String, Object> response = success(data);
            response.put("message", "We will notify you when a consultant is ready.");
            return response;
        } catch (UserSessionCommandService.DailyQueryLimitException limit) {
            Map<String, Object> response = failure(limit.getMessage());
            response.put("code", "DAILY_LIMIT");
            return response;
        } catch (Exception error) { return failure(message(error)); }
    }

    @PostMapping("/generate_token_1to1")
    public Map<String, Object> generateToken(@CurrentUser AuthPrincipal principal,
                                              @RequestBody(required = false) Map<String, Object> body) {
        if (!commands.liveKitReady()) return failure("LIVEKIT_NOT_CONFIGURED");
        try {
            Map<String, Object> response = success(commands.generateLiveKitToken(requireUser(principal), body));
            response.put("message", "Successfully created!");
            return response;
        } catch (Exception error) { return failure(message(error)); }
    }

    @GetMapping("/request-forms")
    public Map<String, Object> requestForms(@CurrentUser AuthPrincipal principal) {
        return read(() -> reads.requestForms(userId(requireUser(principal))));
    }

    @GetMapping("/waitlist-count")
    public Map<String, Object> waitlistCount(@CurrentUser AuthPrincipal principal,
                                             @RequestParam(required = false) String userId) {
        return read(() -> reads.waitlistCount(userId == null || userId.isBlank()
                ? userId(requireUser(principal)) : userId));
    }

    @GetMapping("/waitlist-status")
    public Map<String, Object> waitlistStatus(@CurrentUser AuthPrincipal principal,
                                              @RequestHeader(value = "platform-type", required = false)
                                              String platform) {
        return read(() -> reads.waitlistStatus(requireUser(principal), platform));
    }

    @GetMapping("/waitlist-history")
    public Map<String, Object> waitlistHistory(@CurrentUser AuthPrincipal principal) {
        return read(() -> reads.waitlistHistory(userId(requireUser(principal))));
    }

    @GetMapping("/consultant-follow-up-messages")
    public Map<String, Object> consultantFollowUps(@CurrentUser AuthPrincipal principal) {
        try { return success(reads.consultantFollowUps(userId(requireUser(principal)))); }
        catch (Exception error) {
            Map<String, Object> response = failure(message(error));
            response.put("data", Collections.emptyList());
            return response;
        }
    }

    @GetMapping("/get_live_events")
    public Map<String, Object> liveEvents() {
        return read(reads::liveEvents);
    }

    @GetMapping("/today-quick-query")
    public Map<String, Object> todayQuickQuery(@CurrentUser AuthPrincipal principal) {
        try {
            Map<String, Object> query = reads.todayQuickQuery(userId(requireUser(principal)),
                    LocalDate.now(ZoneOffset.UTC).toString());
            return query == null ? failure("No query found") : success(query);
        } catch (Exception error) { return failure(message(error)); }
    }

    @PostMapping("/get-progress-call")
    public Map<String, Object> progressCall(@CurrentUser AuthPrincipal principal) {
        try {
            Map<String, Object> result = reads.progressCall(userId(requireUser(principal)));
            if (result == null) return failure("No call in progress");
            Map<String, Object> response = success(result);
            response.put("message", "fetched successfully");
            return response;
        } catch (Exception error) {
            return failure(message(error));
        }
    }

    private Map<String, Object> read(ReadOperation operation) {
        try { return success(operation.run()); }
        catch (Exception error) { return failure(message(error)); }
    }

    private Document requireUser(AuthPrincipal principal) {
        Document user = users.load(principal);
        if (user == null) throw new IllegalStateException("Unauthorized");
        return user;
    }

    private String userId(Document user) { return String.valueOf(user.get("_id")); }

    private Map<String, Object> success(Object data) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("data", data);
        return response;
    }

    private Map<String, Object> failure(String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", false);
        response.put("message", message);
        return response;
    }

    private String message(Exception error) {
        return error.getMessage() == null ? "Internal server error" : error.getMessage();
    }

    @FunctionalInterface private interface ReadOperation { Object run(); }
}
