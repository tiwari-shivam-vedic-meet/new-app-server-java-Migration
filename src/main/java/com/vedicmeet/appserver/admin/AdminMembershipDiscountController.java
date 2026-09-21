package com.vedicmeet.appserver.admin;

import com.vedicmeet.appserver.migration.MigrationWrite;
import com.vedicmeet.appserver.security.RequireRole;
import com.vedicmeet.appserver.security.Role;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static com.vedicmeet.appserver.admin.AdminResponses.execute;

@RestController
@RequestMapping("/v2/admin/discount")
@RequireRole({Role.ADMIN, Role.SUB_ADMIN})
public class AdminMembershipDiscountController {

    private final AdminMembershipDiscountService service;

    public AdminMembershipDiscountController(AdminMembershipDiscountService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(@RequestParam Map<String, String> query) {
        return execute("Membership discount list fetched successfully", () -> service.list(query));
    }

    @PostMapping("/add")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> add(@RequestBody(required = false) Map<String, Object> body) {
        return execute("Membership discount added successfully", () -> service.add(body));
    }

    @PostMapping("/add_master_coupon")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> addMasterCoupon(@RequestBody(required = false) Map<String, Object> body) {
        return execute("Membership discount added successfully", () -> service.addMasterCoupon(body));
    }

    @PutMapping("/update")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> update(@RequestBody(required = false) Map<String, Object> body) {
        return execute("Membership discount updated successfully", () -> service.update(body));
    }

    @PutMapping("/block_unblock")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> blockUnblock(@RequestBody(required = false) Map<String, Object> body) {
        return execute("Membership discount blocked/unblocked successfully", () -> service.blockUnblock(body));
    }

    @GetMapping("/details")
    public ResponseEntity<Map<String, Object>> details(@RequestParam Map<String, String> query) {
        return execute("Membership discount details fetched successfully", () -> service.details(query));
    }

    @GetMapping("/offers")
    public ResponseEntity<Map<String, Object>> listOffers(@RequestParam Map<String, String> query) {
        return execute("Membership discount offers fetched successfully", () -> service.listOffers(query));
    }

    @PostMapping("/offers/add")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> addOffers(@RequestBody(required = false) Map<String, Object> body) {
        return execute("Membership discount offers added successfully", () -> service.addOffers(body));
    }

    @PutMapping("/offers/update")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> editOffers(@RequestBody(required = false) Map<String, Object> body) {
        return execute("Membership discount offers updated successfully", () -> service.editOffers(body));
    }

    @GetMapping("/offers/block_unblock")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> blockUnblockOffers(@RequestParam Map<String, String> query) {
        return execute("Membership discount offers blocked/unblocked successfully", () -> service.blockUnblockOffers(query));
    }
}
