package com.vedicmeet.appserver.consultant;

import com.vedicmeet.appserver.migration.MigrationWrite;
import com.vedicmeet.appserver.security.AuthPrincipal;
import com.vedicmeet.appserver.security.AuthUserService;
import com.vedicmeet.appserver.security.CurrentUser;
import com.vedicmeet.appserver.security.RequireRole;
import com.vedicmeet.appserver.security.Role;
import com.vedicmeet.appserver.web.ApiResponse;
import org.bson.Document;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.Map;

/** Production-compatible consultant mobile routes under the migration /v2 prefix. */
@RestController
@RequestMapping("/v2/v1/cons")
public class ConsultantMobileController {

    private final AuthUserService users;
    private final ConsultantMobileReadService reads;
    private final ConsultantWalletReadService wallets;
    private final ConsultantMobileWriteService writes;

    public ConsultantMobileController(AuthUserService users, ConsultantMobileReadService reads,
                                      ConsultantWalletReadService wallets, ConsultantMobileWriteService writes) {
        this.users = users;
        this.reads = reads;
        this.wallets = wallets;
        this.writes = writes;
    }

    @GetMapping("/availability")
    @RequireRole({Role.USER, Role.CONSULTANT})
    public ApiResponse<?> availability(@RequestParam String consultantId,
                                       @RequestParam(required = false) String date) {
        return read("Available details fetched successfully", () -> reads.availability(consultantId, date));
    }

    @PostMapping("/set_availability")
    @RequireRole(Role.CONSULTANT)
    @MigrationWrite
    public ApiResponse<?> setAvailability(@CurrentUser AuthPrincipal principal,
                                          @RequestBody(required = false) Map<String, Object> body) {
        return read("Availability set successfully", () -> writes.setAvailability(actor(principal), map(body)));
    }

    @PutMapping("/set_availability_status")
    @RequireRole(Role.CONSULTANT)
    @MigrationWrite
    public ApiResponse<?> setAvailabilityStatus(@CurrentUser AuthPrincipal principal,
                                                @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> input = map(body);
        return read("Availability status set successfully", () -> writes.setAvailabilityStatus(
                actor(principal), text(input.get("type")), input.get("status")));
    }

    @GetMapping("/wallet")
    @RequireRole(Role.CONSULTANT)
    public ApiResponse<?> wallet(@CurrentUser AuthPrincipal principal,
                                 @RequestParam(required = false) String filterBy,
                                 @RequestParam(required = false, defaultValue = "cons") String userType) {
        return read("Wallet data fetched successfully",
                () -> wallets.wallet(actor(principal), filterBy, userType));
    }

    @GetMapping("/payout-history")
    @RequireRole(Role.CONSULTANT)
    public ApiResponse<?> payoutHistory(@CurrentUser AuthPrincipal principal) {
        return read("Consultant payout history fetched successfully",
                () -> wallets.payoutHistory(actor(principal)));
    }

    @GetMapping("/wallet-history")
    @RequireRole(Role.CONSULTANT)
    public ApiResponse<?> walletHistory(@CurrentUser AuthPrincipal principal,
                                        @RequestParam(required = false) String month) {
        return read("Wallet history fetched successfully", () -> wallets.walletHistory(actor(principal), month));
    }

    @GetMapping("/price_request_list")
    @RequireRole(Role.CONSULTANT)
    public ApiResponse<?> priceRequests(@CurrentUser AuthPrincipal principal,
                                        @RequestParam(defaultValue = "1") int page,
                                        @RequestParam(defaultValue = "10") int limit) {
        return read("Price request list", () -> reads.priceRequests(actor(principal), page, limit));
    }

    @GetMapping("/form16")
    @RequireRole(Role.CONSULTANT)
    public ApiResponse<?> form16(@CurrentUser AuthPrincipal principal,
                                 @RequestParam(defaultValue = "1") int page,
                                 @RequestParam(defaultValue = "50") int limit,
                                 @RequestParam(required = false) String search) {
        return read("Form 16 list", () -> reads.form16(actor(principal), page, limit, search));
    }

    /** Node's GET marks rows read, therefore this endpoint remains behind the write gate. */
    @GetMapping("/broadcast_admin_message")
    @RequireRole(Role.CONSULTANT)
    @MigrationWrite
    public ApiResponse<?> broadcastMessages(@RequestParam(required = false) String broadcastId,
                                             @RequestBody(required = false) Map<String, Object> body) {
        String id = blank(broadcastId) ? text(map(body).get("broadcastId")) : broadcastId;
        return read("Broadcast message list", () -> reads.broadcastMessages(id));
    }

    @GetMapping("/review")
    @RequireRole(Role.CONSULTANT)
    public ApiResponse<?> reviews(@CurrentUser AuthPrincipal principal,
                                  @RequestParam(defaultValue = "1") int page,
                                  @RequestParam(defaultValue = "10") int limit,
                                  @RequestParam(defaultValue = "0") int filter,
                                  @RequestParam(required = false) String type) {
        return read("Review and rating list",
                () -> reads.reviews(actor(principal), page, limit, filter, type));
    }

    @GetMapping("/review/average")
    @RequireRole(Role.CONSULTANT)
    public ApiResponse<?> reviewAverage(@CurrentUser AuthPrincipal principal) {
        return read("Review and rating average", () -> reads.reviewAverage(actor(principal)));
    }

    @PostMapping("/review/reply")
    @RequireRole(Role.CONSULTANT)
    @MigrationWrite
    public ApiResponse<?> reviewReply(@CurrentUser AuthPrincipal principal,
                                      @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> input = map(body);
        return read("Rating reply submitted successfully", () -> writes.replyToReview(actor(principal),
                text(input.get("reviewRatingId")), text(input.get("reply"))));
    }

    @PutMapping("/flag_pin")
    @RequireRole(Role.CONSULTANT)
    @MigrationWrite
    public ApiResponse<?> flagPin(@CurrentUser AuthPrincipal principal,
                                  @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> input = map(body);
        return read("Review rating updated", () -> writes.markReview(actor(principal),
                text(input.get("reviewRatingId")), text(input.get("type")), input.get("status"),
                text(input.get("flagReason"))));
    }

    @GetMapping("/flag_count")
    @RequireRole(Role.CONSULTANT)
    public ApiResponse<?> flagCount(@CurrentUser AuthPrincipal principal) {
        return read("Review and rating flag count", () -> reads.flagCount(actor(principal)));
    }

    @GetMapping("/check_consultant_fixed_session")
    @RequireRole({Role.USER, Role.CONSULTANT})
    public ApiResponse<?> fixedSession(@RequestParam(name = "id") String consultantId) {
        return read("Consultant fixed session", () -> reads.fixedSessions(consultantId));
    }

    @GetMapping("/consultant/waitlist")
    @RequireRole(Role.CONSULTANT)
    public ApiResponse<?> waitlist(@CurrentUser AuthPrincipal principal) {
        return read("Wait list", () -> reads.consultantWaitlist(actor(principal)));
    }

    @GetMapping("/kundali-id-from-request-form")
    @RequireRole(Role.CONSULTANT)
    public ApiResponse<?> kundali(@RequestParam String requestFormId) {
        return read("Kundali id from request form", () -> reads.kundaliFromRequestForm(requestFormId));
    }

    @GetMapping("/order/history")
    @RequireRole(Role.CONSULTANT)
    public ApiResponse<?> orderHistory(@CurrentUser AuthPrincipal principal,
                                       @RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "10") int limit,
                                       @RequestParam(required = false) String search,
                                       @RequestParam(defaultValue = "cons") String listFor,
                                       @RequestParam(defaultValue = "chat") String typeOfConsult,
                                       @RequestParam(required = false) String type) {
        return read("Order history", () -> reads.orderHistory(actor(principal), page, limit, search,
                listFor, typeOfConsult, type));
    }

    @GetMapping("/order/messsage_history")
    @RequireRole(Role.CONSULTANT)
    public ApiResponse<?> orderMessages(@RequestParam(required = false) String roomId,
                                        @RequestBody(required = false) Map<String, Object> body) {
        String id = blank(roomId) ? text(map(body).get("roomId")) : roomId;
        return read("Order chat history", () -> reads.orderMessages(id));
    }

    @GetMapping("/ranking_user_review_number")
    public ApiResponse<?> onlineRanking(@RequestParam(defaultValue = "1") int page,
                                        @RequestParam(defaultValue = "100") int limit) {
        return read("Consultant ranking list", () -> reads.onlineRanking(page, limit));
    }

    @GetMapping("/ranking")
    @RequireRole({Role.USER, Role.CONSULTANT})
    public ApiResponse<?> earningRanking(@RequestParam(defaultValue = "1") int page,
                                         @RequestParam(defaultValue = "100") int limit) {
        return read("Consultant ranking", () -> reads.earningRanking(page, limit));
    }

    @PatchMapping("/active/offer/{offerId}")
    @RequireRole(Role.CONSULTANT)
    @MigrationWrite
    public ApiResponse<?> offer(@CurrentUser AuthPrincipal principal, @PathVariable String offerId,
                                @RequestBody(required = false) Map<String, Object> body) {
        boolean active = !(map(body).get("isActive") instanceof Boolean value) || value;
        try {
            writes.changeOffer(actor(principal), offerId, active);
            return ApiResponse.ok(active ? "Offer actived" : "Offer deactived", Collections.emptyMap());
        } catch (RuntimeException failure) { return fail(failure); }
    }

    @GetMapping("/offers/history")
    @RequireRole(Role.CONSULTANT)
    public ApiResponse<?> offerHistory(@CurrentUser AuthPrincipal principal,
                                       @RequestParam(required = false) String couponId,
                                       @RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "20") int limit) {
        return read("Offer history fetched", () -> reads.offerHistory(actor(principal), couponId, page, limit));
    }

    @GetMapping("/availability/dashboard")
    @RequireRole(Role.CONSULTANT)
    public ApiResponse<?> availabilityDashboard(@CurrentUser AuthPrincipal principal,
                                                 @RequestParam(required = false) String consultantId) {
        String id = blank(consultantId) ? String.valueOf(actor(principal).get("_id")) : consultantId;
        return read("Availability dashboard", () -> reads.availabilityDashboard(id));
    }

    private Document actor(AuthPrincipal principal) {
        Document actor = users.load(principal);
        if (actor == null) throw new IllegalStateException("Invalid token");
        return actor;
    }

    private ApiResponse<?> read(String message, Work work) {
        try { return ApiResponse.ok(message, work.run()); }
        catch (RuntimeException failure) { return fail(failure); }
    }

    private ApiResponse<?> fail(RuntimeException failure) {
        return new ApiResponse<>(false, 500,
                failure.getMessage() == null ? "Internal server error" : failure.getMessage(), null);
    }
    private Map<String, Object> map(Map<String, Object> value) { return value == null ? Collections.emptyMap() : value; }
    private String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private boolean blank(String value) { return value == null || value.isBlank(); }
    @FunctionalInterface private interface Work { Object run(); }
}
