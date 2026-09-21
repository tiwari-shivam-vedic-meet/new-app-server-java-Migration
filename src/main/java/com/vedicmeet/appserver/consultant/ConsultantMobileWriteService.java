package com.vedicmeet.appserver.consultant;

import com.mongodb.MongoWriteException;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.UpdateOptions;
import com.vedicmeet.appserver.call.CallIntegrationOutboxService;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Actor-scoped consultant writes that were read/decide/write races in Node. */
@Service
public class ConsultantMobileWriteService {

    private static final Set<String> AVAILABILITY_TYPES = Set.of("CHAT", "CALL", "VIDEO");
    private static final Set<String> REVIEW_ACTIONS = Set.of("PIN", "FLAG", "LIKE");

    private final MongoTemplate mongo;
    private final CallIntegrationOutboxService outbox;
    private final int offerOffMinutes;

    public ConsultantMobileWriteService(MongoTemplate mongo, CallIntegrationOutboxService outbox,
                                        @Value("${vedicmeet.consultant.offer-off-minutes:60}") int offerOffMinutes) {
        this.mongo = mongo;
        this.outbox = outbox;
        this.offerOffMinutes = Math.max(1, offerOffMinutes);
    }

    @Transactional(transactionManager = "mongoTransactionManager")
    public Document setAvailability(Document actor, Map<String, Object> input) {
        ObjectId consultantId = id(actor.get("_id"), "CONSULTANT_ID_REQUIRED");
        String type = text(input.get("type")).toUpperCase(Locale.ROOT);
        if (!AVAILABILITY_TYPES.contains(type)) throw new IllegalArgumentException("INVALID_AVAILABILITY_TYPE");
        List<?> rawDates = input.get("dates") instanceof List<?> list ? list : List.of(input.get("dates"));
        if (rawDates.isEmpty() || rawDates.get(0) == null) throw new IllegalArgumentException("DATES_REQUIRED");
        Object rawTime = input.get("time");
        if (!(rawTime instanceof List<?>)) throw new IllegalArgumentException("TIME_REQUIRED");

        Document last = null;
        for (Object raw : rawDates) {
            Date date = utcDay(raw);
            Document filter = new Document("consultantId", consultantId).append("type", type).append("date", date);
            Document values = new Document("consultantId", consultantId).append("type", type)
                    .append("date", date).append("time", rawTime)
                    .append("Status", input.getOrDefault("Status", true)).append("updatedAt", new Date());
            last = mongo.getCollection(Collections.CONSULTANT_AVAILABLES).findOneAndUpdate(filter,
                    new Document("$set", values).append("$setOnInsert", new Document("createdAt", new Date())),
                    new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER));
        }
        return last;
    }

    @Transactional(transactionManager = "mongoTransactionManager")
    public Document setAvailabilityStatus(Document actor, String rawType, Object rawStatus) {
        ObjectId consultantId = id(actor.get("_id"), "CONSULTANT_ID_REQUIRED");
        String type = text(rawType).toUpperCase(Locale.ROOT);
        if (!AVAILABILITY_TYPES.contains(type)) throw new IllegalArgumentException("INVALID_AVAILABILITY_TYPE");
        if (!(rawStatus instanceof Boolean status)) throw new IllegalArgumentException("STATUS_MUST_BE_BOOLEAN");
        Document inProgress = mongo.getCollection(Collections.CONSULTANT_FORM_REQUESTS).find(
                new Document("consultantId", consultantId).append("isConsultantProvideOfflineSession", true)
                        .append("isConsultantCompleted", "progress")).first();
        if (inProgress != null) throw new IllegalStateException("OFFLINE_SESSION_PROGRESS");

        String field = switch (type) {
            case "CHAT" -> "isChatLive";
            case "CALL" -> "isCallLive";
            default -> "isVideoLive";
        };
        Document latest = mongo.getCollection(Collections.ONLINE_RECORDS)
                .find(new Document("consultantId", consultantId).append("type", type))
                .sort(new Document("_id", -1)).first();
        Date now = new Date();
        if (status && (latest == null || !Boolean.TRUE.equals(latest.get("status")))) {
            mongo.getCollection(Collections.ONLINE_RECORDS).insertOne(new Document("consultantId", consultantId)
                    .append("type", type).append("startTime", now).append("status", true)
                    .append("createdAt", now).append("updatedAt", now));
        } else if (!status && latest != null && latest.get("endTime") == null) {
            mongo.getCollection(Collections.ONLINE_RECORDS).updateOne(new Document("_id", latest.get("_id")),
                    new Document("$set", new Document("endTime", now).append("status", false).append("updatedAt", now)));
        }
        Document updated = mongo.getCollection(Collections.CONSULTANTS).findOneAndUpdate(
                new Document("_id", consultantId), new Document("$set", new Document(field, status)
                        .append("updatedAt", now)),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        if (updated == null) throw new IllegalStateException("CONSULTANT_NOT_FOUND");
        outbox.enqueue("CONSULTANT_AVAILABILITY:" + consultantId + ":" + type + ":" + now.getTime(),
                "CONSULTANT_AVAILABILITY_CHANGED", new Document("consultantId", consultantId.toHexString())
                        .append("key", "CHAT".equals(type) ? "isChatLive" : "CALL".equals(type) ? "isVoiceLive" : "isVideoLive")
                        .append("value", status));
        return updated;
    }

    @Transactional(transactionManager = "mongoTransactionManager")
    public Document replyToReview(Document actor, String reviewRatingId, String reply) {
        ObjectId consultantId = id(actor.get("_id"), "CONSULTANT_ID_REQUIRED");
        ObjectId reviewId = id(reviewRatingId, "REVIEW_RATING_NOT_EXIST");
        if (blank(reply)) throw new IllegalArgumentException("REPLY_REQUIRED");
        Document review = mongo.getCollection(Collections.REVIEW_AND_RATINGS)
                .find(new Document("_id", reviewId).append("consultantId", consultantId)).first();
        if (review == null) throw new IllegalArgumentException("REPLY_PREMISSON_NOT_EXIST");
        if (Boolean.FALSE.equals(review.get("status"))) throw new IllegalStateException("RATING_STATUS_FALSE");
        Date now = new Date();
        Document response = new Document("reviewRatingId", reviewId).append("consultantId", consultantId)
                .append("reply", reply).append("createdAt", now).append("updatedAt", now);
        mongo.getCollection(Collections.REVIEW_REPLIES).insertOne(response);
        return response;
    }

    @Transactional(transactionManager = "mongoTransactionManager")
    public Document markReview(Document actor, String reviewRatingId, String rawType,
                               Object rawStatus, String reason) {
        ObjectId consultantId = id(actor.get("_id"), "CONSULTANT_ID_REQUIRED");
        ObjectId reviewId = id(reviewRatingId, "REVIEW_RATING_NOT_EXIST");
        String type = text(rawType).toUpperCase(Locale.ROOT);
        if (!REVIEW_ACTIONS.contains(type)) throw new IllegalArgumentException("INVALID_REVIEW_ACTION");
        if (!(rawStatus instanceof Boolean status)) throw new IllegalArgumentException("STATUS_MUST_BE_BOOLEAN");

        Document current = mongo.getCollection(Collections.REVIEW_AND_RATINGS)
                .find(new Document("_id", reviewId).append("consultantId", consultantId)).first();
        if (current == null) throw new IllegalArgumentException("REVIEW_RATING_NOT_EXIST");
        String field = "PIN".equals(type) ? "isPin" : "FLAG".equals(type) ? "isFlag" : "isLike";
        Document set = new Document(field, status).append("updatedAt", new Date());
        if ("FLAG".equals(type)) set.append("flagReason", reason);
        Document updated = mongo.getCollection(Collections.REVIEW_AND_RATINGS).findOneAndUpdate(
                new Document("_id", reviewId).append("consultantId", consultantId), new Document("$set", set),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));

        if ("FLAG".equals(type) && status && !Boolean.TRUE.equals(current.get("isFlag"))) {
            Date now = new Date();
            mongo.getCollection(Collections.FLAG_LOGS).updateOne(
                    new Document("consultant_id", consultantId).append("flag_type", "negative_feedback")
                            .append("temporary_data.feedback_id", reviewId.toHexString()),
                    new Document("$setOnInsert", new Document("consultant_id", consultantId)
                            .append("flag_type", "negative_feedback")
                            .append("waitlist_id", text(current.get("consultantRequestFormId")))
                            .append("temporary_data", new Document("feedback_id", reviewId.toHexString()))
                            .append("flag_status", "pending").append("flag_reason", reason)
                            .append("createdAt", now).append("updatedAt", now)),
                    new UpdateOptions().upsert(true));
        }
        return updated;
    }

    /**
     * Activates/deactivates an offer under a consultant-scoped claim. The claim fixes Node's
     * concurrent "two offers both active" race without changing the mobile response.
     */
    @Transactional(transactionManager = "mongoTransactionManager")
    public void changeOffer(Document actor, String offerId, boolean active) {
        ObjectId consultantId = id(actor.get("_id"), "CONSULTANT_ID_REQUIRED");
        ObjectId couponId = id(offerId, "Invalid offer");
        Document coupon = mongo.getCollection(Collections.COUPONS)
                .find(new Document("_id", couponId).append("couponType", "OFFERS")).first();
        if (coupon == null) throw new IllegalArgumentException("Invalid offer");
        if (!Boolean.TRUE.equals(coupon.get("status"))) throw new IllegalStateException("Inactive offer");

        String claimId = "offer:" + consultantId.toHexString();
        Document claim = mongo.getCollection(Collections.JAVA_BOOKING_CLAIMS)
                .find(new Document("_id", claimId)).first();
        Date now = new Date();
        if (active) {
            if (claim != null && Boolean.TRUE.equals(claim.get("active"))
                    && !couponId.equals(claim.get("offerId"))) {
                throw new IllegalStateException("You can activate single offer at a time");
            }
            try {
                Document claimFilter = new Document("_id", claimId).append("$or", List.of(
                        new Document("active", new Document("$ne", true)), new Document("offerId", couponId)));
                var claimResult = mongo.getCollection(Collections.JAVA_BOOKING_CLAIMS).updateOne(claimFilter,
                        new Document("$setOnInsert", new Document("_id", claimId).append("createdAt", now))
                                .append("$set", new Document("offerId", couponId).append("active", true)
                                        .append("updatedAt", now)), new UpdateOptions().upsert(true));
                if (claimResult.getMatchedCount() == 0 && claimResult.getUpsertedId() == null) {
                    throw new IllegalStateException("You can activate single offer at a time");
                }
            } catch (MongoWriteException collision) {
                throw new IllegalStateException("You can activate single offer at a time");
            }
            mongo.getCollection(Collections.COUPONS).updateOne(new Document("_id", couponId),
                    new Document("$addToSet", new Document("consultantId", consultantId)));
            mongo.getCollection(Collections.COUPON_ACTIVITIES).insertOne(activity(coupon, consultantId, couponId, true, now));
            outbox.enqueue("CONSULTANT_OFFER:" + consultantId + ":" + couponId + ":" + now.getTime(),
                    "CONSULTANT_OFFER_ACTIVATED", new Document("consultantId", consultantId.toHexString())
                            .append("couponId", couponId.toHexString()));
        } else {
            Document lastOn = mongo.getCollection(Collections.COUPON_ACTIVITIES)
                    .find(new Document("couponId", couponId).append("consultantId", consultantId).append("action", true))
                    .sort(new Document("createdAt", -1)).first();
            if (lastOn != null) {
                Instant started = instant(lastOn.get("createdAt"));
                long elapsed = started == null ? offerOffMinutes : Duration.between(started, Instant.now()).toMinutes();
                if (elapsed < offerOffMinutes) {
                    throw new IllegalStateException("Offer can be deactivated after " + (offerOffMinutes - elapsed) + " minutes");
                }
                mongo.getCollection(Collections.COUPON_ACTIVITIES).updateOne(new Document("_id", lastOn.get("_id")),
                        new Document("$set", new Document("disabledAt", now).append("action", false)
                                .append("durationMinutes", elapsed).append("updatedAt", now)));
            }
            mongo.getCollection(Collections.COUPONS).updateOne(new Document("_id", couponId),
                    new Document("$pull", new Document("consultantId", consultantId)));
            mongo.getCollection(Collections.JAVA_BOOKING_CLAIMS).updateOne(
                    new Document("_id", claimId).append("offerId", couponId),
                    new Document("$set", new Document("active", false).append("updatedAt", now)));
        }
    }

    private Document activity(Document coupon, ObjectId consultantId, ObjectId couponId,
                              boolean active, Date now) {
        return new Document("couponId", couponId).append("consultantId", consultantId)
                .append("action", active).append("source", "consultant_app")
                .append("meta", new Document("userType", coupon.getOrDefault("userType", ""))
                        .append("couponDiscount", coupon.get("couponDiscount")).append("title", coupon.get("title")))
                .append("createdAt", now).append("updatedAt", now);
    }

    private Date utcDay(Object value) {
        try {
            LocalDate day;
            String text = String.valueOf(value);
            try { day = Instant.parse(text).atZone(ZoneOffset.UTC).toLocalDate(); }
            catch (Exception ignored) { day = LocalDate.parse(text.substring(0, Math.min(10, text.length()))); }
            return Date.from(day.atStartOfDay().toInstant(ZoneOffset.UTC));
        } catch (Exception invalid) { throw new IllegalArgumentException("INVALID_DATE"); }
    }
    private ObjectId id(Object value, String error) {
        if (value instanceof ObjectId id) return id;
        if (value != null && ObjectId.isValid(String.valueOf(value))) return new ObjectId(String.valueOf(value));
        throw new IllegalArgumentException(error);
    }
    private Instant instant(Object value) {
        if (value instanceof Date date) return date.toInstant();
        if (value instanceof Instant instant) return instant;
        return null;
    }
    private String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private boolean blank(String value) { return value == null || value.isBlank(); }
}
