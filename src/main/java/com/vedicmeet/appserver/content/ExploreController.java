package com.vedicmeet.appserver.content;

import com.vedicmeet.appserver.crypto.CryptoService;
import com.vedicmeet.appserver.security.AuthPrincipal;
import com.vedicmeet.appserver.security.AuthUserService;
import com.vedicmeet.appserver.security.CurrentUser;
import com.vedicmeet.appserver.security.RequireRole;
import com.vedicmeet.appserver.security.Role;
import com.vedicmeet.appserver.web.ApiResponse;
import com.vedicmeet.appserver.migration.MigrationWrite;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.Map;

/**
 * Port of Node modules/explore.js `GET /v1/explore` (authed via `authorization` =
 * user OR consultant). The action/comments/bookmark/counts routes in the Node file
 * are writes or extra reads outside this first slice.
 *
 * Resolves the caller's document (for user._id) via AuthUserService, mirroring how
 * Node's middleware attaches req.user.
 */
@RestController
@RequestMapping("/v2/v1/explore")
public class ExploreController {

    private final ExploreService exploreService;
    private final AuthUserService authUserService;
    private final CryptoService cryptoService;

    public ExploreController(ExploreService exploreService, AuthUserService authUserService,
                             CryptoService cryptoService) {
        this.exploreService = exploreService;
        this.authUserService = authUserService;
        this.cryptoService = cryptoService;
    }

    @GetMapping
    @RequireRole({Role.USER, Role.CONSULTANT})
    public ApiResponse<?> list(@CurrentUser AuthPrincipal principal,
                               @RequestParam(required = false) Integer page,
                               @RequestParam(required = false) Integer limit,
                               @RequestParam(required = false) String search,
                               @RequestParam(required = false) String exploreId) {
        try {
            Document caller = authUserService.load(principal);
            ObjectId userId = caller == null ? null : caller.getObjectId("_id");
            Map<String, Object> result = exploreService.listExplore(userId, page, limit, search, exploreId);
            return ApiResponse.ok("Explore list fetched successfully", result);
        } catch (Exception error) {
            return new ApiResponse<>(false, 500, error.getMessage(), Collections.emptyMap());
        }
    }

    /**
     * PUT /action — Prompt C write (diff-pending). Node validateRequest(actionSchema) decrypts
     * reqData into the body; we accept an encrypted {@code reqData} field (preferred) or the plain
     * decrypted fields directly.
     */
    @PutMapping("/action")
    @MigrationWrite
    @RequireRole({Role.USER, Role.CONSULTANT})
    public ApiResponse<?> action(@CurrentUser AuthPrincipal principal,
                                 @RequestBody(required = false) Map<String, Object> body) {
        try {
            Document caller = authUserService.load(principal);
            Map<String, Object> input = body;
            if (body != null && body.get("reqData") instanceof String) {
                input = cryptoService.decryptToMap((String) body.get("reqData"));
            }
            exploreService.action(input, caller);
            return ApiResponse.ok("Explore action performed successfully", Collections.emptyMap());
        } catch (Exception error) {
            return new ApiResponse<>(false, 500, error.getMessage(), Collections.emptyMap());
        }
    }
}
