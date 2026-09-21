package com.vedicmeet.appserver.payment;

import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.vedicmeet.appserver.call.TimerWriteService;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.util.Date;

/**
 * Port of Node {@code extendOngoingSessionTime} in utils/functions/business-logics.js.
 * A wallet top-up made during a progressing consultation buys more call time: the waitlist session
 * and its Redis duration clock must move together. The Mongo update uses {@code $inc} rather than
 * Node's read-then-set so two simultaneous recharges cannot overwrite one another.
 */
@Service
public class OngoingSessionExtensionService {

    private final MongoTemplate mongo;
    private final TimerWriteService timers;

    public OngoingSessionExtensionService(MongoTemplate mongo, TimerWriteService timers) {
        this.mongo = mongo;
        this.timers = timers;
    }

    /** Returns the seconds added, or zero when the user has no active billable consultation. */
    public long extend(Object userId, double coins) {
        if (userId == null || coins <= 0) return 0;

        Document waitlist = mongo.getCollection(Collections.WAITLISTS)
                .find(new Document("user_id", String.valueOf(userId)).append("status", "progress"))
                .projection(new Document("consultant_id", 1).append("requested_time", 1)
                        .append("session_info.holdAmount", 1))
                .first();
        if (waitlist == null || waitlist.get("_id") == null) return 0;

        Object consultantId = mongoId(waitlist.get("consultant_id"));
        if (consultantId == null) return 0;
        Document consultant = mongo.getCollection(Collections.CONSULTANTS)
                .find(new Document("_id", consultantId))
                .projection(new Document("price.default", 1))
                .first();
        double price = nestedNumber(consultant, "price", "default");
        long addedSeconds = calculateAddedSeconds(coins, price);
        if (addedSeconds <= 0) return 0;

        Document log = new Document("callStatus", "added_to_wallet")
                .append("actionBy", "user")
                .append("actionTimeAdded", addedSeconds)
                .append("actionValue", coins)
                .append("timestamp", new Date());
        Document updated = mongo.getCollection(Collections.WAITLISTS).findOneAndUpdate(
                new Document("_id", waitlist.get("_id")).append("status", "progress"),
                new Document("$inc", new Document("requested_time", addedSeconds)
                        .append("session_info.holdAmount", coins))
                        .append("$push", new Document("logs", log)),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        if (updated == null) return 0;

        timers.extendTimer(String.valueOf(waitlist.get("_id")) + ":duration", addedSeconds);
        return addedSeconds;
    }

    static long calculateAddedSeconds(double coins, double perMinutePrice) {
        if (coins <= 0 || perMinutePrice <= 0) return 0;
        return Math.max(1L, Math.round((coins * 60.0d) / perMinutePrice));
    }

    private Object mongoId(Object value) {
        if (value instanceof ObjectId) return value;
        String text = value == null ? "" : String.valueOf(value);
        return ObjectId.isValid(text) ? new ObjectId(text) : null;
    }

    private double nestedNumber(Document root, String parent, String field) {
        if (root == null || !(root.get(parent) instanceof Document nested)) return 0;
        Object value = nested.get(field);
        if (value instanceof Number number) return number.doubleValue();
        try { return value == null ? 0 : Double.parseDouble(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return 0; }
    }
}
