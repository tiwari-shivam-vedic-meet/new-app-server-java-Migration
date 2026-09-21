package com.vedicmeet.appserver.config;

import com.vedicmeet.appserver.security.CurrentUserArgumentResolver;
import com.vedicmeet.appserver.security.RoleInterceptor;
import com.vedicmeet.appserver.migration.MigrationWriteInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Registers the role-enforcement interceptor and the CORS policy.
 * Allowed origins come from config (CORS_ALLOWED_ORIGINS) so production can lock
 * them down to the real app/website origins instead of "*".
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final RoleInterceptor roleInterceptor;
    private final MigrationWriteInterceptor migrationWriteInterceptor;
    private final CurrentUserArgumentResolver currentUserArgumentResolver;
    private final String allowedOrigins;

    public WebConfig(RoleInterceptor roleInterceptor,
                     MigrationWriteInterceptor migrationWriteInterceptor,
                     CurrentUserArgumentResolver currentUserArgumentResolver,
                     @Value("${vedicmeet.cors.allowed-origins:*}") String allowedOrigins) {
        this.roleInterceptor = roleInterceptor;
        this.migrationWriteInterceptor = migrationWriteInterceptor;
        this.currentUserArgumentResolver = currentUserArgumentResolver;
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(roleInterceptor).addPathPatterns("/v2/**");
        registry.addInterceptor(migrationWriteInterceptor).addPathPatterns("/v2/**");
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentUserArgumentResolver);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = allowedOrigins.split("\\s*,\\s*");
        var mapping = registry.addMapping("/**")
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("X-Correlation-Id")
                .maxAge(3600);
        if (origins.length == 1 && "*".equals(origins[0])) {
            // Wildcard cannot be combined with credentials; keep credentials off.
            mapping.allowedOriginPatterns("*").allowCredentials(false);
        } else {
            mapping.allowedOrigins(origins).allowCredentials(true);
        }
    }
}
