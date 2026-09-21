package com.vedicmeet.appserver.content;

import com.vedicmeet.appserver.migration.MigrationWrite;
import com.vedicmeet.appserver.security.AuthPrincipal;
import com.vedicmeet.appserver.security.AuthUserService;
import com.vedicmeet.appserver.security.CurrentUser;
import com.vedicmeet.appserver.security.RequireRole;
import com.vedicmeet.appserver.security.Role;
import com.vedicmeet.appserver.web.ApiResponse;
import org.bson.Document;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.Map;

/** Exact mobile surface from Node {@code rest-apis/modules/vastu-compass.js}. */
@RestController
@RequestMapping("/v2/v1/vastu-compass")
@RequireRole({Role.USER, Role.CONSULTANT})
public class VastuCompassController {
    private final VastuCompassService service;
    private final AuthUserService users;

    public VastuCompassController(VastuCompassService service, AuthUserService users) {
        this.service = service;
        this.users = users;
    }

    @GetMapping
    public ApiResponse<?> list() {
        return call("Vastu compass category list fetched successfully", service::categories);
    }

    /** Node's GET performs DB writes + notifications, so it must obey the Java write kill-switch. */
    @GetMapping("/report")
    @MigrationWrite
    public ApiResponse<?> report(@CurrentUser AuthPrincipal principal,
                                 @RequestParam Map<String, Object> query) {
        return call("Vastu compass report fetched successfully",
                () -> service.report(query, actor(principal)));
    }

    @PutMapping("/bookmark")
    @MigrationWrite
    public ApiResponse<?> bookmark(@RequestBody Map<String, Object> body) {
        return call("Vastu compass bookmark added successfully", () -> {
            service.bookmark(text(body, "vastuRecordId"), body.get("status") instanceof Boolean b ? b : null);
            return null;
        });
    }

    @GetMapping("/report_list")
    public ApiResponse<?> reportList(@CurrentUser AuthPrincipal principal,
                                     @RequestParam(required = false) Integer page,
                                     @RequestParam(required = false) Integer limit) {
        return call("Vastu compass report list fetched successfully",
                () -> service.reportList(actor(principal), page, limit));
    }

    private Document actor(AuthPrincipal principal) {
        Document actor = users.load(principal);
        if (actor == null) throw new IllegalStateException("ACCOUNT_NOT_FOUND");
        return actor;
    }

    private String text(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private ApiResponse<?> call(String message, Work work) {
        try { return ApiResponse.ok(message, work.run()); }
        catch (Exception error) {
            return new ApiResponse<>(false, 500, error.getMessage(), Collections.emptyMap());
        }
    }

    @FunctionalInterface private interface Work { Object run(); }
}
