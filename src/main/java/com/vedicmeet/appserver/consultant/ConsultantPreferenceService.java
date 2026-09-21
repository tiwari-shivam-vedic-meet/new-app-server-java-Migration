package com.vedicmeet.appserver.consultant;

import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.vedicmeet.appserver.call.CallIntegrationOutboxService;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Ports the consultant seed/device and availability modules without changing their mobile contract. */
@Service
public class ConsultantPreferenceService {

    public record SeedResult(boolean found, Object data) {}

    private static final Set<String> SESSION_KEYS = Set.of(
            "isChatLive", "isVoiceLive", "isVideoLive", "canGoOffline", "isGoLive");
    private static final Set<String> LIVE_KEYS = Set.of("isChatLive", "isVoiceLive", "isVideoLive");
    private static final Set<String> BOOST_KEYS = Set.of("chatActive", "callActive", "videoActive");
    private static final Set<String> SLOT_FIELDS = Set.of("availability", "availabilitySessions");
    private static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter NODE_SLOT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final MongoTemplate mongo;
    private final CallIntegrationOutboxService outbox;

    public ConsultantPreferenceService(MongoTemplate mongo, CallIntegrationOutboxService outbox) {
        this.mongo = mongo;
        this.outbox = outbox;
    }

    public Document updateDevice(Document actor, Map<String, Object> input) {
        String token = text(input.get("token"));
        if (token.isBlank()) throw new IllegalArgumentException("Token is required");

        Document device = doc(actor.get("device"));
        List<String> fcm = appendBounded(strings(device.get("fcmToken")), token, 3);
        String deviceId = text(input.get("deviceId"));
        List<String> uuids = deviceId.isBlank()
                ? strings(device.get("uuid")) : appendBounded(strings(device.get("uuid")), deviceId, 3);
        Object voip = input.containsKey("voipToken") ? input.get("voipToken") : device.get("voipToken");
        String deviceName = text(input.get("deviceName"));

        Document set = new Document("device.fcmToken", fcm)
                .append("device.uuid", uuids)
                .append("device.voipToken", voip)
                .append("updatedAt", new Date());
        if (!deviceName.isBlank()) set.append("device.lastDevice", deviceName);
        Document updated = mongo.getCollection(Collections.CONSULTANTS).findOneAndUpdate(
                new Document("_id", actor.get("_id")), new Document("$set", set),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        if (updated == null) throw new IllegalStateException("Consultant not found");
        return updated;
    }

    public SeedResult seed(String type) {
        if (type == null || type.isBlank()) throw new IllegalArgumentException("Type is required");
        Document seed = mongo.getCollection(Collections.SEED_MASTERS)
                .find(new Document("for", type)).first();
        return new SeedResult(seed != null, seed == null ? null : seed.get("data"));
    }

    @Transactional(transactionManager = "mongoTransactionManager")
    public Document updateSessionStatus(Document actor, String key, Object rawValue,
                                        String nextAvailableTime) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("Key is required");
        if (!SESSION_KEYS.contains(key)) throw new IllegalArgumentException("Unsupported availability key");
        if (!(rawValue instanceof Boolean value)) throw new IllegalArgumentException("Value must be boolean");

        Document set = new Document("sessionsStatus." + key, value).append("updatedAt", new Date());
        if (LIVE_KEYS.contains(key)) {
            set.append("sessionNextAvailableTime." + key,
                    value ? null : roundToNextFiveMinutes(nextAvailableTime));
        }
        Document updated = mongo.getCollection(Collections.CONSULTANTS).findOneAndUpdate(
                new Document("_id", actor.get("_id")), new Document("$set", set),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        if (updated == null) throw new IllegalStateException("Consultant not found");

        if (LIVE_KEYS.contains(key)) {
            String id = id(actor);
            outbox.enqueue("CONSULTANT_AVAILABILITY:" + id + ":" + key + ":" + System.currentTimeMillis(),
                    "CONSULTANT_AVAILABILITY_CHANGED",
                    new Document("consultantId", id).append("key", key).append("value", value));
        }
        return updated;
    }

    public Document updateBoostStatus(Document actor, String key, Object rawValue) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("Key is required");
        if (!BOOST_KEYS.contains(key)) throw new IllegalArgumentException("Unsupported boost key");
        if (!(rawValue instanceof Boolean value)) throw new IllegalArgumentException("Value must be boolean");
        Document updated = mongo.getCollection(Collections.CONSULTANTS).findOneAndUpdate(
                new Document("_id", actor.get("_id")),
                new Document("$set", new Document("boostSessionsStatus." + key, value)
                        .append("updatedAt", new Date())),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        if (updated == null) throw new IllegalStateException("Consultant not found");
        return updated;
    }

    public Document updateTimeSlots(Document actor, String availabilityFor, Object timeSlots) {
        if (availabilityFor == null || availabilityFor.isBlank() || !(timeSlots instanceof List<?>)) {
            throw new IllegalArgumentException("Availability for and time slots are required");
        }
        if (!SLOT_FIELDS.contains(availabilityFor)) {
            throw new IllegalArgumentException("Unsupported availability field");
        }
        Document updated = mongo.getCollection(Collections.CONSULTANTS).findOneAndUpdate(
                new Document("_id", actor.get("_id")),
                new Document("$set", new Document(availabilityFor, timeSlots).append("updatedAt", new Date())),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        if (updated == null) throw new IllegalStateException("Consultant not found");
        return updated;
    }

    static String roundToNextFiveMinutes(String value) {
        if (value == null || value.isBlank()) return null;
        ZonedDateTime date = parse(value).withZoneSameInstant(KOLKATA);
        int remainder = date.getMinute() % 5;
        if (remainder != 0 || date.getSecond() != 0 || date.getNano() != 0) {
            date = date.plusMinutes(remainder == 0 ? 5 : 5 - remainder);
        }
        return date.withSecond(0).withNano(0).format(NODE_SLOT_FORMAT);
    }

    private static ZonedDateTime parse(String value) {
        try { return Instant.parse(value).atZone(KOLKATA); }
        catch (DateTimeParseException ignored) { }
        try { return OffsetDateTime.parse(value).toZonedDateTime(); }
        catch (DateTimeParseException ignored) { }
        try { return LocalDateTime.parse(value).atZone(KOLKATA); }
        catch (DateTimeParseException ignored) { }
        try { return LocalDateTime.parse(value, NODE_SLOT_FORMAT).atZone(KOLKATA); }
        catch (DateTimeParseException ignored) {
            throw new IllegalArgumentException("Invalid nextAvailableTime");
        }
    }

    private List<String> appendBounded(List<String> existing, String value, int maximum) {
        LinkedHashSet<String> values = new LinkedHashSet<>(existing);
        values.add(value);
        List<String> result = new ArrayList<>(values);
        while (result.size() > maximum) result.remove(0);
        return result;
    }

    private List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) return new ArrayList<>();
        return list.stream().filter(v -> v != null && !text(v).isBlank()).map(String::valueOf).toList();
    }

    private String id(Document actor) {
        Object value = actor.get("_id");
        return value instanceof ObjectId objectId ? objectId.toHexString() : text(value);
    }
    private Document doc(Object value) { return value instanceof Document d ? d : new Document(); }
    private String text(Object value) { return value == null ? "" : String.valueOf(value); }
}
