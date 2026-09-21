package com.vedicmeet.appserver.social;

import com.mongodb.client.MongoCollection;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Prompt C writes from the Node /user module (utils/classes/user.js):
 *   - likeLiveEvent (L4512) → PUT /v2/user/live_like
 *   - followCons    (L4556) → POST /v2/user/follow
 *
 * Both are toggle-style writes. Ids that Mongoose stores as ObjectId (follows.userId/consultantId,
 * user_cons_rels.userId/consId, broadcasts._id, comments.broadcastId/userId) are cast explicitly —
 * the native driver does NOT apply Mongoose schema casting, so a raw String would silently fail to
 * match ObjectId-typed fields.
 *
 * Scope note: followCons multiplexes on input.type. FOLLOW, COUNT and the faceted FOLLOWLIST read
 * are all ported. Unknown types return the empty {count:0,list:[],total:0} shape exactly like
 * Node's `default` branch.
 *
 * Diff-pending: enable on /v2 only after the contract-harness diff (and a doc-assertion on the
 * resulting documents) is clean on the TEST server.
 */
@Service
public class SocialWriteService {

    private final MongoTemplate mongo;
    private final com.vedicmeet.appserver.config.AppConstants constants;

    public SocialWriteService(MongoTemplate mongo, com.vedicmeet.appserver.config.AppConstants constants) {
        this.mongo = mongo;
        this.constants = constants;
    }

    /**
     * likeLiveEvent(input, user) — toggle the caller's like on a broadcast. On like (not already
     * liked) a comment "&lt;name&gt; liked event" is created. Node returns undefined.
     */
    public void likeLiveEvent(Map<String, Object> input, Document user) {
        ObjectId broadcastId = new ObjectId(str(input.get("broadcastId")));
        ObjectId userId = user.getObjectId("_id");

        Document broadcastExist = col(Collections.BROADCASTS).find(new Document("_id", broadcastId)).first();
        if (broadcastExist == null) throw new RuntimeException("BROADCAST_NOT_EXIST");

        Document isLiked = col(Collections.BROADCASTS)
                .find(new Document("_id", broadcastId).append("likes", new Document("$in", Arrays.asList(userId)))).first();

        if (isLiked != null) {
            col(Collections.BROADCASTS).updateOne(new Document("_id", broadcastId),
                    new Document("$pull", new Document("likes", userId)));
        } else {
            col(Collections.BROADCASTS).updateOne(new Document("_id", broadcastId),
                    new Document("$push", new Document("likes", userId)));
            Document comment = new Document("broadcastId", broadcastId)
                    .append("userId", userId)
                    .append("comment", strOrEmpty(user.get("name")) + " liked event");
            col(Collections.COMMENTS).insertOne(comment);
        }
    }

    /**
     * followCons(input) — input carries userId, consultantId, type (+ optional broadcastId).
     * Returns { count, list, total } like Node.
     */
    public Map<String, Object> followCons(Map<String, Object> input) {
        long countFollow = 0;
        List<Document> list = new java.util.ArrayList<>();
        long total = 0;

        String type = str(input.get("type"));
        ObjectId userId = oid(input.get("userId"));
        ObjectId consultantId = oid(input.get("consultantId"));

        switch (type == null ? "" : type) {
            case "FOLLOW": {
                boolean followStatus = false;
                Document params = new Document("userId", userId).append("consultantId", consultantId);
                Document consRelFilter = new Document("userId", userId).append("consId", consultantId);

                Document userFollowExist = col(Collections.FOLLOWS).find(params).first();
                Document userConsRelExist = col(Collections.USER_CONS_RELS).find(consRelFilter).first();

                if (userFollowExist != null) {
                    if (Boolean.TRUE.equals(userFollowExist.getBoolean("status"))) {
                        col(Collections.FOLLOWS).updateOne(params,
                                new Document("$set", new Document("status", false)));
                        col(Collections.USER_CONS_RELS).updateOne(consRelFilter,
                                new Document("$set", new Document("isUserFollowed", false)));
                    } else {
                        col(Collections.FOLLOWS).updateOne(params,
                                new Document("$set", new Document("status", true)));
                        followStatus = true;
                        col(Collections.USER_CONS_RELS).updateOne(consRelFilter,
                                new Document("$set", new Document("isUserFollowed", true)));
                    }
                } else {
                    // Mongoose create sets status default true.
                    col(Collections.FOLLOWS).insertOne(new Document(params).append("status", true));
                    if (userConsRelExist == null) {
                        col(Collections.USER_CONS_RELS).insertOne(new Document("userId", userId)
                                .append("consId", consultantId).append("isUserFollowed", true));
                    }
                }

                Document countParams = new Document(params).append("status", true);
                countFollow = col(Collections.FOLLOWS).countDocuments(countParams);

                // consDetails lookup + broadcast-comment branch:
                // Node has a latent bug here — `let user = await user.findOne(...)` references `user`
                // in its own TDZ, so the `if (broadcastId && followStatus)` branch throws before it
                // can create a comment. The effective behaviour is "no comment is written", which we
                // reproduce by intentionally NOT writing it. (Documented for the reviewer.)
                break;
            }
            case "COUNT": {
                Document query = new Document("userId", userId)
                        .append("consultantId", consultantId).append("status", true);
                List<Document> followList = col(Collections.FOLLOWS).aggregate(Arrays.asList(
                        new Document("$match", query),
                        new Document("$count", "total")
                )).into(new java.util.ArrayList<>());
                if (!followList.isEmpty()) {
                    Number t = followList.get(0).get("total", Number.class);
                    countFollow = t == null ? 0 : t.longValue();
                }
                break;
            }
            case "FOLLOWLIST": {
                // FAITHFUL port of the faceted read (Node user.js followCons FOLLOWLIST, L3296-3420).
                int page = intOf(input.get("page"), 1);
                int limit = intOf(input.get("limit"), 10);
                int skipIndex = (page - 1) * limit;
                String search = input.get("search") == null ? "" : str(input.get("search"));
                String userType = str(input.get("userType"));

                // Node: user → {userId, status:true}; else → {consultantId} (status is commented out).
                Document query = "user".equals(userType)
                        ? new Document("userId", userId).append("status", true)
                        : new Document("consultantId", consultantId);

                Document searchDate = new Document();
                if (search != null && !search.isEmpty()) {
                    searchDate.append("userId.name",
                            new Document("$regex", ".*" + search + ".*").append("$options", "i"));
                }

                List<Document> pipeline = new java.util.ArrayList<>();
                pipeline.add(new Document("$match", query));
                pipeline.add(followListConsultantLookup());
                pipeline.add(followListUserLookup());
                pipeline.add(new Document("$unwind", "$consultantId"));
                pipeline.add(new Document("$unwind", "$userId"));
                pipeline.add(new Document("$facet", new Document()
                        .append("listFollow", Arrays.asList(
                                new Document("$match", searchDate),
                                new Document("$sort", new Document("createdAt", -1)),
                                new Document("$skip", skipIndex),
                                new Document("$limit", limit)))
                        .append("count", Arrays.asList(new Document("$count", "total")))));

                List<Document> res = col(Collections.FOLLOWS).aggregate(pipeline)
                        .into(new java.util.ArrayList<>());
                if (!res.isEmpty()) {
                    List<Document> listFollow = res.get(0).getList("listFollow", Document.class);
                    list = listFollow != null ? listFollow : new java.util.ArrayList<>();
                    List<Document> cnt = res.get(0).getList("count", Document.class);
                    if (cnt != null && !cnt.isEmpty()) {
                        Number t = cnt.get(0).get("total", Number.class);
                        total = t == null ? 0 : t.longValue();
                    }
                }
                break;
            }
            default:
                break;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", countFollow);
        result.put("list", list);
        result.put("total", total);
        return result;
    }

    private MongoCollection<Document> col(String name) {
        return mongo.getCollection(name);
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private String strOrEmpty(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private ObjectId oid(Object o) {
        String s = str(o);
        return (s == null || s.isBlank()) ? null : new ObjectId(s);
    }

    private int intOf(Object o, int fallback) {
        if (o == null) return fallback;
        if (o instanceof Number) return ((Number) o).intValue();
        try {
            return Integer.parseInt(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Verbatim consultants $lookup from Node FOLLOWLIST (L3316-3361), __MEDIA_URL__ substituted. */
    Document followListConsultantLookup() {
        String json = "{\"$lookup\":{\"from\":\"consultants\",\"let\":{\"consultantId\":\"$consultantId\"},"
                + "\"pipeline\":["
                + "{\"$match\":{\"$expr\":{\"$and\":[{\"$eq\":[\"$$consultantId\",\"$_id\"]},{\"$eq\":[\"$isDeleted\",false]}]}}},"
                + "{\"$project\":{\"name\":{\"$cond\":[{\"$eq\":[\"$userName\",\"\"]},\"$name\",\"$userName\"]},"
                + "\"accountName\":1,\"userId\":1,\"email\":1,"
                + "\"profileImage\":{\"$cond\":[{\"$eq\":[\"$profileImage\",\"\"]},\"\","
                + "{\"$cond\":[{\"$regexMatch\":{\"input\":\"$profileImage\",\"regex\":\"^https\"}},\"$profileImage\","
                + "{\"$concat\":[\"__MEDIA_URL__\",\"$profileImage\"]}]}]}}}"
                + "],\"as\":\"consultantId\"}}";
        return Document.parse(json.replace("__MEDIA_URL__", constants.mediaUrl));
    }

    /** Verbatim users $lookup from Node FOLLOWLIST (L3363-3399); Node's {@code emial} typo preserved. */
    Document followListUserLookup() {
        String json = "{\"$lookup\":{\"from\":\"users\",\"let\":{\"userId\":\"$userId\"},"
                + "\"pipeline\":["
                + "{\"$match\":{\"$expr\":{\"$and\":[{\"$eq\":[\"$$userId\",\"$_id\"]},{\"$eq\":[\"$isDeleted\",false]}]}}},"
                + "{\"$project\":{\"name\":1,\"emial\":1,\"userId\":1,"
                + "\"profileImage\":{\"$cond\":[{\"$eq\":[\"$image\",\"\"]},\"\","
                + "{\"$cond\":[{\"$regexMatch\":{\"input\":\"$image\",\"regex\":\"^https\"}},\"$image\","
                + "{\"$concat\":[\"__MEDIA_URL__\",\"$image\"]}]}]}}}"
                + "],\"as\":\"userId\"}}";
        return Document.parse(json.replace("__MEDIA_URL__", constants.mediaUrl));
    }
}
