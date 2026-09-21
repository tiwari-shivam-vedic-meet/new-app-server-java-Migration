package com.vedicmeet.appserver.consultant;

import com.vedicmeet.appserver.security.AuthPrincipal;
import com.vedicmeet.appserver.security.AuthUserService;
import com.vedicmeet.appserver.web.ApiResponse;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.*;

class ConsultantMobileControllerTest {

    private final AuthPrincipal principal = new AuthPrincipal(Map.of(
            "role", "consultant", "phone", "9000000010", "phonePrefix", "91"));
    private final Document actor = new Document("_id", new ObjectId());
    private AuthUserService users;
    private ConsultantMobileReadService reads;
    private ConsultantWalletReadService wallets;
    private ConsultantMobileWriteService writes;
    private ConsultantMobileController controller;

    @BeforeEach
    void setUp() {
        users = mock(AuthUserService.class); reads = mock(ConsultantMobileReadService.class);
        wallets = mock(ConsultantWalletReadService.class); writes = mock(ConsultantMobileWriteService.class);
        when(users.load(principal)).thenReturn(actor);
        controller = new ConsultantMobileController(users, reads, wallets, writes);
    }

    @Test
    void walletAlwaysUsesAuthenticatedConsultant() {
        Map<String, Object> result = Map.of("availableBalance", 10);
        when(wallets.wallet(actor, "today", "cons")).thenReturn(result);

        ApiResponse<?> response = controller.wallet(principal, "today", "cons");

        assertEquals(200, response.getCode());
        assertSame(result, response.getResult());
        verify(wallets).wallet(actor, "today", "cons");
    }

    @Test
    void logicalFailureKeepsHttpBodyStyleCode500() {
        when(reads.kundaliFromRequestForm("bad")).thenThrow(new IllegalArgumentException("KUNDLI_NOT_EXIST"));

        ApiResponse<?> response = controller.kundali("bad");

        assertFalse(response.isSuccess());
        assertEquals(500, response.getCode());
        assertEquals("KUNDLI_NOT_EXIST", response.getMessage());
    }

    @Test
    void reviewMutationIsActorScoped() {
        Document updated = new Document("isPin", true);
        when(writes.markReview(actor, "R", "PIN", true, "")).thenReturn(updated);

        ApiResponse<?> response = controller.flagPin(principal,
                Map.of("reviewRatingId", "R", "type", "PIN", "status", true));

        assertSame(updated, response.getResult());
        verify(writes).markReview(actor, "R", "PIN", true, "");
    }

    @Test
    void offerUsesNodeDefaultActiveTrue() {
        ApiResponse<?> response = controller.offer(principal, "O", Map.of());

        assertEquals("Offer actived", response.getMessage());
        verify(writes).changeOffer(actor, "O", true);
    }
}
