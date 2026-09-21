package com.vedicmeet.appserver.content;

import com.vedicmeet.appserver.web.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;

/**
 * Port of Node modules/cms.js `GET /v1/cms/details`. PUBLIC in Node (mounted with
 * NO authorization middleware), so no @RequireRole here. Same success/error envelopes.
 */
@RestController
@RequestMapping("/v2/v1/cms")
public class CmsController {

    private final CmsService cmsService;

    public CmsController(CmsService cmsService) {
        this.cmsService = cmsService;
    }

    @GetMapping("/details")
    public ApiResponse<?> details(@RequestParam(required = false) String userType,
                                  @RequestParam(required = false) String type) {
        try {
            Object result = cmsService.cmsDetails(userType, type);
            return ApiResponse.ok("CMS details fetched successfully", result);
        } catch (Exception error) {
            return new ApiResponse<>(false, 500, error.getMessage(), Collections.emptyMap());
        }
    }
}
