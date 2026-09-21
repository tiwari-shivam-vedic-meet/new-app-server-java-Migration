package com.vedicmeet.appserver.admin;

import com.vedicmeet.appserver.migration.MigrationWrite;
import com.vedicmeet.appserver.security.RequireRole;
import com.vedicmeet.appserver.security.Role;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/v2/admin/transaction")
@RequireRole({Role.ADMIN, Role.SUB_ADMIN})
public class AdminTransactionController {

    private final AdminTransactionService service;

    public AdminTransactionController(AdminTransactionService service) { this.service = service; }

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(@RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(defaultValue = "") String search) {
        return AdminResponses.execute("Transaction list fetched successfully", () -> service.ledger(page, limit, search));
    }

    @PostMapping(value = "/payout/upload/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> payoutUpload(@RequestParam int payoutMonth,
            @RequestParam int payoutYear, @RequestPart("payOut") MultipartFile file) {
        return AdminResponses.execute("Payout sheet uploaded successfully",
                () -> service.importPayout(payoutMonth, payoutYear, file));
    }

    @GetMapping("/payout/list")
    public ResponseEntity<Map<String, Object>> payouts(@RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "") String fromDate,
            @RequestParam(defaultValue = "") String toDate) {
        return AdminResponses.execute("Payout list fetched successfully",
                () -> service.payoutList(page, limit, search, fromDate, toDate));
    }

    @GetMapping("/recharge-history")
    public ResponseEntity<Map<String, Object>> recharge(@RequestParam Map<String, String> query) {
        return AdminResponses.execute("Recharge history fetched successfully", () -> service.rechargeHistory(query));
    }

    @GetMapping("/spending-history")
    public ResponseEntity<Map<String, Object>> spending(@RequestParam Map<String, String> query) {
        return AdminResponses.execute("Spending history fetched successfully", () -> service.spendingHistory(query));
    }

    @GetMapping(value = "/recharge-history/export", produces = "text/csv")
    public ResponseEntity<String> rechargeExport(@RequestParam Map<String, String> query) {
        return csv("recharge-history", service.rechargeCsv(query));
    }

    @GetMapping(value = "/spending-history/export", produces = "text/csv")
    public ResponseEntity<String> spendingExport(@RequestParam Map<String, String> query) {
        return csv("spending-history", service.spendingCsv(query));
    }

    @PutMapping("/update-support-status/{transactionId}")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> supportStatus(@PathVariable String transactionId,
            @RequestBody Map<String, Object> body) {
        return AdminResponses.execute("Transaction support status updated successfully",
                () -> service.updateSupportStatus(transactionId, body));
    }

    private ResponseEntity<String> csv(String name, String content) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "-" + LocalDate.now() + ".csv\"")
                .body(content);
    }
}
