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

/** Exact mobile route/message surface from Node {@code modules/growth-training.js}. */
@RestController
@RequestMapping("/v2/v1/growth")
@RequireRole({Role.USER, Role.CONSULTANT})
public class GrowthTrainingController {
    private final GrowthTrainingService service;
    private final AuthUserService users;

    public GrowthTrainingController(GrowthTrainingService service, AuthUserService users) {
        this.service = service;
        this.users = users;
    }

    @GetMapping("/category")
    public ApiResponse<?> categories(@CurrentUser AuthPrincipal principal,
                                     @RequestParam(required = false) Integer page,
                                     @RequestParam(required = false) Integer limit) {
        return call("Growth training category fetched successfully",
                () -> service.categories(actor(principal), page, limit));
    }

    @GetMapping("/media")
    public ApiResponse<?> media(@RequestParam String listType,
                                @RequestParam(required = false) String growthTrainingCategoryId,
                                @RequestParam(required = false) Integer page,
                                @RequestParam(required = false) Integer limit) {
        return call("Growth training media fetched successfully",
                () -> service.media(listType, growthTrainingCategoryId, page, limit));
    }

    @PostMapping("/video_play")
    @MigrationWrite
    public ApiResponse<?> play(@CurrentUser AuthPrincipal principal,
                               @RequestBody Map<String, Object> body) {
        return call("Growth training media fetched successfully", () -> {
            Object value = body == null ? null : body.get("growthTrainingMediaId");
            service.markPlayed(actor(principal), value == null ? null : String.valueOf(value));
            return null;
        });
    }

    @GetMapping("/check")
    public ApiResponse<?> check(@CurrentUser AuthPrincipal principal) {
        return call("Growth training media fetched successfully", () -> service.checkComplete(actor(principal)));
    }

    private Document actor(AuthPrincipal principal) {
        Document actor = users.load(principal);
        if (actor == null) throw new IllegalStateException("ACCOUNT_NOT_FOUND");
        return actor;
    }

    private ApiResponse<?> call(String message, Work work) {
        try { return ApiResponse.ok(message, work.run()); }
        catch (Exception e) { return new ApiResponse<>(false, 500, e.getMessage(), Collections.emptyMap()); }
    }

    @FunctionalInterface private interface Work { Object run(); }
}
