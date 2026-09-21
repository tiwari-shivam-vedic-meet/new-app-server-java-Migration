package com.vedicmeet.appserver.content;

import com.vedicmeet.appserver.security.RequireRole;
import com.vedicmeet.appserver.security.Role;
import com.vedicmeet.appserver.web.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.Set;

@RestController
@RequestMapping("/v2/v1/horoscope")
@RequireRole({Role.USER, Role.CONSULTANT})
public class HoroscopeController {
    private static final Set<String> TYPES = Set.of("TODAY", "WEEKLY", "MONTHLY", "YEARLY");
    private final HoroscopeService service;
    public HoroscopeController(HoroscopeService service) { this.service = service; }

    @GetMapping
    public ApiResponse<?> list(@RequestParam(required = false) Integer page,
                               @RequestParam(required = false) Integer limit,
                               @RequestParam(required = false) String search) {
        try { return ApiResponse.ok("Horoscope list fetched successfully", service.list(page, limit, search)); }
        catch (Exception e) { return new ApiResponse<>(false, 500, e.getMessage(), Collections.emptyMap()); }
    }

    @GetMapping("/details")
    public ApiResponse<?> details(@RequestParam String horoscopeId, @RequestParam String sign,
                                  @RequestParam String type) {
        try {
            if (!TYPES.contains(type)) throw new IllegalArgumentException("type must be one of TODAY, WEEKLY, MONTHLY, YEARLY");
            return ApiResponse.ok("Horoscope details fetched successfully",
                    service.details(horoscopeId, sign, type));
        } catch (Exception e) {
            return new ApiResponse<>(false, 500, e.getMessage(), Collections.emptyMap());
        }
    }
}
