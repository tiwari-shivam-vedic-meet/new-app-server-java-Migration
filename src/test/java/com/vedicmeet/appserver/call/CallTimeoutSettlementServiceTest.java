package com.vedicmeet.appserver.call;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the faithful CURRENT-production settlement math of {@link CallTimeoutSettlementService}
 * (node-production-bf308ee computeCallSettlement L129-261): NORMAL / PER_MINUTE / FIXED / PERCENT,
 * platform-rate selection, consultant-share override, extra-duration. Money path, shadow-only.
 */
class CallTimeoutSettlementServiceTest {

    private final CallTimeoutSettlementService svc = new CallTimeoutSettlementService();

    private CallTimeoutSettlementService.Input base(double seconds) {
        CallTimeoutSettlementService.Input in = new CallTimeoutSettlementService.Input();
        in.callDurationInSeconds = seconds;
        in.sessionPrice = 10;
        in.basePrice = 10;
        in.platformShare = 20;
        return in;
    }

    @Test
    void normal_perMinuteBase_splitByHundredMinusPlatformShare() {
        var r = svc.compute(base(120)); // 2 min * 10 = 20
        assertEquals("NORMAL", r.typeOfSession);
        assertEquals(20, r.baseAmount, 1e-6);
        assertEquals(16, r.platformAmount, 1e-6); // rate 80
        assertEquals(4, r.consultantWallet, 1e-6);
    }

    @Test
    void boosted_forces70() {
        var in = base(120);
        in.isBoosted = true;
        var r = svc.compute(in);
        assertEquals(14, r.platformAmount, 1e-6);
        assertEquals(6, r.consultantWallet, 1e-6);
    }

    @Test
    void offerOnConsultant_forces50() {
        var in = base(120);
        in.offerOnConsultant = true;
        var r = svc.compute(in);
        assertEquals(10, r.platformAmount, 1e-6);
        assertEquals(10, r.consultantWallet, 1e-6);
    }

    @Test
    void consultantShareOverride_setsRateTo100MinusShare() {
        var in = base(120);
        in.consultantShareOverride = 40.0; // rate = 60
        var r = svc.compute(in);
        assertEquals(60, r.platformFeeRate, 1e-6);
        assertEquals(12, r.platformAmount, 1e-6);
        assertEquals(8, r.consultantWallet, 1e-6);
    }

    @Test
    void perMinute_withinOffer_usesOfferPricePerMinute() {
        var in = base(300);           // 5 min, <= offerDuration
        in.hasVariant = true;
        in.couponType = "PER_MINUTE";
        in.offerDuration = 600.0;
        in.offerPrice = 8;
        var r = svc.compute(in);
        assertEquals(40, r.baseAmount, 1e-6); // 5 * 8
    }

    @Test
    void fixed_withinOffer_isFlatOfferPrice() {
        var in = base(120);
        in.hasVariant = true;
        in.couponType = "FIXED";
        in.offerDuration = 600.0;
        in.offerPrice = 50;
        assertEquals(50, svc.compute(in).baseAmount, 1e-6);
    }

    @Test
    void percent_withinOffer_discountsBasePrice() {
        var in = base(120);
        in.hasVariant = true;
        in.couponType = "PERCENT";
        in.offerDuration = 600.0;
        in.offerPrice = 20;   // 20% off
        in.basePrice = 100;
        assertEquals(80, svc.compute(in).baseAmount, 1e-6); // 100 * 0.8
    }

    @Test
    void perMinute_beyondOffer_addsExtraDurationAtBasePrice() {
        var in = base(720);           // 12 min, > offerDuration 10 min
        in.hasVariant = true;
        in.couponType = "PER_MINUTE";
        in.offerDuration = 600.0;
        in.offerPrice = 8;
        in.basePrice = 12;
        var r = svc.compute(in);
        // (600/60*8) + (120/60*12) = 80 + 24 = 104
        assertEquals(104, r.baseAmount, 1e-6);
        assertEquals(120, r.extraDuration, 1e-6);
        assertEquals(24, r.extraDurationAmount, 1e-6);
    }

    @Test
    void session_usesPerMinuteBase_butConsultantWalletStaysZero() {
        var in = base(120);
        in.isSession = true;
        var r = svc.compute(in);
        assertEquals("SESSION", r.typeOfSession);
        assertEquals(20, r.baseAmount, 1e-6);
        assertEquals(16, r.platformAmount, 1e-6);
        assertEquals(0, r.consultantWallet, 1e-6, "SESSION consultantWallet left 0 (block commented out in prod)");
    }
}