package com.vedicmeet.appserver.admin;

import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.UpdateOptions;
import com.vedicmeet.appserver.cache.CacheService;
import com.vedicmeet.appserver.config.AppConstants;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import com.vedicmeet.appserver.media.MediaUploadService;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Native Java port of the low-risk admin banner/gift/feedback/refer/common-message modules. */
@Service
public class AdminContentService {

    private final MongoTemplate mongo;
    private final MediaUploadService uploads;
    private final CacheService cache;
    private final AppConstants constants;

    public AdminContentService(MongoTemplate mongo, MediaUploadService uploads,
                               CacheService cache, AppConstants constants) {
        this.mongo = mongo;
        this.uploads = uploads;
        this.cache = cache;
        this.constants = constants;
    }

    public Document addBanner(String title, String categoryId, String userType, MultipartFile image) {
        require(categoryId, "CATEGORY_ID_REQUIRE");
        require(userType, "USER_TYPE_REQUIRE");
        if (!List.of("user", "cons", "all").contains(userType)) throw new IllegalArgumentException("INVALID_USER_TYPE");
        if (image == null || image.isEmpty()) throw new IllegalArgumentException("IMAGE_REQUIRE");
        Date now = new Date();
        Document banner = new Document("_id", new ObjectId()).append("title", value(title))
                .append("categoryId", id(categoryId)).append("userType", userType)
                .append("image", uploads.upload(image, "banner")).append("status", true)
                .append("createdAt", now).append("updatedAt", now);
        mongo.getCollection(Collections.BANNERS).insertOne(banner);
        cache.invalidate("banner:list:*");
        return withBannerVirtual(banner);
    }

    public Document updateBanner(String bannerId, String title, String categoryId,
                                 String userType, MultipartFile image) {
        require(bannerId, "BANNER_ID_REQUIRE");
        Document set = new Document("updatedAt", new Date());
        if (title != null) set.append("title", title);
        if (categoryId != null && !categoryId.isBlank()) set.append("categoryId", id(categoryId));
        if (userType != null && !userType.isBlank()) {
            if (!List.of("user", "cons", "all").contains(userType)) throw new IllegalArgumentException("INVALID_USER_TYPE");
            set.append("userType", userType);
        }
        if (image != null && !image.isEmpty()) set.append("image", uploads.upload(image, "banner"));
        Document updated = updateById(Collections.BANNERS, bannerId, set);
        if (updated == null) throw new IllegalStateException("BANNER_NOT_EXIST");
        cache.invalidate("banner:list:*");
        return withBannerVirtual(updated);
    }

    public Map<String, Object> listBanners(int page, int limit, String search,
                                           String status, String userType) {
        Document filter = new Document();
        if (!value(userType).isBlank()) filter.append("userType", userType);
        if (!value(status).isBlank()) filter.append("status", Boolean.parseBoolean(status));
        addSearch(filter, search, List.of("title"));
        List<Document> list = mongo.getCollection(Collections.BANNERS).find(filter)
                .sort(new Document("createdAt", -1)).skip(skip(page, limit)).limit(safeLimit(limit))
                .into(new ArrayList<>());
        list.replaceAll(this::withBannerCategory);
        return page(list, mongo.getCollection(Collections.BANNERS).countDocuments(filter));
    }

    public Document setBannerStatus(String bannerId, boolean status) {
        Document updated = updateById(Collections.BANNERS, bannerId,
                new Document("status", status).append("updatedAt", new Date()));
        if (updated == null) throw new IllegalStateException("BANNER_NOT_EXIST");
        cache.invalidate("banner:list:*");
        return withBannerVirtual(updated);
    }

    public Document addGift(String title, String coin, MultipartFile icon) {
        require(title, "TITLE_REQUIRE");
        require(coin, "COIN_REQUIRE");
        if (mongo.getCollection(Collections.GIFTS).find(new Document("title", title)).first() != null)
            throw new IllegalStateException("TITLE_EXIST");
        if (icon == null || icon.isEmpty()) throw new IllegalArgumentException("ICON_REQUIRE");
        Date now = new Date();
        Document gift = new Document("_id", new ObjectId()).append("title", title)
                .append("coin", integer(coin, "INVALID_COIN")).append("icon", uploads.upload(icon, "gift"))
                .append("status", true).append("createdAt", now).append("updatedAt", now);
        mongo.getCollection(Collections.GIFTS).insertOne(gift);
        return withGiftVirtual(gift);
    }

    public Document updateGift(String giftId, String title, String coin, MultipartFile icon) {
        require(giftId, "GIFT_ID_REQUIRE");
        if (findById(Collections.GIFTS, giftId) == null) throw new IllegalStateException("GIFT_NOT_EXIST");
        if (title != null && mongo.getCollection(Collections.GIFTS).find(new Document("_id",
                new Document("$ne", id(giftId))).append("title", title)).first() != null)
            throw new IllegalStateException("TITLE_EXIST");
        Document set = new Document("updatedAt", new Date());
        if (title != null) set.append("title", title);
        if (coin != null && !coin.isBlank()) set.append("coin", integer(coin, "INVALID_COIN"));
        if (icon != null && !icon.isEmpty()) set.append("icon", uploads.upload(icon, "gift"));
        return withGiftVirtual(updateById(Collections.GIFTS, giftId, set));
    }

    public Map<String, Object> listGifts(int page, int limit, String search) {
        Document filter = new Document();
        addSearch(filter, search, List.of("title"));
        List<Document> list = mongo.getCollection(Collections.GIFTS).find(filter)
                .sort(new Document("createdAt", -1)).skip(skip(page, limit)).limit(safeLimit(limit))
                .into(new ArrayList<>());
        list.replaceAll(this::withGiftVirtual);
        return page(list, mongo.getCollection(Collections.GIFTS).countDocuments(filter));
    }

    public Document setGiftStatus(String giftId, boolean status) {
        Document updated = updateById(Collections.GIFTS, giftId,
                new Document("status", status).append("updatedAt", new Date()));
        if (updated == null) throw new IllegalStateException("GIFT_NOT_EXIST");
        return withGiftVirtual(updated);
    }

    public Map<String, Object> listFeedback(int page, int limit, String search, String userType) {
        Document filter = new Document();
        if (!value(userType).isBlank()) filter.append("userType", userType);
        addSearch(filter, search, List.of("review", "feedback_type"));
        List<Document> list = mongo.getCollection(Collections.FEEDBACKS).find(filter)
                .sort(new Document("createdAt", -1)).skip(skip(page, limit)).limit(safeLimit(limit))
                .into(new ArrayList<>());
        for (Document feedback : list) {
            Document user = findById(Collections.USERS, feedback.get("userId"));
            Document consultant = findById(Collections.CONSULTANTS, feedback.get("consultantId"));
            if (user != null) feedback.put("userDetails", actorProjection(user));
            if (consultant != null) feedback.put("consultantDetails", actorProjection(consultant));
        }
        return page(list, mongo.getCollection(Collections.FEEDBACKS).countDocuments(filter));
    }

    public void setFeedbackStatus(String feedbackId, boolean status) {
        if (updateById(Collections.FEEDBACKS, feedbackId,
                new Document("status", status).append("updatedAt", new Date())) == null)
            throw new IllegalStateException("FEEDBACK_NOT_EXIST");
    }

    public Map<String, Object> listReferrals(int page, int limit, String search,
                                             String userType, String referralDate) {
        Document filter = new Document();
        if (!value(userType).isBlank()) filter.append("userType", userType);
        if (!value(referralDate).isBlank()) {
            LocalDate day = LocalDate.parse(referralDate);
            Instant start = day.atStartOfDay().toInstant(ZoneOffset.UTC);
            filter.append("createdAt", new Document("$gte", Date.from(start))
                    .append("$lt", Date.from(start.plusSeconds(86400))));
        }
        addSearch(filter, search, List.of("referCode"));
        List<Document> list = mongo.getCollection(Collections.REFERS).find(filter)
                .sort(new Document("createdAt", -1)).skip(skip(page, limit)).limit(safeLimit(limit))
                .into(new ArrayList<>());
        String actorCollection = "cons".equals(userType) ? Collections.CONSULTANTS : Collections.USERS;
        for (Document refer : list) {
            refer.put("referBy", actorProjection(findById(actorCollection, refer.get("referBy"))));
            refer.put("referTo", actorProjection(findById(actorCollection, refer.get("referTo"))));
        }
        return page(list, mongo.getCollection(Collections.REFERS).countDocuments(filter));
    }

    public void approveReferral(String referId) {
        Document current = findById(Collections.REFERS, referId);
        if (current == null) throw new IllegalStateException("REFER_NOT_EXIST");
        if (number(current.get("referStatus")) == 2) throw new IllegalStateException("REFER_ALREADy_APPROVED");
        updateById(Collections.REFERS, referId,
                new Document("referStatus", 2).append("updatedAt", new Date()));
    }

    public Document putCommonMessages(Object messages) {
        Date now = new Date();
        return mongo.getCollection(Collections.COMMON_MESSAGES).findOneAndUpdate(new Document(),
                new Document("$set", new Document("messages", messages).append("createdAt", now))
                        .append("$setOnInsert", new Document("_id", new ObjectId())),
                new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER));
    }

    public Document getCommonMessages() {
        return mongo.getCollection(Collections.COMMON_MESSAGES).find().sort(new Document("createdAt", -1)).first();
    }

    private Document withBannerCategory(Document source) {
        Document result = withBannerVirtual(source);
        Document category = findById(Collections.CATEGORIES, source.get("categoryId"));
        if (category != null) result.put("categoryId", new Document("_id", category.get("_id"))
                .append("title", category.get("title")).append("status", category.get("status")));
        return result;
    }

    private Document withBannerVirtual(Document source) {
        if (source == null) return null;
        Document result = new Document(source);
        String image = value(source.get("image"));
        result.put("bannerImage", image.isBlank() ? "" : constants.mediaUrl + image);
        result.put("id", value(source.get("_id")));
        return result;
    }

    private Document withGiftVirtual(Document source) {
        if (source == null) return null;
        Document result = new Document(source);
        String icon = value(source.get("icon"));
        result.put("giftIcon", icon.isBlank() ? "" : constants.mediaUrl + icon);
        result.put("id", value(source.get("_id")));
        return result;
    }

    private Document actorProjection(Document actor) {
        if (actor == null) return null;
        String name = value(actor.get("userName"));
        if (name.isBlank()) name = value(actor.get("name"));
        String image = value(actor.get("image"));
        if (image.isBlank()) image = value(actor.get("profileImage"));
        return new Document("_id", actor.get("_id")).append("name", name)
                .append("email", actor.get("email")).append("address", actor.get("address"))
                .append("profileImage", image.isBlank() ? "" : constants.mediaUrl + image);
    }

    private Document updateById(String collection, Object rawId, Document set) {
        return mongo.getCollection(collection).findOneAndUpdate(new Document("_id", id(rawId)),
                new Document("$set", set), new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
    }

    private Document findById(String collection, Object rawId) {
        if (rawId == null) return null;
        Object identifier = id(rawId);
        Document found = mongo.getCollection(collection).find(new Document("_id", identifier)).first();
        if (found == null && identifier instanceof ObjectId) {
            found = mongo.getCollection(collection).find(new Document("_id", value(rawId))).first();
        }
        return found;
    }

    private void addSearch(Document filter, String search, List<String> fields) {
        if (value(search).isBlank()) return;
        List<Document> terms = fields.stream().map(field -> new Document(field,
                new Document("$regex", ".*" + java.util.regex.Pattern.quote(search.trim()) + ".*")
                        .append("$options", "i"))).toList();
        if (terms.size() == 1) filter.putAll(terms.get(0)); else filter.append("$or", terms);
    }

    private Map<String, Object> page(List<Document> list, long total) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", list);
        result.put("total", total);
        return result;
    }

    private int skip(int page, int limit) { return (Math.max(1, page) - 1) * safeLimit(limit); }
    private int safeLimit(int limit) { return Math.max(1, Math.min(limit <= 0 ? 10 : limit, 100)); }
    private int integer(String value, String error) {
        try { return Integer.parseInt(value); } catch (NumberFormatException ex) { throw new IllegalArgumentException(error); }
    }
    private int number(Object value) {
        if (value instanceof Number number) return number.intValue();
        try { return Integer.parseInt(String.valueOf(value)); } catch (Exception ignored) { return 0; }
    }
    private Object id(Object raw) {
        if (raw instanceof ObjectId) return raw;
        String value = value(raw);
        return ObjectId.isValid(value) ? new ObjectId(value) : raw;
    }
    private void require(String value, String error) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(error);
    }
    private String value(Object value) { return value == null ? "" : String.valueOf(value); }
}
