package com.vedicmeet.appserver.support;

import com.vedicmeet.appserver.config.AppConstants;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import com.vedicmeet.appserver.media.MediaUploadService;
import com.vedicmeet.appserver.notification.PushNotificationService;
import com.mongodb.client.MongoCollection;
import jakarta.annotation.PostConstruct;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Faithful port of:
 *  - Node SupportService.listSupportMaster (utils/classes/support.js) -> GET /v1/support
 *  - the /v1/support/i18n handler (modules/support.js), including appLanguageTree.
 *
 * i18n: version 16, active languages en/hi/kn (te/ta are commented out in Node). The
 * locale strings are loaded from the same JSON files (copied to resources/locales).
 */
@Service
public class SupportService {

    private static final int I18N_VERSION = 16;

    private final MongoTemplate mongo;
    private final AppConstants constants;
    private final ChatServerClient chatServerClient;
    private final PushNotificationService push;
    private final MediaUploadService mediaUploadService;
    private final Map<String, Document> appLanguageTree = new LinkedHashMap<>();

    public SupportService(MongoTemplate mongo, AppConstants constants, ChatServerClient chatServerClient,
                          PushNotificationService push, MediaUploadService mediaUploadService) {
        this.mongo = mongo;
        this.constants = constants;
        this.chatServerClient = chatServerClient;
        this.push = push;
        this.mediaUploadService = mediaUploadService;
    }

    // ---- Prompt C writes (modules/support.js initiate_query L32 + chat_send L228). Diff-pending. ----

    private static final String QUERY_EXIST = "Query is already exist in pending state";
    private static final String QUERY_NOT_EXIST = "Query does not exist";
    private static final String QUERY_ALREADY_RESOLVED = "Query is already resolved";
    private static final String SUPPORT_TEAM_REPLY_ON_QUERY =
            "Your query has received a response from our support team. Please check the reply and let us know if you need further assistance.";

    /**
     * customerQueryInitiate (utils/classes/support.js L507). Creates a support query, two
     * `notificationns` records, fires FCM (seam), then creates the chat-server thread and stores
     * threadId back on the query. Ticket numbers increment off the latest query's `#QUERY_<n>`.
     * Dedup guard: throws QUERY_EXIST if an unresolved query of the same supportType exists.
     */
    public Map<String, Object> customerQueryInitiate(Map<String, Object> input, Document user, String userType) {
        ObjectId callerId = user.getObjectId("_id");
        Document params = new Document("supportType", input.get("supportType")).append("isResolved", false);
        input.put("userType", "user".equals(userType) ? "user" : "cons");
        if ("user".equals(userType)) {
            params.append("userId", callerId);
            input.put("userId", callerId);
        } else {
            params.append("consultantId", callerId);
            input.put("consultantId", callerId);
        }

        Document queryExist = col(Collections.CUSTOMER_SUPPORT_QUERIES).find(params).first();
        if (queryExist != null) throw new RuntimeException(QUERY_EXIST);

        Document last = col(Collections.CUSTOMER_SUPPORT_QUERIES).find()
                .sort(new Document("createdAt", -1)).limit(1).first();
        if (last != null && last.get("ticketNumber") != null) {
            String tn = String.valueOf(last.get("ticketNumber"));
            String[] parts = tn.split("_");
            int n = 0;
            try { n = Integer.parseInt(parts[parts.length - 1]); } catch (Exception ignore) { }
            input.put("ticketNumber", "#QUERY_" + (n + 1));
        } else {
            input.put("ticketNumber", "#QUERY_1");
        }

        String ticketMsg = "Your raised query has been updated. Your Ticket no. is " + input.get("ticketNumber")
                + " . Thank you for reaching Vedic Meet customer support. We'd love to hear your feedback to help"
                + " us improve our customer experience.";
        for (String token : deviceFcmTokens(user)) {
            push.sendNotificationAndCons("consultant".equals(userType) ? "cons" : "user", token, ticketMsg, "SUPPORT");
        }

        col(Collections.NOTIFICATION_RECORDS).insertOne(
                new Document("receiverId", callerId).append("message", ticketMsg));
        col(Collections.NOTIFICATION_RECORDS).insertOne(new Document("userType", "admin")
                .append("senderType", "system")
                .append("message", "You received a new query with the ticket no. " + input.get("ticketNumber")
                        + " in the support Management . "));

        Document queryDoc = new Document(input);
        col(Collections.CUSTOMER_SUPPORT_QUERIES).insertOne(queryDoc);
        String queryId = queryDoc.getObjectId("_id").toHexString();

        Map<String, Object> userPayload = new LinkedHashMap<>();
        userPayload.put("_id", callerId);
        userPayload.put("name", user.get("name"));
        userPayload.put("profileImage", withMediaUrl(user.getString("profileImage")));
        Map<String, Object> adminPayload = new LinkedHashMap<>();
        adminPayload.put("_id", "000000000000000000000000");
        adminPayload.put("name", "Vedicmeet Support");
        adminPayload.put("profileImage", "https://vedicmeet.com/statics/vedic-meet_title-logo.webp");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("user", userPayload);
        payload.put("admin", adminPayload);
        payload.put("ticketNumber", queryId);

        Map<String, Object> threadResponse = chatServerClient.createThreadSupport(payload);
        if (!Boolean.TRUE.equals(threadResponse.get("success"))) throw new RuntimeException("Failed to create thread!");
        Object threadData = threadResponse.get("data");

        col(Collections.CUSTOMER_SUPPORT_QUERIES).updateOne(new Document("_id", queryDoc.getObjectId("_id")),
                new Document("$set", new Document("threadId", threadData)));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("threadId", threadData);
        result.put("isAdminReply", input.get("isAdminReply"));
        result.put("_queryID", queryId);
        return result;
    }

    /**
     * supportChat (utils/classes/support.js L730) = POST /chat_send. Appends a chat message to a
     * query; the admin's FIRST reply also spins up the chat-server thread. Optional media file goes
     * through the S3 seam.
     *
     * Node bug preserved: for the user/consultant path it sets input.userId/consultantId = `user._id`
     * where `user` is the imported MODEL (not `userObj`) → undefined, so those fields are NOT stored.
     * Reproduced by intentionally not setting them.
     */
    public Document supportChat(Map<String, Object> input, Document userObj, String userType,
                                org.springframework.web.multipart.MultipartFile file) {
        Object queryIdRaw = input.get("customerSupportQueryId");
        Document queryExist = col(Collections.CUSTOMER_SUPPORT_QUERIES)
                .find(new Document("_id", toObjectId(queryIdRaw))).first();
        if (queryExist == null) throw new RuntimeException(QUERY_NOT_EXIST);
        if (Boolean.TRUE.equals(queryExist.getBoolean("isResolved"))) throw new RuntimeException(QUERY_ALREADY_RESOLVED);

        boolean isByAdmin = false;
        if (userType != null && !userType.isEmpty()) {
            String qUserType = queryExist.getString("userType");
            input.put("senderType", qUserType != null ? qUserType : "user");
            // Node references `user._id` (the model, undefined) here → userId/consultantId stay unset.
        } else {
            input.put("senderType", "admin");
            isByAdmin = true;
        }

        if (file != null && !file.isEmpty()) {
            String ct = file.getContentType() == null ? "" : file.getContentType();
            String mediaType = ct.contains("/") ? ct.split("/")[0] : ct;
            Document media = new Document("mediaType", mediaType)
                    .append("mediaUrl", mediaUploadService.upload(file, "support/chat"));
            input.put("media", media);
        }

        long isCustomerQuery = col(Collections.CUSTOMER_SUPPORT_CHATS)
                .countDocuments(new Document("customerSupportQueryId", queryExist.get("_id")));

        if (isCustomerQuery == 0 && isByAdmin) {
            Document userDetails = col(Collections.USERS).find(new Document("_id", queryExist.get("consultantId"))).first();
            Document consultantDetails = col(Collections.CONSULTANTS).find(new Document("_id", queryExist.get("consultantId"))).first();
            Map<String, Object> chatUser = new LinkedHashMap<>();
            if (userDetails != null) {
                chatUser.put("id", userDetails.get("_id"));
                chatUser.put("name", userDetails.get("name"));
                chatUser.put("profileImage", userDetails.get("profileImage"));
            }
            if (consultantDetails != null) {
                chatUser.put("id", consultantDetails.get("_id"));
                chatUser.put("name", consultantDetails.get("name"));
                chatUser.put("profileImage", consultantDetails.get("profileImage"));
            }
            Document adminDetails = col(Collections.ADMINS).find(new Document("_id", userObj.get("_id"))).first();
            Map<String, Object> adminObj = new LinkedHashMap<>();
            adminObj.put("id", adminDetails == null ? null : adminDetails.get("_id"));
            adminObj.put("name", adminDetails == null ? null : adminDetails.get("name"));

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("user", chatUser);
            payload.put("admin", adminObj);
            payload.put("title", queryExist.get("title"));
            payload.put("description", queryExist.get("description"));

            Map<String, Object> threadResponse = chatServerClient.createQuery(payload);
            if (!Boolean.TRUE.equals(threadResponse.get("success"))) throw new RuntimeException("Failed to create thread!");
            Object data = threadResponse.get("data");
            if (data instanceof List && !((List<?>) data).isEmpty()) {
                Object first = ((List<?>) data).get(0);
                Object threadId = (first instanceof Map) ? ((Map<?, ?>) first).get("_id") : null;
                col(Collections.CUSTOMER_SUPPORT_QUERIES).updateOne(new Document("_id", queryExist.get("_id")),
                        new Document("$set", new Document("threadId", threadId).append("isAdminReply", true)));
            }
        }

        Document chatDoc = new Document(input);
        Object repliedMessage = input.get("repliedMessage");
        if (repliedMessage instanceof String) {
            chatDoc.put("repliedMessage", Document.parse((String) repliedMessage));
        }
        col(Collections.CUSTOMER_SUPPORT_CHATS).insertOne(chatDoc);

        if (isByAdmin) {
            Document userConsDetails = "user".equals(queryExist.getString("userType"))
                    ? col(Collections.USERS).find(new Document("_id", queryExist.get("userId"))).first()
                    : col(Collections.CONSULTANTS).find(new Document("_id", queryExist.get("consultantId"))).first();
            String message = SUPPORT_TEAM_REPLY_ON_QUERY + " " + queryExist.get("ticketNumber");
            if (userConsDetails != null) {
                for (String token : topFcmTokens(userConsDetails)) {
                    push.sendNotificationAndCons(queryExist.getString("userType"), token, message);
                }
                col(Collections.NOTIFICATION_RECORDS).insertOne(
                        new Document("receiverId", userConsDetails.get("_id")).append("message", message));
            }
        }
        return chatDoc;
    }

    private MongoCollection<Document> col(String name) {
        return mongo.getCollection(name);
    }

    private String withMediaUrl(String profileImage) {
        if (profileImage != null && profileImage.startsWith("https")) return profileImage;
        return constants.mediaUrl + profileImage;
    }

    private ObjectId toObjectId(Object v) {
        if (v instanceof ObjectId) return (ObjectId) v;
        return v == null ? null : new ObjectId(String.valueOf(v));
    }

    /** Node: user.device.fcmToken (nested). */
    @SuppressWarnings("unchecked")
    private List<String> deviceFcmTokens(Document user) {
        Object device = user.get("device");
        if (device instanceof Document) {
            Object tokens = ((Document) device).get("fcmToken");
            if (tokens instanceof List) return (List<String>) tokens;
        }
        return new ArrayList<>();
    }

    /** Node: userConsDetails.fcmToken (top-level). */
    @SuppressWarnings("unchecked")
    private List<String> topFcmTokens(Document doc) {
        Object tokens = doc.get("fcmToken");
        if (tokens instanceof List) return (List<String>) tokens;
        return new ArrayList<>();
    }

    @PostConstruct
    void loadLocales() {
        appLanguageTree.put("en", buildLang("en", "English", "locales/en.json"));
        appLanguageTree.put("hi", buildLang("hi", "Hindi", "locales/hi.json"));
        appLanguageTree.put("kn", buildLang("kn", "Kannada", "locales/kn.json"));
    }

    private Document buildLang(String code, String name, String resourcePath) {
        Document strings;
        try (InputStream in = new ClassPathResource(resourcePath).getInputStream()) {
            strings = Document.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            strings = new Document();
        }
        return new Document("version", I18N_VERSION).append("language", code)
                .append("name", name).append("strings", strings);
    }

    /** GET /v1/support list. Note: route passes no userType, so the TECHNICAL userType
     *  clause is undefined in Node (stripped) and is likewise omitted here. */
    public Map<String, Object> listSupportMaster(String supportType, Integer pageParam, Integer limitParam, String search) {
        int page = pageParam == null ? 1 : pageParam;
        int limit = limitParam == null ? 10 : limitParam;
        int skipIndex = (page - 1) * limit;

        Document params = new Document("supportType", supportType);
        if (search != null && !search.isEmpty()) {
            params.append("question", new Document("$regex", ".*" + search + ".*").append("$options", "i"));
            params.append("description", new Document("$regex", ".*" + search + ".*").append("$options", "i"));
        }

        List<Document> list = mongo.getCollection(Collections.SUPPORT_MASTERS)
                .find(params)
                .projection(new Document("question", 1).append("description", 1)
                        .append("supportType", 1).append("userType", 1))
                .sort(new Document("createdAt", -1)).skip(skipIndex).limit(limit)
                .into(new ArrayList<>());
        long total = mongo.getCollection(Collections.SUPPORT_MASTERS).countDocuments(params);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", list);
        result.put("total", total);
        return result;
    }

    /** null return => controller emits the Node 400 "Invalid type" response. */
    public Map<String, Object> i18n(String type, String languageParam) {
        String language = (languageParam == null || languageParam.isEmpty()) ? "en" : languageParam;
        Map<String, Object> result = new LinkedHashMap<>();

        if ("languages".equals(type) || "version".equals(type)) {
            result.put("languages", availableLanguages());
        } else if ("language".equals(type) || "langauage".equals(type)) {
            Document tree = appLanguageTree.getOrDefault(language, appLanguageTree.get("en"));
            result.put("language", tree);
        } else {
            return null; // invalid type -> HTTP 400 in controller
        }
        return result;
    }

    private List<Map<String, Object>> availableLanguages() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, Document> e : appLanguageTree.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", e.getKey());
            m.put("name", e.getValue().getString("name"));
            m.put("version", e.getValue().getInteger("version"));
            out.add(m);
        }
        return out;
    }
}
