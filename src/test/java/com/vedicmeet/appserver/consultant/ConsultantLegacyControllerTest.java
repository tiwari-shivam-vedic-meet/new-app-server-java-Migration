package com.vedicmeet.appserver.consultant;

import com.vedicmeet.appserver.crypto.CryptoService;
import com.vedicmeet.appserver.discovery.ConsultantListService;
import com.vedicmeet.appserver.security.AuthPrincipal;
import com.vedicmeet.appserver.security.AuthUserService;
import com.vedicmeet.appserver.security.RequireRole;
import com.vedicmeet.appserver.security.Role;
import com.vedicmeet.appserver.web.ApiResponse;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConsultantLegacyControllerTest {

    private final AuthPrincipal principal = new AuthPrincipal(Map.of(
            "role", "consultant", "phone", "9000000010", "phonePrefix", "91"));
    private final Document actor = new Document("_id", new ObjectId());
    private AuthUserService users;
    private ConsultantLegacyReadService reads;
    private ConsultantLegacyWriteService writes;
    private ConsultantListService lists;
    private ConsultantLegacyController controller;

    @BeforeEach
    void setup() {
        users = mock(AuthUserService.class);
        reads = mock(ConsultantLegacyReadService.class);
        writes = mock(ConsultantLegacyWriteService.class);
        lists = mock(ConsultantListService.class);
        when(users.load(principal)).thenReturn(actor);
        controller = new ConsultantLegacyController(users, reads, writes, lists, mock(CryptoService.class));
    }

    @Test
    void boostUsesAuthenticatedConsultantInsteadOfRepeatingNodeMissingActorBug() {
        Document result = new Document("callActive", true);
        when(writes.boost(actor, "CALL")).thenReturn(result);

        ApiResponse<?> response = controller.boost(principal, "CALL");

        assertSame(result, response.getResult());
        verify(writes).boost(actor, "CALL");
    }

    @Test
    void liveHistoryIsAlwaysActorScoped() {
        Map<String, Object> result = Map.of("list", java.util.List.of(), "total", 0);
        when(reads.liveHistory(actor, 1, 10, "")).thenReturn(result);

        ApiResponse<?> response = controller.liveHistory(principal, 1, 10, "");

        assertSame(result, response.getResult());
        verify(reads).liveHistory(actor, 1, 10, "");
    }

    @Test
    void paySlipNotFoundKeepsSuccessfulNodeEnvelope() {
        when(reads.paySlip(actor, 8, 2026)).thenReturn(null);

        ApiResponse<?> response = controller.paySlip(principal, 8, 2026);

        assertEquals(true, response.isSuccess());
        assertEquals("Pay slip not found", response.getMessage());
    }

    @Test
    void dashboardRejectsCallerTryingToReadAnotherConsultant() {
        when(reads.performanceFirst(actor, "other")).thenThrow(
                new IllegalArgumentException("CONSULTANT_ID_MISMATCH"));

        ApiResponse<?> response = controller.performanceFirst(principal, Map.of("consultantId", "other"));

        assertEquals(false, response.isSuccess());
        assertEquals(500, response.getCode());
        assertEquals("CONSULTANT_ID_MISMATCH", response.getMessage());
    }

    @Test
    void legacyIntakeAndWalletCorrectionRequireUserRole() throws Exception {
        Method intake = ConsultantLegacyController.class.getMethod(
                "consultationIntake", AuthPrincipal.class, Map.class);
        Method wallet = ConsultantLegacyController.class.getMethod(
                "verifyWallet", AuthPrincipal.class, String.class);

        assertEquals(Role.USER, intake.getAnnotation(RequireRole.class).value()[0]);
        assertEquals(Role.USER, wallet.getAnnotation(RequireRole.class).value()[0]);
    }
}
