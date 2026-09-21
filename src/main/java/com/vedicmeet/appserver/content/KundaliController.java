package com.vedicmeet.appserver.content;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vedicmeet.appserver.migration.MigrationWrite;
import com.vedicmeet.appserver.security.*;
import com.vedicmeet.appserver.web.ApiResponse;
import org.bson.Document;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Exact mobile route/message surface from Node {@code modules/kundali.js}. */
@RestController
@RequestMapping("/v2/v1/kundali")
@RequireRole({Role.USER, Role.CONSULTANT})
public class KundaliController {
    private final KundaliService service;
    private final AuthUserService users;
    private final ObjectMapper mapper;

    public KundaliController(KundaliService service, AuthUserService users, ObjectMapper mapper) {
        this.service = service; this.users = users; this.mapper = mapper;
    }

    @GetMapping
    public ApiResponse<?> list(@CurrentUser AuthPrincipal principal,
                               @RequestParam(required = false) String consultantFormRequestId,
                               @RequestParam(required = false) Integer page,
                               @RequestParam(required = false) Integer limit,
                               @RequestParam(required = false) String search) {
        return call("Kundali list fetched successfully",
                () -> service.list(actor(principal), consultantFormRequestId, page, limit, search));
    }

    @PostMapping("/add")
    @MigrationWrite
    public ApiResponse<?> add(@CurrentUser AuthPrincipal principal, @RequestBody Map<String, Object> body) {
        return call("Kundali added successfully", () -> service.add(body, actor(principal)));
    }

    @PutMapping("/update")
    @MigrationWrite
    public ApiResponse<?> update(@RequestBody Map<String, Object> body) {
        return call("Kundali updated successfully", () -> service.update(body));
    }

    @GetMapping("/details")
    public ApiResponse<?> details(@RequestParam String kundaliId) {
        return call("Kundali details fetched successfully", () -> service.details(kundaliId));
    }

    @PostMapping("/match_making_add")
    @MigrationWrite
    public ApiResponse<?> addMatch(@CurrentUser AuthPrincipal principal,
                                   @RequestBody(required = false) Map<String, Object> body) {
        return call("Match making added successfully", () -> service.addMatch(body, actor(principal)));
    }

    @GetMapping("/match_making")
    public ApiResponse<?> matches(@CurrentUser AuthPrincipal principal,
                                  @RequestParam(required = false) Integer page,
                                  @RequestParam(required = false) Integer limit) {
        return call("Match making list fetched successfully",
                () -> service.matches(actor(principal), page, limit));
    }

    @PostMapping("/yogas")
    public ApiResponse<?> yogas(@RequestParam(required = false) String planets,
                                @RequestBody(required = false) List<Map<String, Object>> body) {
        return call("Yogas fetched successfully", () -> service.yogas(
                body != null ? body : parsePlanets(planets)));
    }

    private List<Map<String, Object>> parsePlanets(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        try { return mapper.readValue(raw, new TypeReference<>() {}); }
        catch (Exception e) { throw new IllegalArgumentException("planets must be valid JSON"); }
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
