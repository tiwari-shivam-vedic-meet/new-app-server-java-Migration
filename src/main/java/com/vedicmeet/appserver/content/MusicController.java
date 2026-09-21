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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.Map;

/** Exact mobile route/message surface from Node {@code rest-apis/modules/music.js}. */
@RestController
@RequestMapping("/v2/v1/music")
@RequireRole({Role.USER, Role.CONSULTANT})
public class MusicController {
    private final MusicService service;
    private final AuthUserService users;

    public MusicController(MusicService service, AuthUserService users) {
        this.service = service;
        this.users = users;
    }

    @GetMapping
    public ApiResponse<?> list(@CurrentUser AuthPrincipal principal,
                               @RequestParam(required = false) Integer page,
                               @RequestParam(required = false) Integer limit) {
        return call("Music list fetched successfully", () -> service.categories(actor(principal), page, limit));
    }

    @GetMapping("/media")
    public ApiResponse<?> media(@CurrentUser AuthPrincipal principal, @RequestParam String musicId,
                                @RequestParam(required = false) Integer page,
                                @RequestParam(required = false) Integer limit) {
        return call("Music media list fetched successfully",
                () -> service.media(actor(principal), musicId, page, limit));
    }

    @PostMapping("/fav_add")
    @MigrationWrite
    public ApiResponse<?> favorite(@CurrentUser AuthPrincipal principal,
                                   @RequestBody Map<String, Object> body) {
        return call("Music media favorite added successfully",
                () -> service.toggleFavorite(actor(principal), text(body, "musicMediaId")));
    }

    @GetMapping("/fav_list")
    public ApiResponse<?> favorites(@CurrentUser AuthPrincipal principal,
                                    @RequestParam(required = false) Integer page,
                                    @RequestParam(required = false) Integer limit) {
        return call("Music media favorite list fetched successfully",
                () -> service.favorites(actor(principal), page, limit));
    }

    @PostMapping("/recent_add")
    @MigrationWrite
    public ApiResponse<?> recent(@CurrentUser AuthPrincipal principal,
                                 @RequestBody Map<String, Object> body) {
        return call("Music media played successfully", () -> {
            service.markPlayed(actor(principal), text(body, "musicMediaId"));
            return null;
        });
    }

    @PostMapping("/mood_set")
    @MigrationWrite
    public ApiResponse<?> mood(@CurrentUser AuthPrincipal principal,
                               @RequestBody Map<String, Object> body) {
        return call("Music media mood set successfully",
                () -> service.setMood(actor(principal), body.get("moodAvg") instanceof Number n ? n : null));
    }

    @GetMapping("/mood_list")
    public ApiResponse<?> moods(@CurrentUser AuthPrincipal principal, @RequestParam String type) {
        return call("Music media mood list fetched successfully",
                () -> service.moods(actor(principal), type));
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
