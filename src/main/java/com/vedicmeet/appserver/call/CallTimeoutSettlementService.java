package com.vedicmeet.appserver.call;

import org.springframework.stereotype.Service;

/**
 * SHADOW-ONLY / MONEY. FAITHFUL port of the CURRENT production settlement
 * CallManager.computeCallSettlement (node-production-bf308ee utils/classes/call.js L129-261),
 * migrated as-is. This REPLACES the earlier port of the older handleCallTimeout inline model
 * (fixed_time/first_purchase/5-coin), which no longer matches production.
 *
 * Model: typeOfSession is NORMAL, SESSION (used_for==='session'), or the coupon.type when the coupon
 * carries a variantId (PER_MINUTE / FIXED / PERCENT), whose offer is loaded from offerVariant. Platform
 * fee rate: boosted 70, consultant-offer 50, else 100 - platformShare, with a v2 CONSULTANT-coupon
 * consultantShare override. Base amount by type, extra-duration beyond the offer priced at basePrice.
 * consultantWallet = base - platform, except SESSION (left 0 here, as the SESSION block is commented out
 * in production). All amounts run through roundMoney (2dp; NaN to 0).
 */
@Service
public final class CallTimeoutSettlementService {

    /** Flattened inputs from waitlist.session_info + consultant + the (optional) offer variant. */
    public static final class Input {
        public double callDurationInSeconds;
        public double sessionPrice;          // waitlist.session_info.price
        public double basePrice;             // Number(session.basePrice) || 0
        public boolean isBoosted;            // session_info.isBoosted
        public boolean offerOnConsultant;    // waitlist.offerOnConsultant
        public double platformShare;         // consultant.price.platformShare
        public boolean isSession;            // used_for === 'session'
        public boolean hasVariant;           // coupon.variantId present -> typeOfSession = coupon.type
        public String couponType;            // 'PER_MINUTE' | 'FIXED' | 'PERCENT' (when hasVariant)
        public Double offerDuration;         // offer.duration (seconds); null => no offer loaded
        public double offerPrice;            // offer.price
        public Double consultantShareOverride; // v2 CONSULTANT-coupon consultantShare (when it applies)
    }

    public static final class CallTimeoutSettlement {
        public double baseAmount;
        public double platformAmount;
        public double consultantWallet;
        public double userWallet;
        public String typeOfSession;
        public double platformFeeRate;
        public double extraDuration;
        public double extraDurationAmount;
    }

    public CallTimeoutSettlement compute(Input in) {
        CallTimeoutSettlement r = new CallTimeoutSettlement();

        String typeOfSession = "NORMAL";
        if (in.isSession) typeOfSession = "SESSION";
        if (in.hasVariant) typeOfSession = in.couponType;

        boolean offerPresent = in.hasVariant && in.offerDuration != null;

        double platformFeeRate;
        if (in.isBoosted) {
            platformFeeRate = 70;
        } else if (in.offerOnConsultant) {
            platformFeeRate = 50;
        } else {
            platformFeeRate = 100 - orZero(in.platformShare);
        }
        if (in.consultantShareOverride != null) {
            double v = 100 - orZero(in.consultantShareOverride);
            platformFeeRate = Double.isNaN(v) ? 0 : v; // Node: 100 - Number(consultantShare) || 0
        }

        double baseAmount = 0;
        double userWallet = 0;
        double consultantWallet = 0;
        double extraDuration = 0;
        double extraDurationAmount = 0;

        if ("NORMAL".equals(typeOfSession)) {
            baseAmount = (pos(in.callDurationInSeconds) / 60) * in.sessionPrice;
        } else if (offerPresent && in.offerDuration != 0) {
            double usedMinutes = pos(in.callDurationInSeconds) / 60;
            if (in.callDurationInSeconds <= in.offerDuration) {
                if ("PER_MINUTE".equals(typeOfSession)) {
                    baseAmount = usedMinutes * in.offerPrice;
                } else if ("FIXED".equals(typeOfSession)) {
                    baseAmount = in.offerPrice;
                } else if ("PERCENT".equals(typeOfSession)) {
                    baseAmount = in.basePrice * (1 - in.offerPrice / 100);
                }
            } else {
                extraDuration = in.callDurationInSeconds - in.offerDuration;
                extraDurationAmount = (pos(extraDuration) / 60) * in.basePrice;
                if ("PER_MINUTE".equals(typeOfSession)) {
                    baseAmount = ((pos(in.offerDuration) / 60) * in.offerPrice) + extraDurationAmount;
                } else if ("FIXED".equals(typeOfSession)) {
                    baseAmount = in.offerPrice + extraDurationAmount;
                } else if ("PERCENT".equals(typeOfSession)) {
                    baseAmount = in.basePrice * (1 - in.offerPrice / 100) + extraDurationAmount;
                }
            }
        } else {
            baseAmount = (pos(in.callDurationInSeconds) / 60) * in.sessionPrice;
        }

        double platformAmount = baseAmount * (platformFeeRate / 100);
        if (!"SESSION".equals(typeOfSession)) {
            consultantWallet = baseAmount - platformAmount;
        }

        r.baseAmount = roundMoney(baseAmount);
        r.platformAmount = roundMoney(platformAmount);
        r.consultantWallet = roundMoney(consultantWallet);
        r.userWallet = roundMoney(userWallet);
        r.typeOfSession = typeOfSession;
        r.platformFeeRate = platformFeeRate;
        r.extraDuration = extraDuration;
        r.extraDurationAmount = extraDurationAmount;
        return r;
    }

    /** Node roundMoney: NaN -> 0 else Math.round(v*100)/100. */
    static double roundMoney(double value) {
        if (Double.isNaN(value)) return 0;
        return Math.round(value * 100.0) / 100.0;
    }

    private static double pos(double v) {
        return v > 0 ? v : 0;
    }

    private static double orZero(double v) {
        return Double.isNaN(v) ? 0 : v;
    }
}
