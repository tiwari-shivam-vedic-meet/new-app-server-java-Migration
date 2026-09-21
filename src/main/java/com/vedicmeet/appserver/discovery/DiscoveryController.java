package com.vedicmeet.appserver.discovery;

import com.vedicmeet.appserver.security.AuthPrincipal;
import com.vedicmeet.appserver.security.AuthUserService;
import com.vedicmeet.appserver.security.CurrentUser;
import com.vedicmeet.appserver.security.RequireRole;
import com.vedicmeet.appserver.security.Role;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Consultant discovery endpoints (Phase 2.1) — Node rest-apis/modules/user/cons.js
 * and user/index.js, mounted here under /v2/user/cons and /v2/user.
 *
 * These are authed via Node's `authorization` (user OR consultant), so they carry
 * {@code @RequireRole({USER, CONSULTANT})} and resolve the caller document via
 * {@link AuthUserService} exactly like ExploreController.
 *
 * IMPORTANT: several of these Node handlers respond with a RAW shape
 * ({@code { success, data }}), not the {@code {code,message,result}} envelope —
 * reproduced verbatim so the contract-harness diff matches. Enable on /v2 only
 * after the diff is clean on the TEST server (see DISCOVERY_PORTING_GUIDE.md).
 */
@RestController
@RequestMapping("/v2/user/cons")
public class DiscoveryController {

    private final ConsDiscoveryService consDiscoveryService;
    private final ConsultantPopularService consultantPopularService;
    private final AuthUserService authUserService;

    public DiscoveryController(ConsDiscoveryService consDiscoveryService,
                               ConsultantPopularService consultantPopularService,
                               AuthUserService authUserService) {
        this.consDiscoveryService = consDiscoveryService;
        this.consultantPopularService = consultantPopularService;
        this.authUserService = authUserService;
    }

    /**
     * GET /v2/user/cons/get-list — port of cons.js L19-123.
     * Returns Node's raw { success, data:{ list, pagination } }; on error, Node's
     * { success:false, message:'Internal server error' } (still HTTP 200).
     */
    @GetMapping("/get-list")
    @RequireRole({Role.USER, Role.CONSULTANT})
    public Map<String, Object> getList(@CurrentUser AuthPrincipal principal,
                                       @RequestParam(required = false) Integer page,
                                       @RequestParam(required = false) Integer pageSize,
                                       @RequestParam(required = false) String sortField,
                                       @RequestParam(required = false) String sortDirection,
                                       @RequestParam(required = false) String filterField,
                                       @RequestParam(required = false) String filterOperator,
                                       @RequestParam(required = false) String filterValue) {
        try {
            Document caller = authUserService.load(principal);
            ObjectId userId = caller == null ? null : caller.getObjectId("_id");
            return consDiscoveryService.getList(userId, page, pageSize,
                    sortField, sortDirection, filterField, filterOperator, filterValue);
        } catch (Exception error) {
            Map<String, Object> fail = new LinkedHashMap<>();
            fail.put("success", false);
            fail.put("message", "Internal server error");
            return fail;
        }
    }

    /**
     * GET /v2/user/cons/popular — port of cons.js L353-760.
     * Returns Node's raw { success, data:{...} }; on error, Node's
     * { success:false, message:'Internal server error' } (still HTTP 200).
     */
    @GetMapping("/popular")
    @RequireRole({Role.USER, Role.CONSULTANT})
    public Map<String, Object> popular(@CurrentUser AuthPrincipal principal,
                                       @RequestParam(required = false) String consultantId) {
        try {
            Document caller = authUserService.load(principal);
            return consultantPopularService.popular(consultantId, caller);
        } catch (Exception error) {
            Map<String, Object> fail = new LinkedHashMap<>();
            fail.put("success", false);
            fail.put("message", "Internal server error");
            return fail;
        }
    }
}
