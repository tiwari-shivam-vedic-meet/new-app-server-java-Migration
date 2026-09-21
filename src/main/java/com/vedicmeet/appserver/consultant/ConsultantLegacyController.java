package com.vedicmeet.appserver.consultant;

import com.vedicmeet.appserver.crypto.CryptoService;
import com.vedicmeet.appserver.discovery.ConsultantListService;
import com.vedicmeet.appserver.migration.MigrationWrite;
import com.vedicmeet.appserver.security.AuthPrincipal;
import com.vedicmeet.appserver.security.AuthUserService;
import com.vedicmeet.appserver.security.CurrentUser;
import com.vedicmeet.appserver.security.RequireRole;
import com.vedicmeet.appserver.security.Role;
import com.vedicmeet.appserver.web.ApiResponse;
import org.bson.Document;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Remaining active routes from the production consultant mobile router. */
@RestController
@RequestMapping("/v2/v1/cons")
public class ConsultantLegacyController {

    private final AuthUserService users;
    private final ConsultantLegacyReadService reads;
    private final ConsultantLegacyWriteService writes;
    private final ConsultantListService consultantList;
    private final CryptoService crypto;

    public ConsultantLegacyController(AuthUserService users, ConsultantLegacyReadService reads,
                                      ConsultantLegacyWriteService writes,
                                      ConsultantListService consultantList, CryptoService crypto) {
        this.users = users;
        this.reads = reads;
        this.writes = writes;
        this.consultantList = consultantList;
        this.crypto = crypto;
    }

    @PostMapping("/website_top_consultant_list")
    public ApiResponse<?> websiteTopConsultants(@RequestBody(required = false) Map<String, Object> body) {
        try {
            Map<String, Object> input = decode(body);
            Document anonymous = new Document("isMembership", false)
                    .append("device", new Document("fcmToken", List.of()));
            return ApiResponse.ok("Top consultant list fetched successfully",
                    consultantList.consultantList(input, anonymous));
        } catch (RuntimeException failure) { return failure(failure, Collections.emptyMap()); }
    }

    @GetMapping("/boost")
    @RequireRole(Role.CONSULTANT)
    @MigrationWrite
    public ApiResponse<?> boost(@CurrentUser AuthPrincipal principal,
                                @RequestParam String boostType) {
        return work("Boost profile updated successfully", () -> writes.boost(actor(principal), boostType));
    }

    @GetMapping("/pay_slip")
    @RequireRole(Role.CONSULTANT)
    public ApiResponse<?> paySlip(@CurrentUser AuthPrincipal principal,
                                  @RequestParam int month, @RequestParam int year) {
        try {
            Document result = reads.paySlip(actor(principal), month, year);
            return ApiResponse.ok(result == null ? "Pay slip not found" : "Pay slip sent successfully", result);
        } catch (RuntimeException failure) { return failure(failure, null); }
    }

    /**
     * Node accidentally protects this user-intake endpoint with consAuthMiddleware. Java uses the
     * intended USER role; the write remains dark until the migration gate is enabled.
     */
    @PostMapping("/consultationIntekeForm")
    @RequireRole(Role.USER)
    @MigrationWrite
    public ApiResponse<?> consultationIntake(@CurrentUser AuthPrincipal principal,
                                             @RequestBody(required = false) Map<String, Object> body) {
        try {
            Document result = writes.createIntake(actor(principal), map(body));
            Document envelope = new Document("currentDate", new java.util.Date())
                    .append("formdata", result).append("consultantFormRequestId", result.get("_id"));
            return ApiResponse.ok("Consultation request created", envelope);
        } catch (ConsultantLegacyWriteService.RequestInProgressException inProgress) {
            return new ApiResponse<>(false, 470, "REQUEST_INPROGRESS", null);
        } catch (RuntimeException failure) { return failure(failure, null); }
    }

    /** Intended user-scoped version of Node's broken /user/waitlist route. */
    @GetMapping("/user/waitlist")
    @RequireRole(Role.USER)
    public ApiResponse<?> userWaitlist(@CurrentUser AuthPrincipal principal) {
        try {
            Document result = reads.userWaitlist(actor(principal));
            int status = result.getInteger("status", 0);
            long seconds = ((Document) result.get("data")).get("waitingTime") instanceof Number number
                    ? number.longValue() : 0;
            String message = status == 1 ? "Consultant is available right now. You can join now"
                    : status == 2 ? "Consultant busy with other user your wait time is "
                    + (long) Math.ceil(seconds / 60d) + " minutes"
                    : "You will connecting with consultant in " + (long) Math.ceil(seconds / 60d) + " minutes";
            return ApiResponse.ok(message, new Document("currentDate", result.get("currentDate"))
                    .append("formdata", result.get("data")));
        } catch (RuntimeException failure) { return failure(failure, null); }
    }

    @GetMapping("/order/live/history")
    @RequireRole(Role.CONSULTANT)
    public ApiResponse<?> liveHistory(@CurrentUser AuthPrincipal principal,
                                      @RequestParam(defaultValue = "1") int page,
                                      @RequestParam(defaultValue = "10") int limit,
                                      @RequestParam(required = false) String search) {
        return work("Live order list", () -> reads.liveHistory(actor(principal), page, limit, search));
    }

    @PostMapping("/dashboard/performance/first")
    @RequireRole(Role.CONSULTANT)
    public ApiResponse<?> performanceFirst(@CurrentUser AuthPrincipal principal,
                                           @RequestBody(required = false) Map<String, Object> body) {
        return work("Dashboard performace detail", () -> reads.performanceFirst(actor(principal),
                text(map(body).get("consultantId"))));
    }

    @PostMapping("/dashboard/performance/second")
    @RequireRole(Role.CONSULTANT)
    public ApiResponse<?> performanceSecond(@CurrentUser AuthPrincipal principal,
                                            @RequestBody(required = false) Map<String, Object> body) {
        return work("Dashboard performace detail", () -> reads.performanceSecond(actor(principal),
                text(map(body).get("consultantId"))));
    }

    @GetMapping("/dashboard/other/performance")
    @RequireRole(Role.CONSULTANT)
    public ApiResponse<?> otherPerformance(@CurrentUser AuthPrincipal principal,
                                           @RequestParam(required = false) String consultantId) {
        return work("Dashboard performace detail", () -> reads.otherPerformance(actor(principal), consultantId));
    }

    @GetMapping("/dashboard/performance/tag")
    @RequireRole(Role.CONSULTANT)
    public ApiResponse<?> performanceTag(@CurrentUser AuthPrincipal principal,
                                         @RequestParam(required = false) String consultantId) {
        return work("Dashboard performance tag fetched successfully",
                () -> reads.performanceTags(actor(principal), consultantId));
    }

    /** Node middleware is wrong here too; this operation belongs to the authenticated user. */
    @GetMapping("/verify/wallet/balance")
    @RequireRole(Role.USER)
    @MigrationWrite
    public ApiResponse<?> verifyWallet(@CurrentUser AuthPrincipal principal,
                                       @RequestParam String consultantFormRequestId) {
        return work("Wallet balance verified successfully",
                () -> writes.verifyWallet(actor(principal), consultantFormRequestId));
    }

    private Document actor(AuthPrincipal principal) {
        Document actor = users.load(principal);
        if (actor == null) throw new IllegalStateException("Invalid token");
        return actor;
    }
    private Map<String, Object> decode(Map<String, Object> body) {
        Map<String, Object> input = map(body);
        Object encrypted = input.get("reqData");
        if (encrypted == null || text(encrypted).isBlank()) return input;
        return crypto.decryptToMap(text(encrypted));
    }
    private Map<String, Object> map(Map<String, Object> value) {
        return value == null ? Collections.emptyMap() : new LinkedHashMap<>(value);
    }
    private ApiResponse<?> work(String message, Work action) {
        try { return ApiResponse.ok(message, action.run()); }
        catch (RuntimeException failure) { return failure(failure, null); }
    }
    private ApiResponse<?> failure(RuntimeException error, Object result) {
        return new ApiResponse<>(false, 500,
                error.getMessage() == null ? "Internal server error" : error.getMessage(), result);
    }
    private String text(Object value) { return value == null ? "" : String.valueOf(value); }
    @FunctionalInterface private interface Work { Object run(); }
}
