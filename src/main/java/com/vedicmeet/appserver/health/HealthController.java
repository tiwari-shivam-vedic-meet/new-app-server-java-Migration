package com.vedicmeet.appserver.health;

import com.vedicmeet.appserver.web.ApiResponse;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * First migrated endpoint under the /v2 prefix. Confirms the service is up and
 * that the shared MongoDB connection is reachable (read-only ping). Used by the
 * gateway health check and by the contract-test harness as a smoke test.
 */
@RestController
@RequestMapping("/v2")
public class HealthController {

    private final MongoTemplate mongoTemplate;

    public HealthController(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("service", "new-app-server-java");
        body.put("time", Instant.now().toString());
        try {
            mongoTemplate.getDb().runCommand(new org.bson.Document("ping", 1));
            body.put("mongo", "up");
        } catch (Exception e) {
            body.put("mongo", "down");
        }
        return ApiResponse.ok("OK", body);
    }
}
