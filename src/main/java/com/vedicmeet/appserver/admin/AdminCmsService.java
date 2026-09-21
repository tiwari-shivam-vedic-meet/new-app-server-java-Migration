package com.vedicmeet.appserver.admin;

import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.vedicmeet.appserver.cache.CacheService;
import com.vedicmeet.appserver.config.AppConstants;
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
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Faithful native-driver port of the Node admin CMS module
 * (rest-apis/modules/admin/cms.js + utils/classes/cms.js). Six routes:
 * getCmsDetails, addAndUpdateCms, addContent, editContent, listContent, statusChageContent.
 *
 * The Node routes are called with {@code req.body}/{@code req.query} only — the service's optional
 * {@code files} parameter is ALWAYS undefined. This drives two faithful quirks that are preserved here:
 *   - addContent 'skills' can never upload an image, so after the title check it ALWAYS throws
 *     IMAGE_REQUIRE (the skill is never created via this route).
 *   - editContent 'skills' simply skips the image upload and updates the other fields.
 *
 * Envelope: every route uses the {code,success,message,result} shape and, on error, echoes
 * {@code error.message} with {@code result:{}} — exactly what {@link AdminResponses#execute} renders.
 * Void service methods (addContent/editContent/statusChange) return {@code null}; Node returns
 * {@code undefined} (systemic null-vs-absent gap, accepted across the migration).
 */
@Service
public class AdminCmsService {

    private static final Pattern LEADING_INT = Pattern.compile("^[+-]?\\d+");

    private final MongoTemplate mongo;
    private final AdminMongoSupport support;
    private final CacheService cache;
    private final PushNotificationService push;
    private final AppConstants constants;

    public AdminCmsService(MongoTemplate mongo, AdminMongoSupport support, CacheService cache,
                           PushNotificationService push, AppConstants constants) {
        this.mongo = mongo;
        this.support = support;
        this.cache = cache;
        this.push = push;
        this.constants = constants;
    }

    // ---------------------------------------------------------------- cms doc

    public Object getCmsDetails(Map<String, String> query) {
        String userType = str(query.get("userType"));
        Document params = new Document("userType", userType).append("type", parseIntJs(query.get("type")));
        // Node cache key uses the RAW string type from the query, not the parsed int.
        String cacheKey = "cms:" + userType + ":" + str(query.get("type"));

        // getOrSet: return cached on hit; else fetch, cache for 24h, return.
        Document cached = cache.get(cacheKey, Document.class);
        if (cached != null) return cached;
        Document doc = withIdVirtual(mongo.getCollection(Collections.CMS).find(params).first());
        cache.set(cacheKey, doc, 24L * 60L * 60L); // Node caches even null (re-fetches next time)
        return doc;
    }

    public Object addAndUpdateCms(Map<String, Object> body) {
        String userType = str(body.get("userType"));
        Document params = new Document("userType", userType).append("type", parseIntJs(body.get("type")));

        Document existing = mongo.getCollection(Collections.CMS).find(params).first();
        boolean isUpdate;
        Document cmsData;
        if (existing != null) {
            Document set = cmsWritableFields(body);
            set.append("updatedAt", new Date());
            cmsData = mongo.getCollection(Collections.CMS).findOneAndUpdate(params, new Document("$set", set),
                    new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
            isUpdate = true;
        } else {
            Date now = new Date();
            // Mongoose create(input) with schema defaults for absent fields.
            Document doc = new Document("_id", new ObjectId())
                    .append("userType", body.containsKey("userType") ? userType : "user")
                    .append("title", body.containsKey("title") ? str(body.get("title")) : "About us")
                    .append("type", parseIntJs(body.get("type")))
                    .append("description", body.containsKey("description") ? str(body.get("description")) : "")
                    .append("createdAt", now).append("updatedAt", now);
            mongo.getCollection(Collections.CMS).insertOne(doc);
            cmsData = doc;
            isUpdate = false;
        }

        // Node del key also uses the RAW string type from the body.
        cache.invalidate("cms:" + userType + ":" + str(body.get("type")));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cmsData", withIdVirtual(cmsData));
        result.put("isUpdate", isUpdate);
        return result;
    }

    /** cms schema paths that a request may set: userType, title, type(int), description. */
    private Document cmsWritableFields(Map<String, Object> body) {
        Document set = new Document();
        if (body.containsKey("userType")) set.append("userType", str(body.get("userType")));
        if (body.containsKey("title")) set.append("title", str(body.get("title")));
        if (body.containsKey("type")) set.append("type", parseIntJs(body.get("type")));
        if (body.containsKey("description")) set.append("description", str(body.get("description")));
        return set;
    }

    // ------------------------------------------------------------ add content

    public Object addContent(Map<String, Object> body) {
        String contentType = str(body.get("contentType"));
        switch (contentType) {
            case "notice" -> {
                Date now = new Date();
                Document notice = new Document("_id", new ObjectId());
                if (body.containsKey("userType")) notice.append("userType", str(body.get("userType")));
                notice.append("title", body.containsKey("title") ? str(body.get("title")) : "")
                        .append("status", true)          // schema default
                        .append("isRead", new ArrayList<>()) // schema default []
                        .append("createdAt", now).append("updatedAt", now);
                mongo.getCollection(Collections.NOTICE_BOARDS).insertOne(notice);
                broadcastNoticeToConsultants(notice.get("_id"));
            }
            case "language" -> {
                if (mongo.getCollection(Collections.LANGUAGES)
                        .find(new Document("title", body.get("title"))).first() != null) {
                    throw new IllegalArgumentException("TITLE_EXIST");
                }
                Date now = new Date();
                Document lang = new Document("_id", new ObjectId())
                        .append("userType", body.containsKey("userType") ? str(body.get("userType")) : "cons")
                        .append("title", body.containsKey("title") ? str(body.get("title")) : "")
                        .append("status", true)
                        .append("createdAt", now).append("updatedAt", now);
                mongo.getCollection(Collections.LANGUAGES).insertOne(lang);
            }
            case "skills" -> {
                if (mongo.getCollection(Collections.SKILLS)
                        .find(new Document("title", body.get("title"))).first() != null) {
                    throw new IllegalArgumentException("TITLE_EXIST");
                }
                // FAITHFUL(node-quirk): the route never passes files, so files?.skillImage is undefined and
                // this branch always throws before creating the skill — cms.js:104-109.
                throw new IllegalArgumentException("IMAGE_REQUIRE");
            }
            case "faq" -> {
                Date now = new Date();
                Document faq = new Document("_id", new ObjectId())
                        .append("userType", body.containsKey("userType") ? str(body.get("userType")) : "cons")
                        .append("question", body.containsKey("question") ? str(body.get("question")) : "")
                        .append("answer", body.containsKey("answer") ? str(body.get("answer")) : "")
                        .append("status", true)
                        .append("createdAt", now).append("updatedAt", now);
                mongo.getCollection(Collections.FAQS).insertOne(faq);
            }
            default -> throw new IllegalArgumentException("CONTENT_TYPE_REQUIRE");
        }
        return null;
    }

    /**
     * Port of firebase.sendAllConsultantNotification: push the NOTICE_SEND_TO_CONSULTANT template to
     * every non-deleted consultant's fcm tokens and persist a notification per consultant. Wrapped in a
     * swallowing try/catch like Node; the push seam is itself FCM-gated (no-op when FCM is disabled).
     */
    private void broadcastNoticeToConsultants(Object noticeId) {
        try {
            String title = "Vedic Meet";
            String messageBody = "you receive a new notice from admin";
            Document data = new Document("screen", "NoticeBoardContainer").append("noticeId", noticeId);
            // FAITHFUL(node-quirk): the JS object literal repeats the `device` key, so only the last
            // condition survives -> { isDeleted:false, device: { $ne: { fcmToken: [] } } } — firebase.js.
            Document filter = new Document("isDeleted", false)
                    .append("device", new Document("$ne", new Document("fcmToken", new ArrayList<>())));
            List<Document> consultants = mongo.getCollection(Collections.CONSULTANTS)
                    .find(filter).into(new ArrayList<>());
            for (Document consultant : consultants) {
                for (Object token : fcmTokens(consultant)) {
                    push.sendNotificationAndCons("consultant", str(token), messageBody, data, title, "");
                }
                Date now = new Date();
                mongo.getCollection(Collections.NOTIFICATIONS).insertOne(new Document("_id", new ObjectId())
                        .append("receiverId", consultant.get("_id"))
                        .append("message", messageBody)
                        .append("title", title)
                        .append("userType", "cons")
                        .append("senderType", "system")
                        .append("type", "other")
                        .append("data", data)
                        .append("isRead", false)
                        .append("status", true)
                        .append("createdAt", now).append("updatedAt", now));
            }
        } catch (Exception ignored) {
            // FAITHFUL(node-quirk): sendAllConsultantNotification catches and logs, never rethrows — firebase.js.
        }
    }

    // ----------------------------------------------------------- edit content

    public Object editContent(Map<String, Object> body) {
        String contentType = str(body.get("contentType"));
        switch (contentType) {
            case "notice" -> {
                Document updated = mongo.getCollection(Collections.NOTICE_BOARDS).findOneAndUpdate(
                        new Document("_id", support.id(body.get("noticeId"))),
                        new Document("$set", noticeWritableFields(body).append("updatedAt", new Date())),
                        new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
                if (updated == null) throw new IllegalStateException("NOTICE_NOT_EXIST");
            }
            case "language" -> {
                if (mongo.getCollection(Collections.LANGUAGES).find(new Document("_id",
                        new Document("$nin", List.of(support.id(body.get("languageId")))))
                        .append("title", body.get("title"))).first() != null) {
                    throw new IllegalArgumentException("TITLE_EXIST");
                }
                Document updated = mongo.getCollection(Collections.LANGUAGES).findOneAndUpdate(
                        new Document("_id", support.id(body.get("languageId"))),
                        new Document("$set", languageWritableFields(body).append("updatedAt", new Date())),
                        new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
                if (updated == null) throw new IllegalStateException("LANGUAGE_NOT_EXIST");
            }
            case "skills" -> {
                if (mongo.getCollection(Collections.SKILLS).find(new Document("_id",
                        new Document("$nin", List.of(support.id(body.get("skillsId")))))
                        .append("title", body.get("title"))).first() != null) {
                    throw new IllegalArgumentException("TITLE_EXIST");
                }
                // FAITHFUL(node-quirk): no files on the route, so the image upload is skipped and the
                // remaining fields are updated — cms.js:171-179.
                Document updated = mongo.getCollection(Collections.SKILLS).findOneAndUpdate(
                        new Document("_id", support.id(body.get("skillsId"))),
                        new Document("$set", skillWritableFields(body).append("updatedAt", new Date())),
                        new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
                if (updated == null) throw new IllegalStateException("SKILLS_NOT_EXIST");
            }
            case "faq" -> {
                Document updated = mongo.getCollection(Collections.FAQS).findOneAndUpdate(
                        new Document("_id", support.id(body.get("faqId"))),
                        new Document("$set", faqWritableFields(body).append("updatedAt", new Date())),
                        new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
                if (updated == null) throw new IllegalStateException("FAQ_NOT_EXIST");
            }
            default -> throw new IllegalArgumentException("CONTENT_TYPE_REQUIRE");
        }
        return null;
    }

    // ----------------------------------------------------------- list content

    public Object listContent(Map<String, String> query) {
        int limit = parseIntJs(query.getOrDefault("limit", "10"));
        int page = parseIntJs(query.getOrDefault("page", "1"));
        int skipIndex = (page - 1) * limit;
        Document params = new Document("userType", query.get("userType"));

        String contentType = str(query.get("contentType"));
        String collection = switch (contentType) {
            case "notice" -> Collections.NOTICE_BOARDS;
            case "language" -> Collections.LANGUAGES;
            case "skills" -> Collections.SKILLS;
            case "faq" -> Collections.FAQS;
            default -> throw new IllegalArgumentException("CONTENT_TYPE_REQUIRE");
        };

        List<Document> raw = mongo.getCollection(collection).find(params)
                .sort(new Document("createdAt", -1)).skip(skipIndex).limit(limit).into(new ArrayList<>());
        List<Document> list = new ArrayList<>();
        for (Document doc : raw) {
            Document view = withIdVirtual(doc);
            if ("skills".equals(contentType)) {
                String image = str(doc.get("image"));
                view.put("skillImage", image.isEmpty() ? "" : constants.mediaUrl + image);
            }
            list.add(view);
        }
        long total = mongo.getCollection(collection).countDocuments(params);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", list);
        result.put("total", total);
        return result;
    }

    // --------------------------------------------------------- status change

    public Object statusChangeContent(Map<String, Object> body) {
        String contentType = str(body.get("contentType"));
        switch (contentType) {
            case "notice" -> mongo.getCollection(Collections.NOTICE_BOARDS)
                    .deleteOne(new Document("_id", support.id(body.get("noticeId"))));
            case "language" -> mongo.getCollection(Collections.LANGUAGES).findOneAndUpdate(
                    new Document("_id", support.id(body.get("languageId"))),
                    new Document("$set", new Document("status", toBool(body.get("status"))).append("updatedAt", new Date())),
                    new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
            case "skills" -> mongo.getCollection(Collections.SKILLS).findOneAndUpdate(
                    new Document("_id", support.id(body.get("skillsId"))),
                    new Document("$set", new Document("status", toBool(body.get("status"))).append("updatedAt", new Date())),
                    new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
            case "faq" -> mongo.getCollection(Collections.FAQS).findOneAndUpdate(
                    new Document("_id", support.id(body.get("faqId"))),
                    new Document("$set", new Document("status", toBool(body.get("status"))).append("updatedAt", new Date())),
                    new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
            default -> { /* no-op, mirrors Node default branch */ }
        }
        return null;
    }

    // ---------------------------------------------------------------- helpers

    private Document noticeWritableFields(Map<String, Object> body) {
        Document set = new Document();
        if (body.containsKey("userType")) set.append("userType", str(body.get("userType")));
        if (body.containsKey("title")) set.append("title", str(body.get("title")));
        if (body.containsKey("status")) set.append("status", toBool(body.get("status")));
        return set;
    }

    private Document languageWritableFields(Map<String, Object> body) {
        Document set = new Document();
        if (body.containsKey("userType")) set.append("userType", str(body.get("userType")));
        if (body.containsKey("title")) set.append("title", str(body.get("title")));
        if (body.containsKey("status")) set.append("status", toBool(body.get("status")));
        return set;
    }

    private Document skillWritableFields(Map<String, Object> body) {
        Document set = new Document();
        if (body.containsKey("userType")) set.append("userType", str(body.get("userType")));
        if (body.containsKey("title")) set.append("title", str(body.get("title")));
        if (body.containsKey("status")) set.append("status", toBool(body.get("status")));
        return set;
    }

    private Document faqWritableFields(Map<String, Object> body) {
        Document set = new Document();
        if (body.containsKey("userType")) set.append("userType", str(body.get("userType")));
        if (body.containsKey("question")) set.append("question", str(body.get("question")));
        if (body.containsKey("answer")) set.append("answer", str(body.get("answer")));
        if (body.containsKey("status")) set.append("status", toBool(body.get("status")));
        return set;
    }

    @SuppressWarnings("unchecked")
    private List<Object> fcmTokens(Document consultant) {
        Object device = consultant.get("device");
        Object tokens = device instanceof Document d ? d.get("fcmToken") : null;
        List<Object> out = new ArrayList<>();
        if (tokens instanceof Iterable<?> iterable) iterable.forEach(out::add);
        return out;
    }

    private Document withIdVirtual(Document source) {
        if (source == null) return null;
        Document result = new Document(source);
        result.put("id", str(source.get("_id")));
        return result;
    }

    private static boolean toBool(Object value) {
        if (value instanceof Boolean b) return b;
        return "true".equalsIgnoreCase(String.valueOf(value));
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /** JS parseInt: leading integer of the string, else 0 (NaN-ish, matches unmatched Mongo query). */
    private static int parseIntJs(Object value) {
        if (value == null) return 0;
        Matcher m = LEADING_INT.matcher(String.valueOf(value).trim());
        return m.find() ? Integer.parseInt(m.group()) : 0;
    }
}
