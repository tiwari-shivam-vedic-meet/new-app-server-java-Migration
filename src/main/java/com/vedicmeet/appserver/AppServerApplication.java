package com.vedicmeet.appserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Java rewrite of the VedicMeet new-app-server.
 *
 * Migration model: strangler fig. This service runs alongside the existing Node
 * service, shares the SAME MongoDB and Redis, and exposes migrated endpoints
 * under a /v2 prefix. Business logic is ported unchanged; only the API version
 * prefix is added. See README.md.
 */
@SpringBootApplication
public class AppServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(AppServerApplication.class, args);
    }
}
