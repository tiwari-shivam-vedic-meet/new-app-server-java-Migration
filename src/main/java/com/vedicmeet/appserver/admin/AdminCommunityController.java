package com.vedicmeet.appserver.admin;

import com.vedicmeet.appserver.migration.MigrationWrite;
import com.vedicmeet.appserver.security.RequireRole;
import com.vedicmeet.appserver.security.Role;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static com.vedicmeet.appserver.admin.AdminResponses.execute;

@RestController
@RequestMapping("/v2/admin/community")
@RequireRole({Role.ADMIN, Role.SUB_ADMIN})
public class AdminCommunityController {

    private final AdminCommunityService service;

    public AdminCommunityController(AdminCommunityService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(@RequestBody(required = false) Map<String, Object> body) {
        return execute("Community list fetched successfully", () -> service.list(body));
    }

    @PostMapping("/add")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> add(@RequestBody(required = false) Map<String, Object> body) {
        return execute("Community added successfully", () -> service.add(body));
    }

    @GetMapping("/member/list")
    public ResponseEntity<Map<String, Object>> memberList(@RequestParam Map<String, String> query) {
        return execute("Community member list fetched successfully", () -> service.memberList(query));
    }

    @GetMapping("/member/chat/list")
    public ResponseEntity<Map<String, Object>> memberChatList(@RequestBody(required = false) Map<String, Object> body) {
        return execute("Community member chat list fetched successfully", () -> service.memberChatList(body));
    }

    @PatchMapping("/change/user/status")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> changeCommunityUserStatus(
            @RequestBody(required = false) Map<String, Object> body) {
        return execute("Community user status changed successfully", () -> service.changeCommunityUserStatus(body));
    }
}
