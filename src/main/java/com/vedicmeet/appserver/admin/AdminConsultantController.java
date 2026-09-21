package com.vedicmeet.appserver.admin;

import com.vedicmeet.appserver.migration.MigrationWrite;
import com.vedicmeet.appserver.security.RequireRole;
import com.vedicmeet.appserver.security.Role;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static com.vedicmeet.appserver.admin.AdminResponses.execute;

/** Scaffold port of Node rest-apis/modules/admin/consultant.js. */
@RestController
@RequestMapping("/v2/admin/consultant")
@RequireRole({Role.ADMIN, Role.SUB_ADMIN})
public class AdminConsultantController {

    private final AdminConsultantService service;

    public AdminConsultantController(AdminConsultantService service) {
        this.service = service;
    }

    // PUT /approve is intentionally not scaffolded here: ConsultantAuthController already owns that full mapping.


    @PostMapping
    public ResponseEntity<Map<String, Object>> list(@RequestBody(required = false) Map<String, Object> body) {
        return execute("Consultant list fetched successfully", () -> service.listConsultants(body));
    }

    @PostMapping("/online_offline")
    public ResponseEntity<Map<String, Object>> onlineOffline(@RequestBody(required = false) Map<String, Object> body) {
        return execute("Consultant list fetched successfully", () -> service.onlineOfflineList(body));
    }

    @GetMapping("/delete/list")
    public ResponseEntity<Map<String, Object>> deleteList(@RequestParam Map<String, String> query) {
        return execute("Consultant list fetched successfully", () -> service.deleteListConsultant(query));
    }

    @PostMapping("/add")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> add(@RequestBody(required = false) Map<String, Object> body) {
        return execute("Consultant created successfully", () -> service.addConsultant(body));
    }

    @PutMapping("/update")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> update(@RequestBody(required = false) Map<String, Object> body) {
        return execute("Consultant updated successfully", () -> service.updateConsultant(body));
    }

    @GetMapping("/details")
    public ResponseEntity<Map<String, Object>> details(@RequestParam Map<String, String> query) {
        return execute("Consultant details fetched successfully", () -> service.details(query));
    }

    @GetMapping("/numerical-analytics")
    public ResponseEntity<Map<String, Object>> numericalAnalytics(@RequestParam Map<String, String> query) {
        return execute("Consultant numerical analytics fetched successfully", () -> service.numericalAnalytics(query));
    }

    @PutMapping("/block_unblock")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> blockUnblock(@RequestBody(required = false) Map<String, Object> body) {
        return execute("Consultant blocked successfully", () -> service.blockUnblock(body));
    }

    @PutMapping("/toggle_fakeness")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> toggleFakeness(@RequestBody(required = false) Map<String, Object> body) {
        return execute("Consultant fakeness status updated successfully", () -> service.toggleFakeness(body));
    }

    @GetMapping("/download")
    public ResponseEntity<Map<String, Object>> download(@RequestParam Map<String, String> query) {
        return execute("Consultant downloaded successfully", () -> service.download(query));
    }

    @PostMapping("/send_warning")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> sendWarning(@RequestBody(required = false) Map<String, Object> body) {
        return execute("Consultant warning sent successfully", () -> service.sendWarning(body));
    }

    @PostMapping("/upload_form16")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> uploadForm16(@RequestBody(required = false) Map<String, Object> body) {
        return execute("Consultant form16 uploaded successfully", () -> service.uploadForm16(body));
    }

    @PutMapping("/update_form16")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> updateForm16(@RequestBody(required = false) Map<String, Object> body) {
        return execute("Consultant form16 updated successfully", () -> service.updateForm16(body));
    }

    @PutMapping("/block_unblock_form16")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> blockUnblockForm16(@RequestBody(required = false) Map<String, Object> body) {
        return execute("Consultant form16 blocked successfully", () -> service.blockUnblockForm16(body));
    }

    @GetMapping("/form16")
    public ResponseEntity<Map<String, Object>> form16(@RequestParam Map<String, String> query) {
        return execute("Consultant form16 list fetched successfully", () -> service.form16(query));
    }

    @GetMapping("/order_history")
    public ResponseEntity<Map<String, Object>> orderHistory(@RequestParam Map<String, String> query) {
        return execute("Consultant order history fetched successfully", () -> service.orderHistory(query));
    }

    @GetMapping("/waitlist")
    public ResponseEntity<Map<String, Object>> waitlist(@RequestParam Map<String, String> query) {
        return execute("Consultant waitlist fetched successfully", () -> service.waitlist(query));
    }

    @DeleteMapping("/remove_from_waitlist")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> removeFromWaitlist(@RequestBody(required = false) Map<String, Object> body) {
        return execute("Consultant waitlist removed successfully", () -> service.removeFromWaitlist(body));
    }

    @PostMapping("/wallet/deduction")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> walletDeduction(@RequestBody(required = false) Map<String, Object> body) {
        return execute("Consultant wallet amount deduction successfully", () -> service.walletDeduction(body));
    }

    @PostMapping("/wallet")
    public ResponseEntity<Map<String, Object>> wallet(@RequestBody(required = false) Map<String, Object> body) {
        return execute("Consultants wallet list fetched successfully", () -> service.wallet(body));
    }

    @PostMapping("/{consultantId}/wallet-details")
    public ResponseEntity<Map<String, Object>> walletDetails(@PathVariable String consultantId, @RequestBody(required = false) Map<String, Object> body) {
        return execute("Consultant wallet details fetched successfully", () -> service.walletDetails(consultantId, body));
    }

    @PostMapping("/wallet/addition")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> walletAddition(@RequestBody(required = false) Map<String, Object> body) {
        return execute("Consultant wallet amount added successfully", () -> service.walletAddition(body));
    }

    @PostMapping("/avg/rating")
    public ResponseEntity<Map<String, Object>> avgRating(@RequestBody(required = false) Map<String, Object> body) {
        return execute("Consultant average rating fetched successfully", () -> service.avgRating(body));
    }

    @GetMapping("/avg/talk/time")
    public ResponseEntity<Map<String, Object>> avgTalkTime(@RequestParam Map<String, String> query) {
        return execute("Consultant average talk time fetched successfully", () -> service.avgTalkTime(query));
    }

    @GetMapping("/chat/total")
    public ResponseEntity<Map<String, Object>> totalChat(@RequestParam Map<String, String> query) {
        return execute("Consultant total chat fetched successfully", () -> service.totalChat(query));
    }

    @GetMapping("/availibility/rate")
    public ResponseEntity<Map<String, Object>> availibilityRate(@RequestParam Map<String, String> query) {
        return execute("Consultant availibility rate fetched successfully", () -> service.availibilityRate(query));
    }

    @GetMapping("/loyal/customer")
    public ResponseEntity<Map<String, Object>> loyalCustomer(@RequestParam Map<String, String> query) {
        return execute("Consultant loyal customer fetched successfully", () -> service.loyalCustomer(query));
    }

    @GetMapping("/new/customer/conversion")
    public ResponseEntity<Map<String, Object>> newCustomerConversion(@RequestParam Map<String, String> query) {
        return execute("Consultant new customer conversion fetched successfully", () -> service.newCustomerConversion(query));
    }

    @GetMapping("/customer/satisfaction")
    public ResponseEntity<Map<String, Object>> customerSatisfaction(@RequestParam Map<String, String> query) {
        return execute("Consultant customer satisfaction fetched successfully", () -> service.customerSatisfaction(query));
    }

    @GetMapping("/new/user/serve/properly")
    public ResponseEntity<Map<String, Object>> newUserServeProperly(@RequestParam Map<String, String> query) {
        return execute("Consultant new user serve properly fetched successfully", () -> service.newUserServeProperly(query));
    }

    @GetMapping("/new/customer/rating")
    public ResponseEntity<Map<String, Object>> newCustomerRating(@RequestParam Map<String, String> query) {
        return execute("Consultant new customer rating fetched successfully", () -> service.newCustomerRating(query));
    }

    @GetMapping("/customer/retention")
    public ResponseEntity<Map<String, Object>> customerRetention(@RequestParam Map<String, String> query) {
        return execute("Consultant customer retention fetched successfully", () -> service.customerRetention(query));
    }

    @GetMapping("/new/user/conversion")
    public ResponseEntity<Map<String, Object>> newUserConversion(@RequestParam Map<String, String> query) {
        return execute("Consultant new user conversion fetched successfully", () -> service.newUserConversion(query));
    }

    @GetMapping("/user/retention")
    public ResponseEntity<Map<String, Object>> userRetention(@RequestParam Map<String, String> query) {
        return execute("Consultant user retention fetched successfully", () -> service.userRetention(query));
    }

    @PutMapping("/mark_green_tick")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> markGreenTick(@RequestBody(required = false) Map<String, Object> body) {
        return execute("Consultant green tick marked successfully", () -> service.markGreenTick(body));
    }

    @PutMapping("/go_live")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> goLive(@RequestBody(required = false) Map<String, Object> body) {
        return execute("Consultant go live successfully", () -> service.goLive(body));
    }

    @PutMapping("/high_priority")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> highPriority(@RequestBody(required = false) Map<String, Object> body) {
        return execute("Consultant mark as high priority successfully", () -> service.highPriority(body));
    }

    @PostMapping("/shopify/discount_coupon/add")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> addShopifyDiscountCoupon(@RequestBody(required = false) Map<String, Object> body) {
        return execute("Consultant shopify discount coupon added successfully", () -> service.addShopifyDiscountCoupon(body));
    }

    @PutMapping("/shopify/discount_coupon/update")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> updateShopifyDiscountCoupon(@RequestBody(required = false) Map<String, Object> body) {
        return execute("Consultant shopify discount coupon updated successfully", () -> service.updateShopifyDiscountCoupon(body));
    }

    @GetMapping("/shopify/discount_coupon")
    public ResponseEntity<Map<String, Object>> shopifyDiscountCoupon(@RequestParam Map<String, String> query) {
        return execute("Consultant shopify discount coupon list fetched successfully", () -> service.shopifyDiscountCoupon(query));
    }

    @DeleteMapping("/shopify/discount_coupon/delete")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> deleteShopifyDiscountCoupon(@RequestBody(required = false) Map<String, Object> body) {
        return execute("Consultant shopify discount coupon deleted successfully", () -> service.deleteShopifyDiscountCoupon(body));
    }

    @PutMapping("/shopify/discount_coupon/status")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> updateShopifyDiscountCouponStatus(@RequestBody(required = false) Map<String, Object> body) {
        return execute("Consultant shopify discount coupon status updated successfully", () -> service.updateShopifyDiscountCouponStatus(body));
    }

    @GetMapping("/shopify/order/commission")
    public ResponseEntity<Map<String, Object>> shopifyOrderCommission(@RequestParam Map<String, String> query) {
        return execute("Consultant shopify order commission fetched successfully", () -> service.shopifyOrderCommission(query));
    }

    @GetMapping("/shopify/order")
    public ResponseEntity<Map<String, Object>> shopifyOrder(@RequestParam Map<String, String> query) {
        return execute("Consultant shopify order list fetched successfully", () -> service.shopifyOrder(query));
    }

    @GetMapping("/log_out")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> logOut(@RequestParam Map<String, String> query) {
        return execute("Consultant log out from all device successfully", () -> service.logOut(query));
    }

    @PostMapping("/refund/consultation")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> refundConsultation(@RequestBody(required = false) Map<String, Object> body) {
        return execute("Consultation refunded successfully", () -> service.refundConsultation(body));
    }

    @GetMapping("/transactions")
    public ResponseEntity<Map<String, Object>> transactions(@RequestParam Map<String, String> query) {
        return execute("Consultant transaction history fetched successfully", () -> service.transactions(query));
    }

    @GetMapping("/tags")
    public ResponseEntity<Map<String, Object>> tags() {
        return execute("Tags fetched successfully", () -> service.tags());
    }

    @PostMapping("/tags")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> createTag(@RequestBody(required = false) Map<String, Object> body) {
        return execute("Tag created successfully", () -> service.createTag(body));
    }

    @PutMapping("/set_tags")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> setTags(@RequestBody(required = false) Map<String, Object> body) {
        return execute("Tags updated successfully", () -> service.setTags(body));
    }

    @PutMapping("/tags/update")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> updateTag(@RequestBody(required = false) Map<String, Object> body) {
        return execute("Tag updated successfully", () -> service.updateTag(body));
    }

    @DeleteMapping("/tags/{tagId}")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> deleteTag(@PathVariable String tagId) {
        return execute("Tag deleted successfully", () -> service.deleteTag(tagId));
    }

}
