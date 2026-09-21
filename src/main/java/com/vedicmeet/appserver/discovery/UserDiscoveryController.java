package com.vedicmeet.appserver.discovery;

import com.vedicmeet.appserver.crypto.CryptoService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.Map;

/**
 * Consultant-discovery endpoints that live directly under /v2/user (not /cons):
 * consultant_details (this slice), and later consultant_list / top_consultant_list.
 *
 * Node mounts these behind `authorization` + `checkUserHaveMembership`, so each
 * resolves the caller via {@link AuthUserService} and augments it with membership
 * via {@link MembershipService} before delegating — mirroring the Node middleware chain.
 */
@RestController
@RequestMapping("/v2/user")
public class UserDiscoveryController {

    private final ConsultantDetailsService consultantDetailsService;
    private final ConsultantListService consultantListService;
    private final AuthUserService authUserService;
    private final MembershipService membershipService;
    private final CryptoService cryptoService;

    public UserDiscoveryController(ConsultantDetailsService consultantDetailsService,
                                   ConsultantListService consultantListService,
                                   AuthUserService authUserService,
                                   MembershipService membershipService,
                                   CryptoService cryptoService) {
        this.consultantDetailsService = consultantDetailsService;
        this.consultantListService = consultantListService;
        this.authUserService = authUserService;
        this.membershipService = membershipService;
        this.cryptoService = cryptoService;
    }

    /**
     * GET /v2/user/consultant_details — port of user/index.js `consultant_details`
     * (delegates to UserService.consultantDetails). Envelope response.
     *
     * Node reads consultantId from the validated (decrypted reqData) body. We accept
     * either an encrypted {@code reqData} param (preferred, faithful) or a plain
     * {@code consultantId} query param as a fallback for the harness.
     */
    @GetMapping("/consultant_details")
    @RequireRole({Role.USER, Role.CONSULTANT})
    public ApiResponse<?> consultantDetails(@CurrentUser AuthPrincipal principal,
                                            @RequestParam(required = false) String reqData,
                                            @RequestParam(required = false) String consultantId) {
        try {
            String resolvedId = consultantId;
            if (reqData != null && !reqData.isEmpty()) {
                Map<String, Object> body = cryptoService.decryptToMap(reqData);
                Object cid = body.get("consultantId");
                if (cid != null) resolvedId = cid.toString();
            }
            if (resolvedId == null || resolvedId.isEmpty()) {
                return new ApiResponse<>(false, 500, "consultantId is required", Collections.emptyMap());
            }

            Document caller = authUserService.load(principal);
            membershipService.applyMembership(caller); // mirrors checkUserHaveMembership
            Map<String, Object> result = consultantDetailsService.consultantDetails(resolvedId, caller);
            return ApiResponse.ok("Consultant details fetched successfully", result);
        } catch (Exception error) {
            return new ApiResponse<>(false, 500, error.getMessage(), Collections.emptyMap());
        }
    }

    /**
     * POST /v2/user/consultant_list — port of user/index.js `consultant_list`
     * (delegates to UserService.consultantList). Encrypted `reqData` body; envelope response.
     */
    @PostMapping("/consultant_list")
    @RequireRole({Role.USER, Role.CONSULTANT})
    public ApiResponse<?> consultantList(@CurrentUser AuthPrincipal principal,
                                         @RequestBody Map<String, Object> body) {
        try {
            Map<String, Object> input = decodeReqData(body);
            Document caller = authUserService.load(principal);
            membershipService.applyMembership(caller);
            Map<String, Object> result = consultantListService.consultantList(input, caller);
            return ApiResponse.ok("Consultant list fetched successfully", result);
        } catch (Exception error) {
            return new ApiResponse<>(false, 500, error.getMessage(), Collections.emptyMap());
        }
    }

    /**
     * POST /v2/user/top_consultant_list — Node delegates to the SAME UserService.consultantList;
     * only the success message differs.
     */
    @PostMapping("/top_consultant_list")
    @RequireRole({Role.USER, Role.CONSULTANT})
    public ApiResponse<?> topConsultantList(@CurrentUser AuthPrincipal principal,
                                            @RequestBody Map<String, Object> body) {
        try {
            Map<String, Object> input = decodeReqData(body);
            Document caller = authUserService.load(principal);
            membershipService.applyMembership(caller);
            Map<String, Object> result = consultantListService.consultantList(input, caller);
            return ApiResponse.ok("Top consultant list fetched successfully", result);
        } catch (Exception error) {
            return new ApiResponse<>(false, 500, error.getMessage(), Collections.emptyMap());
        }
    }

    /** Node's validateRequest decrypts the reqData envelope into req.body; mirror that. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> decodeReqData(Map<String, Object> body) {
        if (body != null && body.get("reqData") instanceof String) {
            return cryptoService.decryptToMap((String) body.get("reqData"));
        }
        return body == null ? new java.util.LinkedHashMap<>() : body;
    }
}
