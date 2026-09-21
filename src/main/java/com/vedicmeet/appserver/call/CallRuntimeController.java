package com.vedicmeet.appserver.call;

import com.vedicmeet.appserver.migration.MigrationWrite;
import com.vedicmeet.appserver.security.RequireRole;
import com.vedicmeet.appserver.security.Role;
import com.vedicmeet.appserver.web.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Controlled hand-off endpoint used when the strangler router assigns one call to Java. */
@RestController
@RequestMapping("/v2/internal/calls")
public class CallRuntimeController {

    public record InitiateRequest(@NotBlank String userId, @NotBlank String consultantId,
                                  @NotBlank String roomId, @NotBlank String callMode) {}

    private final CallLifecycleService calls;
    private final boolean executionEnabled;

    public CallRuntimeController(CallLifecycleService calls,
                                 @Value("${vedicmeet.call.execution-enabled:false}") boolean executionEnabled) {
        this.calls = calls;
        this.executionEnabled = executionEnabled;
    }

    @PostMapping("/initiate")
    @RequireRole({Role.ADMIN, Role.SUB_ADMIN})
    @MigrationWrite
    public ApiResponse<?> initiate(@Valid @RequestBody InitiateRequest request) {
        if (!executionEnabled) return ApiResponse.fail("Java call execution is disabled");
        try {
            return ApiResponse.ok("Call initiation processed", calls.initiateCall(request.userId(),
                    request.consultantId(), request.roomId(), request.callMode()));
        } catch (RuntimeException error) {
            return new ApiResponse<>(false, 500,
                    error.getMessage() == null ? "Call initiation failed" : error.getMessage(), null);
        }
    }
}
