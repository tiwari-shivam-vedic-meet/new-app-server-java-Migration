package com.vedicmeet.appserver.master;

import com.mongodb.client.MongoCollection;
import com.vedicmeet.appserver.cache.CacheService;
import com.vedicmeet.appserver.config.AppConstants;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Faithful port of Node MasterService.list + _fetchMasterData
 * (utils/classes/master.js). Runs the IDENTICAL MongoDB pipelines via the native
 * driver so the response is byte-for-byte the same; no logic changed.
 *
 * Read-only, cached 6h. Called on almost every app open (the highest-value first
 * migration target in the Week-2 plan).
 *
 * Note on cache key: Node keys by `user.userType` (from the full req.user document).
 * Until AuthCache read-through is wired, the userType passed here is null -> "default".
 * This changes only which cache bucket is used, never the response (the payload does
 * not depend on the caller). The response is what the contract harness compares.
 */
@Service
public class MasterService {

    private static final long CACHE_TTL_SECONDS = 6 * 60 * 60; // 6 hours, as in Node

    private final MongoTemplate mongo;
    private final CacheService cache;
    private final AppConstants constants;

    public MasterService(MongoTemplate mongo, CacheService cache, AppConstants constants) {
        this.mongo = mongo;
        this.cache = cache;
        this.constants = constants;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> list(String deviceType, String userType) {
        String device = (deviceType == null || deviceType.isEmpty()) ? "ANDROID" : deviceType;
        String cacheKey = "master:data:" + (userType == null || userType.isEmpty() ? "default" : userType) + ":" + device;

        Map<String, Object> cached = cache.get(cacheKey, Map.class);
        if (cached != null) {
            return cached;
        }

        Map<String, Object> data = fetchMasterData(device);
        cache.set(cacheKey, data, CACHE_TTL_SECONDS);
        return data;
    }

    private Map<String, Object> fetchMasterData(String deviceType) {
        List<Document> categoryList = aggregate(Collections.CATEGORIES,
                Arrays.asList(match(new Document("status", true)),
                        projectTitleImage(false),
                        sortCreatedAtDesc()));

        List<Document> musicCategorylist = aggregate(Collections.MUSICS,
                Arrays.asList(match(new Document("status", true)),
                        projectTitleImage(false),
                        sortCreatedAtDesc()));

        List<Document> meditationCategoryList = aggregate(Collections.MEDITATION_CATEGORY,
                Arrays.asList(match(new Document("status", true)),
                        projectTitleImage(false),
                        sortCreatedAtDesc()));

        Document vedicPranayam = coll(Collections.MEDITATION_MEDIA)
                .find(new Document("type", 1).append("status", true)).first();

        // skills: match -> sort -> project (with skillType), matching Node's stage order
        List<Document> skillList = aggregate(Collections.SKILLS,
                Arrays.asList(match(new Document("status", true)),
                        sortCreatedAtDesc(),
                        projectTitleImage(true)));

        List<Document> languageList = find(Collections.LANGUAGES,
                new Document("status", true),
                new Document("title", 1).append("createdAt", 1),
                new Document("createdAt", -1));

        List<Document> giftList = find(Collections.GIFTS,
                new Document("status", true), null, null);

        Document masterData = coll(Collections.MASTERS)
                .find(new Document())
                .projection(new Document("_id", 0).append("__v", 0)
                        .append("vastuLogic", 0).append("applicationDetails", 0)
                        .append("Vimshottari", 0).append("vimShotriDasha", 0))
                .first();

        List<Document> rechargeList = find(Collections.MEMBERSHIP_DISCOUNTS,
                new Document("status", true).append("type", "recharge").append("platform", deviceType),
                new Document("rechargeAmount", 1).append("rechargeCoins", 1)
                        .append("rechargeDiscount", 1).append("rechargeOfferExpiry", 1)
                        .append("discountPercentage", 1).append("applicableOnConsultationComplete", 1),
                new Document("createdAt", -1));
        // Drop rechargeOfferExpiry when it is explicitly null (Node deletes it).
        for (Document item : rechargeList) {
            if (item.containsKey("rechargeOfferExpiry") && item.get("rechargeOfferExpiry") == null) {
                item.remove("rechargeOfferExpiry");
            }
        }

        List<Document> membershipPlans = find(Collections.MEMBERSHIP_DISCOUNTS,
                new Document("type", "membership"), null, null);

        List<Document> communityList = find(Collections.COMMUNITIES,
                new Document("status", true), null, null);
        // Expand each community's members to { _id, name } (mirrors the Node loop).
        for (Document community : communityList) {
            List<Document> members = (List<Document>) community.get("member");
            if (members == null) {
                continue;
            }
            List<ObjectId> userIds = new ArrayList<>();
            for (Document m : members) {
                Object uid = m.get("user");
                if (uid instanceof ObjectId oid) {
                    userIds.add(oid);
                }
            }
            List<Document> memberUsers = find(Collections.USERS,
                    new Document("_id", new Document("$in", userIds)),
                    new Document("name", 1), null);
            community.put("member", memberUsers);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("categoryList", categoryList);
        result.put("musicCategorylist", musicCategorylist);
        result.put("meditationCategoryList", meditationCategoryList);
        result.put("vedicPranayam", vedicPranayam);
        result.put("skillList", skillList);
        result.put("languageList", languageList);
        result.put("masterData", masterData);
        result.put("giftList", giftList);
        result.put("rechargeList", rechargeList);
        result.put("membershipPlans", membershipPlans);
        result.put("communityList", communityList);
        result.put("rewardUrl", constants.tagRewardsBanner);
        return result;
    }

    // ---- helpers ----

    private MongoCollection<Document> coll(String name) {
        return mongo.getCollection(name);
    }

    private List<Document> aggregate(String collection, List<Document> pipeline) {
        return coll(collection).aggregate(pipeline).into(new ArrayList<>());
    }

    private List<Document> find(String collection, Document filter, Document projection, Document sort) {
        var it = coll(collection).find(filter);
        if (projection != null) it = it.projection(projection);
        if (sort != null) it = it.sort(sort);
        return it.into(new ArrayList<>());
    }

    private Document match(Document criteria) {
        return new Document("$match", criteria);
    }

    private Document sortCreatedAtDesc() {
        return new Document("$sort", new Document("createdAt", -1));
    }

    /**
     * The repeated { title, image: <concat MEDIA_URL when non-empty> } projection.
     * When withSkillType is true, also project skillType (skills list).
     */
    private Document projectTitleImage(boolean withSkillType) {
        Document cond = new Document("$cond", new Document()
                .append("if", new Document("$ne", Arrays.asList("$image", "")))
                .append("then", new Document("$concat", Arrays.asList(constants.mediaUrl, "$image")))
                .append("else", ""));
        Document project = new Document("title", 1).append("image", cond);
        if (withSkillType) {
            project.append("skillType", 1);
        }
        return new Document("$project", project);
    }
}
