package com.vedicmeet.appserver.updates;

import org.bson.Document;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Port of Node modules/app-updates.js `GET /updates/manifest.json` (PUBLIC).
 * Returns the manifest object directly (not the ApiResponse envelope), matching Node.
 * Missing manifest -> HTTP 404 {success:false,message}; error -> HTTP 500
 * {success:false,message,error}. The bundle upload/serve/theme routes in the Node file
 * are admin/file operations outside this read slice.
 */
@RestController
@RequestMapping("/v2/updates")
public class UpdatesController {

    private final UpdatesService updatesService;

    public UpdatesController(UpdatesService updatesService) {
        this.updatesService = updatesService;
    }

    @GetMapping("/manifest.json")
    public ResponseEntity<Object> manifest(@RequestParam(required = false) String platform) {
        try {
            Document manifest = updatesService.getManifest(platform);
            if (manifest == null) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("success", false);
                body.put("message", "Manifest file not found. Please upload bundles first.");
                return ResponseEntity.status(404).body(body);
            }
            return ResponseEntity.ok(manifest);
        } catch (Exception error) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", false);
            body.put("message", "Error reading manifest file");
            body.put("error", error.getMessage());
            return ResponseEntity.status(500).body(body);
        }
    }
}
