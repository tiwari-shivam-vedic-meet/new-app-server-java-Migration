package com.vedicmeet.appserver.admin;

import com.mongodb.client.FindIterable;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.vedicmeet.appserver.config.AppConstants;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AdminCommunityService {

    private static final Pattern LEADING_INT = Pattern.compile("^[+-]?\\d+");

    private final MongoTemplate mongo;
    private final AdminMongoSupport support;
    private final AppConstants constants;

    public AdminCommunityService(MongoTemplate mongo, AdminMongoSupport support, AppConstants constants) {
        this.mongo = mongo;
        this.support = support;
        this.constants = constants;
    }

    public Map<String, Object> list(Map<String, Object> body) {
        Map<String, Object> input = objectBody(body);
        int page = jsInt(input.get("page"), 1);
        int limit = jsInt(input.get("limit"), 10);
        int skipIndex = (page - 1) * limit;

        Document params = new Document("status", true);
        String search = str(input.getOrDefault("search", ""));
        if (!search.isEmpty()) {
            // FAITHFUL(node-quirk): utils/classes/community.js:57-60 builds an unescaped contains regex.
            params.append("title", new Document("$regex", ".*" + search + ".*").append("$options", "i"));
        }

        FindIterable<Document> iterable = mongo.getCollection(Collections.COMMUNITIES).find(params)
                .sort(new Document("createdAt", -1)).skip(skipIndex).limit(limit);
        List<Map<String, Object>> list = iterable.into(new ArrayList<>()).stream().map(this::withCommunityVirtuals).toList();
        long total = mongo.getCollection(Collections.COMMUNITIES).countDocuments(params);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", list);
        result.put("total", total);
        return result;
    }

    public Map<String, Object> add(Map<String, Object> body) {
        Map<String, Object> input = objectBody(body);
        Document titleExist = mongo.getCollection(Collections.COMMUNITIES)
                .find(new Document("title", input.get("title"))).first();
        if (titleExist != null) throw new IllegalArgumentException("TITLE_EXIST");

        // FAITHFUL(node-quirk): utils/classes/community.js:23-28 requires files.communityImage after title check.
        if (isBlank(input.get("image"))) throw new IllegalArgumentException("IMAGE_REQUIRE");

        Date now = new Date();
        // FAITHFUL(node-quirk): utils/models/community-model.js:7-45 create(input) keeps schema paths/defaults only.
        Document doc = new Document("_id", new ObjectId())
                .append("title", input.containsKey("title") ? str(input.get("title")) : "")
                .append("image", str(input.get("image")))
                .append("description", input.containsKey("description") ? str(input.get("description")) : "")
                .append("member", listValue(input.get("member")))
                .append("blockUser", listValue(input.get("blockUser")))
                .append("activeMember", objectIdList(input.get("activeMember")))
                .append("status", input.containsKey("status") ? toBool(input.get("status")) : true)
                .append("createdAt", now).append("updatedAt", now);
        mongo.getCollection(Collections.COMMUNITIES).insertOne(doc);
        return withCommunityVirtuals(doc);
    }

    public Map<String, Object> memberList(Map<String, String> query) {
        Map<String, String> input = query == null ? Map.of() : query;
        Object page = input.containsKey("page") ? input.get("page") : 1;
        Object limit = input.containsKey("limit") ? input.get("limit") : 10;
        String search = input.getOrDefault("search", "");

        List<Document> rows = mongo.getCollection(Collections.COMMUNITIES)
                .aggregate(memberListPipeline(input.get("communityId"), page, limit, search)).into(new ArrayList<>());
        Document facet = rows.isEmpty() ? new Document("member", List.of()).append("total", List.of()) : rows.get(0);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", facetTotal(facet.getList("total", Document.class)));
        result.put("member", listOrEmpty(facet.get("member")));
        return result;
    }

    /** {@code [$match,$project,$unwind,$lookup,$unwind,$project,$match,$facet]} for member list. */
    List<Document> memberListPipeline(String communityId, Object page, Object limit, String search) {
        Document textsearch = new Document();
        if (search != null && !search.isEmpty()) {
            // FAITHFUL(node-quirk): utils/classes/community.js:103-114 builds unescaped regexes for three fields.
            textsearch.append("$or", List.of(
                    new Document("name", regex(search)),
                    new Document("mobile", regex(search)),
                    new Document("email", regex(search))));
        }
        // FAITHFUL(node-bug): utils/classes/community.js:168-173 prefixes MEDIA_URL a second time after lookup already did it.
        return List.of(
                new Document("$match", new Document("_id", support.id(communityId))),
                Document.parse("{ $project: { member: 1 } }"),
                Document.parse("{ $unwind: '$member' }"),
                userLookup("$member.user", "user", true),
                Document.parse("{ $unwind: '$user' }"),
                new Document("$project", new Document("name", "$user.name")
                        .append("email", "$user.email")
                        .append("mobile", "$user.mobile")
                        .append("image", media("$user.image"))
                        .append("gender", "$user.gender")
                        .append("status", "$member.status")
                        .append("joinDate", "$member.date")
                        .append("userId", "$member.user")),
                new Document("$match", textsearch),
                new Document("$facet", new Document("total", List.of(
                        new Document("$group", new Document("_id", "null").append("count", new Document("$sum", 1)))))
                        .append("member", List.of(new Document("$skip", page), new Document("$limit", limit)))));
    }

    public Map<String, Object> memberChatList(Map<String, Object> body) {
        // FAITHFUL(node-bug): utils/classes/community.js:5 does require("../models").chatMessage, but
        // utils/models/index.js exports NO `chatMessage` key -> chatMessageModel is undefined. memberChatList()
        // (community.js:200) immediately calls chatMessageModel.aggregate(...), so this endpoint ALWAYS throws a
        // TypeError at runtime; the route returns HTTP-200 {success:false, message:<TypeError message>}.
        throw new IllegalStateException("Cannot read properties of undefined (reading 'aggregate')");
    }

    public Map<String, Object> changeCommunityUserStatus(Map<String, Object> body) {
        Map<String, Object> request = objectBody(body);
        Object communityId = request.get("communityId");
        Object userId = request.get("userId");
        Object status = request.get("status");

        Document set = new Document("member.$.status", toBool(status)).append("updatedAt", new Date());
        Document updatedCommunity = mongo.getCollection(Collections.COMMUNITIES).findOneAndUpdate(
                new Document("_id", support.id(communityId)).append("member.user", support.id(userId)),
                new Document("$set", set),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));

        Document userIsBlock = mongo.getCollection(Collections.COMMUNITIES).find(
                new Document("_id", support.id(communityId)).append("blockUser", new Document("$in", List.of(userId)))).first();
        Document blockMutation = userIsBlock != null
                ? new Document("$pull", new Document("blockUser", userId))
                : new Document("$push", new Document("blockUser", userId));
        // FAITHFUL(node-quirk): utils/classes/community.js:269-275 toggles blockUser before checking update success.
        blockMutation.append("$set", new Document("updatedAt", new Date()));
        mongo.getCollection(Collections.COMMUNITIES).findOneAndUpdate(new Document("_id", support.id(communityId)), blockMutation);

        if (updatedCommunity == null) throw new IllegalStateException("COMMUNITY_NOT_EXIST");
        return withCommunityVirtuals(updatedCommunity);
    }

    public Map<String, Object> details(Map<String, Object> body) {
        Map<String, Object> input = objectBody(body);
        Document details = mongo.getCollection(Collections.COMMUNITIES)
                .find(new Document("_id", support.id(input.get("communityId")))).first();
        if (details == null) throw new IllegalStateException("COMMUNITY_NOT_EXIST");
        return withCommunityVirtuals(details);
    }

    private Document userLookup(String userExpression, String as, boolean includeEmailMobileGender) {
        Document project = new Document("name", 1)
                .append("image", media("$image"));
        if (includeEmailMobileGender) project.append("email", 1).append("mobile", 1).append("gender", 1);
        return new Document("$lookup", new Document("from", Collections.USERS)
                .append("let", new Document("userId", userExpression))
                .append("pipeline", List.of(
                        new Document("$match", new Document("$expr", new Document("$eq", List.of("$_id", "$$userId")))),
                        new Document("$project", project)))
                .append("as", as));
    }

    private Document media(String field) {
        return new Document("$cond", new Document("if", new Document("$eq", List.of(field, "")))
                .append("then", "")
                .append("else", new Document("$concat", List.of(constants.mediaUrl, field))));
    }

    private Document regex(String value) {
        return new Document("$regex", ".*" + value + ".*").append("$options", "i");
    }

    private Map<String, Object> withCommunityVirtuals(Document source) {
        if (source == null) return null;
        Document result = new Document(source);
        String image = str(source.get("image"));
        result.put("communityImage", image.isEmpty() ? "" : constants.mediaUrl + image);
        result.put("id", str(source.get("_id")));
        return result;
    }

    private long facetTotal(List<Document> total) {
        if (total == null || total.isEmpty()) return 0L;
        Object count = total.get(0).get("count");
        return count instanceof Number number ? number.longValue() : 0L;
    }

    @SuppressWarnings("unchecked")
    private List<Object> listValue(Object value) {
        if (value instanceof List<?> list) return new ArrayList<>((List<Object>) list);
        return new ArrayList<>();
    }

    private List<Object> objectIdList(Object value) {
        if (!(value instanceof List<?> list)) return new ArrayList<>();
        return list.stream().map(support::id).toList();
    }

    private List<?> listOrEmpty(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    private boolean isBlank(Object value) {
        return str(value).isBlank();
    }

    private static int jsInt(Object value, int def) {
        if (value == null) return def;
        Matcher m = LEADING_INT.matcher(String.valueOf(value).trim());
        if (!m.find()) throw new IllegalArgumentException("NaN");
        return Integer.parseInt(m.group());
    }

    private static boolean toBool(Object value) {
        if (value instanceof Boolean b) return b;
        return "true".equalsIgnoreCase(String.valueOf(value));
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static Map<String, Object> objectBody(Map<String, Object> body) {
        return body == null ? Map.of() : body;
    }
}
