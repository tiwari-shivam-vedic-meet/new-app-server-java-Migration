package com.vedicmeet.appserver.call;

import com.vedicmeet.appserver.payment.AesWalletService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * ⚠ SHADOW-ONLY / MONEY — HUMAN REVIEW + HARNESS REQUIRED. Faithful port of the call-end wallet
 * settlement (Node utils/classes/db-query.js L520-573, the `consult` branch): bill the user for
 * minutes beyond the free trial, split the charge into platform commission + consultant pay, then
 * debit the user and credit the consultant on the AES-encrypted `coins` wallet via the already-built
 * + tested {@link AesWalletService}. NOT wired to any route.
 *
 * Node parity:
 *   - billable = max(0, totalMinutes - freeTrialMinutes); deduct = chargePerMin * billable, .toFixed(2).
 *   - platform = deduct * commission / 100 ; consultantPay = deduct - platform  (calculateAmounts).
 *   - userWalletUpdate debits (floors coins at 0); consultantWalletUpdate credits.
 */
@Service
public class CallSettlementService {

    private final AesWalletService aesWallet;

    public CallSettlementService(AesWalletService aesWallet) {
        this.aesWallet = aesWallet;
    }

    public static final class Settlement {
        public final double deduct;
        public final double platform;
        public final double consultantPay;

        Settlement(double deduct, double platform, double consultantPay) {
            this.deduct = deduct;
            this.platform = platform;
            this.consultantPay = consultantPay;
        }
    }

    /** The pure amount split (no wallet writes). */
    public Settlement compute(double totalMinutes, double chargePerMin, double commission, double freeTrialMinutes) {
        double deduct = 0;
        if (totalMinutes > freeTrialMinutes) {
            double billableMinutes = totalMinutes - freeTrialMinutes;
            deduct = round2(chargePerMin * billableMinutes);
        }
        double platform = deduct * commission / 100;
        double consultantPay = deduct - platform;
        return new Settlement(deduct, platform, consultantPay);
    }

    /** Compute + apply: debit user coins, credit consultant coins (AES). Returns the amounts. */
    public Settlement settle(Object userId, Object consultantId, double totalMinutes,
                             double chargePerMin, double commission, double freeTrialMinutes) {
        Settlement s = compute(totalMinutes, chargePerMin, commission, freeTrialMinutes);
        aesWallet.debitUserCoins(userId, s.deduct);
        aesWallet.creditConsultantCoins(consultantId, s.consultantPay);
        return s;
    }

    /** Node uses Number(deduct.toFixed(2)); reproduce with half-up rounding to 2 dp. */
    private double round2(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
