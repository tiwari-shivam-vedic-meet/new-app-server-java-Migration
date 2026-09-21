package com.vedicmeet.appserver.admin;

import com.mongodb.bulk.BulkWriteResult;
import com.mongodb.client.AggregateIterable;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.UpdateOneModel;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.WriteModel;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class AdminZodiacMessageService {

    private static final Set<String> ZODIAC_SIGNS = Set.of("aries", "taurus", "gemini", "cancer", "leo", "virgo",
            "libra", "scorpio", "sagittarius", "capricorn", "aquarius", "pisces");
    private static final String DEFAULT_INSTRUCTIONS =
            "Make it warm, friendly, and emotionally engaging. Keep the tone uplifting and relatable.";
    private static final String MISSING_SEEDER =
            "Cannot find module '../../../utils/seeders/zodiac-message-seeder'";

    private final MongoTemplate mongo;
    @SuppressWarnings("unused")
    private final AdminMongoSupport support;

    public AdminZodiacMessageService(MongoTemplate mongo, AdminMongoSupport support) {
        this.mongo = mongo;
        this.support = support;
    }

    public Map<String, Object> create(Map<String, Object> body, String adminId) {
        Map<String, Object> input = objectBody(body);
        validateCreate(input);
        validateTemplateContainsName(input.get("messageTemplate"));

        Date now = new Date();
        // FAITHFUL(node-quirk): zodiac-message.js:20 create spreads request body, then overwrites lastUpdatedBy with admin._id.
        // FAITHFUL(node-quirk): zodiac-message.js:25 Mongoose create persists only schema paths and applies schema defaults.
        Document doc = new Document("_id", new ObjectId())
                .append("zodiacSign", str(input.get("zodiacSign")))
                .append("displayName", str(input.get("displayName")))
                .append("messageTemplate", str(input.get("messageTemplate")))
                .append("personalizationInstructions", str(input.getOrDefault("personalizationInstructions", DEFAULT_INSTRUCTIONS)))
                .append("wordLimit", intOf(input.getOrDefault("wordLimit", 35)))
                .append("isActive", boolOf(input.getOrDefault("isActive", true)))
                .append("lastUpdatedBy", adminId == null ? null : support.id(adminId))
                .append("version", 1)
                .append("createdAt", now)
                .append("updatedAt", now);
        mongo.getCollection(Collections.ZODIAC_MESSAGES).insertOne(doc);
        return withVirtuals(doc);
    }

    public Map<String, Object> update(Map<String, Object> body, String adminId) {
        Map<String, Object> input = objectBody(body);
        validateUpdate(input);
        String zodiacSign = str(input.get("zodiacSign")).toLowerCase();

        // FAITHFUL(node-quirk): zodiac-message.js:41 removes zodiacSign from the update body before persistence.
        Document set = schemaUpdate(input, false);
        set.put("updatedAt", new Date());
        Document update = new Document();
        if (!set.isEmpty()) update.append("$set", set);
        // FAITHFUL(node-quirk): zodiac-message.js:46-49 increments version while not setting lastUpdatedBy.
        update.append("$inc", new Document("version", 1));

        // FAITHFUL(node-quirk): zodiac-message.js:44-50 omits { new: true }, so findOneAndUpdate returns the pre-update document.
        Document updated = mongo.getCollection(Collections.ZODIAC_MESSAGES).findOneAndUpdate(
                new Document("zodiacSign", zodiacSign), update);
        if (updated == null) {
            throw new IllegalStateException("Zodiac message configuration not found");
        }
        return withVirtuals(updated);
    }

    public Map<String, Object> get(Map<String, String> query) {
        validateZodiacQuery(query);
        String zodiacSign = query.get("zodiacSign").toLowerCase();
        // FAITHFUL(node-quirk): zodiac-message.js:73-76 only active configurations are fetched and lastUpdatedBy is populated.
        List<Document> rows = aggregate(getPipeline(zodiacSign));
        if (rows.isEmpty()) {
            throw new IllegalStateException("Zodiac message configuration not found");
        }
        return withVirtuals(rows.get(0));
    }

    public Map<String, Object> list(Map<String, String> query) {
        validateList(query);
        Page page = page(query, 12);
        Document params = new Document();
        if (query != null && query.containsKey("isActive")) {
            // FAITHFUL(node-quirk): zodiac-message.js:101-102 compares the string value to 'true'; everything else is false.
            params.put("isActive", "true".equals(query.get("isActive")));
        }

        // FAITHFUL(node-quirk): zodiac-message.js:105-111 performs the list read and count independently.
        List<Map<String, Object>> rows = aggregate(listPipeline(params, page.skip, page.limit)).stream()
                .map(this::withVirtuals).toList();
        long total = mongo.getCollection(Collections.ZODIAC_MESSAGES).countDocuments(params);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("zodiacMessages", rows);
        result.put("total", total);
        result.put("page", page.page);
        result.put("limit", page.limit);
        // FAITHFUL(node-quirk): zodiac-message.js:119 uses Math.ceil(total / limit).
        result.put("totalPages", (long) Math.ceil((double) total / page.limit));
        return result;
    }

    public Map<String, Object> delete(Map<String, Object> body, String adminId) {
        Map<String, Object> input = objectBody(body);
        validateZodiacBody(input, "Invalid zodiac sign");
        String zodiacSign = str(input.get("zodiacSign")).toLowerCase();

        // FAITHFUL(node-quirk): zodiac-message.js:137-145 soft-deletes, sets lastUpdatedBy, increments version, and asks for new doc.
        Document deleted = mongo.getCollection(Collections.ZODIAC_MESSAGES).findOneAndUpdate(
                new Document("zodiacSign", zodiacSign),
                new Document("$set", new Document("isActive", false)
                        .append("lastUpdatedBy", adminId == null ? null : support.id(adminId))
                        .append("updatedAt", new Date()))
                        .append("$inc", new Document("version", 1)),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        if (deleted == null) {
            throw new IllegalStateException("Zodiac message configuration not found");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message", "Zodiac message configuration deleted successfully");
        return result;
    }

    public Map<String, Object> bulkUpdate(Map<String, Object> body, String adminId) {
        Map<String, Object> input = objectBody(body);
        List<Map<String, Object>> messages = messages(input.get("messages"));
        validateBulkMessages(messages);

        List<WriteModel<Document>> writes = new ArrayList<>();
        for (Map<String, Object> message : messages) {
            // FAITHFUL(node-quirk): zodiac-message.js:286-295 lowercases each filter, spreads each message, overwrites lastUpdatedBy, increments version, and upserts.
            Document set = schemaUpdate(message, true);
            set.put("lastUpdatedBy", adminId == null ? null : support.id(adminId));
            Date now = new Date();
            set.put("updatedAt", now);
            Document setOnInsert = new Document("createdAt", now);
            if (!set.containsKey("personalizationInstructions")) setOnInsert.put("personalizationInstructions", DEFAULT_INSTRUCTIONS);
            if (!set.containsKey("wordLimit")) setOnInsert.put("wordLimit", 35);
            if (!set.containsKey("isActive")) setOnInsert.put("isActive", true);
            Bson update = new Document("$set", set).append("$inc", new Document("version", 1))
                    .append("$setOnInsert", setOnInsert);
            writes.add(new UpdateOneModel<>(new Document("zodiacSign", str(message.get("zodiacSign")).toLowerCase()),
                    update, new UpdateOptions().upsert(true)));
        }

        BulkWriteResult bulk = mongo.getCollection(Collections.ZODIAC_MESSAGES).bulkWrite(writes);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("modifiedCount", bulk.getModifiedCount());
        result.put("upsertedCount", bulk.getUpserts().size());
        result.put("message", "Bulk update completed successfully");
        return result;
    }

    public Map<String, Object> personalized(Map<String, String> query) {
        validatePersonalized(query, false);
        return personalizedMessage(query.get("zodiacSign"), query.get("userName"), query.get("userId"));
    }

    public Map<String, Object> userPersonalized(Map<String, String> query) {
        validatePersonalized(query, true);
        // FAITHFUL(node-quirk): zodiac-message.js:186-189 user-personalized supplies 'Test User' before delegating when userName is falsy.
        return personalizedMessage(query.get("zodiacSign"),
                present(query.get("userName")) ? query.get("userName") : "Test User", query.get("userId"));
    }

    public Map<String, Object> seed(Map<String, Object> body, String adminId) {
        // FAITHFUL(node-quirk): zodiac-message.js:216 requires a seeder module that is absent from the Node source tree.
        throw new IllegalStateException(MISSING_SEEDER);
    }

    public Map<String, Object> stats(Map<String, String> query) {
        // FAITHFUL(node-quirk): zodiac-message.js:266 requires a seeder module that is absent from the Node source tree.
        throw new IllegalStateException(MISSING_SEEDER);
    }

    List<Document> getPipeline(String zodiacSign) {
        return List.of(
                Document.parse("{ $match: { zodiacSign: " + quote(zodiacSign) + ", isActive: true } }"),
                populateAdminStage(),
                Document.parse("{ $unwind: { path: '$lastUpdatedBy', preserveNullAndEmptyArrays: true } }"),
                Document.parse("{ $limit: 1 }"));
    }

    List<Document> listPipeline(Document params, int skipIndex, int limit) {
        return List.of(
                new Document("$match", params),
                populateAdminStage(),
                Document.parse("{ $unwind: { path: '$lastUpdatedBy', preserveNullAndEmptyArrays: true } }"),
                Document.parse("{ $sort: { zodiacSign: 1 } }"),
                Document.parse("{ $skip: " + skipIndex + " }"),
                Document.parse("{ $limit: " + limit + " }"));
    }

    private Map<String, Object> personalizedMessage(String zodiacSign, String userName, String userId) {
        // FAITHFUL(node-quirk): zodiac-message.js:172-180 falls back to hard-coded defaults instead of throwing when no active config exists.
        Document config = mongo.getCollection(Collections.ZODIAC_MESSAGES)
                .find(new Document("zodiacSign", zodiacSign.toLowerCase()).append("isActive", true)).first();
        if (config == null) {
            return defaultPersonalizedMessage(zodiacSign, userName);
        }

        String personalizedName = "there";
        if (present(userName) && !userName.trim().isEmpty()) {
            personalizedName = userName.trim();
        } else if (present(userId)) {
            // FAITHFUL(node-quirk): zodiac-message.js:189-192 userId does not cause a lookup and still uses 'there'.
            personalizedName = "there";
        }

        String personalized = str(config.get("messageTemplate")).replace("{{name}}", personalizedName);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("label", config.get("personalizationInstructions"));
        result.put("description", personalized);
        result.put("zodiacSign", config.get("zodiacSign"));
        result.put("displayName", config.get("displayName"));
        // FAITHFUL(node-quirk): zodiac-message.js:203 counts non-empty whitespace-delimited words.
        result.put("wordCount", wordCount(personalized));
        result.put("personalizedName", personalizedName);
        result.put("isPersonalized", !"there".equals(personalizedName));
        return result;
    }

    private Map<String, Object> defaultPersonalizedMessage(String zodiacSign, String userName) {
        String sign = zodiacSign.toLowerCase();
        String name = present(userName) ? userName : "there";
        Map<String, String> defaults = defaultMessages(name);
        String description = defaults.getOrDefault(sign, defaults.get("aries"));

        Map<String, Object> result = new LinkedHashMap<>();
        // FAITHFUL(node-quirk): zodiac-message.js:243-249 fallback lacks personalizedName/isPersonalized and counts words with split(' '), not regex.
        result.put("label", "Today Looks: " + dynamicLabel(sign));
        result.put("description", description);
        result.put("zodiacSign", sign);
        result.put("displayName", sign.substring(0, 1).toUpperCase() + sign.substring(1));
        result.put("wordCount", defaults.containsKey(sign) ? description.split(" ").length : 0);
        return result;
    }

    private Document populateAdminStage() {
        return Document.parse("{ $lookup: { from: " + quote(Collections.ADMINS) + ", let: { adminId: '$lastUpdatedBy' }, "
                + "pipeline: [ { $match: { $expr: { $eq: [ '$_id', '$$adminId' ] } } }, "
                + "{ $project: { name: 1, email: 1 } } ], as: 'lastUpdatedBy' } }");
    }

    private List<Document> aggregate(List<Document> pipeline) {
        AggregateIterable<Document> iterable = mongo.getCollection(Collections.ZODIAC_MESSAGES).aggregate(pipeline);
        return iterable.into(new ArrayList<>());
    }

    private Document schemaUpdate(Map<String, Object> input, boolean includeZodiacSign) {
        Document set = new Document();
        if (includeZodiacSign && input.containsKey("zodiacSign")) set.put("zodiacSign", str(input.get("zodiacSign")));
        if (input.containsKey("displayName")) set.put("displayName", str(input.get("displayName")));
        if (input.containsKey("messageTemplate")) set.put("messageTemplate", str(input.get("messageTemplate")));
        if (input.containsKey("personalizationInstructions")) {
            set.put("personalizationInstructions", str(input.get("personalizationInstructions")));
        }
        if (input.containsKey("wordLimit")) set.put("wordLimit", intOf(input.get("wordLimit")));
        if (input.containsKey("isActive")) set.put("isActive", boolOf(input.get("isActive")));
        if (input.containsKey("lastUpdatedBy")) set.put("lastUpdatedBy", support.id(input.get("lastUpdatedBy")));
        return set;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> messages(Object value) {
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("Messages array is required");
        }
        return (List<Map<String, Object>>) list;
    }

    private Map<String, Object> withVirtuals(Document source) {
        Document result = new Document(source);
        String sign = str(source.get("zodiacSign"));
        if (!sign.isEmpty()) {
            // FAITHFUL(node-quirk): zodiac-message.js:25 returns a Mongoose document whose model adds formattedZodiacSign.
            result.put("formattedZodiacSign", sign.substring(0, 1).toUpperCase() + sign.substring(1));
        }
        result.put("id", str(source.get("_id")));
        return result;
    }

    private void validateCreate(Map<String, Object> input) {
        validateZodiacBody(input,
                "Invalid zodiac sign. Must be one of: aries, taurus, gemini, cancer, leo, virgo, libra, scorpio, sagittarius, capricorn, aquarius, pisces");
        required(input, "displayName", "Display name is required");
        checkStringLength(input.get("displayName"), 2, 50, "Display name must be at least 2 characters long",
                "Display name cannot exceed 50 characters");
        required(input, "messageTemplate", "Message template is required");
        checkStringLength(input.get("messageTemplate"), 10, 500, "Message template must be at least 10 characters long",
                "Message template cannot exceed 500 characters");
        validateOptional(input);
    }

    private void validateUpdate(Map<String, Object> input) {
        validateZodiacBody(input, "Invalid zodiac sign");
        validateOptional(input);
    }

    private void validateBulkMessages(List<Map<String, Object>> messages) {
        if (messages.isEmpty()) throw new IllegalArgumentException("At least one message configuration is required");
        if (messages.size() > 12) throw new IllegalArgumentException("Cannot update more than 12 zodiac signs at once");
        for (Map<String, Object> message : messages) {
            validateUpdate(message);
        }
    }

    private void validateOptional(Map<String, Object> input) {
        if (input.containsKey("displayName")) {
            checkStringLength(input.get("displayName"), 2, 50, "Display name must be at least 2 characters long",
                    "Display name cannot exceed 50 characters");
        }
        if (input.containsKey("messageTemplate")) {
            checkStringLength(input.get("messageTemplate"), 10, 500, "Message template must be at least 10 characters long",
                    "Message template cannot exceed 500 characters");
        }
        if (input.containsKey("personalizationInstructions")
                && str(input.get("personalizationInstructions")).length() > 200) {
            throw new IllegalArgumentException("Personalization instructions cannot exceed 200 characters");
        }
        if (input.containsKey("wordLimit")) {
            int value = intOf(input.get("wordLimit"), "Word limit must be an integer");
            if (value < 10) throw new IllegalArgumentException("Word limit must be at least 10");
            if (value > 100) throw new IllegalArgumentException("Word limit cannot exceed 100");
        }
    }

    private void validateList(Map<String, String> query) {
        if (query == null) return;
        if (query.containsKey("page")) {
            int value = intOf(query.get("page"), "Page must be an integer");
            if (value < 1) throw new IllegalArgumentException("Page must be at least 1");
        }
        if (query.containsKey("limit")) {
            int value = intOf(query.get("limit"), "Limit must be an integer");
            if (value < 1) throw new IllegalArgumentException("Limit must be at least 1");
            if (value > 50) throw new IllegalArgumentException("Limit cannot exceed 50");
        }
        if (query.containsKey("isActive") && !List.of("true", "false").contains(query.get("isActive"))) {
            throw new IllegalArgumentException("isActive must be either \"true\" or \"false\"");
        }
    }

    private void validatePersonalized(Map<String, String> query, boolean allowUserId) {
        validateZodiacQuery(query);
        if (query.containsKey("userName") && str(query.get("userName")).length() > 50) {
            throw new IllegalArgumentException("User name cannot exceed 50 characters");
        }
        if (!allowUserId && query.containsKey("userId")) {
            throw new IllegalArgumentException("\"userId\" is not allowed");
        }
    }

    private void validateZodiacQuery(Map<String, String> query) {
        if (query == null || !query.containsKey("zodiacSign")) {
            throw new IllegalArgumentException("Zodiac sign is required");
        }
        validateSign(query.get("zodiacSign"), "Invalid zodiac sign");
    }

    private void validateZodiacBody(Map<String, Object> input, String invalidMessage) {
        required(input, "zodiacSign", "Zodiac sign is required");
        validateSign(str(input.get("zodiacSign")), invalidMessage);
    }

    private void validateSign(String value, String message) {
        if (!ZODIAC_SIGNS.contains(str(value))) {
            throw new IllegalArgumentException(message);
        }
    }

    private void validateTemplateContainsName(Object template) {
        if (!str(template).contains("{{name}}")) {
            throw new IllegalArgumentException(
                    "zodiacMessage validation failed: messageTemplate: Message template must contain {{name}} placeholder for personalization");
        }
    }

    private void required(Map<String, Object> input, String field, String message) {
        if (input == null || !input.containsKey(field) || input.get(field) == null || str(input.get(field)).isEmpty()) {
            throw new IllegalArgumentException(message);
        }
    }

    private void checkStringLength(Object value, int min, int max, String minMessage, String maxMessage) {
        int len = str(value).length();
        if (len < min) throw new IllegalArgumentException(minMessage);
        if (len > max) throw new IllegalArgumentException(maxMessage);
    }

    private Page page(Map<String, String> query, int defaultLimit) {
        int page = query == null || !query.containsKey("page") ? 1 : intOf(query.get("page"), "Page must be an integer");
        int limit = query == null || !query.containsKey("limit") ? defaultLimit : intOf(query.get("limit"), "Limit must be an integer");
        // FAITHFUL(node-quirk): zodiac-message.js:96-97 calculates skip using JS-coerced page and limit defaults.
        return new Page(page, limit, (page - 1) * limit);
    }

    private int wordCount(String value) {
        if (value.isBlank()) return 0;
        return value.trim().split("\\s+").length;
    }

    private Map<String, String> defaultMessages(String name) {
        Map<String, String> messages = new LinkedHashMap<>();
        messages.put("aries", "Hey " + name + "! Your energy is magnetic today - embrace new opportunities! 🔥");
        messages.put("taurus", "Hi " + name + "! Your steady approach will lead to great success today. 💪");
        messages.put("gemini", "Hello " + name + "! Your communication skills will shine bright today! ✨");
        messages.put("cancer", "Hey " + name + "! Trust your intuition - it's guiding you perfectly today. 🌙");
        messages.put("leo", "Hi " + name + "! Your natural leadership will inspire others today! 👑");
        messages.put("virgo", "Hello " + name + "! Your attention to detail will pay off beautifully today. 🌟");
        messages.put("libra", "Hey " + name + "! Your balanced perspective will bring harmony today! ⚖️");
        messages.put("scorpio", "Hi " + name + "! Your deep insights will reveal important truths today. 🔮");
        messages.put("sagittarius", "Hello " + name + "! Your adventurous spirit will open new doors today! 🏹");
        messages.put("capricorn", "Hey " + name + "! Your determination will help you reach new heights today! 🏔️");
        messages.put("aquarius", "Hi " + name + "! Your innovative ideas will make a real difference today! 💡");
        messages.put("pisces", "Hello " + name + "! Your compassionate nature will touch hearts today! 🐠");
        return messages;
    }

    private String dynamicLabel(String zodiacSign) {
        Map<String, String> labels = Map.ofEntries(
                Map.entry("aries", "Energetic for You"),
                Map.entry("taurus", "Stable for You"),
                Map.entry("gemini", "Communicative for You"),
                Map.entry("cancer", "Intuitive for You"),
                Map.entry("leo", "Confident for You"),
                Map.entry("virgo", "Detailed for You"),
                Map.entry("libra", "Balanced for You"),
                Map.entry("scorpio", "Transformative for You"),
                Map.entry("sagittarius", "Adventurous for You"),
                Map.entry("capricorn", "Ambitious for You"),
                Map.entry("aquarius", "Innovative for You"),
                Map.entry("pisces", "Compassionate for You"));
        return labels.getOrDefault(zodiacSign.toLowerCase(), "Promising for You");
    }

    private boolean present(String value) {
        return value != null && !value.isEmpty();
    }

    private boolean boolOf(Object value) {
        if (value instanceof Boolean b) return b;
        return "true".equals(String.valueOf(value));
    }

    private int intOf(Object value) {
        return intOf(value, "Word limit must be an integer");
    }

    private int intOf(Object value, String integerMessage) {
        if (value instanceof Number n) {
            double d = n.doubleValue();
            if (d % 1 != 0) throw new IllegalArgumentException(integerMessage);
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(integerMessage);
        }
    }

    private String quote(String value) {
        return "'" + (value == null ? "" : value.replace("\\", "\\\\").replace("'", "\\'")) + "'";
    }

    private String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private Map<String, Object> objectBody(Map<String, Object> body) {
        return body == null ? Map.of() : body;
    }

    private record Page(int page, int limit, int skip) {}
}
