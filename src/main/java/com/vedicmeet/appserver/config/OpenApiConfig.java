package com.vedicmeet.appserver.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger / OpenAPI configuration for onboarding.
 *
 * <p>Browse the interactive docs at <b>/swagger-ui.html</b> (raw spec at /v3/api-docs).</p>
 *
 * <p>The migration onboards <b>authentication &amp; authorization first</b>, so the default
 * documented group is scoped to the auth package ({@code com.vedicmeet.appserver.auth}) — the
 * mobile user, consultant, and admin auth controllers. This keeps the UI focused on exactly the
 * surface going to production first. A second "all-v2" group is provided for whenever the wider
 * surface needs to be reviewed.</p>
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI vedicMeetOpenApi() {
        return new OpenAPI().info(new Info()
                .title("VedicMeet — Java (/v2) Migration API")
                .version("v2")
                .description("Migrated Spring Boot endpoints. Auth is being onboarded to production first. "
                        + "Send the JWT in the 'vm-user-auth' header (or 'Authorization: Bearer <token>')."));
    }

    /** Default group: authentication & authorization endpoints (the first production onboarding). */
    @Bean
    public GroupedOpenApi authenticationApi() {
        return GroupedOpenApi.builder()
                .group("authentication")
                .packagesToScan("com.vedicmeet.appserver.auth")
                .build();
    }

    /** Everything migrated under /v2, for wider review when needed. */
    @Bean
    public GroupedOpenApi allV2Api() {
        return GroupedOpenApi.builder()
                .group("all-v2")
                .pathsToMatch("/v2/**")
                .build();
    }
}
