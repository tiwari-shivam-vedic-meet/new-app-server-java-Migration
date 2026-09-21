package com.vedicmeet.appserver.consultant;

import com.vedicmeet.appserver.config.AppConstants;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import com.vedicmeet.appserver.media.LiveKitProvider;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** LiveKit token and audio-egress operations from production consultant/session.js. */
@Service
public class ConsultantRecordingService {

    public record StopResult(String message, Object data) {}

    private final MongoTemplate mongo;
    private final LiveKitProvider liveKit;
    private final AppConstants constants;

    public ConsultantRecordingService(MongoTemplate mongo, LiveKitProvider liveKit, AppConstants constants) {
        this.mongo = mongo;
        this.liveKit = liveKit;
        this.constants = constants;
    }

    public Map<String, Object> token(Document actor, Map<String, Object> input) {
        requiredActor(actor);
        String waitlistId = text(input.get("waitlistId"));
        if (!waitlistId.isBlank()) requireOwnedWaitlist(actor, waitlistId);
        String roomName = text(input.get("roomName"));
        if (roomName.isBlank()) roomName = "room_" + ThreadLocalRandom.current().nextInt(1, 1_000_001);
        String participant = text(input.get("participantName"));
        if (participant.isBlank()) participant = "user_" + System.currentTimeMillis() + "_"
                + ThreadLocalRandom.current().nextInt(1000);
        boolean canPublish = !Boolean.FALSE.equals(input.get("canPublish"));
        return liveKit.participantToken(roomName, participant, canPublish);
    }

    public Document start(Document actor, Map<String, Object> input) {
        requiredActor(actor);
        String roomName = text(input.get("roomName"));
        if (roomName.isBlank()) throw new IllegalArgumentException("roomName required");
        String waitlistId = waitlistId(input);
        Document waitlist = waitlistId == null ? null : requireOwnedWaitlist(actor, waitlistId);

        Document recording = liveKit.startAudioRecording(roomName);
        String egressId = text(recording.get("egressId"));
        String url = recordingUrl(recording);
        try {
            if (waitlist != null) {
                mongo.getCollection(Collections.WAITLISTS).updateOne(
                        ownedFilter(actor, waitlistId),
                        new Document("$set", new Document("egressId", emptyToNull(egressId))
                                .append("recordingUrl", url)));
            }
        } catch (RuntimeException databaseFailure) {
            // Do not leave an expensive orphan egress running if persistence fails.
            if (!egressId.isBlank()) {
                try { liveKit.stopRecording(egressId); } catch (RuntimeException ignored) { }
            }
            throw databaseFailure;
        }
        return recording;
    }

    public StopResult stop(Document actor, Map<String, Object> input) {
        requiredActor(actor);
        String waitlistId = waitlistId(input);
        Document waitlist = waitlistId == null ? null : requireOwnedWaitlist(actor, waitlistId);
        String requestedEgress = text(input.get("egressId"));
        String roomName = text(input.get("roomName"));
        String storedEgress = waitlist == null ? "" : text(waitlist.get("egressId"));

        // Exact production compatibility: explicit egress against an already-cleared row is a no-op.
        if (waitlist != null && !requestedEgress.isBlank() && storedEgress.isBlank()) {
            return new StopResult("Recording already stopped", null);
        }
        String egressId = !requestedEgress.isBlank() ? requestedEgress : storedEgress;
        if (egressId.isBlank() && !roomName.isBlank()) {
            List<Document> active = liveKit.activeRecordings(roomName);
            if (!active.isEmpty()) egressId = text(active.get(0).get("egressId"));
        }
        if (egressId.isBlank()) return new StopResult("No active recording found to stop", null);

        // A direct egress id without a waitlist/room cannot be ownership-validated; reject the Node hole.
        if (waitlist == null && roomName.isBlank()) {
            throw new IllegalArgumentException("waitlistId or roomName is required");
        }
        Document stopped = liveKit.stopRecording(egressId);
        String url = recordingUrl(stopped);
        if (url == null && waitlist != null) url = text(waitlist.get("recordingUrl"));
        if (waitlist != null) {
            mongo.getCollection(Collections.WAITLISTS).updateOne(ownedFilter(actor, waitlistId),
                    new Document("$set", new Document("recordingUrl", emptyToNull(url)))
                            .append("$unset", new Document("egressId", "")));
        }
        return new StopResult("Recording stopped", stopped);
    }

    private Document requireOwnedWaitlist(Document actor, String waitlistId) {
        if (!ObjectId.isValid(waitlistId)) throw new IllegalArgumentException("Valid waitlistId is required");
        Document value = mongo.getCollection(Collections.WAITLISTS).find(ownedFilter(actor, waitlistId)).first();
        if (value == null) throw new IllegalArgumentException("Waitlist not found");
        return value;
    }

    private Document ownedFilter(Document actor, String waitlistId) {
        return new Document("_id", new ObjectId(waitlistId))
                .append("consultant_id", new Document("$in", MongoIds.variants(actor.get("_id"))));
    }

    private String waitlistId(Map<String, Object> input) {
        String value = text(input.get("waitlistId"));
        if (value.isBlank()) value = text(input.get("roomId"));
        return ObjectId.isValid(value) ? value : null;
    }

    private String recordingUrl(Document egress) {
        Object files = egress == null ? null : egress.get("fileResults");
        if (!(files instanceof List<?> list)) return null;
        for (Object item : list) {
            Document file = item instanceof Document d ? d
                    : item instanceof Map<?, ?> map ? new Document((Map<String, Object>) map) : null;
            if (file == null) continue;
            String candidate = text(file.get("location"));
            if (candidate.isBlank()) candidate = text(file.get("filename"));
            String normalized = normalize(candidate);
            if (normalized != null) return normalized;
        }
        return null;
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) return null;
        if (value.startsWith("http://") || value.startsWith("https://")) return value;
        if (value.startsWith("s3://")) {
            String remainder = value.substring(5);
            int slash = remainder.indexOf('/');
            if (slash > 0) {
                String bucket = remainder.substring(0, slash);
                String key = remainder.substring(slash + 1);
                int marker = constants.mediaUrl.indexOf(".s3.");
                String suffix = marker < 0 ? "" : constants.mediaUrl.substring(marker);
                return "https://" + bucket + suffix + encodePath(key);
            }
        }
        return constants.mediaUrl + encodePath(value.replaceFirst("^/+", ""));
    }

    private String encodePath(String input) { return input.replace(" ", "%20"); }
    private Object emptyToNull(String value) { return value == null || value.isBlank() ? null : value; }
    private String requiredActor(Document actor) {
        String id = actor == null ? "" : text(actor.get("_id"));
        if (id.isBlank()) throw new IllegalStateException("Consultant not found");
        return id;
    }
    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }
}
