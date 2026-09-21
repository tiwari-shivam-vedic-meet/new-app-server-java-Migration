package com.vedicmeet.appserver.admin;

import com.vedicmeet.appserver.migration.MigrationWrite;
import com.vedicmeet.appserver.security.RequireRole;
import com.vedicmeet.appserver.security.Role;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminContentControllerTest {

    private AdminContentService service;
    private AdminContentController controller;

    @BeforeEach
    void setUp() {
        service = mock(AdminContentService.class);
        controller = new AdminContentController(service);
    }

    @Test
    void everyRouteRequiresAdminOrSubAdmin() {
        RequireRole roles = AdminContentController.class.getAnnotation(RequireRole.class);
        assertArrayEquals(new String[]{Role.ADMIN, Role.SUB_ADMIN}, roles.value());
    }

    @Test
    void allMutationsRemainBehindMigrationWriteGate() {
        for (Method method : AdminContentController.class.getDeclaredMethods()) {
            String name = method.getName();
            if (name.startsWith("add") || name.startsWith("edit") || name.endsWith("Status")
                    || name.startsWith("approve") || name.startsWith("put")) {
                assertNotNull(method.getAnnotation(MigrationWrite.class), name);
            }
        }
    }

    @Test
    void bannerListKeepsNodeEnvelopeAndDelegatesFilters() {
        Map<String, Object> result = Map.of("list", java.util.List.of(), "total", 0);
        when(service.listBanners(2, 20, "home", "true", "user")).thenReturn(result);

        ResponseEntity<Map<String, Object>> response =
                controller.listBanners(2, 20, "home", "true", "user");

        assertEquals(200, response.getStatusCode().value());
        assertEquals(true, response.getBody().get("success"));
        assertSame(result, response.getBody().get("result"));
    }

    @Test
    void logicalFailureKeepsNodeHttp200AndBodyCode500() {
        when(service.setGiftStatus("missing", true)).thenThrow(new IllegalStateException("GIFT_NOT_EXIST"));

        ResponseEntity<Map<String, Object>> response =
                controller.giftStatus(Map.of("giftId", "missing", "status", true));

        assertEquals(200, response.getStatusCode().value());
        assertEquals(false, response.getBody().get("success"));
        assertEquals(500, response.getBody().get("code"));
        assertEquals("GIFT_NOT_EXIST", response.getBody().get("message"));
    }

    @Test
    void referralApprovalDelegatesLegacyId() {
        controller.approveReferral(Map.of("referId", "507f1f77bcf86cd799439011"));
        verify(service).approveReferral("507f1f77bcf86cd799439011");
    }

    @Test
    void commonMessageModulePreservesItsDifferentDataEnvelope() {
        Document result = new Document("messages", java.util.List.of());
        when(service.getCommonMessages()).thenReturn(result);

        ResponseEntity<Map<String, Object>> response = controller.getCommonMessages();

        assertEquals(true, response.getBody().get("success"));
        assertSame(result, response.getBody().get("data"));
        assertFalse(response.getBody().containsKey("result"));
    }
}
