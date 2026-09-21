package com.vedicmeet.appserver.admin;

import com.vedicmeet.appserver.migration.MigrationWrite;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

import static com.vedicmeet.appserver.admin.AdminResponses.execute;

/** MONEY-MOVING: intentionally not implemented; must not move funds; requires human sign-off before real porting. */
@RestController
@RequestMapping("/v2/admin/payout")
// Node mounts this WITHOUT adminAuthMiddleware.
public class AdminPayoutController {

    private final AdminPayoutService service;

    public AdminPayoutController(AdminPayoutService service) {
        this.service = service;
    }

    @GetMapping(value = "/export", produces = "text/csv")
    public ResponseEntity<String> export(@RequestParam Map<String, String> query) {
        service.export(query);
        return csv("payout-export");
    }

    @GetMapping(value = "/export-with-period", produces = "text/csv")
    public ResponseEntity<String> exportWithPeriod(@RequestParam Map<String, String> query) {
        service.exportWithPeriod(query);
        return csv("payout-export-with-period");
    }

    @PostMapping("/upload-with-settlement")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> uploadWithSettlement(
            @RequestBody(required = false) Map<String, Object> body) {
        return execute("Payout settlement completed successfully", () -> service.uploadWithSettlement(body));
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(@RequestParam Map<String, String> query) {
        return execute("Payout list fetched successfully", () -> service.list(query));
    }

    @GetMapping("/specific-month")
    public ResponseEntity<Map<String, Object>> specificMonth(@RequestParam Map<String, String> query) {
        return execute("Payout list fetched successfully", () -> service.specificMonth(query));
    }

    private ResponseEntity<String> csv(String name) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "-" + LocalDate.now() + ".csv\"")
                .body("");
    }
}
