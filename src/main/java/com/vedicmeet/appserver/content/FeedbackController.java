package com.vedicmeet.appserver.content;

import com.vedicmeet.appserver.migration.MigrationWrite;
import com.vedicmeet.appserver.security.*;
import com.vedicmeet.appserver.web.ApiResponse;
import org.bson.Document;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.Map;

@RestController
@RequestMapping("/v2/v1/feedback")
public class FeedbackController {
    private final FeedbackService service;
    private final AuthUserService users;
    public FeedbackController(FeedbackService service, AuthUserService users) {
        this.service = service; this.users = users;
    }

    @PostMapping("/add")
    @RequireRole({Role.USER, Role.CONSULTANT})
    @MigrationWrite
    public ApiResponse<?> add(@CurrentUser AuthPrincipal principal,
                              @RequestBody(required = false) Map<String, Object> body) {
        try {
            Document actor = users.load(principal);
            if (actor == null) throw new IllegalStateException("ACCOUNT_NOT_FOUND");
            service.add(body == null ? Map.of() : body, actor,
                    Role.CONSULTANT.equals(principal.getRole()) ? "cons" : "user");
            return ApiResponse.ok("Feedback added successfully", Collections.emptyMap());
        } catch (Exception e) {
            return new ApiResponse<>(false, 500, e.getMessage(), Collections.emptyMap());
        }
    }
}
