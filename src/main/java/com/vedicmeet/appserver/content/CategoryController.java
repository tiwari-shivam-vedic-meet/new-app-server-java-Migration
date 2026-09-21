package com.vedicmeet.appserver.content;

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
 * Port of Node modules/category.js `GET /v1/category` (mounted with `authorization`
 * = user OR consultant). Same success/error envelopes as Node.
 */
@RestController
@RequestMapping("/v2/v1/category")
public class CategoryController {

    private final CategoryService categoryService;

    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @GetMapping
    @RequireRole({Role.USER, Role.CONSULTANT})
    public ApiResponse<?> list(@RequestParam(required = false) Integer page,
                                    @RequestParam(required = false) Integer limit,
                                    @RequestParam(required = false) String search,
                                    @RequestParam(required = false) String status) {
        try {
            Map<String, Object> result = categoryService.listCategory(page, limit, search, status);
            return ApiResponse.ok("Category list fetched successfully", result);
        } catch (Exception error) {
            return new ApiResponse<>(false, 500, error.getMessage(), Collections.emptyMap());
        }
    }
}
