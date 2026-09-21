package com.vedicmeet.appserver.session;

import com.vedicmeet.appserver.config.AppConstants;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import com.mongodb.client.MongoCollection;
import com.vedicmeet.appserver.support.ChatServerClient;
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
 * Read-only port of the app-facing reads in
 * {@code rest-apis/modules/user/session.js} (production bf308ee).
 *
 * <p>These methods deliberately read Node's existing collections and document shapes. They do not
 * own timers, mutate waitlists, or invoke external systems, so they can be shadow-compared safely.
 * The waitlist stores user/consultant ids as strings while wallet rows store ObjectIds; that mixed
 * representation is preserved here.</p>
 */
@Service
public class UserSessionReadService {

    private final MongoTemplate mongo;
    private final AppConstants constants;
    private final ChatServerClient chat;

    public UserSessionReadService(MongoTemplate mongo, AppConstants constants, ChatServerClient chat) {
        this.mongo = mongo;
        this.constants = constants;
        this.chat = chat;
    }

    /** Node GET request-forms L213-257. */
    public List<Document> requestForms(String userId) {
        return collection(Collections.WAITLISTS).aggregate(List.of(
                new Document("$match", new Document("user_id", userId)
                        .append("request_form", new Document("$exists", true).append("$ne", null))),
                new Document("$group", new Document("_id", new Document("firstName", "$request_form.firstName")
                        .append("gender", "$request_form.gender")
                        .append("dateOfBirth", "$request_form.dateOfBirth")
                        .append("timeOfBirth", "$request_form.timeOfBirth")
                        .append("placeOfBirth", "$request_form.placeOfBirth")
                        .append("maritalStatus", "$request_form.maritalStatus"))
                        .append("request_form", new Document("$first", "$request_form"))
                        .append("count", new Document("$sum", 1))),
                new Document("$project", new Document("_id", 0).append("request_form", 1).append("count", 1)),
                new Document("$sort", new Document("count", -1))
        )).into(new ArrayList<>());
    }

    /** Node Waitlist.getWaitlistCount L638: completed consultations only when no status is supplied. */
    public long waitlistCount(String userId) {
        return collection(Collections.WAITLISTS).countDocuments(new Document("user_id", userId)
                .append("status", new Document("$in", List.of("completed"))));
    }

    /** Node GET waitlist-status L1235-1344. */
    public Map<String, Object> waitlistStatus(Document user, String platform) {
        String userId = idString(user.get("_id"));
        Object userObjectId = user.get("_id");
        String deviceUuid = latestString(nested(user, "device", "uuid"));

        // Node can accidentally broaden this query when uuid is undefined. Java fails safe: no
        // device means no completed-on-this-device result rather than a cross-user count.
        long completedOnDevice = deviceUuid == null ? 0 : collection(Collections.WAITLISTS)
                .countDocuments(new Document("deviceUsedToken", deviceUuid).append("status", "completed"));
        long walletTopups = collection(Collections.WALLET_TRANSACTIONS)
                .countDocuments(new Document("userId", userObjectId).append("transactionFor", "topup"));
        boolean onCall = collection(Collections.WAITLISTS)
                .countDocuments(new Document("user_id", userId).append("status", "progress")) > 0;

        Document minimum = seed("forFirstEndlessConsultation");
        Document free = seed("android".equals(platform)
                ? "firstFreeConsultantForAndroid" : "firstFreeConsultantForIos");
        Document settings = seed("userMasterSettings");
        Document review = collection(Collections.PLAYSTORE_REVIEW_UPLOADS)
                .find(new Document("userId", userObjectId)).first();

        List<Document> completed = collection(Collections.WAITLISTS).aggregate(List.of(
                new Document("$match", new Document("user_id", userId).append("status", "completed")),
                new Document("$project", new Document("consultant_id", 1).append("createdAt", 1)
                        .append("onCompletion", 1).append("deviceUsedToken", 1)),
                new Document("$sort", new Document("createdAt", -1)),
                new Document("$limit", 4)
        )).into(new ArrayList<>());

        List<Document> canceled = collection(Collections.WAITLISTS).aggregate(List.of(
                new Document("$match", new Document("user_id", userId)
                        .append("status", new Document("$in", List.of("canceled")))),
                new Document("$sort", new Document("createdAt", -1)),
                new Document("$limit", 1),
                new Document("$project", new Document("consultant_id", 1)
                        .append("createdAt", 1).append("status", 1))
        )).into(new ArrayList<>());

        boolean firstChatCanceled = !completed.isEmpty() && deviceUuid != null
                && (deviceUuid + "_previous_taken").equals(completed.get(0).getString("deviceUsedToken"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalWalletTransactions", walletTopups);
        result.put("totalConsultationTaken", completedOnDevice);
        result.put("isFirstChatAlreadyTakenAndCancelledByUser", firstChatCanceled);
        result.put("consultancyAccessbilities", data(free));
        result.put("alreadyTakenConsultancies", completed);
        result.put("canceledORMissedConsultancies", canceled);
        result.put("isUserOnCall", onCall);
        result.put("userMasterSettings", data(settings));
        result.put("isUserAlreadyHasPlaystoreReview", completedOnDevice == 1 ? review != null : true);
        result.put("firstMinimumConsultancyMinutes", data(minimum));
        return result;
    }

    /** Node GET waitlist-history L1405-1462. */
    public List<Document> waitlistHistory(String userId) {
        Document profileImage = new Document("$cond", new Document("if", new Document("$and", List.of(
                new Document("$ne", List.of("$profileImage", "")),
                new Document("$not", List.of(new Document("$regexMatch",
                        new Document("input", "$profileImage").append("regex", "^https?://")))))))
                .append("then", new Document("$concat", List.of(constants.mediaUrl, "$profileImage")))
                .append("else", "$profileImage"));
        return collection(Collections.WAITLISTS).aggregate(List.of(
                new Document("$sort", new Document("createdAt", -1)),
                new Document("$match", new Document("user_id", userId)),
                new Document("$addFields", new Document("consultant_id", new Document("$toObjectId", "$consultant_id"))),
                new Document("$lookup", new Document("from", Collections.CONSULTANTS)
                        .append("localField", "consultant_id").append("foreignField", "_id")
                        .append("pipeline", List.of(new Document("$project", new Document("accountName", 1)
                                .append("profileImage", profileImage).append("isActive", 1)
                                .append("sessionsStatus", 1))))
                        .append("as", "consultant"))
        )).into(new ArrayList<>());
    }

    /** Node GET consultant-follow-up-messages L1346-1403. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> consultantFollowUps(String userId) {
        Map<String, Object> response = chat.consultantFollowUpsForUser(userId);
        if (!Boolean.TRUE.equals(response.get("success")) || !(response.get("data") instanceof List<?> rows)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object raw : rows) {
            if (!(raw instanceof Map<?, ?> source)) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            source.forEach((key, value) -> item.put(String.valueOf(key), value));
            String threadId = item.get("threadId") == null ? null : item.get("threadId").toString();
            Document waitlist = threadId == null ? null : collection(Collections.WAITLISTS)
                    .find(new Document("threadId", threadId).append("user_id", userId)).first();
            if (waitlist != null && waitlist.get("consultant_id") != null
                    && ObjectId.isValid(waitlist.get("consultant_id").toString())) {
                Document consultant = collection(Collections.CONSULTANTS)
                        .find(new Document("_id", new ObjectId(waitlist.get("consultant_id").toString())))
                        .projection(new Document("accountName", 1).append("profileImage", 1)).first();
                if (consultant != null) {
                    if (consultant.get("accountName") != null) item.put("consultantName", consultant.get("accountName"));
                    if (consultant.get("profileImage") != null) item.put("consultantProfileImg", consultant.get("profileImage"));
                }
            }
            Object image = item.get("consultantProfileImg");
            if (image != null && !image.toString().matches("(?i)^https?://.*")) {
                item.put("consultantProfileImg", constants.mediaUrl + image.toString().replaceFirst("^/", ""));
            }
            item.put("waitlistId", waitlist == null ? null : idString(waitlist.get("_id")));
            result.add(item);
        }
        return result;
    }

    /** Node GET get_live_events L1464-1477. */
    public List<Document> liveEvents() {
        return collection(Collections.CONSULTANT_LIVE_EVENTS)
                .find(new Document("status", "live")).into(new ArrayList<>());
    }

    /** Node GET today-quick-query L1973-2052. */
    public Map<String, Object> todayQuickQuery(String userId, String dayKey) {
        List<Document> rows = collection(Collections.WAITLISTS).aggregate(List.of(
                new Document("$match", new Document("user_id", userId)
                        .append("session_info.dayKey", dayKey).append("used_for", "query")),
                new Document("$addFields", new Document("consultant_id_str",
                        new Document("$ifNull", Arrays.asList("$consultant_id", null)))),
                new Document("$lookup", new Document("from", Collections.CONSULTANTS)
                        .append("let", new Document("consultantId", "$consultant_id_str"))
                        .append("pipeline", List.of(new Document("$match", new Document("$expr",
                                new Document("$and", List.of(
                                        new Document("$ne", Arrays.asList("$$consultantId", null)),
                                        new Document("$eq", List.of("$_id",
                                                new Document("$toObjectId", "$$consultantId")))))))))
                        .append("as", "consultant")),
                new Document("$addFields", new Document("consultant", new Document("$cond", List.of(
                        new Document("$eq", Arrays.asList("$consultant_id_str", null)), null,
                        new Document("$arrayElemAt", List.of("$consultant", 0)))))),
                new Document("$project", new Document("consultant_id_str", 0))
        )).into(new ArrayList<>());
        if (rows.isEmpty()) return null;
        Document query = rows.get(0);
        Map<String, Object> result = new LinkedHashMap<>(query);
        result.put("waitlist", query);
        return result;
    }

    /** Node POST get-progress-call L1747-1797. */
    public Map<String, Object> progressCall(String userId) {
        Document waitlist = collection(Collections.WAITLISTS)
                .find(new Document("user_id", userId).append("status", "progress")).first();
        if (waitlist == null) return null;
        Document info = waitlist.get("session_info", Document.class);
        Map<String, Object> permissions = new LinkedHashMap<>();
        permissions.put("canShareFiles", true);
        permissions.put("canUserPost", true);
        permissions.put("canShareAudio", true);
        permissions.put("canShareVideo", false);
        permissions.put("canAccessGallery", true);
        permissions.put("canAccessEndCall", true);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("waitlistId", idString(waitlist.get("_id")));
        result.put("roomId", waitlist.get("threadId")); // roomId in join payload is chat threadId.
        result.put("channel", info == null ? null : info.get("mode"));
        result.put("callStatus", "progress");
        result.put("permissions", permissions);
        return result;
    }

    private Document seed(String key) {
        return collection(Collections.SEED_MASTERS).find(new Document("for", key)).first();
    }

    private Object data(Document seed) { return seed == null ? null : seed.get("data"); }

    private MongoCollection<Document> collection(String name) { return mongo.getCollection(name); }

    private String idString(Object value) { return value == null ? null : value.toString(); }

    private Object nested(Document source, String parent, String child) {
        Object value = source == null ? null : source.get(parent);
        return value instanceof Document document ? document.get(child) : null;
    }

    private String latestString(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) return null;
        Object latest = list.get(list.size() - 1);
        return latest == null ? null : latest.toString();
    }
}
