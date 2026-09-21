package com.vedicmeet.appserver.social;

import com.vedicmeet.appserver.crypto.CryptoService;
import com.vedicmeet.appserver.security.AuthPrincipal;
import com.vedicmeet.appserver.security.AuthUserService;
import com.vedicmeet.appserver.security.CurrentUser;
import com.vedicmeet.appserver.security.RequireRole;
import com.vedicmeet.appserver.security.Role;
import com.vedicmeet.appserver.web.ApiResponse;
import com.vedicmeet.appserver.migration.MigrationWrite;
import org.bson.Document;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.Map;

/**
 * Prompt C social writes from Node modules/user/index.js:
 *   - PUT  /v2/user/live_like → UserService.likeLiveEvent
 *   - POST /v2/user/follow    → UserService.followCons
 *
 * Node's validateRequest decrypts the reqData envelope into req.body; we mirror that (accept an
 * encrypted {@code reqData} field or the plain decrypted fields). Same {code,success,message,result}
 * envelope as Node.
 *
 * NOTE: this is a third controller mapped at /v2/user (with UserController + UserDiscoveryController).
 * No path collision — consider consolidating the /v2/user controllers later (see MIGRATION_STATUS.md).
 * Diff-pending: enable on /v2 only after the contract-harness diff is clean on the TEST server.
 */
@RestController
@RequestMapping("/v2/user")
public class SocialController {

    private final SocialWriteService service;
    private final AuthUserService authUserService;
    private final CryptoService cryptoService;

    public SocialController(SocialWriteService service, AuthUserService authUserService,
                            CryptoService cryptoService) {
        this.service = service;
        this.authUserService = authUserService;
        this.cryptoService = cryptoService;
    }

    @PutMapping("/live_like")
    @MigrationWrite
    @RequireRole(Role.USER)
    public ApiResponse<?> liveLike(@CurrentUser AuthPrincipal principal,
                                   @RequestBody(required = false) Map<String, Object> body) {
        try {
            Document user = authUserService.load(principal);
            service.likeLiveEvent(decode(body), user);
            return ApiResponse.ok("Live like updated successfully", Collections.emptyMap());
        } catch (Exception error) {
            return new ApiResponse<>(false, 500, error.getMessage(), Collections.emptyMap());
        }
    }

    @PostMapping("/follow")
    @MigrationWrite
    @RequireRole(Role.USER)
    public ApiResponse<?> follow(@CurrentUser AuthPrincipal principal,
                                 @RequestBody(required = false) Map<String, Object> body) {
        try {
            // Node followCons(req.body) trusts body.userId (does not read req.user).
            return ApiResponse.ok("Follow consultant fetched successfully", service.followCons(decode(body)));
        } catch (Exception error) {
            return new ApiResponse<>(false, 500, error.getMessage(), Collections.emptyMap());
        }
    }

    private Map<String, Object> decode(Map<String, Object> body) {
        if (body != null && body.get("reqData") instanceof String) {
            return cryptoService.decryptToMap((String) body.get("reqData"));
        }
        return body;
    }
}
