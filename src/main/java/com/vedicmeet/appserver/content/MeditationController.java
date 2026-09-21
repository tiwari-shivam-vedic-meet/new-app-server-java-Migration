package com.vedicmeet.appserver.content;

import com.vedicmeet.appserver.migration.MigrationWrite;
import com.vedicmeet.appserver.security.AuthPrincipal;
import com.vedicmeet.appserver.security.AuthUserService;
import com.vedicmeet.appserver.security.CurrentUser;
import com.vedicmeet.appserver.security.RequireRole;
import com.vedicmeet.appserver.security.Role;
import com.vedicmeet.appserver.web.ApiResponse;
import org.bson.Document;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.Map;

/** Exact mobile route/message surface from Node {@code modules/meditation.js}. */
@RestController
@RequestMapping("/v2/v1/meditation")
@RequireRole({Role.USER, Role.CONSULTANT})
public class MeditationController {
    private final MeditationService service;
    private final AuthUserService users;

    public MeditationController(MeditationService service, AuthUserService users) {
        this.service = service;
        this.users = users;
    }

    @GetMapping
    public ApiResponse<?> list(@CurrentUser AuthPrincipal principal,
                               @RequestParam(required = false) Integer page,
                               @RequestParam(required = false) Integer limit) {
        return call("Meditation list fetched successfully",
                () -> service.categories(actor(principal), page, limit));
    }

    @PostMapping("/play_stop")
    @MigrationWrite
    public ApiResponse<?> playStop(@CurrentUser AuthPrincipal principal,
                                   @RequestBody Map<String, Object> body) {
        return call("Meditation played successfully", () -> {
            service.playOrStop(actor(principal), text(body, "meditationMediaId"),
                    text(body, "playType"), body.get("timeDuration") instanceof Number n ? n : null);
            return null;
        });
    }

    @GetMapping("/dashboard")
    public ApiResponse<?> dashboard(@CurrentUser AuthPrincipal principal) {
        return call("Meditation dashboard fetched successfully", () -> service.dashboard(actor(principal)));
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
        catch (Exception e) { return new ApiResponse<>(false, 500, e.getMessage(), Collections.emptyMap()); }
    }

    @FunctionalInterface private interface Work { Object run(); }
}
