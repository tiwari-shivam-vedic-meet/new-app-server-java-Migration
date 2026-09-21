package com.vedicmeet.appserver.master;

import com.vedicmeet.appserver.security.RequireRole;
import com.vedicmeet.appserver.security.Role;
import com.vedicmeet.appserver.web.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.Map;

/**
 * Port of Node modules/master.js `GET /master` (mounted with the `authorization`
 * middleware = user OR consultant). Same success and error envelopes as Node:
 *   success: { code:200, success:true, message:'Master list fetched successfully', result }
 *   error:   { code:500, success:false, message:<err>, result:{} }   (HTTP 200)
 *
 * Only the /add_edit, /add_vimshotri, /clear, /getCategory routes from the Node file
 * are admin/write paths and are intentionally NOT part of Week-2 read migration.
 */
@RestController
@RequestMapping("/v2/master")
public class MasterController {

    private final MasterService masterService;

    public MasterController(MasterService masterService) {
        this.masterService = masterService;
    }

    @GetMapping
    @RequireRole({Role.USER, Role.CONSULTANT})
    public ApiResponse<?> list(@RequestParam(name = "deviceType", required = false) String deviceType) {
        try {
            // userType null -> "default" cache bucket for now (see MasterService note).
            Map<String, Object> result = masterService.list(deviceType, null);
            return ApiResponse.ok("Master list fetched successfully", result);
        } catch (Exception error) {
            return new ApiResponse<>(false, 500, error.getMessage(), Collections.emptyMap());
        }
    }
}
