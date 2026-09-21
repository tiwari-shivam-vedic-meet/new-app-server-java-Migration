package com.vedicmeet.appserver.content;

import com.vedicmeet.appserver.security.RequireRole;
import com.vedicmeet.appserver.security.Role;
import com.vedicmeet.appserver.web.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;

/**
 * Port of Node modules/membership-discount.js (GET list + GET /details), authed via
 * `authorization` = user OR consultant. Same success/error envelopes.
 */
@RestController
@RequestMapping("/v2/v1/membership-discount")
public class MembershipDiscountController {

    private final MembershipDiscountService service;

    public MembershipDiscountController(MembershipDiscountService service) {
        this.service = service;
    }

    @GetMapping
    @RequireRole({Role.USER, Role.CONSULTANT})
    public ApiResponse<?> list(@RequestParam(required = false) String type,
                               @RequestParam(required = false) Integer page,
                               @RequestParam(required = false) Integer limit,
                               @RequestParam(required = false) String search,
                               @RequestParam(required = false) String couponType) {
        try {
            return ApiResponse.ok("Membership discount list fetched successfully",
                    service.list(type, page, limit, search, couponType));
        } catch (Exception error) {
            return new ApiResponse<>(false, 500, error.getMessage(), Collections.emptyMap());
        }
    }

    @GetMapping("/details")
    @RequireRole({Role.USER, Role.CONSULTANT})
    public ApiResponse<?> details(@RequestParam(required = false) String type,
                                  @RequestParam(required = false) String couponId,
                                  @RequestParam(required = false) String membershipDiscountId,
                                  @RequestParam(required = false) String couponType) {
        try {
            return ApiResponse.ok("Membership discount details fetched successfully",
                    service.details(type, couponId, membershipDiscountId, couponType));
        } catch (Exception error) {
            return new ApiResponse<>(false, 500, error.getMessage(), Collections.emptyMap());
        }
    }
}
