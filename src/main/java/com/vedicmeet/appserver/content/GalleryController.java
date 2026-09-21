package com.vedicmeet.appserver.content;

import com.vedicmeet.appserver.web.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.Map;

/**
 * Port of Node modules/gallery.js `GET /v1/gallery` (PUBLIC in Node). The add route
 * (POST /add) is an authed write and is intentionally out of Week-2 read scope.
 * Same success/error envelopes as Node.
 */
@RestController
@RequestMapping("/v2/v1/gallery")
public class GalleryController {

    private final GalleryService galleryService;

    public GalleryController(GalleryService galleryService) {
        this.galleryService = galleryService;
    }

    @GetMapping
    public ApiResponse<?> list(@RequestParam(required = false) String consultantId,
                               @RequestParam(required = false) Integer page,
                               @RequestParam(required = false) Integer limit,
                               @RequestParam(required = false) String galleryType) {
        try {
            Map<String, Object> result = galleryService.listGalleries(consultantId, page, limit, galleryType);
            return ApiResponse.ok("Gallery list fetched successfully", result);
        } catch (Exception error) {
            return new ApiResponse<>(false, 500, error.getMessage(), Collections.emptyMap());
        }
    }
}
