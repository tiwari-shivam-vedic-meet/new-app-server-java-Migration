package com.vedicmeet.appserver.consultant;

import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

/** Consultant HTTP live-event lifecycle from production consultant/session.js. */
@Service
public class ConsultantLiveEventCommandService {

    private final MongoTemplate mongo;

    public ConsultantLiveEventCommandService(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    public Document create(Document actor, Map<String, Object> input) {
        String consultantId = requiredActor(actor);
        Date now = new Date();
        Document engagement = new Document("chats", 0).append("reactions", 0)
                .append("privateCallRequests", 0).append("handRaises", 0);
        Document stats = new Document("peakViewers", 0).append("totalViewers", 0)
                .append("totalDuration", 0).append("totalChats", 0)
                .append("totalPrivateCalls", 0).append("totalEarnings", 0)
                .append("averageViewDuration", 0).append("userEngagement", engagement);
        Document permissions = new Document("chat", permission())
                .append("voice", permission()).append("privateCall", permission())
                .append("raiseHand", permission());
        Document event = new Document("label", input.get("event_name"))
                .append("consultant_id", actor.get("_id"))
                .append("greeting_message", input.get("greeting_message"))
                .append("expected_duration", input.get("duration"))
                // LiveEventsManager's defaults are overridden by route input status:'live'.
                .append("status", "live").append("stats", stats)
                .append("blockedUsers", new ArrayList<>())
                .append("logs", List.of(new Document("timestamp", now).append("action", "event_created")))
                .append("event_ended_reason", "").append("permissions", permissions)
                .append("createdAt", now).append("updatedAt", now);
        mongo.getCollection(Collections.CONSULTANT_LIVE_EVENTS).insertOne(event);
        return event;
    }

    /** Unlike Node findByIdAndUpdate, the event must belong to the authenticated consultant. */
    public Document close(Document actor, String eventId) {
        String consultantId = requiredActor(actor);
        Date now = new Date();
        Document event = mongo.getCollection(Collections.CONSULTANT_LIVE_EVENTS).findOneAndUpdate(
                new Document("_id", MongoIds.id(eventId))
                        .append("consultant_id", new Document("$in", MongoIds.variants(consultantId))),
                new Document("$set", new Document("status", "ended")
                        .append("event_ended_reason", "consultant_ended").append("updatedAt", now))
                        .append("$push", new Document("logs", new Document("timestamp", now)
                                .append("action", "status_changed_to_ended"))),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        if (event == null) throw new IllegalArgumentException("Event not found");
        return event;
    }

    private Document permission() {
        return new Document("enabled", true).append("onlySubscribers", false)
                .append("minWalletBalance", 0);
    }

    private String requiredActor(Document actor) {
        String value = actor == null ? "" : String.valueOf(actor.get("_id"));
        if (value.isBlank()) throw new IllegalStateException("Consultant not found");
        return value;
    }
}
