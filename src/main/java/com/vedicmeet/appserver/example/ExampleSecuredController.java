package com.vedicmeet.appserver.example;

import com.vedicmeet.appserver.crypto.CryptoService;
import com.vedicmeet.appserver.security.AuthPrincipal;
import com.vedicmeet.appserver.security.CurrentUser;
import com.vedicmeet.appserver.security.RequireRole;
import com.vedicmeet.appserver.security.Role;
import com.vedicmeet.appserver.web.ApiResponse;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * TEMPLATE — not a real endpoint. Shows the exact pattern every migrated route
 * follows, so a developer (or an AI generating the ports) has a worked example:
 *
 *   - @RequireRole gates the route the way the Node middleware did
 *   - @CurrentUser injects the authenticated caller
 *   - CryptoService handles the encrypted reqData envelope in and out
 *   - ApiResponse preserves the { success, code, message, result } contract
 *
 * Delete this package once a couple of real modules are migrated.
 */
@RestController
@RequestMapping("/v2/example")
public class ExampleSecuredController {

    private final CryptoService cryptoService;

    public ExampleSecuredController(CryptoService cryptoService) {
        this.cryptoService = cryptoService;
    }

    /** Plain authenticated GET — user role only. */
    @GetMapping("/whoami")
    @RequireRole(Role.USER)
    public ApiResponse<Map<String, Object>> whoami(@CurrentUser AuthPrincipal user) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("phone", user.getPhone());
        result.put("phonePrefix", user.getPhonePrefix());
        result.put("role", user.getRole());
        return ApiResponse.ok("OK", result);
    }

    /** Encrypted round-trip — user or consultant. Body is { "reqData": "<base64>" }. */
    @PostMapping("/echo")
    @RequireRole({Role.USER, Role.CONSULTANT})
    public Map<String, Object> echo(@RequestBody Map<String, String> body,
                                    @CurrentUser AuthPrincipal user) {
        Map<String, Object> decrypted = cryptoService.decryptToMap(body.get("reqData"));
        decrypted.put("_echoedFor", user.getPhone());

        // Respond in the same encrypted envelope the apps expect.
        String reqData = cryptoService.encrypt(ApiResponse.ok("OK", decrypted));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reqData", reqData);
        return out;
    }
}
