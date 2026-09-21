package com.vedicmeet.appserver.admin;

import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
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

@Service
public class AdminMasterService {

    // FAITHFUL(node): master schema paths (utils/models/master-model.js); Mongoose strict mode persists only these.
    private static final List<String> MASTER_FIELDS = List.of(
            "taskRepeats", "taskEarlyReminder", "vastuCategory", "vastuTitle", "coins", "problems",
            "applicationDetails", "vastuLogic", "graphology", "payment", "charges", "feedbackPrefieldData",
            "masterCoupon", "consDashboadScore", "supportNumber");

    private final MongoTemplate mongo;
    private final AdminMongoSupport support;
    private final AppConstants constants;
    private final CacheService cache;

    public AdminMasterService(MongoTemplate mongo, AdminMongoSupport support, AppConstants constants, CacheService cache) {
        this.mongo = mongo;
        this.support = support;
        this.constants = constants;
        this.cache = cache;
    }

    public Map<String, Object> list(Map<String, String> query, String adminId) {
        // FAITHFUL: master.js adminList(req) ignores req and rebuilds every master list (no cache on the admin path).
        List<Document> categoryList = aggregate(Collections.CATEGORIES,
                Arrays.asList(match(new Document("status", true)), projectTitleImage(false), sortCreatedAtDesc()));
        List<Document> musicCategorylist = aggregate(Collections.MUSICS,
                Arrays.asList(match(new Document("status", true)), projectTitleImage(false), sortCreatedAtDesc()));
        List<Document> meditationCategoryList = aggregate(Collections.MEDITATION_CATEGORY,
                Arrays.asList(match(new Document("status", true)), projectTitleImage(false), sortCreatedAtDesc()));
        Document vedicPranayam = coll(Collections.MEDITATION_MEDIA)
                .find(new Document("type", 1).append("status", true)).first();
        List<Document> skillList = aggregate(Collections.SKILLS,
                Arrays.asList(match(new Document("status", true)), sortCreatedAtDesc(), projectTitleImage(true)));
        List<Document> languageList = find(Collections.LANGUAGES, new Document("status", true),
                new Document("title", 1).append("createdAt", 1), new Document("createdAt", -1));
        List<Document> giftList = find(Collections.GIFTS, new Document("status", true), null, null);
        Document masterData = coll(Collections.MASTERS).find(new Document())
                .projection(new Document("_id", 0).append("__v", 0).append("vastuLogic", 0)
                        .append("applicationDetails", 0).append("Vimshottari", 0).append("vimShotriDasha", 0))
                .first();
        // FAITHFUL(node-quirk): admin recharge query has no platform filter and omits discountPercentage (master.js adminList).
        List<Document> rechargeList = find(Collections.MEMBERSHIP_DISCOUNTS,
                new Document("status", true).append("type", "recharge"),
                new Document("rechargeAmount", 1).append("rechargeCoins", 1).append("rechargeDiscount", 1)
                        .append("rechargeOfferExpiry", 1).append("applicableOnConsultationComplete", 1),
                new Document("createdAt", -1));
        for (Document item : rechargeList) {
            if (item.containsKey("rechargeOfferExpiry") && item.get("rechargeOfferExpiry") == null) {
                item.remove("rechargeOfferExpiry");
            }
        }
        List<Document> membershipPlans = find(Collections.MEMBERSHIP_DISCOUNTS,
                new Document("type", "membership"), null, null);
        List<Document> communityList = find(Collections.COMMUNITIES, new Document("status", true), null, null);
        for (Document community : communityList) {
            @SuppressWarnings("unchecked")
            List<Document> members = (List<Document>) community.get("member");
            if (members == null) {
                continue;
            }
            List<Object> userIds = new ArrayList<>();
            for (Document m : members) {
                userIds.add(m.get("user"));
            }
            List<Document> memberUsers = find(Collections.USERS,
                    new Document("_id", new Document("$in", userIds)), new Document("name", 1), null);
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

    public Map<String, Object> addEdit(Map<String, Object> body, String adminId) {
        Map<String, Object> input = body == null ? Map.of() : body;
        Object type = input.get("type");
        if ("add".equals(type)) {
            // FAITHFUL: masterModel.create(input) keeps only schema paths and applies array/supportNumber defaults.
            Document doc = pick(input);
            doc.putIfAbsent("taskRepeats", new ArrayList<>());
            doc.putIfAbsent("taskEarlyReminder", new ArrayList<>());
            doc.putIfAbsent("vastuCategory", new ArrayList<>());
            doc.putIfAbsent("vastuTitle", new ArrayList<>());
            doc.putIfAbsent("coins", new ArrayList<>());
            doc.putIfAbsent("problems", new ArrayList<>());
            doc.putIfAbsent("feedbackPrefieldData", new ArrayList<>());
            doc.putIfAbsent("supportNumber", "");
            doc.put("_id", new ObjectId());
            coll(Collections.MASTERS).insertOne(doc);
            return doc;
        }
        if ("edit".equals(type)) {
            // FAITHFUL: strict-mode $set persists only schema paths; type/masterId are dropped (master.js addEdit).
            Document set = pick(input);
            Document updated = coll(Collections.MASTERS).findOneAndUpdate(
                    new Document("_id", support.id(input.get("masterId"))),
                    new Document("$set", set),
                    new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
            cache.invalidate("master:data:*");
            return updated;
        }
        // FAITHFUL(node-quirk): switch default returns undefined (result key resolves to null via the envelope).
        return null;
    }

    public Map<String, Object> addVimshotri(Map<String, Object> body, String adminId) {
        // FAITHFUL(node-bug): master.js route calls MasterService.addVimshotri, which is never defined -> TypeError -> 500.
        throw new IllegalStateException("MasterService.addVimshotri is not a function");
    }

    public Map<String, Object> clear(Map<String, String> query, String adminId) {
        // FAITHFUL(node-bug): master.js registers GET /clear twice; the first (queueClear) wins and is undefined -> TypeError -> 500.
        throw new IllegalStateException("MasterService.queueClear is not a function");
    }

    public Map<String, Object> getCategory(Map<String, String> query, String adminId) {
        // FAITHFUL(node-bug): master.js route calls MasterService.getCategory, which is never defined -> TypeError -> 500.
        throw new IllegalStateException("MasterService.getCategory is not a function");
    }

    private Document pick(Map<String, Object> body) {
        Document out = new Document();
        for (String field : MASTER_FIELDS) {
            if (body.containsKey(field)) {
                out.put(field, body.get(field));
            }
        }
        return out;
    }

    private MongoCollection<Document> coll(String name) {
        return mongo.getCollection(name);
    }

    private List<Document> aggregate(String collection, List<Document> pipeline) {
        return coll(collection).aggregate(pipeline).into(new ArrayList<>());
    }

    private List<Document> find(String collection, Document filter, Document projection, Document sort) {
        FindIterable<Document> it = coll(collection).find(filter);
        if (projection != null) {
            it = it.projection(projection);
        }
        if (sort != null) {
            it = it.sort(sort);
        }
        return it.into(new ArrayList<>());
    }

    private Document match(Document criteria) {
        return new Document("$match", criteria);
    }

    private Document sortCreatedAtDesc() {
        return new Document("$sort", new Document("createdAt", -1));
    }

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
