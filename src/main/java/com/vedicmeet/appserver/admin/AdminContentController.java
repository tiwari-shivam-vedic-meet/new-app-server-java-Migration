package com.vedicmeet.appserver.admin;

import com.vedicmeet.appserver.migration.MigrationWrite;
import com.vedicmeet.appserver.security.RequireRole;
import com.vedicmeet.appserver.security.Role;
import org.bson.Document;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/** Legacy-compatible native admin routes for the first low-risk content slice. */
@RestController
@RequestMapping("/v2/admin")
@RequireRole({Role.ADMIN, Role.SUB_ADMIN})
public class AdminContentController {

    private final AdminContentService service;

    public AdminContentController(AdminContentService service) {
        this.service = service;
    }

    @PostMapping(value = "/banner/add", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> addBanner(
            @RequestParam(required = false) String title,
            @RequestParam String categoryId, @RequestParam String userType,
            @RequestPart(value = "bannerImage", required = false) MultipartFile image) {
        return execute("Banner added successfully", () -> service.addBanner(title, categoryId, userType, image));
    }

    @PutMapping(value = "/banner/edit", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> editBanner(
            @RequestParam String bannerId, @RequestParam(required = false) String title,
            @RequestParam(required = false) String categoryId,
            @RequestParam(required = false) String userType,
            @RequestPart(value = "bannerImage", required = false) MultipartFile image) {
        return execute("Banner updated successfully",
                () -> service.updateBanner(bannerId, title, categoryId, userType, image));
    }

    @PutMapping(value = "/banner/edit", consumes = MediaType.APPLICATION_JSON_VALUE)
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> editBannerJson(@RequestBody Map<String, Object> body) {
        return execute("Banner updated successfully", () -> service.updateBanner(text(body, "bannerId"),
                nullable(body, "title"), nullable(body, "categoryId"), nullable(body, "userType"), null));
    }

    @GetMapping("/banner/list")
    public ResponseEntity<Map<String, Object>> listBanners(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "") String status,
            @RequestParam(defaultValue = "") String userType) {
        return execute("Banner list fetched successfully",
                () -> service.listBanners(page, limit, search, status, userType));
    }

    @PutMapping("/banner/block_unblock")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> bannerStatus(@RequestBody Map<String, Object> body) {
        return execute("Banner blocked successfully",
                () -> service.setBannerStatus(text(body, "bannerId"), bool(body.get("status"))));
    }

    @PostMapping(value = "/gift/add", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> addGift(
            @RequestParam String title, @RequestParam String coin,
            @RequestPart(value = "giftIcon", required = false) MultipartFile icon) {
        return execute("Gift added successfully", () -> service.addGift(title, coin, icon));
    }

    @PutMapping(value = "/gift/update", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> editGift(
            @RequestParam String giftId, @RequestParam(required = false) String title,
            @RequestParam(required = false) String coin,
            @RequestPart(value = "giftIcon", required = false) MultipartFile icon) {
        return execute("Gift updated successfully", () -> service.updateGift(giftId, title, coin, icon));
    }

    @PutMapping(value = "/gift/update", consumes = MediaType.APPLICATION_JSON_VALUE)
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> editGiftJson(@RequestBody Map<String, Object> body) {
        return execute("Gift updated successfully", () -> service.updateGift(text(body, "giftId"),
                nullable(body, "title"), nullable(body, "coin"), null));
    }

    @GetMapping("/gift")
    public ResponseEntity<Map<String, Object>> listGifts(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(defaultValue = "") String search) {
        return execute("Gift list fetched successfully", () -> service.listGifts(page, limit, search));
    }

    @PutMapping("/gift/block_unblock")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> giftStatus(@RequestBody Map<String, Object> body) {
        return execute("Gift blocked/unblocked successfully",
                () -> service.setGiftStatus(text(body, "giftId"), bool(body.get("status"))));
    }

    @GetMapping("/feedback")
    public ResponseEntity<Map<String, Object>> listFeedback(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "") String userType) {
        return execute("Feedback list fetched successfully",
                () -> service.listFeedback(page, limit, search, userType));
    }

    @PutMapping("/feedback/block_unblock")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> feedbackStatus(@RequestBody Map<String, Object> body) {
        return execute("Feedback blocked/unblocked successfully", () -> {
            service.setFeedbackStatus(text(body, "feedbackId"), bool(body.get("status")));
            return null;
        });
    }

    @GetMapping("/refer")
    public ResponseEntity<Map<String, Object>> listReferrals(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(defaultValue = "") String search,
            @RequestParam String userType,
            @RequestParam(defaultValue = "") String referalDate) {
        return execute("Refer list fetched successfully",
                () -> service.listReferrals(page, limit, search, userType, referalDate));
    }

    @PutMapping("/refer/approve")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> approveReferral(@RequestBody Map<String, Object> body) {
        return execute("Refer approved successfully", () -> {
            service.approveReferral(text(body, "referId"));
            return null;
        });
    }

    @PostMapping("/commonmessage/add-commonmessage")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> putCommonMessages(@RequestBody Object body) {
        try {
            return ResponseEntity.ok(commonOk(service.putCommonMessages(body)));
        } catch (Exception error) {
            return ResponseEntity.internalServerError().body(commonError("Internal server error", error));
        }
    }

    @GetMapping("/commonmessage/get-commonmessage")
    public ResponseEntity<Map<String, Object>> getCommonMessages() {
        try {
            return ResponseEntity.ok(commonOk(service.getCommonMessages()));
        } catch (Exception error) {
            return ResponseEntity.internalServerError().body(commonError("Error fetching messages", error));
        }
    }

    private ResponseEntity<Map<String, Object>> execute(String message, Supplier<Object> action) {
        try {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("code", 200);
            response.put("success", true);
            response.put("message", message);
            response.put("result", action.get());
            return ResponseEntity.ok(response);
        } catch (Exception error) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("code", 500);
            response.put("success", false);
            response.put("message", error.getMessage() == null ? "Internal server error" : error.getMessage());
            response.put("result", new Document());
            return ResponseEntity.ok(response);
        }
    }

    private Map<String, Object> commonOk(Object data) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("data", data);
        return response;
    }

    private Map<String, Object> commonError(String message, Exception error) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", false);
        response.put("message", message);
        response.put("error", error.getMessage());
        return response;
    }

    private String text(Map<String, Object> body, String key) {
        String value = nullable(body, key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(key.toUpperCase() + "_REQUIRE");
        return value;
    }

    private String nullable(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private boolean bool(Object value) {
        if (value instanceof Boolean bool) return bool;
        if (value == null) throw new IllegalArgumentException("STATUS_REQUIRE");
        String text = String.valueOf(value);
        if (!"true".equalsIgnoreCase(text) && !"false".equalsIgnoreCase(text))
            throw new IllegalArgumentException("INVALID_STATUS");
        return Boolean.parseBoolean(text);
    }
}
