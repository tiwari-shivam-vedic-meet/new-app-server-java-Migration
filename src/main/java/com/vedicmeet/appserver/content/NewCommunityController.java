package com.vedicmeet.appserver.content;

import com.vedicmeet.appserver.migration.MigrationWrite;
import com.vedicmeet.appserver.security.AuthPrincipal;
import com.vedicmeet.appserver.security.AuthUserService;
import com.vedicmeet.appserver.security.CurrentUser;
import com.vedicmeet.appserver.security.RequireRole;
import com.vedicmeet.appserver.security.Role;
import org.bson.Document;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** Node-compatible routes under {@code /v1/user/new_community}. */
@RestController
@RequestMapping("/v2/v1/user/new_community")
@RequireRole({Role.USER, Role.CONSULTANT})
public class NewCommunityController {
    private final NewCommunityService service;
    private final AuthUserService users;

    public NewCommunityController(NewCommunityService service, AuthUserService users) {
        this.service = service;
        this.users = users;
    }

    @PostMapping("/horoscope_personal")
    public Map<String, Object> horoscope(@CurrentUser AuthPrincipal principal,
                                         @RequestBody Map<String, Object> body) {
        return call("Horoscope personal", () -> service.personalizedHoroscope(body, actor(principal)));
    }

    @GetMapping("/get-list")
    public Map<String, Object> list(@CurrentUser AuthPrincipal principal, @RequestParam String groupId) {
        return call("List", () -> service.getList(groupId, actor(principal)));
    }

    @PutMapping("/request") @MigrationWrite
    public Map<String, Object> request(@CurrentUser AuthPrincipal principal,
                                       @RequestBody Map<String, Object> body) {
        return call("Request", () -> service.request(body, actor(principal)));
    }

    @PutMapping("/add_member_details") @MigrationWrite
    public Map<String, Object> addMember(@RequestBody Map<String, Object> body) {
        return call("Add member details", () -> service.addMemberDetails(body));
    }

    @PostMapping("/accept") @MigrationWrite
    public Map<String, Object> accept(@CurrentUser AuthPrincipal principal,
                                      @RequestBody Map<String, Object> body) {
        return call("Accept", () -> service.accept(body, actor(principal)));
    }

    @GetMapping("/get-all-requests")
    public Map<String, Object> requests(@CurrentUser AuthPrincipal principal,
                                        @RequestParam String groupId,
                                        @RequestParam(required = false) String status) {
        return call("Get all requests", () -> service.allRequests(groupId, status, actor(principal)));
    }

    private Document actor(AuthPrincipal principal) {
        Document actor = users.load(principal);
        if (actor == null) throw new IllegalStateException("ACCOUNT_NOT_FOUND");
        return actor;
    }

    private Map<String, Object> call(String message, Work work) {
        Map<String, Object> out = new LinkedHashMap<>();
        try { out.put("success", true); out.put("message", message); out.put("data", work.run()); }
        catch (Exception error) { out.put("success", false); out.put("message",
                error.getMessage() == null ? "Something went wrong" : error.getMessage()); }
        return out;
    }

    @FunctionalInterface private interface Work { Object run(); }
}
