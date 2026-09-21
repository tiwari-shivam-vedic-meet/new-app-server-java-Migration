package com.vedicmeet.appserver.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vedicmeet.appserver.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Operational kill switch for strangler writes. Read routes can be shadowed safely while mutating
 * routes remain unavailable until contract, side-effect, and rollback sign-off is complete.
 */
@Component
public class MigrationWriteInterceptor implements HandlerInterceptor {

    private final boolean writesEnabled;
    private final ObjectMapper mapper;

    public MigrationWriteInterceptor(
            @Value("${vedicmeet.migration.writes-enabled:false}") boolean writesEnabled,
            ObjectMapper mapper) {
        this.writesEnabled = writesEnabled;
        this.mapper = mapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!(handler instanceof HandlerMethod method) || !isMigrationWrite(method) || writesEnabled) {
            return true;
        }
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("X-VedicMeet-Migration-Write", "disabled");
        response.getWriter().write(mapper.writeValueAsString(
                ApiResponse.fail("Migration write route is disabled")));
        return false;
    }

    private boolean isMigrationWrite(HandlerMethod method) {
        return method.getMethodAnnotation(MigrationWrite.class) != null
                || method.getBeanType().getAnnotation(MigrationWrite.class) != null;
    }
}
