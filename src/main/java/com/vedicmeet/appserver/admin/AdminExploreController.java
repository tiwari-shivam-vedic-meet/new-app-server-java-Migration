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
@RequestMapping("/v2/admin/explore")
@RequireRole({Role.ADMIN, Role.SUB_ADMIN})
public class AdminExploreController {

    private final AdminExploreService service;

    public AdminExploreController(AdminExploreService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(@RequestParam Map<String, String> query) {
        return execute("Explore list fetched successfully", () -> service.list(query));
    }

    @PostMapping("/add")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> add(@RequestBody(required = false) Map<String, Object> body) {
        return execute("Explore added successfully", () -> service.add(body));
    }

    @PostMapping("/add/Consultant_detail")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> addConsultantDetail(@RequestBody(required = false) Map<String, Object> body) {
        return execute("Explore added successfully", () -> service.addDetailVideo(body));
    }

    @PutMapping("/update")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> update(@RequestBody(required = false) Map<String, Object> body) {
        return execute("Explore updated successfully", () -> service.update(body));
    }

    @PutMapping("/block_unblock")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> blockUnblock(@RequestBody(required = false) Map<String, Object> body) {
        return execute("Explore blocked/unblocked successfully", () -> service.blockUnblock(body));
    }

    @GetMapping("/details")
    public ResponseEntity<Map<String, Object>> details(@RequestParam Map<String, String> query) {
        return execute("Explore details fetched successfully", () -> service.getDetails(query));
    }

    @GetMapping("/likes_comments")
    public ResponseEntity<Map<String, Object>> likesComments(@RequestParam Map<String, String> query) {
        return execute("Likes and comments fetched successfully", () -> service.getLikesAndComments(query));
    }

    @PutMapping("/update_visiblity")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> updateVisibility(@RequestBody(required = false) Map<String, Object> body) {
        return execute("Comment visibility updated successfully", () -> service.updateCommentVisibility(body));
    }

    @GetMapping("/insights")
    public ResponseEntity<Map<String, Object>> insights(@RequestParam Map<String, String> query) {
        return execute("Explore insights fetched successfully", () -> service.getExploreInsights(query));
    }

    @GetMapping("/user_insights")
    public ResponseEntity<Map<String, Object>> userInsights(@RequestParam Map<String, String> query) {
        return execute("Explore user insights fetched successfully", () -> service.getExploreActivityMetrics(query));
    }

    @GetMapping("/user_activity_log")
    public ResponseEntity<Map<String, Object>> userActivityLog(@RequestParam Map<String, String> query) {
        return execute("Explore user activity log fetched successfully", () -> service.getUserActivityLog(query));
    }

    @PostMapping("/publish_scheduled")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> publishScheduled() {
        return execute("Scheduled posts published successfully", () -> service.publishScheduledPosts());
    }
}