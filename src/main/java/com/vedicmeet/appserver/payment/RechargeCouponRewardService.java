package com.vedicmeet.appserver.payment;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Date;
import java.util.Locale;

/** Faithful port of Node _handleRechargeCouponRewards + CouponV2Service recharge validation. */
@Service
public class RechargeCouponRewardService {
    private static final Logger log = LoggerFactory.getLogger(RechargeCouponRewardService.class);
    private final RechargeCouponRewardStore store;

    public RechargeCouponRewardService(RechargeCouponRewardStore store) {
        this.store = store;
    }

    /**
     * Invalid/inapplicable coupons remain best-effort like Node. Once a valid reward starts writing,
     * failures propagate so the enclosing Mongo transaction rolls back all money/audit effects.
     */
    public boolean process(Document transaction) {
        if (transaction == null) return false;
        Object userId = transaction.get("userId");
        Object transactionId = transaction.get("_id");
        if (userId == null || transactionId == null || store.rewardAlreadyRecorded(transactionId)) return false;

        String code = text(transaction.get("couponCode"));
        if (blank(code)) {
            Document saved = store.findLatestSavedPayment(userId);
            code = saved == null ? null : text(saved.get("couponCode"));
        }
        if (blank(code)) return false;
        code = code.trim().toUpperCase(Locale.ROOT);

        Document coupon;
        Document influencer;
        try {
            coupon = store.findActiveCoupon(code);
            if (!validCoupon(coupon)) return false;
            influencer = store.findActiveInfluencer(coupon.get("influencerId"));
            if (influencer == null) return false;
        } catch (RuntimeException validationFailure) {
            log.warn("Recharge coupon validation failed for transaction {}: {}",
                    transactionId, validationFailure.getMessage());
            return false;
        }

        double base = number(transaction.get("baseAmount"), 0);
        double gst = number(transaction.get("gstAmount"), 0);
        double total = number(transaction.get("paidAmount"), 0);
        double discountPercent = number(coupon.get("discountPercent"), 0);
        double sharePercent = number(influencer.get("sharePercent"), 0);
        double extraCoins = money(base * discountPercent / 100.0);
        double influencerEarning = money(base * sharePercent / 100.0);
        double finalAmount = money(base + gst);

        store.record(new RechargeCouponRewardStore.Reward(
                userId, transactionId, coupon.get("_id"), code, influencer.get("_id"),
                base, gst, total, discountPercent, 0, extraCoins, finalAmount,
                sharePercent, influencerEarning));
        return true;
    }

    private boolean validCoupon(Document coupon) {
        if (coupon == null) return false;
        Date now = new Date();
        Date from = coupon.getDate("validFrom"), to = coupon.getDate("validTo");
        if (from != null && now.before(from)) return false;
        if (to != null && now.after(to)) return false;
        if (!"INFLUENCER".equals(coupon.getString("type"))) return false;
        if (!"RECHARGE".equals(coupon.getString("appliesOn"))) return false;
        Object limitValue = coupon.get("usageLimit");
        if (limitValue != null && number(coupon.get("usageCount"), 0) >= number(limitValue, 0)) return false;
        return coupon.get("influencerId") != null;
    }

    private double money(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
    private double number(Object value, double fallback) {
        try { return value == null ? fallback : Double.parseDouble(String.valueOf(value)); }
        catch (Exception ignored) { return fallback; }
    }
    private String text(Object value) { return value == null ? null : String.valueOf(value); }
    private boolean blank(String value) { return value == null || value.isBlank(); }
}
