package com.vedicmeet.appserver.consultant;

import com.vedicmeet.appserver.migration.MigrationWrite;
import com.vedicmeet.appserver.security.AuthPrincipal;
import com.vedicmeet.appserver.security.AuthUserService;
import com.vedicmeet.appserver.security.CurrentUser;
import com.vedicmeet.appserver.security.RequireRole;
import com.vedicmeet.appserver.security.Role;
import org.bson.Document;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Exact route surface mounted by Node at /api/cons/{seed,availability,feedback,support,analytics}. */
@RestController
@RequestMapping("/v2/cons")
@RequireRole(Role.CONSULTANT)
public class ConsultantOperationsController {

    private final AuthUserService users;
    private final ConsultantPreferenceService preferences;
    private final ConsultantFeedbackService feedback;
    private final ConsultantSupportService support;
    private final ConsultantAnalyticsService analytics;

    public ConsultantOperationsController(AuthUserService users,
                                          ConsultantPreferenceService preferences,
                                          ConsultantFeedbackService feedback,
                                          ConsultantSupportService support,
                                          ConsultantAnalyticsService analytics) {
        this.users = users;
        this.preferences = preferences;
        this.feedback = feedback;
        this.support = support;
        this.analytics = analytics;
    }

    @PutMapping("/seed/device")
    @MigrationWrite
    public Map<String, Object> device(@CurrentUser AuthPrincipal principal,
                                      @RequestBody(required = false) Map<String, Object> body) {
        return execute("Device updated successfully", () -> preferences.updateDevice(actor(principal), map(body)));
    }

    /** Production Node exposes this as GET but mutates device tokens; keep the method and gate the write. */
    @GetMapping("/seed/home-screen")
    @MigrationWrite
    public Map<String, Object> homeScreen(@CurrentUser AuthPrincipal principal,
                                          @RequestBody(required = false) Map<String, Object> body) {
        return execute("Device updated successfully", () -> preferences.updateDevice(actor(principal), map(body)));
    }

    @GetMapping("/seed/get-seed-data")
    public Map<String, Object> seed(@RequestParam(required = false) String type) {
        try {
            ConsultantPreferenceService.SeedResult result = preferences.seed(type);
            return success(result.found() ? "Data fetched successfully" : "No seed document for this type",
                    result.data());
        } catch (Exception error) { return failure(message(error)); }
    }

    @PutMapping("/availability/sessions-status")
    @MigrationWrite
    public Map<String, Object> sessionStatus(@CurrentUser AuthPrincipal principal,
                                             @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> input = map(body);
        return execute("Availability updated successfully", () -> preferences.updateSessionStatus(
                actor(principal), text(input.get("key")), input.get("value"), text(input.get("nextAvailableTime"))));
    }

    @PutMapping("/availability/boost-status")
    @MigrationWrite
    public Map<String, Object> boostStatus(@CurrentUser AuthPrincipal principal,
                                           @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> input = map(body);
        return execute("Boost status updated successfully", () -> preferences.updateBoostStatus(
                actor(principal), text(input.get("key")), input.get("value")));
    }

    @PutMapping("/availability/time-slots")
    @MigrationWrite
    public Map<String, Object> timeSlots(@CurrentUser AuthPrincipal principal,
                                         @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> input = map(body);
        return execute("Availability updated successfully", () -> preferences.updateTimeSlots(
                actor(principal), text(input.get("availabilityFor")), input.get("timeSlots")));
    }

    @GetMapping("/feedback/all")
    public Map<String, Object> feedbacks(@CurrentUser AuthPrincipal principal) {
        return execute("Successfully fetched feedbacks!", () -> feedback.all(actor(principal)));
    }

    @PostMapping("/feedback/submit-flag")
    @MigrationWrite
    public Map<String, Object> submitFlag(@CurrentUser AuthPrincipal principal,
                                          @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> input = map(body);
        return execute("Successfully submitted flag!", () -> feedback.submitFlag(
                actor(principal), text(input.get("feedbackId")), text(input.get("reason"))));
    }

    @PostMapping("/feedback/submit-reply")
    @MigrationWrite
    public Map<String, Object> submitReply(@CurrentUser AuthPrincipal principal,
                                           @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> input = map(body);
        return execute("Successfully submitted reply!", () -> feedback.submitReply(
                actor(principal), text(input.get("feedbackId")), text(input.get("reply"))));
    }

    @GetMapping("/support/consultant_leave_a_message")
    @MigrationWrite
    public Map<String, Object> leaveMessage(@CurrentUser AuthPrincipal principal,
                                            @RequestParam(required = false) String userId,
                                            @RequestParam(required = false) String isNotification) {
        try {
            String thread = support.openLeaveMessage(actor(principal), userId,
                    isNotification != null && !isNotification.isBlank());
            return success(null, Map.of("threadId", thread));
        } catch (Exception error) { return failure(message(error)); }
    }

    @GetMapping("/support/consultant_quick_notes")
    public Map<String, Object> quickNotes(@CurrentUser AuthPrincipal principal) {
        return execute("Quick notes retrieved successfully", () -> support.quickNotes(actor(principal)));
    }

    @PostMapping("/support/consultant_quick_notes")
    @MigrationWrite
    public Map<String, Object> createQuickNote(@CurrentUser AuthPrincipal principal,
                                               @RequestBody(required = false) Map<String, Object> body) {
        return execute("Quick note created successfully", () -> support.createQuickNote(
                actor(principal), text(map(body).get("note"))));
    }

    @PutMapping("/support/consultant_quick_notes/{id}")
    @MigrationWrite
    public Map<String, Object> updateQuickNote(@CurrentUser AuthPrincipal principal,
                                               @PathVariable String id,
                                               @RequestBody(required = false) Map<String, Object> body) {
        return execute("Quick note updated successfully", () -> support.updateQuickNote(
                actor(principal), id, text(map(body).get("note"))));
    }

    @DeleteMapping("/support/consultant_quick_notes/{id}")
    @MigrationWrite
    public Map<String, Object> deleteQuickNote(@CurrentUser AuthPrincipal principal,
                                               @PathVariable String id) {
        try {
            support.deleteQuickNote(actor(principal), id);
            return success("Quick note deleted successfully", null);
        } catch (Exception error) { return failure(message(error)); }
    }

    @GetMapping("/analytics/performance-report")
    public Map<String, Object> performance(@CurrentUser AuthPrincipal principal) {
        return execute("Successfully fetched!", () -> analytics.performanceReport(actor(principal)));
    }

    @GetMapping("/analytics/coupon-offer")
    public Map<String, Object> couponOffer(@CurrentUser AuthPrincipal principal,
                                           @RequestParam(required = false) String from,
                                           @RequestParam(required = false) String to) {
        try {
            Map<String, Object> response = success("Coupon & offer analytics fetched",
                    analytics.couponOffer(actor(principal), from, to));
            response.put("code", 200);
            return response;
        } catch (Exception error) {
            Map<String, Object> response = failure(message(error));
            response.put("code", 500);
            return response;
        }
    }

    private Document actor(AuthPrincipal principal) {
        Document actor = users.load(principal);
        if (actor == null) throw new IllegalStateException("Invalid token");
        return actor;
    }

    private Map<String, Object> execute(String message, Operation operation) {
        try { return success(message, operation.run()); }
        catch (Exception error) { return failure(message(error)); }
    }

    private Map<String, Object> success(String message, Object data) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        if (data != null) response.put("data", data);
        if (message != null) response.put("message", message);
        return response;
    }

    private Map<String, Object> failure(String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", false);
        response.put("message", message == null || message.isBlank() ? "Internal server error" : message);
        return response;
    }

    private Map<String, Object> map(Map<String, Object> value) {
        return value == null ? Collections.emptyMap() : value;
    }
    private String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private String message(Exception error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getMessage() == null) cause = cause.getCause();
        return cause.getMessage() == null ? "Internal server error" : cause.getMessage();
    }
    @FunctionalInterface private interface Operation { Object run(); }
}
