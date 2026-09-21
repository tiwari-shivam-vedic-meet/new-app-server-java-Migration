package com.vedicmeet.appserver.discovery;

import com.vedicmeet.appserver.config.AppConstants.Collections;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;

/**
 * Faithful port of Node's {@code checkUserHaveMembership} middleware
 * (utils/middle-wares.js L219). The Node middleware augments {@code req.user}
 * with membership fields before the consultant-discovery controllers run; here
 * the discovery controllers call {@link #applyMembership(Document)} on the user
 * document returned by {@code AuthUserService.load(...)} to reproduce that exactly.
 *
 * Node logic (preserved 1:1):
 *   today = Date.UTC(year, month, date)            // midnight UTC of today
 *   query = { userId: user._id, transactionFor: 'membership',
 *             startDate: { $lte: today }, endDate: { $gte: today } }
 *   memberShipExist = walletTransactionModel.findOne(query).sort({ createdAt: -1 })
 *   if found -> isMembership=true (+ planType, walletTransactionId, discountPercentage, consultantId)
 *   else     -> isMembership=false
 *
 * Quirk preserved: Node sets {@code walletTransactionId} only when {@code planType}
 * is truthy (it reuses the planType check), so a membership row without a planType
 * yields {@code isMembership=true} but a {@code null} walletTransactionId. Ported as-is.
 */
@Service
public class MembershipService {

    private final MongoTemplate mongo;

    public MembershipService(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    /**
     * Mutates and returns the given user document with the membership fields set,
     * mirroring how the Node middleware rewrites {@code req.user}. Never throws for
     * a missing membership — it simply sets {@code isMembership=false}.
     */
    public Document applyMembership(Document user) {
        if (user == null) {
            return null;
        }

        Date today = midnightUtcToday();

        Document query = new Document("userId", user.get("_id"))
                .append("transactionFor", "membership")
                .append("startDate", new Document("$lte", today))
                .append("endDate", new Document("$gte", today));

        Document membership = mongo.getCollection(Collections.WALLET_TRANSACTIONS)
                .find(query)
                .sort(new Document("createdAt", -1))
                .first();

        if (membership != null) {
            Object planType = membership.get("planType");
            boolean hasPlanType = truthy(planType);

            user.put("isMembership", true);
            user.put("planType", hasPlanType ? planType : "");
            // Node: walletTransactionId = planType ? memberShipExist._id : null
            user.put("walletTransactionId", hasPlanType ? membership.get("_id") : null);
            user.put("discountPercentage",
                    truthy(membership.get("discountPercentage")) ? membership.get("discountPercentage") : null);
            user.put("consultantId",
                    truthy(membership.get("consultantId")) ? membership.get("consultantId") : null);
        } else {
            user.put("isMembership", false);
        }
        return user;
    }

    /** new Date(Date.UTC(y, m, d)) — midnight UTC of the current day. */
    private Date midnightUtcToday() {
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTime();
    }

    /** JS truthiness for the fields Node guards on (non-null, non-empty-string, non-zero). */
    private boolean truthy(Object v) {
        if (v == null) return false;
        if (v instanceof String) return !((String) v).isEmpty();
        if (v instanceof Number) return ((Number) v).doubleValue() != 0;
        if (v instanceof Boolean) return (Boolean) v;
        return true;
    }
}
