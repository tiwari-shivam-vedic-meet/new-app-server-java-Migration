package com.vedicmeet.appserver.session;

import com.vedicmeet.appserver.migration.MigrationWrite;
import com.vedicmeet.appserver.security.AuthPrincipal;
import com.vedicmeet.appserver.security.AuthUserService;
import com.vedicmeet.appserver.security.CurrentUser;
import com.vedicmeet.appserver.security.RequireRole;
import com.vedicmeet.appserver.security.Role;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** Mobile fallback endpoints for call push/VoIP actions; disabled until Java owns calls. */
@RestController
@RequestMapping("/v2/v1/user/session")
@RequireRole(Role.USER)
public class UserSessionCallController {

    private final UserSessionCallService calls;
    private final AuthUserService users;
    private final boolean executionEnabled;

    public UserSessionCallController(UserSessionCallService calls, AuthUserService users,
                                     @Value("${vedicmeet.call.execution-enabled:false}") boolean executionEnabled) {
        this.calls = calls;
        this.users = users;
        this.executionEnabled = executionEnabled;
    }

    @PostMapping("/call_status")
    @MigrationWrite
    public Map<String, Object> callStatus(@CurrentUser AuthPrincipal principal,
                                          @RequestBody(required = false) Map<String, Object> body) {
        if (!executionEnabled) return failure("Java call execution is disabled");
        try {
            String type = text(body, "type");
            Document result = calls.callStatus(userId(principal), type, text(body, "appState"));
            Map<String, Object> response = success(type != null && type.equals("cancel")
                    ? "Call status deleted successfully" : "Successfully fetched!");
            if ("pick".equals(type)) response.put("data", result);
            return response;
        } catch (Exception error) { return failure(message(error)); }
    }

    @PostMapping("/accept_incoming_session_request")
    @MigrationWrite
    public Map<String, Object> acceptIncoming(@CurrentUser AuthPrincipal principal,
                                               @RequestBody(required = false) Map<String, Object> body) {
        if (!executionEnabled) return failure("Java call execution is disabled");
        try {
            Map<String, Object> result = calls.acceptIncoming(userId(principal), text(body, "roomId"),
                    text(body, "type"));
            Map<String, Object> response = success("Successfully fetched!");
            response.put("data", result);
            return response;
        } catch (Exception error) { return failure(message(error)); }
    }

    private String userId(AuthPrincipal principal) {
        Document user = users.load(principal);
        if (user == null) throw new IllegalStateException("Consultant not found"); // Node message preserved.
        return String.valueOf(user.get("_id"));
    }

    private String text(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value == null ? null : value.toString();
    }

    private Map<String, Object> success(String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true); response.put("message", message); return response;
    }

    private Map<String, Object> failure(String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", false); response.put("message", message); return response;
    }

    private String message(Exception error) {
        return error.getMessage() == null ? "Internal server error" : error.getMessage();
    }
}
