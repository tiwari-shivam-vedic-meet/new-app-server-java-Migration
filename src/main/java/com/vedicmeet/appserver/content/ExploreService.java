package com.vedicmeet.appserver.content;

import com.vedicmeet.appserver.cache.CacheService;
import com.vedicmeet.appserver.config.AppConstants;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Faithful port of Node ExploreService.listExplore (utils/classes/explore.js),
 * served by GET /v1/explore (authed: user OR consultant).
 *
 * The heavy, static pipeline stages (consultant/comments lookups, media URL rewrite,
 * final projection) are the EXACT Mongo JSON from Node, parsed via Document.parse with
 * only the media base URL templated in. The caller-specific stages (the $match filter
 * and the isLiked/isBookmark checks, which embed user._id as an ObjectId) are built
 * programmatically. This maximizes fidelity while keeping the dynamic parts correct.
 *
 * Same cache rule as Node: cache only when there is no search, 5-minute TTL, keyed by
 * user id. Same { list, total } result via a $facet.
 */
@Service
public class ExploreService {

    private static final long CACHE_TTL_SECONDS = 5 * 60; // 5 minutes, as in Node

    private final MongoTemplate mongo;
    private final CacheService cache;
    private final AppConstants constants;

    public ExploreService(MongoTemplate mongo, CacheService cache, AppConstants constants) {
        this.mongo = mongo;
        this.cache = cache;
        this.constants = constants;
    }

    // ---- Prompt C write (utils/classes/explore.js action L1630). Diff-pending. ----

    /**
     * action(input, user) — the explore engagement multiplexer. Each branch is an idempotent
     * toggle (find-then-$push-or-$pull) except comments/reply which always append. No idempotency
     * guard exists in Node beyond the find-first check; none added.
     *
     * Node quirks preserved:
     *   - actionType "notRecommand" writes the misspelled DB field "notRecommed".
     *   - "comments" does {@code commentModel.create(input)} with {@code input.userId = user._id};
     *     the NATIVE driver stores every field as provided and does NOT apply Mongoose schema
     *     casting/stripping (e.g. exploreId stays a String). Flagged for the harness doc-assertion.
     */
    public void action(Map<String, Object> input, Document user) {
        ObjectId userId = user.getObjectId("_id");
        ObjectId exploreId = new ObjectId(str(input.get("exploreId")));

        Document exploreExist = mongo.getCollection(Collections.EXPLORES)
                .find(new Document("_id", exploreId)).first();
        if (exploreExist == null) throw new RuntimeException("EXPLORE_NOT_EXIST");

        String actionType = str(input.get("actionType"));
        switch (actionType == null ? "" : actionType) {
            case "like": {
                Document liked = col(Collections.EXPLORES)
                        .find(new Document("_id", exploreId).append("likes.userId", userId)).first();
                if (liked != null) {
                    col(Collections.EXPLORES).updateOne(
                            new Document("_id", exploreId).append("likes.userId", userId),
                            new Document("$pull", new Document("likes", new Document("userId", userId))));
                } else {
                    col(Collections.EXPLORES).updateOne(new Document("_id", exploreId),
                            new Document("$push", new Document("likes", new Document("userId", userId))));
                }
                break;
            }
            case "notIntrest":
                toggleMembership(Collections.EXPLORES, exploreId, "notIntrest", userId);
                break;
            case "notRecommand": // Node field is the misspelled "notRecommed"
                toggleMembership(Collections.EXPLORES, exploreId, "notRecommed", userId);
                break;
            case "bookmark":
                toggleMembership(Collections.EXPLORES, exploreId, "bookmark", userId);
                break;
            case "comments": {
                Document doc = new Document(input);
                if (doc.get("exploreId") != null) doc.put("exploreId", new ObjectId(str(doc.get("exploreId"))));
                doc.put("userId", userId);
                col(Collections.COMMENTS).insertOne(doc);
                break;
            }
            case "reply": {
                Document reply = new Document("userId", userId)
                        .append("innerComment", input.get("innerComment"))
                        .append("innerCommentTime", new Date());
                col(Collections.COMMENTS).updateOne(
                        new Document("_id", new ObjectId(str(input.get("commentId")))),
                        new Document("$push", new Document("reply", reply)));
                break;
            }
            case "commentLike": {
                ObjectId commentId = new ObjectId(str(input.get("commentId")));
                Document liked = col(Collections.COMMENTS)
                        .find(new Document("_id", commentId).append("likes", new Document("$in", Arrays.asList(userId)))).first();
                if (liked != null) {
                    col(Collections.COMMENTS).updateOne(new Document("_id", commentId),
                            new Document("$pull", new Document("likes", userId)));
                } else {
                    col(Collections.COMMENTS).updateOne(new Document("_id", commentId),
                            new Document("$push", new Document("likes", userId)));
                }
                break;
            }
            default:
                break;
        }
    }

    /** Node pattern for scalar-array membership fields (notIntrest / notRecommed / bookmark). */
    private void toggleMembership(String collection, ObjectId exploreId, String field, ObjectId userId) {
        Document present = col(collection)
                .find(new Document("_id", exploreId).append(field, new Document("$in", Arrays.asList(userId)))).first();
        if (present != null) {
            col(collection).updateOne(
                    new Document("_id", exploreId).append(field, new Document("$in", Arrays.asList(userId))),
                    new Document("$pull", new Document(field, userId)));
        } else {
            col(collection).updateOne(
                    new Document("_id", exploreId).append(field, new Document("$nin", Arrays.asList(userId))),
                    new Document("$push", new Document(field, userId)));
        }
    }

    private com.mongodb.client.MongoCollection<Document> col(String name) {
        return mongo.getCollection(name);
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> listExplore(ObjectId userId, Integer pageParam, Integer limitParam,
                                           String search, String exploreId) {
        int page = pageParam == null ? 1 : pageParam;
        int limit = limitParam == null ? 10 : limitParam;
        int skipIndex = (page - 1) * limit;
        String searchStr = search == null ? "" : search;

        String cacheKey = searchStr.isEmpty()
                ? "explore:list:" + userId.toHexString() + ":" + page + ":" + limit
                : null;
        if (cacheKey != null) {
            Map<String, Object> cached = cache.get(cacheKey, Map.class);
            if (cached != null) {
                return cached;
            }
        }

        // --- $match params (dynamic: embeds user._id) ---
        Document params = new Document("status", true)
                .append("notIntrest", new Document("$nin", Arrays.asList(userId)))
                .append("notRecommed", new Document("$nin", Arrays.asList(userId)));
        if (exploreId != null && !exploreId.isEmpty()) {
            params.append("_id", new ObjectId(exploreId));
        }
        if (!searchStr.isEmpty()) {
            String s = searchStr.trim();
            params.append("$or", Arrays.asList(
                    new Document("title", new Document("$regex", ".*" + s + ".*").append("$options", "i")),
                    new Document("hastag", new Document("$regex", ".*" + s + ".*").append("$options", "i"))));
        }

        List<Document> pipeline = new ArrayList<>();
        pipeline.add(new Document("$match", params));
        pipeline.add(parse(LOOKUP_CONSULTANTS));
        pipeline.add(parse(ADDFIELDS_CONSULTANT));
        pipeline.add(parse(PROJECT_DROP_JOIN));
        pipeline.add(parse(LOOKUP_COMMENTS));
        pipeline.add(isInAddField("isLiked", userId, "$likes.userId"));
        pipeline.add(isInAddField("isBookmark", userId, "$bookmark"));
        pipeline.add(parse(ADDFIELDS_MEDIA));
        pipeline.add(parse(FINAL_PROJECT));
        pipeline.add(new Document("$facet", new Document()
                .append("list", Arrays.asList(
                        new Document("$sort", new Document("createdAt", -1)),
                        new Document("$skip", skipIndex),
                        new Document("$limit", limit)))
                .append("count", Arrays.asList(new Document("$count", "total")))));

        List<Document> facetResult = mongo.getCollection(Collections.EXPLORES)
                .aggregate(pipeline).into(new ArrayList<>());

        List<Document> list = new ArrayList<>();
        long total = 0;
        if (!facetResult.isEmpty()) {
            Document facet = facetResult.get(0);
            List<Document> l = (List<Document>) facet.get("list");
            if (l != null) list = l;
            List<Document> count = (List<Document>) facet.get("count");
            if (count != null && !count.isEmpty()) {
                Number t = count.get(0).get("total", Number.class);
                total = t == null ? 0 : t.longValue();
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", list);
        result.put("total", total);

        if (cacheKey != null) {
            cache.set(cacheKey, result, CACHE_TTL_SECONDS);
        }
        return result;
    }

    /** { $addFields: { <field>: { $cond: { if: { $in: [userId, <arrayPath>] }, then:true, else:false } } } } */
    private Document isInAddField(String field, ObjectId userId, String arrayPath) {
        return new Document("$addFields", new Document(field, new Document("$cond",
                new Document("if", new Document("$in", Arrays.asList(userId, arrayPath)))
                        .append("then", true)
                        .append("else", false))));
    }

    private Document parse(String json) {
        return Document.parse(json.replace("__MEDIA_URL__", constants.mediaUrl));
    }

    // ----- static pipeline stages, verbatim Mongo JSON from Node (media URL templated) -----

    private static final String LOOKUP_CONSULTANTS = """
        { "$lookup": { "from": "consultants", "localField": "consultantId",
          "foreignField": "_id", "as": "_consultantJoin" } }
        """;

    private static final String ADDFIELDS_CONSULTANT = """
        { "$addFields": { "consultant": { "$cond": [
          { "$gt": [ { "$size": { "$ifNull": ["$_consultantJoin", []] } }, 0 ] },
          { "$let": { "vars": { "c": { "$arrayElemAt": [ { "$ifNull": ["$_consultantJoin", []] }, 0 ] } },
            "in": {
              "_id": "$$c._id",
              "name": { "$ifNull": ["$$c.name", "$$c.accountName"] },
              "accountName": "$$c.accountName",
              "profileImage": { "$cond": [
                { "$eq": [ { "$ifNull": ["$$c.profileImage", ""] }, "" ] }, "",
                { "$cond": [
                  { "$or": [
                    { "$eq": [ { "$substrCP": [ { "$toString": "$$c.profileImage" }, 0, 7 ] }, "http://" ] },
                    { "$eq": [ { "$substrCP": [ { "$toString": "$$c.profileImage" }, 0, 8 ] }, "https://" ] }
                  ] },
                  { "$toString": "$$c.profileImage" },
                  { "$concat": ["__MEDIA_URL__", { "$toString": "$$c.profileImage" }] }
                ] }
              ] },
              "greenTick": { "$ifNull": ["$$c.greenTick", false] },
              "expertiseLine": { "$let": { "vars": { "ex": { "$ifNull": ["$$c.expertise", []] } },
                "in": { "$cond": [
                  { "$and": [ { "$isArray": "$$ex" }, { "$gt": [ { "$size": "$$ex" }, 0 ] } ] },
                  { "$arrayElemAt": ["$$ex", 0] },
                  { "$ifNull": ["$$c.PersonalDetails.tag", "Astrologer"] }
                ] } } }
            } } },
          null
        ] } } }
        """;

    private static final String PROJECT_DROP_JOIN = """
        { "$project": { "_consultantJoin": 0 } }
        """;

    private static final String LOOKUP_COMMENTS = """
        { "$lookup": { "from": "comments", "let": { "exploreId": "$_id" },
          "pipeline": [
            { "$match": { "$expr": { "$eq": ["$$exploreId", "$exploreId"] } } },
            { "$lookup": { "from": "users", "localField": "userId", "foreignField": "_id", "as": "user" } },
            { "$unwind": "$user" },
            { "$project": { "_id": 1, "comment": 1, "createdAt": 1,
              "user": { "_id": 1, "name": 1, "profileImage": { "$cond": [
                { "$eq": ["$user.profileImage", ""] }, "",
                { "$concat": ["__MEDIA_URL__", "$user.profileImage"] }
              ] } } } },
            { "$sort": { "createdAt": -1 } }
          ], "as": "commentsList" } }
        """;

    private static final String ADDFIELDS_MEDIA = """
        { "$addFields": { "media": { "$let": {
          "vars": { "mediaItems": { "$cond": [
            { "$isArray": "$media" }, "$media",
            { "$cond": [ { "$ne": ["$media", null] }, ["$media"], [] ] }
          ] } },
          "in": { "$map": { "input": "$$mediaItems", "as": "m", "in": { "$mergeObjects": [ "$$m", {
            "url": { "$cond": [
              { "$eq": [ { "$toString": { "$ifNull": ["$$m.url", ""] } }, "" ] }, "",
              { "$cond": [
                { "$or": [
                  { "$eq": [ { "$substrCP": [ { "$toString": "$$m.url" }, 0, 7 ] }, "http://" ] },
                  { "$eq": [ { "$substrCP": [ { "$toString": "$$m.url" }, 0, 8 ] }, "https://" ] }
                ] },
                { "$toString": "$$m.url" },
                { "$concat": ["__MEDIA_URL__", { "$toString": "$$m.url" }] }
              ] }
            ] },
            "music": { "$let": { "vars": { "mu": { "$ifNull": ["$$m.music", ""] } },
              "in": { "$cond": [
                { "$eq": [ { "$toString": "$$mu" }, "" ] }, "",
                { "$cond": [
                  { "$or": [
                    { "$eq": [ { "$substrCP": [ { "$toString": "$$mu" }, 0, 7 ] }, "http://" ] },
                    { "$eq": [ { "$substrCP": [ { "$toString": "$$mu" }, 0, 8 ] }, "https://" ] }
                  ] },
                  { "$toString": "$$mu" },
                  { "$concat": ["__MEDIA_URL__", { "$toString": "$$mu" }] }
                ] }
              ] } } },
            "playlistUrl": { "$let": { "vars": { "pu": { "$ifNull": ["$$m.playlistUrl", ""] } },
              "in": { "$cond": [
                { "$eq": [ { "$toString": "$$pu" }, "" ] }, "",
                { "$cond": [
                  { "$or": [
                    { "$eq": [ { "$substrCP": [ { "$toString": "$$pu" }, 0, 7 ] }, "http://" ] },
                    { "$eq": [ { "$substrCP": [ { "$toString": "$$pu" }, 0, 8 ] }, "https://" ] }
                  ] },
                  { "$toString": "$$pu" },
                  { "$concat": ["__MEDIA_URL__", { "$toString": "$$pu" }] }
                ] }
              ] } } }
          } ] } } }
        } } } }
        """;

    private static final String FINAL_PROJECT = """
        { "$project": {
          "title": 1, "hastag": 1, "description": 1, "consultantId": 1, "consultant": 1,
          "exploreImage": { "$cond": [ { "$eq": ["$exploreImage", ""] }, "", { "$concat": ["__MEDIA_URL__", "$exploreImage"] } ] },
          "media": 1, "createdAt": 1, "isLiked": 1, "isBookmark": 1,
          "videoThumbnail": { "$cond": [ { "$eq": ["$videoThumbnail", ""] }, "", { "$concat": ["__MEDIA_URL__", "$videoThumbnail"] } ] },
          "totalLikes": { "$size": "$likes" },
          "totalComments": { "$size": "$commentsList" },
          "commentsList": 1
        } }
        """;
}
