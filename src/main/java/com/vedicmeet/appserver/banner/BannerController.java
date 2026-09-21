package com.vedicmeet.appserver.banner;

import com.vedicmeet.appserver.security.AuthPrincipal;
import com.vedicmeet.appserver.security.AuthUserService;
import com.vedicmeet.appserver.security.CurrentUser;
import com.vedicmeet.appserver.security.RequireRole;
import com.vedicmeet.appserver.security.Role;
import com.vedicmeet.appserver.migration.MigrationWrite;
import com.vedicmeet.appserver.web.ApiResponse;
import org.bson.Document;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.Map;

/**
 * Port of Node modules/banner.js `GET /v1/banner` (authed via `authorization` =
 * user OR consultant). Language comes from the `app-device-language` header (default
 * `en`), userType from the query, exactly as Node. Same success/error envelopes.
 *
 * NOTE: this endpoint mutates boost state and computes pricing (see BannerService /
 * BANNER_PHASE2_SPEC.md). It must be diff-verified AND write-verified via the contract
 * harness before it is enabled on /v2.
 */
@RestController
@RequestMapping("/v2/v1/banner")
public class BannerController {

    private final BannerService bannerService;
    private final AuthUserService authUserService;

    public BannerController(BannerService bannerService, AuthUserService authUserService) {
        this.bannerService = bannerService;
        this.authUserService = authUserService;
    }

    @GetMapping
    @MigrationWrite
    @RequireRole({Role.USER, Role.CONSULTANT})
    public ApiResponse<?> home(@CurrentUser AuthPrincipal principal,
                               @RequestParam(name = "userType", required = false) String userType,
                               @RequestHeader(name = "app-device-language", required = false) String appDeviceLanguage) {
        try {
            String language = (appDeviceLanguage == null || appDeviceLanguage.isEmpty()) ? "en" : appDeviceLanguage;
            Document user = authUserService.load(principal);
            Map<String, Object> result = bannerService.home(userType, language, user);
            return ApiResponse.ok("Banner list fetched successfully", result);
        } catch (Exception error) {
            return new ApiResponse<>(false, 500, error.getMessage(), Collections.emptyMap());
        }
    }
}
