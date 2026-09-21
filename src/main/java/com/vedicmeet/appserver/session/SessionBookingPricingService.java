package com.vedicmeet.appserver.session;

import org.bson.Document;
import org.springframework.stereotype.Service;

import java.util.List;

/** Pure price/duration port of business-logics.js calculateSessionPriceAndDuration. */
@Service
public class SessionBookingPricingService {

    public record Quote(double basePrice, double price, boolean offerApplied, String bookType,
                        boolean minimumBalanceMissing, double requiredBalance,
                        long maximumTimeSeconds, Document couponSnapshot,
                        SessionOfferStore.Selection selection) {}

    private final SessionOfferStore offers;

    public SessionBookingPricingService(SessionOfferStore offers) {
        this.offers = offers;
    }

    public Quote quote(String requestedMode, Document user, Document consultant,
                       boolean applyOffer, String couponCode) {
        double wallet = number(user.get("wallet"));
        Document consultantPrice = doc(consultant.get("price"));
        double base = number(consultantPrice.get("default"));
        if (base <= 0) throw new IllegalStateException("Consultant price is not configured!");

        SessionOfferStore.Selection selection = applyOffer
                ? offers.select(user, consultant, requestedMode, couponCode) : null;
        if (selection == null) {
            long maximum = (long) Math.floor(wallet / base) * 60L;
            return new Quote(base, base, false, null, wallet / base < 5,
                    base * 5, maximum, null, null);
        }

        if (selection.kind() == SessionOfferStore.Kind.MANUAL_COUPON) {
            Document coupon = selection.coupon();
            double discount = number(coupon.get("discountPercent"));
            double price = base * (1 - discount / 100.0);
            if (price <= 0) throw new IllegalStateException("Coupon produces an invalid consultation price.");
            long maximum = (long) Math.floor(wallet / price) * 60L;
            Document snapshot = new Document("code", coupon.get("code"))
                    .append("discountPercent", discount).append("type", "MANUAL_COUPON")
                    .append("price", price).append("duration", maximum)
                    .append("couponId", coupon.get("_id"));
            return new Quote(base, price, true, "PER_MINUTE", wallet / price < 5,
                    price * 5, maximum, snapshot, selection);
        }

        Document rule = selection.rule();
        Document variant = selection.variant();
        if (!applicable(rule, variant, consultant, requestedMode)) {
            long maximum = (long) Math.floor(wallet / base) * 60L;
            return new Quote(base, base, false, null, wallet / base < 5,
                    base * 5, maximum, null, null);
        }

        String type = text(variant.get("type"));
        double configuredPrice = number(variant.get("price"));
        long duration = Math.max(0L, whole(variant.get("duration")));
        double price = configuredPrice;
        double required;
        boolean missing;
        long maximum = duration > 0 ? duration : 300;
        switch (type) {
            case "PER_MINUTE" -> {
                double minutes = duration > 0 ? Math.round(duration) / 60.0 : 5;
                required = price * minutes;
                missing = price <= 0 || wallet / price < minutes;
            }
            case "FIXED" -> {
                required = price;
                missing = wallet < price;
            }
            case "PERCENT" -> {
                double minutes = duration > 0 ? Math.round(duration) / 60.0 : 5;
                price = base * (1 - configuredPrice / 100.0);
                required = price * minutes;
                missing = price <= 0 || wallet / price < minutes;
            }
            default -> throw new IllegalStateException("Unsupported consultation offer type");
        }
        Document snapshot = new Document("offerId", selection.offerRuleId())
                .append("variantId", selection.variantId()).append("type", type)
                .append("price", price).append("duration", maximum)
                .append("discountPercent", "PERCENT".equals(type) ? configuredPrice : 0);
        return new Quote(base, price, true, type, missing, required, maximum,
                snapshot, selection);
    }

    public void consume(Quote quote) {
        if (quote != null && quote.offerApplied() && quote.selection() != null) {
            offers.consume(quote.selection());
        }
    }

    static boolean applicable(Document rule, Document variant, Document consultant,
                              String requestedMode) {
        String mode = normalizeMode(requestedMode);
        List<String> applicableModes = strings(rule.get("applicableModes"));
        if (!applicableModes.isEmpty() && !applicableModes.contains(mode)) return false;

        Document target = doc(rule.get("consultants"));
        if ("SELECTED".equals(text(target.get("type")))) {
            String consultantId = text(consultant.get("_id"));
            if (values(target.get("ids")).stream().noneMatch(id -> consultantId.equals(text(id)))) return false;
        }

        Document status = doc(consultant.get("sessionsStatus"));
        boolean online = bool(status.get("isChatLive")) || bool(status.get("isVoiceLive"));
        String requiredStatus = text(rule.get("consultantStatus"));
        if ("ONLINE".equals(requiredStatus) && !online) return false;
        return !"OFFLINE".equals(requiredStatus) || !online;
    }

    static String normalizeMode(String mode) {
        if ("audio".equalsIgnoreCase(mode) || "call".equalsIgnoreCase(mode)) return "CALL";
        return "CHAT";
    }

    private static List<?> values(Object value) { return value instanceof List<?> list ? list : List.of(); }
    private static List<String> strings(Object value) {
        return values(value).stream().map(SessionBookingPricingService::text).toList();
    }
    private static Document doc(Object value) { return value instanceof Document d ? d : new Document(); }
    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private static boolean bool(Object value) { return Boolean.TRUE.equals(value); }
    private static long whole(Object value) {
        if (value instanceof Number n) return n.longValue();
        try { return Long.parseLong(text(value)); } catch (NumberFormatException ignored) { return 0; }
    }
    private static double number(Object value) {
        if (value instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(text(value)); } catch (NumberFormatException ignored) { return 0; }
    }
}
