package com.vedicmeet.appserver.payment;

import com.vedicmeet.appserver.migration.MigrationWrite;
import com.vedicmeet.appserver.security.AuthPrincipal;
import com.vedicmeet.appserver.security.AuthUserService;
import com.vedicmeet.appserver.security.CurrentUser;
import com.vedicmeet.appserver.security.RequireRole;
import com.vedicmeet.appserver.security.Role;
import com.vedicmeet.appserver.web.ApiResponse;
import org.bson.Document;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** Missing mobile payment facade from Node rest-apis/modules/transaction.js. */
@RestController
@RequestMapping("/v2/v1/payment")
public class MobilePaymentController {
    private final PaymentOrderService orders;
    private final PaymentConfirmService confirms;
    private final PaymentFromWalletService wallet;
    private final MembershipPurchaseService memberships;
    private final PaymentHistoryService history;
    private final AuthUserService users;

    public MobilePaymentController(PaymentOrderService orders, PaymentConfirmService confirms,
            PaymentFromWalletService wallet, MembershipPurchaseService memberships,
            PaymentHistoryService history, AuthUserService users) {
        this.orders = orders; this.confirms = confirms; this.wallet = wallet;
        this.memberships = memberships; this.history = history; this.users = users;
    }

    @PostMapping("/init") @RequireRole({Role.USER, Role.CONSULTANT}) @MigrationWrite
    public ResponseEntity<ApiResponse<?>> initiate(@CurrentUser AuthPrincipal principal,
            @RequestBody(required = false) Map<String, Object> body) {
        try {
            return ResponseEntity.ok(ApiResponse.ok("Payment initiated successfully",
                    orders.initiate(orders.decryptIfNeeded(body), actor(principal))));
        } catch (RuntimeException failure) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ApiResponse<>(false, 500, failure.getMessage(), new LinkedHashMap<>()));
        }
    }

    @PostMapping("/create") @RequireRole({Role.USER, Role.CONSULTANT}) @MigrationWrite
    public ResponseEntity<ApiResponse<?>> create(@CurrentUser AuthPrincipal principal,
            @RequestBody(required = false) Map<String, Object> body) {
        try {
            return ResponseEntity.ok(ApiResponse.ok("Payment created successfully", orders.create(body, actor(principal))));
        } catch (RuntimeException failure) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ApiResponse<>(false, 500, failure.getMessage(), new LinkedHashMap<>()));
        }
    }

    @PostMapping("/confirm") @RequireRole({Role.USER, Role.CONSULTANT}) @MigrationWrite
    public ApiResponse<?> confirm(@CurrentUser AuthPrincipal principal,
            @RequestBody(required = false) Map<String, Object> body) {
        try {
            Map<String, Object> input = orders.decryptIfNeeded(body);
            String orderId = required(input, "orderId"), paymentId = required(input, "paymentId");
            Document actor = actor(principal);
            orders.assertOrderOwner(orderId, actor.get("_id"));
            return ApiResponse.ok("Payment confirmed successfully", confirms.confirmPayment(orderId, paymentId,
                    Boolean.TRUE.equals(input.get("eventQueuedFired"))));
        } catch (RuntimeException failure) { return fail(failure); }
    }

    @PostMapping("/via_wallet") @RequireRole({Role.USER, Role.CONSULTANT}) @MigrationWrite
    public ApiResponse<?> viaWallet(@CurrentUser AuthPrincipal principal,
            @RequestBody(required = false) Map<String, Object> body) {
        try {
            wallet.paymentFromWallet(new Document(orders.decryptIfNeeded(body)), actor(principal));
            return ApiResponse.ok("Payment from wallet successfully", null);
        } catch (RuntimeException failure) { return fail(failure); }
    }

    @PostMapping("/membership_purchase") @RequireRole({Role.USER, Role.CONSULTANT}) @MigrationWrite
    public ApiResponse<?> membership(@CurrentUser AuthPrincipal principal,
            @RequestBody(required = false) Map<String, Object> body) {
        try {
            memberships.purchaseMembership(new Document(orders.decryptIfNeeded(body)), actor(principal));
            return ApiResponse.ok("Membership purchased successfully", null);
        } catch (RuntimeException failure) { return fail(failure); }
    }

    @GetMapping("/transaction_list") @RequireRole({Role.USER, Role.CONSULTANT})
    public ApiResponse<?> transactions(@CurrentUser AuthPrincipal principal,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String filter, @RequestParam(required = false) String userType) {
        try {
            String actualType = Role.CONSULTANT.equals(principal.getRole()) ? "cons" : "user";
            // Prevent Node's invalid/missing userType branch from accidentally querying the full ledger.
            if (userType != null && !userType.equals(actualType)) throw new IllegalArgumentException("Invalid userType");
            return ApiResponse.ok("Transaction list fetched successfully",
                    history.list(actor(principal), actualType, page, limit, filter));
        } catch (RuntimeException failure) { return fail(failure); }
    }

    @GetMapping("/Guide_purchase")
    public ApiResponse<?> guide() {
        try { return ApiResponse.ok("Guide purchased successfully", history.guidePurchase()); }
        catch (RuntimeException failure) { return fail(failure); }
    }

    private Document actor(AuthPrincipal principal) {
        Document actor = users.load(principal);
        if (actor == null) throw new IllegalStateException("ACCOUNT_NOT_FOUND");
        return actor;
    }
    private String required(Map<String, Object> input, String field) {
        Object value = input.get(field);
        if (value == null || String.valueOf(value).isBlank()) throw new IllegalArgumentException(field + " is required");
        return String.valueOf(value);
    }
    private ApiResponse<?> fail(RuntimeException failure) {
        return new ApiResponse<>(false, 500, failure.getMessage(), new LinkedHashMap<>());
    }
}
