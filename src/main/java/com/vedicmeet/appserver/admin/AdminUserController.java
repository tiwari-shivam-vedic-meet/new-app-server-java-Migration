package com.vedicmeet.appserver.admin;

import com.vedicmeet.appserver.migration.MigrationWrite;
import com.vedicmeet.appserver.security.AuthPrincipal;
import com.vedicmeet.appserver.security.CurrentUser;
import com.vedicmeet.appserver.security.RequireRole;
import com.vedicmeet.appserver.security.Role;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static com.vedicmeet.appserver.admin.AdminResponses.bool;
import static com.vedicmeet.appserver.admin.AdminResponses.execute;
import static com.vedicmeet.appserver.admin.AdminResponses.integer;
import static com.vedicmeet.appserver.admin.AdminResponses.text;

/** Native legacy-compatible admin user and consultation operations. */
@RestController
@RequestMapping("/v2/admin/user")
@RequireRole({Role.ADMIN, Role.SUB_ADMIN})
public class AdminUserController {

    private final AdminUserService service;

    public AdminUserController(AdminUserService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> grid(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "1000") int pageSize,
            @RequestParam(required = false) String sortField,
            @RequestParam(required = false) String sortDirection,
            @RequestParam(required = false) String filterField,
            @RequestParam(required = false) String filterOperator,
            @RequestParam(required = false) String filterValue) {
        return AdminResponses.simple(() -> service.grid(page, pageSize, sortField, sortDirection,
                filterField, filterOperator, filterValue));
    }

    @PostMapping("/list")
    public ResponseEntity<Map<String, Object>> list(@RequestBody(required = false) Map<String, Object> body) {
        return execute("User list fetched successfully", () -> service.users(body, false));
    }

    @PostMapping("/list_marketing")
    public ResponseEntity<Map<String, Object>> marketing(@RequestBody(required = false) Map<String, Object> body) {
        return execute("User list fetched successfully", () -> service.users(body, true));
    }

    @PostMapping("/list/waitlist")
    public ResponseEntity<Map<String, Object>> waiting(@RequestBody(required = false) Map<String, Object> body) {
        return consultation(body, "waiting", "Waiting list fetched successfully");
    }

    @DeleteMapping("/cancel-waitlist")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> cancel(@RequestBody Map<String, Object> body) {
        return execute("Waitlist cancelled successfully", () -> { service.cancelWaitlist(text(body, "waitlistId")); return null; });
    }

    @PostMapping("/list/failed")
    public ResponseEntity<Map<String, Object>> failed(@RequestBody(required = false) Map<String, Object> body) {
        return consultation(body, "failed", "Failed consultation list fetched successfully");
    }

    @PostMapping("/list/missed")
    public ResponseEntity<Map<String, Object>> missed(@RequestBody(required = false) Map<String, Object> body) {
        return consultation(body, "missed", "Missed consultation list fetched successfully");
    }

    @PostMapping("/list/completed")
    public ResponseEntity<Map<String, Object>> completed(@RequestBody(required = false) Map<String, Object> body) {
        return consultation(body, "completed", "Completed consultation list fetched successfully");
    }

    @PostMapping("/list/progress")
    public ResponseEntity<Map<String, Object>> progress(@RequestBody(required = false) Map<String, Object> body) {
        return consultation(body, "progress", "Progress consultation list fetched successfully");
    }

    @PostMapping("/list/booked-waitlist")
    public ResponseEntity<Map<String, Object>> booked(@RequestBody(required = false) Map<String, Object> body) {
        return consultation(body, "booked", "Booked consultation list fetched successfully");
    }

    @GetMapping("/delete/list")
    public ResponseEntity<Map<String, Object>> deleted(@RequestParam Map<String, String> query) {
        return execute("User list fetched successfully", () -> service.deletedUsers(objectMap(query)));
    }

    @PutMapping("/block_unblock")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> status(@RequestBody Map<String, Object> body) {
        return execute("User blocked/unblocked successfully",
                () -> service.setStatus(text(body, "userId"), bool(body.get("status"))));
    }

    @PutMapping("/update_wallet")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> wallet(@RequestBody Map<String, Object> body) {
        return execute("User wallet updated successfully", () -> service.updateWallet(text(body, "userId"),
                number(body.get("amount")), text(body, "action")));
    }

    @GetMapping("/details")
    public ResponseEntity<Map<String, Object>> details(@RequestParam String userId) {
        return execute("User details fetched successfully", () -> service.details(userId));
    }

    @GetMapping("/history")
    public ResponseEntity<Map<String, Object>> history(@RequestParam String userId,
            @RequestParam(defaultValue = "") String consultantId,
            @RequestParam(defaultValue = "") String type,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int limit) {
        return execute("User order history fetched successfully",
                () -> service.history(userId, consultantId, type, page, limit));
    }

    @GetMapping("/log_out")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> logout(@RequestParam String userId) {
        return execute("User logged out from all devices successfully", () -> { service.logoutAll(userId); return null; });
    }

    @GetMapping("/remedy")
    public ResponseEntity<Map<String, Object>> remedies(@RequestParam String type,
            @RequestParam(required = false) String areaOfConcernId,
            @RequestParam(required = false) String areaOfConcernRemedyId) {
        return execute("User area of concern fetched successfully",
                () -> service.remedies(type, areaOfConcernId, areaOfConcernRemedyId));
    }

    @PostMapping("/remedy/add")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> addRemedy(@RequestBody Map<String, Object> body) {
        return execute("Remedy added successfully", () -> service.addRemedy(body));
    }

    @GetMapping("/numerical-analytics")
    public ResponseEntity<Map<String, Object>> analytics(@RequestParam String userId) {
        return execute("Numerical analytics fetched successfully", () -> service.numericalAnalytics(userId));
    }

    @GetMapping("/transactions")
    public ResponseEntity<Map<String, Object>> transactions(@RequestParam String userId,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "10") int limit) {
        return execute("User transaction history fetched successfully", () -> service.walletTransactions(userId, page, limit));
    }

    @GetMapping("/counts")
    public ResponseEntity<Map<String, Object>> counts(@CurrentUser AuthPrincipal admin) {
        return execute("Counts fetched successfully", () -> service.counts(admin.getId(), false));
    }

    @GetMapping("/counts-debug")
    public ResponseEntity<Map<String, Object>> countDebug(@CurrentUser AuthPrincipal admin) {
        return execute("Debug counts fetched successfully", () -> service.counts(admin.getId(), true));
    }

    @PostMapping("/mark-all-as-unread")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> markUnread(@CurrentUser AuthPrincipal admin) {
        return execute("Items marked as unread successfully", () -> service.markAllUnread(admin.getId()));
    }

    @PostMapping("/mark-as-read")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> markRead(@CurrentUser AuthPrincipal admin,
            @RequestBody Map<String, Object> body) {
        Object ids = body.get("entityIds");
        if (!(ids instanceof List<?> list)) return execute("Items marked as read successfully",
                () -> { throw new IllegalArgumentException("entityType and entityIds array are required"); });
        return execute("Items marked as read successfully", () -> Map.of("markedCount",
                service.markRead(admin.getId(), text(body, "entityType"), list)));
    }

    @PostMapping("/mark-all-as-read")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> markAllRead(@CurrentUser AuthPrincipal admin,
            @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked") Map<String, Object> filter = body.get("filterQuery") instanceof Map<?, ?> map
                ? (Map<String, Object>) map : Map.of();
        return execute("Items marked as read successfully", () -> Map.of("markedCount",
                service.markAllRead(admin.getId(), text(body, "entityType"), filter)));
    }

    @GetMapping(value = {"/download/completed", "/download/missed", "/download/failed", "/download/waitlist"},
            produces = "text/csv")
    public ResponseEntity<String> consultationDownload(jakarta.servlet.http.HttpServletRequest request,
                                                        @RequestParam Map<String, String> query) {
        String kind = request.getRequestURI().substring(request.getRequestURI().lastIndexOf('/') + 1);
        return csv(kind + "-consultations", service.consultationCsv(objectMap(query), kind));
    }

    @GetMapping(value = "/download", produces = "text/csv")
    public ResponseEntity<String> userDownload() { return csv("users", service.usersCsv(false)); }

    @GetMapping(value = "/deletedDownload", produces = "text/csv")
    public ResponseEntity<String> deletedDownload() { return csv("deleted-users", service.usersCsv(true)); }

    private ResponseEntity<Map<String, Object>> consultation(Map<String, Object> body, String type, String message) {
        return execute(message, () -> service.consultations(body, type));
    }

    private ResponseEntity<String> csv(String name, String content) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "-" + LocalDate.now() + ".csv\"")
                .body(content);
    }

    private Map<String, Object> objectMap(Map<String, String> input) {
        return input == null ? Map.of() : new java.util.LinkedHashMap<>(input);
    }

    private double number(Object raw) {
        if (raw instanceof Number number) return number.doubleValue();
        try { return Double.parseDouble(String.valueOf(raw)); }
        catch (Exception error) { throw new IllegalArgumentException("INVALID_AMOUNT"); }
    }
}
