package com.vedicmeet.appserver.pricing;

import com.vedicmeet.appserver.config.AppConstants.Collections;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Faithful port of Node UserService.getConsOfferDiscountForUser (utils/classes/user.js).
 * Computes the per-consultant offer discount shown to a user on the home banner.
 *
 * Node returns { status, discountPrice }. Behaviour preserved exactly:
 *   - no OFFERS coupon for the consultant  -> { false, charge }
 *   - LOYALUSERS coupon + >=2 completes in last 7 days -> discounted
 *   - NEWUSERS coupon + no prior complete  -> discounted
 *   - otherwise                            -> { true, charge } (unchanged price)
 *
 * NOTE: Node's ConsultantRequestFormModel and consultantRequestModel both point to the
 * same model (consultantformrequests), so both queries hit that one collection.
 */
@Service
public class OfferService {

    private final MongoTemplate mongo;

    public OfferService(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    public Map<String, Object> getConsOfferDiscountForUser(ObjectId userId, ObjectId consultantId,
                                                           Number consultantChargeCoinMin) {
        Map<String, Object> out = new LinkedHashMap<>();
        double discountPrice = consultantChargeCoinMin == null ? 0 : consultantChargeCoinMin.doubleValue();

        Document offersCoupon = mongo.getCollection(Collections.COUPONS).find(new Document("$and", Arrays.asList(
                new Document("couponType", "OFFERS"),
                new Document("status", true),
                new Document("consultantId", new Document("$in", Arrays.asList(consultantId)))))).first();

        if (offersCoupon == null) {
            out.put("status", false);
            out.put("discountPrice", numberLike(consultantChargeCoinMin));
            return out;
        }

        Document userOrderExist = mongo.getCollection(Collections.CONSULTANT_FORM_REQUESTS)
                .find(new Document("userId", userId).append("consultantId", consultantId)
                        .append("isConsultantCompleted", "complete")).first();

        String couponUserType = offersCoupon.getString("userType");
        Number couponDiscount = offersCoupon.get("couponDiscount", Number.class);

        if (userOrderExist != null && "LOYALUSERS".equals(couponUserType)) {
            Date sevenDaysAgo = new Date(System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000);
            long loyalCount = mongo.getCollection(Collections.CONSULTANT_FORM_REQUESTS).countDocuments(
                    new Document("userId", userId).append("consultantId", consultantId)
                            .append("isConsultantCompleted", "complete")
                            .append("createdAt", new Document("$gte", sevenDaysAgo)));
            if (loyalCount >= 2) {
                discountPrice = applyDiscount(consultantChargeCoinMin, couponDiscount);
            }
        } else if (userOrderExist == null && "NEWUSERS".equals(couponUserType)) {
            discountPrice = applyDiscount(consultantChargeCoinMin, couponDiscount);
        }
        // else: "logic for future" (no change), matching Node

        out.put("status", true);
        out.put("discountPrice", discountPrice);
        return out;
    }

    private double applyDiscount(Number charge, Number couponDiscount) {
        double c = charge == null ? 0 : charge.doubleValue();
        double d = couponDiscount == null ? 0 : couponDiscount.doubleValue();
        return c - (c * d) / 100;
    }

    /** Preserve the original charge value/type when returning the no-offer case. */
    private Object numberLike(Number charge) {
        return charge;
    }
}
