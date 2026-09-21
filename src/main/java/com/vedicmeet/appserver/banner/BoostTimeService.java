package com.vedicmeet.appserver.banner;

import com.vedicmeet.appserver.config.AppConstants.Collections;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;

/**
 * Faithful port of Node ConsultantService.getCurrentTimeStringAndCheckBoostTime
 * (utils/classes/consultant-service.js). Only the two cases the banner uses are ported.
 *
 * WARNING: `updateTimeDuration` WRITES — it updates the consultant's boost document
 * (consultant_boosts) via findOneAndUpdate({new:true}) and returns the updated doc.
 * The write payload and toggle logic are reproduced exactly.
 */
@Service
public class BoostTimeService {

    private final MongoTemplate mongo;

    public BoostTimeService(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    /** case "currentTime": local HH:mm:ss (server local timezone, like Node's Date.getHours). */
    public String currentTime() {
        Calendar now = Calendar.getInstance();
        return String.format("%02d:%02d:%02d",
                now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), now.get(Calendar.SECOND));
    }

    /**
     * case "updateTimeDuration": recompute boost duration and toggle the boost flag for
     * boostType, persist, and return the updated boost document.
     *
     * consAbleToBoost is hard-coded true in Node (the 30-min cap is commented out), so the
     * else-branch toggle logic always runs.
     */
    public Document updateTimeDuration(Document consBoostData, String boostType) {
        // startTime "HH:mm:ss" -> today at that local time
        String startTime = consBoostData.getString("startTime");
        String[] parts = startTime.split(":");
        Calendar startDate = Calendar.getInstance();
        startDate.set(Calendar.HOUR_OF_DAY, Integer.parseInt(parts[0]));
        startDate.set(Calendar.MINUTE, Integer.parseInt(parts[1]));
        startDate.set(Calendar.SECOND, Integer.parseInt(parts[2]));
        startDate.set(Calendar.MILLISECOND, 0);

        long now = System.currentTimeMillis();
        long differenceInMinutes = (long) Math.floor((now - startDate.getTimeInMillis()) / (1000.0 * 60));

        boolean consAbleToBoost = true;

        // lastBoostDate = UTC midnight of today
        Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        utc.set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH), 0, 0, 0);
        utc.set(Calendar.MILLISECOND, 0);
        Date lastBoostDate = utc.getTime();

        Document payload = new Document("lastBoostDate", lastBoostDate)
                .append("totalBoostDuration", differenceInMinutes);

        if (!consAbleToBoost) {
            payload.append("callActive", false).append("chatActive", false).append("videoActive", false);
        } else {
            payload.append("callActive", toggle("CALL", boostType, consBoostData.getBoolean("callActive", false)));
            payload.append("chatActive", toggle("CHAT", boostType, consBoostData.getBoolean("chatActive", false)));
            payload.append("videoActive", toggle("VIDEO", boostType, consBoostData.getBoolean("videoActive", false)));
        }

        return mongo.getCollection(Collections.CONSULTANT_BOOSTS).findOneAndUpdate(
                new Document("_id", consBoostData.get("_id")),
                new Document("$set", payload),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
    }

    /** boostType match -> flip current; otherwise keep current. Mirrors the Node ternary. */
    private boolean toggle(String forType, String boostType, boolean current) {
        if (forType.equals(boostType)) {
            return !current;
        }
        return current;
    }
}
