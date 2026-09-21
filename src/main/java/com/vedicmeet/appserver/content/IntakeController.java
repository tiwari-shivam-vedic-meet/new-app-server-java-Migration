package com.vedicmeet.appserver.content;

import com.vedicmeet.appserver.migration.MigrationWrite;
import com.vedicmeet.appserver.security.*;
import com.vedicmeet.appserver.web.ApiResponse;
import org.bson.Document;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.Map;

@RestController
@RequestMapping("/v2/v1/intake")
@RequireRole({Role.USER, Role.CONSULTANT})
public class IntakeController {
    private final IntakeService service;
    private final AuthUserService users;
    public IntakeController(IntakeService service, AuthUserService users) {
        this.service = service; this.users = users;
    }

    @GetMapping
    public ApiResponse<?> list(@CurrentUser AuthPrincipal principal,
                               @RequestParam(required = false) Integer page,
                               @RequestParam(required = false) Integer limit,
                               @RequestParam(required = false) String search) {
        try { return ApiResponse.ok("Intake list fetched successfully",
                service.list(requireUser(principal), page, limit, search)); }
        catch (Exception e) { return new ApiResponse<>(false, 500, e.getMessage(), Collections.emptyMap()); }
    }

    @PostMapping("/add_update")
    @MigrationWrite
    public ApiResponse<?> addUpdate(@CurrentUser AuthPrincipal principal,
                                    @RequestBody(required = false) Map<String, Object> body) {
        try {
            service.addOrUpdate(body, requireUser(principal));
            return ApiResponse.ok("Intake added successfully", Collections.emptyMap());
        } catch (Exception e) { return new ApiResponse<>(false, 500, e.getMessage(), Collections.emptyMap()); }
    }

    private Document requireUser(AuthPrincipal principal) {
        Document user = users.load(principal);
        if (user == null) throw new IllegalStateException("USER_NOT_FOUND");
        return user;
    }
}
