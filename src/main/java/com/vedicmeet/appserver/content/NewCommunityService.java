package com.vedicmeet.appserver.content;

import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import com.vedicmeet.appserver.notification.PushNotificationService;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Mobile parity for the reachable behavior in Node {@code utils/classes/new-community.js}. */
@Service
public class NewCommunityService {
    private static final List<String> ZODIACS = List.of("aries", "taurus", "gemini", "cancer",
            "leo", "virgo", "libra", "scorpio", "sagittarius", "capricorn", "aquarius", "pisces");

    private final MongoTemplate mongo;
    private final PushNotificationService push;

    public NewCommunityService(MongoTemplate mongo, PushNotificationService push) {
        this.mongo = mongo;
        this.push = push;
    }

    public Map<String, Object> personalizedHoroscope(Map<String, Object> input, Document actor) {
        String requested = text(input, "zodiac");
        String zodiac = requested == null ? "aries" : requested.toLowerCase(Locale.ENGLISH);
        if (!ZODIACS.contains(zodiac)) zodiac = "aries";
        String userName = firstNonBlank(actor == null ? null : actor.getString("name"),
                nestedText(actor, "details", "name"), text(input, "name"), "user");
        Document config = mongo.getCollection(Collections.ZODIAC_MESSAGES)
                .find(new Document("zodiacSign", zodiac).append("isActive", true)).first();
        if (config == null) return defaultMessage(zodiac, userName);
        String template = config.getString("messageTemplate");
        String personalized = template == null ? "" : template.replace("{{name}}", userName.trim());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("label", config.get("personalizationInstructions"));
        out.put("description", personalized);
        out.put("zodiacSign", config.get("zodiacSign"));
        out.put("displayName", config.get("displayName"));
        out.put("wordCount", personalized.isBlank() ? 0 : personalized.trim().split("\\s+").length);
        out.put("personalizedName", userName.trim());
        out.put("isPersonalized", !"there".equals(userName.trim()));
        return out;
    }

    public Document getList(String groupId, Document actor) {
        Document mapping = mongo.getCollection(Collections.COMMUNITY_MAPPINGS)
                .find(new Document("for", groupId).append("owner", actorId(actor))).first();
        if (mapping == null) throw new IllegalArgumentException("No data found");
        return mapping;
    }

    public Document request(Map<String, Object> input, Document actor) {
        String groupId = required(input, "groupId");
        String other = required(input, "otherUserId");
        Document target = mongo.getCollection(Collections.USERS).find(new Document("$or", List.of(
                        new Document("details.phone", other), new Document("details.email", other)))
                .append("isDeleted", false)).first();
        if (target == null) throw new IllegalArgumentException("User not found");
        ObjectId ownerId = actorId(actor);
        ObjectId targetId = objectId(target.get("_id"), "otherUserId");
        Document key = new Document("for", groupId).append("owner", ownerId);
        Document mapping = mongo.getCollection(Collections.COMMUNITY_MAPPINGS).find(key).first();
        Date now = new Date();
        if (mapping == null) {
            mapping = new Document("_id", new ObjectId()).append("for", groupId).append("owner", ownerId)
                    .append("list", new ArrayList<>(List.of(member(targetId, null, null, "PENDING", now))))
                    .append("createdAt", now).append("updatedAt", now);
            mongo.getCollection(Collections.COMMUNITY_MAPPINGS).insertOne(mapping);
            return mapping;
        }
        List<Document> list = mapping.getList("list", Document.class, new ArrayList<>());
        Document existing = list.stream().filter(item -> sameId(item.get("userId"), targetId)).findFirst().orElse(null);
        if (existing != null) {
            if ("PENDING".equals(existing.getString("status"))) throw new IllegalStateException("Request already pending");
            if ("ACCEPTED".equals(existing.getString("status"))) throw new IllegalStateException("Request already accepted");
            mongo.getCollection(Collections.COMMUNITY_MAPPINGS).updateOne(
                    new Document("_id", mapping.getObjectId("_id")).append("list.userId", targetId),
                    new Document("$set", new Document("list.$.status", "PENDING")
                            .append("list.$.requestedAt", now).append("updatedAt", now)));
        } else {
            mongo.getCollection(Collections.COMMUNITY_MAPPINGS).updateOne(
                    new Document("_id", mapping.getObjectId("_id")),
                    new Document("$push", new Document("list", member(targetId, null, null, "PENDING", now)))
                            .append("$set", new Document("updatedAt", now)));
        }
        return mongo.getCollection(Collections.COMMUNITY_MAPPINGS)
                .find(new Document("_id", mapping.getObjectId("_id"))).first();
    }

    public Document addMemberDetails(Map<String, Object> input) {
        String groupId = required(input, "groupId");
        String name = required(input, "name");
        String zodiac = required(input, "zodiac");
        ObjectId owner = objectId(input.get("ownerId"), "ownerId");
        Document mapping = mongo.getCollection(Collections.COMMUNITY_MAPPINGS)
                .find(new Document("for", groupId).append("owner", owner)).first();
        if (mapping == null) throw new IllegalArgumentException("No data found");
        List<Document> list = mapping.getList("list", Document.class, List.of());
        if (list.stream().anyMatch(item -> name.equals(item.getString("name"))
                && zodiac.equals(item.getString("zodiac"))))
            throw new IllegalStateException("Member already exists in the list");
        Date now = new Date();
        Document member = member(null, name, zodiac, "ACCEPTED", now)
                .append("acceptedAt", now);
        Document updated = mongo.getCollection(Collections.COMMUNITY_MAPPINGS).findOneAndUpdate(
                new Document("_id", mapping.getObjectId("_id")),
                new Document("$push", new Document("list", member))
                        .append("$set", new Document("updatedAt", now)),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        sendMemberAdded(owner, name, mapping.getObjectId("_id"));
        return updated;
    }

    public Document accept(Map<String, Object> input, Document actor) {
        String groupId = required(input, "groupId");
        ObjectId otherUserId = objectId(input.get("otherUserId"), "otherUserId");
        String action = required(input, "action").toLowerCase(Locale.ENGLISH);
        if (!List.of("accept", "decline").contains(action))
            throw new IllegalArgumentException("Action must be either accept or decline");
        ObjectId currentId = actorId(actor);
        Document senderMapping = mongo.getCollection(Collections.COMMUNITY_MAPPINGS)
                .find(new Document("for", groupId).append("list",
                        new Document("$elemMatch", new Document("userId", currentId)))).first();
        if (senderMapping == null) throw new IllegalArgumentException("No request found for this group");
        Document otherMapping = mongo.getCollection(Collections.COMMUNITY_MAPPINGS)
                .find(new Document("for", groupId).append("owner", otherUserId)).first();
        if (otherMapping == null) throw new IllegalArgumentException("Request not found");
        Date now = new Date();
        if ("accept".equals(action)) {
            updateMember(senderMapping.getObjectId("_id"), currentId, "ACCEPTED", "acceptedAt", now);
            upsertMember(otherMapping.getObjectId("_id"), currentId, "ACCEPTED", now);
        } else {
            updateMember(senderMapping.getObjectId("_id"), otherUserId, "DECLINED", "declinedAt", now);
        }
        return mongo.getCollection(Collections.COMMUNITY_MAPPINGS)
                .find(new Document("_id", senderMapping.getObjectId("_id"))).first();
    }

    public Map<String, Object> allRequests(String groupId, String status, Document actor) {
        if (groupId == null || groupId.isBlank()) throw new IllegalArgumentException("Group ID is required");
        List<Document> mappings = mongo.getCollection(Collections.COMMUNITY_MAPPINGS)
                .find(new Document("list.userId", actorId(actor))).into(new ArrayList<>());
        List<Document> all = new ArrayList<>();
        for (Document mapping : mappings) {
            Document owner = mongo.getCollection(Collections.USERS)
                    .find(new Document("_id", mapping.get("owner")))
                    .projection(new Document("name", 1).append("email", 1).append("mobile", 1)
                            .append("profileImage", 1)).first();
            for (Document member : mapping.getList("list", Document.class, List.of())) {
                Document row = new Document(member);
                Object memberId = member.get("userId");
                if (memberId != null) {
                    Document populated = mongo.getCollection(Collections.USERS).find(new Document("_id", memberId))
                            .projection(new Document("name", 1).append("email", 1).append("mobile", 1)
                                    .append("profileImage", 1)).first();
                    row.put("userId", populated);
                }
                row.put("groupName", mapping.get("for"));
                row.put("groupId", mapping.get("_id"));
                row.put("owner", owner);
                all.add(row);
            }
        }
        if (status != null && List.of("PENDING", "ACCEPTED", "DECLINED")
                .contains(status.toUpperCase(Locale.ENGLISH)))
            return Map.of("requests", all.stream().filter(r -> status.equalsIgnoreCase(r.getString("status"))).toList());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("all", all);
        out.put("pending", filterStatus(all, "PENDING"));
        out.put("accepted", filterStatus(all, "ACCEPTED"));
        out.put("declined", filterStatus(all, "DECLINED"));
        return out;
    }

    private void updateMember(ObjectId mappingId, ObjectId memberId, String status,
                              String dateField, Date now) {
        mongo.getCollection(Collections.COMMUNITY_MAPPINGS).updateOne(
                new Document("_id", mappingId).append("list.userId", memberId),
                new Document("$set", new Document("list.$.status", status)
                        .append("list.$." + dateField, now).append("updatedAt", now)));
    }

    private void upsertMember(ObjectId mappingId, ObjectId memberId, String status, Date now) {
        Document mapping = mongo.getCollection(Collections.COMMUNITY_MAPPINGS)
                .find(new Document("_id", mappingId).append("list.userId", memberId)).first();
        if (mapping != null) updateMember(mappingId, memberId, status, "acceptedAt", now);
        else mongo.getCollection(Collections.COMMUNITY_MAPPINGS).updateOne(new Document("_id", mappingId),
                new Document("$push", new Document("list", member(memberId, null, null, status, now)
                        .append("acceptedAt", now))).append("$set", new Document("updatedAt", now)));
    }

    private void sendMemberAdded(ObjectId owner, String name, ObjectId familyId) {
        Document user = mongo.getCollection(Collections.USERS).find(new Document("_id", owner)).first();
        if (user == null) return;
        String body = " " + name + " member was added to your family space.";
        Map<String, Object> data = Map.of("screen", "CommunityMembers", "familyId", familyId.toHexString());
        for (String token : tokens(user)) push.sendNotificationAndCons("user", token, body, data,
                "Vedic Meet", "vastu");
        Date now = new Date();
        mongo.getCollection(Collections.NOTIFICATIONS).insertOne(new Document("receiverId", owner)
                .append("message", body).append("title", "Vedic Meet").append("userType", "user")
                .append("senderType", "system").append("type", "other").append("data", data)
                .append("readByReceiver", new ArrayList<>()).append("createdAt", now).append("updatedAt", now));
    }

    private Map<String, Object> defaultMessage(String zodiac, String userName) {
        Map<String, String> messages = Map.ofEntries(
                Map.entry("aries", "Hey %s! Your energy is magnetic today - embrace new opportunities! 🔥"),
                Map.entry("taurus", "Hi %s! Your steady approach will lead to great success today. 💪"),
                Map.entry("gemini", "Hello %s! Your communication skills will shine bright today! ✨"),
                Map.entry("cancer", "Hey %s! Trust your intuition - it's guiding you perfectly today. 🌙"),
                Map.entry("leo", "Hi %s! Your natural leadership will inspire others today! 👑"),
                Map.entry("virgo", "Hello %s! Your attention to detail will pay off beautifully today. 🌟"),
                Map.entry("libra", "Hey %s! Your balanced perspective will bring harmony today! ⚖️"),
                Map.entry("scorpio", "Hi %s! Your deep insights will reveal important truths today. 🔮"),
                Map.entry("sagittarius", "Hello %s! Your adventurous spirit will open new doors today! 🏹"),
                Map.entry("capricorn", "Hey %s! Your determination will help you reach new heights today! 🏔️"),
                Map.entry("aquarius", "Hi %s! Your innovative ideas will make a real difference today! 💡"),
                Map.entry("pisces", "Hello %s! Your compassionate nature will touch hearts today! 🐠"));
        String message = messages.getOrDefault(zodiac, messages.get("aries")).formatted(userName);
        Map<String, String> labels = Map.ofEntries(Map.entry("aries", "Energetic for You"),
                Map.entry("taurus", "Stable for You"), Map.entry("gemini", "Communicative for You"),
                Map.entry("cancer", "Intuitive for You"), Map.entry("leo", "Confident for You"),
                Map.entry("virgo", "Detailed for You"), Map.entry("libra", "Balanced for You"),
                Map.entry("scorpio", "Transformative for You"), Map.entry("sagittarius", "Adventurous for You"),
                Map.entry("capricorn", "Ambitious for You"), Map.entry("aquarius", "Innovative for You"),
                Map.entry("pisces", "Compassionate for You"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("label", "Today Looks: " + labels.getOrDefault(zodiac, "Promising for You"));
        out.put("description", message);
        out.put("zodiacSign", zodiac);
        out.put("displayName", zodiac.substring(0, 1).toUpperCase(Locale.ENGLISH) + zodiac.substring(1));
        out.put("wordCount", message.split("\\s+").length);
        return out;
    }

    private List<Document> filterStatus(List<Document> rows, String status) {
        return rows.stream().filter(row -> status.equals(row.getString("status"))).toList();
    }

    private Document member(ObjectId userId, String name, String zodiac, String status, Date now) {
        Document member = new Document("_id", new ObjectId());
        if (userId != null) member.put("userId", userId);
        if (name != null) member.put("name", name);
        if (zodiac != null) member.put("zodiac", zodiac);
        member.put("status", status);
        member.put("requestedAt", now);
        return member;
    }

    private List<String> tokens(Document user) {
        Object device = user.get("device");
        Object value = device instanceof Document d ? d.get("fcmToken")
                : device instanceof Map<?, ?> map ? map.get("fcmToken") : null;
        List<String> out = new ArrayList<>();
        if (value instanceof Iterable<?> iterable)
            for (Object item : iterable) if (item != null) out.add(String.valueOf(item));
        return out;
    }

    private ObjectId actorId(Document actor) { return objectId(actor == null ? null : actor.get("_id"), "owner"); }

    private ObjectId objectId(Object value, String field) {
        if (value instanceof ObjectId id) return id;
        if (value != null && ObjectId.isValid(String.valueOf(value))) return new ObjectId(String.valueOf(value));
        throw new IllegalArgumentException(field + " is required");
    }

    private boolean sameId(Object left, Object right) {
        return left != null && right != null && String.valueOf(left).equals(String.valueOf(right));
    }

    private String nestedText(Document root, String parent, String child) {
        Object value = root == null ? null : root.get(parent);
        if (value instanceof Document d) return d.getString(child);
        if (value instanceof Map<?, ?> map && map.get(child) != null) return String.valueOf(map.get(child));
        return null;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return "user";
    }

    private String required(Map<String, Object> input, String key) {
        String value = text(input, key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(key + " is required");
        return value;
    }

    private String text(Map<String, Object> input, String key) {
        Object value = input == null ? null : input.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
