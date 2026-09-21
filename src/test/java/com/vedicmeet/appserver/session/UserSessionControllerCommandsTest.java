package com.vedicmeet.appserver.session;

import com.vedicmeet.appserver.security.AuthPrincipal;
import com.vedicmeet.appserver.security.AuthUserService;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserSessionControllerCommandsTest {

    private final AuthPrincipal principal = new AuthPrincipal(Map.of(
            "role", "user", "phone", "9000000001", "phonePrefix", "91"));
    private AuthUserService users;
    private UserSessionCouponService coupons;
    private UserFixedSessionBookingService fixed;
    private UserSessionCommandService commands;

    @BeforeEach
    void setup() {
        users = mock(AuthUserService.class);
        coupons = mock(UserSessionCouponService.class);
        fixed = mock(UserFixedSessionBookingService.class);
        commands = mock(UserSessionCommandService.class);
        when(users.load(principal)).thenReturn(new Document("_id", new ObjectId()));
    }

    @Test
    void couponValidationRemainsAvailableWithEveryWriteGateOff() {
        when(coupons.validate(any(Document.class), anyMap())).thenReturn(
                new UserSessionCouponService.CouponResult(new Document("code", "SAVE"),
                        "Coupon is valid.", "v2"));
        Map<String, Object> response = controller(false, false, false, false, false)
                .couponStatus(principal, Map.of("code", "SAVE"));
        assertEquals(true, response.get("success"));
        assertEquals("v2", response.get("version"));
    }

    @Test
    void fixedBookingNeedsItsIndependentMoneyGate() {
        Map<String, Object> response = controller(true, false, true, true, true)
                .bookFixedSession(principal, Map.of());
        assertEquals(false, response.get("success"));
        assertEquals("Java fixed-session booking is disabled", response.get("message"));
        verifyNoInteractions(fixed);
    }

    @Test
    void rechargeExtensionNeedsCommandsCallTimerAndOutboxTogether() {
        Map<String, Object> response = controller(true, true, false, true, true)
                .rechargeStatus(principal, Map.of("type", "extend"));
        assertEquals(false, response.get("success"));
        assertEquals("Java call extension is disabled", response.get("message"));
        verifyNoInteractions(commands);
    }

    private UserSessionController controller(boolean commandsGate, boolean fixedGate,
                                             boolean call, boolean timer, boolean outbox) {
        return new UserSessionController(mock(UserSessionReadService.class), users,
                mock(SessionBookingService.class), coupons, fixed, commands,
                false, commandsGate, fixedGate, call, false, timer, outbox);
    }
}
