package com.vedicmeet.appserver.consultant;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.vedicmeet.appserver.auth.cache.AuthDocumentCache;
import com.vedicmeet.appserver.call.CallIntegrationOutboxService;
import com.vedicmeet.appserver.config.AppConstants;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import com.vedicmeet.appserver.security.Role;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Consultant-owned profile, notices, warnings, change requests and live-event administration.
 *
 * <p>This is the Spring equivalent of {@code consultant-service.js} lines 568-1535.  It preserves
 * the Mongo field aliases used by the mobile clients while closing the old mass-assignment and
 * actor-spoofing holes: status, approval, wallet and another consultant's id can never be changed
 * through these endpoints.</p>
 */
@Service
public class ConsultantSelfService {

    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    private static final Set<String> PROFILE_FIELDS = Set.of(
            "name", "userName", "accountName", "consType", "language", "primarySkills",
            "otherSkills", "expertise", "avgLiveBrier", "address", "city", "state", "country",
            "pincode", "reasonOfOnboard", "mainSourceIncome", "qualification",
            "highestQualification", "learnAstrologyFrom", "instaLink", "faceBookLink",
            "linkedinLink", "youTubeLink", "foreignCountryNo", "workingFullTimeJob",
            "greaterChallengeAndConquer", "bio", "isRefer", "experienceYear", "dailyWorkHour",
            "hearAboutUs", "otherOnlinePlatformWork", "minimumEarningExpectation",
            "whenHearAboutUs", "onlinePlatformWork", "onlinePlatformName", "otherSkillsDeleted"
    );
    private static final Set<String> LIST_FIELDS = Set.of("language", "primarySkills", "otherSkills", "expertise");
    private static final Set<String> REQUEST_TYPES = Set.of("PRICE", "BANK", "PHONE", "PANCARD");

    private final MongoTemplate mongo;
    private final ObjectMapper mapper;
    private final AuthDocumentCache authCache;
    private final CallIntegrationOutboxService outbox;
    private final AppConstants constants;
    private final int dailyBroadcastLimit;
    private final int priceAmountIncrease;
    private final int pricePercentIncrease;

    public ConsultantSelfService(MongoTemplate mongo, ObjectMapper mapper, AuthDocumentCache authCache,
                                 CallIntegrationOutboxService outbox, AppConstants constants,
                                 @Value("${vedicmeet.consultant.daily-broadcast-limit:500}") int dailyBroadcastLimit,
                                 @Value("${vedicmeet.consultant.price-amount-increase:4}") int priceAmountIncrease,
                                 @Value("${vedicmeet.consultant.price-percent-increase:10}") int pricePercentIncrease) {
        this.mongo = mongo;
        this.mapper = mapper;
        this.authCache = authCache;
        this.outbox = outbox;
        this.constants = constants;
        this.dailyBroadcastLimit = Math.max(0, dailyBroadcastLimit);
        this.priceAmountIncrease = priceAmountIncrease;
        this.pricePercentIncrease = pricePercentIncrease;
    }

    @Transactional(transactionManager = "mongoTransactionManager")
    public Document updateProfile(Document actor, Map<String, Object> input, String profileImageKey) {
        ObjectId id = actorId(actor);
        Document existing = mongo.getCollection(Collections.CONSULTANTS)
                .find(new Document("_id", id)).first();
        if (existing == null) throw new IllegalStateException("CONSULTANT_NOT_EXIST");

        String mobile = text(input.get("mobile"));
        String email = text(input.get("email"));
        String userName = text(input.get("userName"));
        assertUniqueIdentity(id, mobile, email, userName);

        Document update = new Document();
        for (String field : PROFILE_FIELDS) {
            if (!input.containsKey(field)) continue;
            Object value = input.get(field);
            if (LIST_FIELDS.contains(field)) value = normalizeList(value);
            update.put(field, value);
        }
        update.put("score", input.containsKey("score") ? integer(input.get("score"), "INVALID_SCORE") : 0);

        Document details = doc(existing.get("details"));
        putNonBlank(details, "phone", mobile);
        putNonBlank(details, "email", email);
        putNonBlank(details, "phonePrefix", text(input.get("countryCode")));
        putNonBlank(details, "gender", text(input.get("gender")));
        putNonBlank(details, "dob", text(input.get("dob")));
        if (!blank(text(input.get("dateOfBirth")))) details.put("dateOfBirth", parseDate(input.get("dateOfBirth")));
        for (Map.Entry<String, String> alias : Map.of(
                "problems", "problems", "address", "address", "city", "city", "state", "state",
                "country", "country", "pincode", "zip").entrySet()) {
            putNonBlank(details, alias.getValue(), text(input.get(alias.getKey())));
        }
        update.put("details", details);
        putNonBlank(update, "mobile", mobile);
        putNonBlank(update, "email", email);
        putNonBlank(update, "countryCode", text(input.get("countryCode")));

        if (input.containsKey("price") && !blank(text(input.get("price")))) {
            Document price = doc(existing.get("price"));
            price.put("default", number(input.get("price"), "INVALID_PRICE"));
            update.put("price", price);
        }
        if ("No".equals(input.get("otherOnlinePlatformWork"))) {
            update.put("onlinePlatformWork", "");
            update.put("onlinePlatformName", "");
        }
        if (input.containsKey("qualification") && !"Others".equals(input.get("qualification"))) {
            update.put("highestQualification", "");
        }

        Document device = doc(existing.get("device"));
        putSingleton(device, "fcmToken", input.get("fcmToken"));
        putSingleton(device, "uuid", input.get("uuid"));
        putNonBlank(device, "lastDevice", text(input.get("lastDevice")));
        update.put("device", device);
        if (!blank(profileImageKey)) {
            update.put("image", profileImageKey);
            update.put("profileImage", profileImageKey);
        }
        update.put("updatedAt", new Date());

        Document changed = mongo.getCollection(Collections.CONSULTANTS).findOneAndUpdate(
                new Document("_id", id), new Document("$set", update),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        authCache.invalidate(Role.CONSULTANT, existing);
        authCache.invalidate(Role.CONSULTANT, changed);
        return changed;
    }

    @Transactional(transactionManager = "mongoTransactionManager")
    public Document updateEducation(Document actor, Map<String, Object> input, List<String> certificateKeys) {
        ObjectId id = actorId(actor);
        Document existing = mongo.getCollection(Collections.CONSULTANTS)
                .find(new Document("_id", id)).first();
        if (existing == null) throw new IllegalStateException("CONSULTANT_NOT_EXIST");
        Document education = new Document();
        copyIfPresent(input, education, "highestQualification", "degreeDiploma", "college");
        if (certificateKeys != null && !certificateKeys.isEmpty()) education.put("certificate", certificateKeys);
        Document set = new Document("education", education).append("highestQualification",
                !blank(text(input.get("highestQualification")))
                        ? input.get("highestQualification") : existing.get("highestQualification"))
                .append("updatedAt", new Date());
        Document changed = mongo.getCollection(Collections.CONSULTANTS).findOneAndUpdate(
                new Document("_id", id), new Document("$set", set),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        authCache.invalidate(Role.CONSULTANT, existing);
        return changed;
    }

    public void checkPhoneAndEmail(Map<String, Object> input) {
        String mobile = text(input.get("mobile"));
        String email = text(input.get("email"));
        String referCode = text(input.get("referCode"));
        if (!blank(mobile) && mongo.getCollection(Collections.CONSULTANTS).find(new Document("$or", List.of(
                new Document("details.phone", mobile), new Document("mobile", mobile)))
                .append("isDeleted", false)).first() != null) throw new IllegalStateException("MOBILE_NUMBER_EXIST");
        if (!blank(email) && mongo.getCollection(Collections.CONSULTANTS).find(new Document("$or", List.of(
                new Document("details.email", email), new Document("email", email)))
                .append("isDeleted", false)).first() != null) throw new IllegalStateException("EMAIL_EXIST");
        if (!blank(referCode) && mongo.getCollection(Collections.CONSULTANTS)
                .find(new Document("referId", referCode)).first() == null) throw new IllegalStateException("REFER_NOT_VALID");
    }

    public Map<String, Object> notices(Document actor, int page, int limit) {
        ObjectId id = actorId(actor);
        int safeLimit = clamp(limit, 1, 100);
        Date start = actor.get("createdAt") instanceof Date date ? date : new Date(0);
        Date end = Date.from(LocalDate.now(INDIA).plusDays(1).atStartOfDay(INDIA).toInstant());
        Document match = new Document("createdAt", new Document("$gte", start).append("$lte", end))
                .append("$or", List.of(new Document("consultantId", id), new Document("userType", "cons")));
        List<Document> list = mongo.getCollection(Collections.NOTICE_BOARDS).find(match)
                .sort(new Document("createdAt", -1)).skip(Math.max(0, page - 1) * safeLimit)
                .limit(safeLimit).into(new ArrayList<>());
        String consultantName = display(actor);
        list.forEach(item -> {
            item.put("isNew", !containsId(item.get("isRead"), id));
            item.put("consultantName", consultantName);
        });
        return Map.of("list", list, "total", mongo.getCollection(Collections.NOTICE_BOARDS).countDocuments(match));
    }

    @Transactional(transactionManager = "mongoTransactionManager")
    public Map<String, Object> noticeDetails(Document actor, String noticeId) {
        ObjectId actorId = actorId(actor);
        ObjectId id = objectId(noticeId, "NOTICE_NOT_EXIST");
        Document notice = mongo.getCollection(Collections.NOTICE_BOARDS).find(new Document("_id", id)).first();
        if (notice == null) throw new IllegalStateException("NOTICE_NOT_EXIST");
        mongo.getCollection(Collections.NOTICE_BOARDS).updateOne(
                new Document("_id", id), new Document("$addToSet", new Document("isRead", actorId)));
        return Map.of("noticeBoard", notice, "consultantName", display(actor));
    }

    @Transactional(transactionManager = "mongoTransactionManager")
    public Document toggle(Document actor, String type, Object rawStatus) {
        ObjectId id = actorId(actor);
        boolean status = bool(rawStatus, "STATUS_REQUIRED");
        Document set = new Document("updatedAt", new Date());
        if ("emergency".equals(type)) {
            set.put("emergencyCall", status);
        } else if ("freeTrailOffer".equals(type)) {
            Document admin = mongo.getCollection(Collections.ADMINS).find(new Document("role", "admin"))
                    .sort(new Document("createdAt", 1)).first();
            boolean adminEnabled = admin == null || !Boolean.FALSE.equals(admin.get("isFreeTrailOffer"));
            if (status && !adminEnabled) throw new IllegalStateException("FREE_TRAIL_OFF_BY_ADMIN");
            if (!status && adminEnabled) throw new IllegalStateException("NOT_ABLE_TO_OFF");
            set.put("freeTrailOffer", status);
        } else {
            throw new IllegalArgumentException("INVALID_TOGGLE_TYPE");
        }
        Document changed = mongo.getCollection(Collections.CONSULTANTS).findOneAndUpdate(
                new Document("_id", id), new Document("$set", set),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        if (changed == null) throw new IllegalStateException("CONSULTANT_NOT_EXIST");
        authCache.invalidate(Role.CONSULTANT, actor);
        return changed;
    }

    public Map<String, Object> warnings(Document actor, int page, int limit) {
        ObjectId id = actorId(actor);
        int safeLimit = clamp(limit, 1, 100);
        Document match = new Document("consultantId", id);
        List<Document> list = mongo.getCollection(Collections.WARNINGS).find(match)
                .sort(new Document("createdAt", -1)).skip(Math.max(0, page - 1) * safeLimit)
                .limit(safeLimit).into(new ArrayList<>());
        return Map.of("list", list, "total", mongo.getCollection(Collections.WARNINGS).countDocuments(match));
    }

    public Document warningDetails(Document actor, String warningId) {
        Document result = mongo.getCollection(Collections.WARNINGS).find(new Document("_id",
                objectId(warningId, "WARNING_NOT_EXIST")).append("consultantId", actorId(actor))).first();
        if (result == null) throw new IllegalStateException("WARNING_NOT_EXIST");
        return result;
    }

    @Transactional(transactionManager = "mongoTransactionManager")
    public Document acknowledgeWarning(Document actor, String warningId) {
        Document result = mongo.getCollection(Collections.WARNINGS).findOneAndUpdate(
                new Document("_id", objectId(warningId, "WARNING_NOT_EXIST"))
                        .append("consultantId", actorId(actor)),
                new Document("$set", new Document("warningStatus", true).append("updatedAt", new Date())),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        if (result == null) throw new IllegalStateException("WARNING_NOT_EXIST");
        return result;
    }

    public Map<String, Object> bankOrPanRequest(Document actor, String documentType) {
        String type = text(documentType).toUpperCase(Locale.ROOT);
        if (!Set.of("BANK", "PANCARD", "PHONE").contains(type)) throw new IllegalArgumentException("INVALID_DOCUMENT_TYPE");
        Document latest = mongo.getCollection(Collections.REQUESTS)
                .find(new Document("consultantId", actorId(actor)).append("requestType", type))
                .sort(new Document("createdAt", -1)).first();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("details", latest);
        result.put("requestIsInPending", latest != null);
        return result;
    }

    @Transactional(transactionManager = "mongoTransactionManager")
    public void addRequest(Document actor, Map<String, Object> rawInput,
                           String attachmentKey, String panImageKey) {
        ObjectId consultantId = actorId(actor);
        String type = text(rawInput.get("requestType")).toUpperCase(Locale.ROOT);
        if (!REQUEST_TYPES.contains(type)) throw new IllegalArgumentException("INVALID_REQUEST_TYPE");
        Map<String, Object> input = new LinkedHashMap<>(rawInput);
        Document current = null;

        if ("BANK".equals(type)) {
            require(input, "accountNumber", "bankName", "ifsc", "bankHolderName");
            if (blank(attachmentKey)) throw new IllegalArgumentException("ATTACHMENT_REQUIRED");
            input.put("attachment", constants.mediaUrl + attachmentKey);
            current = mongo.getCollection(Collections.BANKS)
                    .find(new Document("consultantId", consultantId)).first();
        } else if ("PANCARD".equals(type)) {
            require(input, "panNumber");
            if (blank(panImageKey)) throw new IllegalArgumentException("PANCARD_IMAGE_REQUIRED");
            input.put("panImage", constants.mediaUrl + panImageKey);
            current = mongo.getCollection(Collections.BANKS)
                    .find(new Document("consultantId", consultantId).append("documentType", "PANCARD")).first();
        } else if ("PHONE".equals(type)) {
            require(input, "countryCode", "mobile");
            String requested = text(input.get("mobile"));
            Document details = doc(actor.get("details"));
            if (requested.equals(text(details.get("phone")))) throw new IllegalStateException("NUMBER_IS_PRIMARY");
            Document used = mongo.getCollection(Collections.CONSULTANTS).find(new Document("$or", List.of(
                    new Document("details.phone", requested), new Document("mobile", requested)))
                    .append("_id", new Document("$ne", consultantId)).append("isDeleted", false)).first();
            if (used != null) throw new IllegalStateException("MOBILE_NUMBER_EXIST");
            current = new Document("countryCode", details.get("secondaryMobileCountryCode"))
                    .append("mobile", details.get("secondaryMobile"));
        } else {
            Document consultant = mongo.getCollection(Collections.CONSULTANTS)
                    .find(new Document("_id", consultantId)).first();
            if (consultant == null) throw new IllegalStateException("CONSULTANT_NOT_EXIST");
            double currentPrice = price(consultant.get("price"));
            Document lastApproved = mongo.getCollection(Collections.REQUESTS)
                    .find(new Document("consultantId", consultantId).append("approveStatus", 2)
                            .append("requestType", "PRICE")).sort(new Document("createdAt", -1)).first();
            enforcePriceCooldown(currentPrice, lastApproved);
            double requested = Math.max(currentPrice + priceAmountIncrease,
                    currentPrice + currentPrice * pricePercentIncrease / 100.0);
            input.put("price", requested);
            current = new Document("price", consultant.get("price"));
        }

        Date now = new Date();
        Document request = new Document("consultantId", consultantId).append("requestType", type)
                .append("currentData", current == null ? null : json(current))
                .append("newData", json(input)).append("approveStatus", 1).append("status", true)
                .append("createdAt", now).append("updatedAt", now);
        mongo.getCollection(Collections.REQUESTS).insertOne(request);
        mongo.getCollection(Collections.NOTIFICATION_RECORDS).insertOne(new Document("userType", "admin")
                .append("senderType", "system")
                .append("message", "consultant with id : " + consultantId + " create a request for " + type)
                .append("status", true).append("createdAt", now).append("updatedAt", now));
    }

    @Transactional(transactionManager = "mongoTransactionManager")
    public Document addEvent(Document actor, Map<String, Object> rawInput, String eventImageKey) {
        ObjectId consultantId = actorId(actor);
        String title = text(rawInput.get("title"));
        String eventType = text(rawInput.get("eventType"));
        if (blank(title) || !title.matches("^[a-zA-Z_][a-zA-Z0-9_\\s]*$")) throw new IllegalArgumentException("INVALID_TITLE");
        if (!Set.of("live", "schedule").contains(eventType)) throw new IllegalArgumentException("INVALID_EVENT_TYPE");
        if (mongo.getCollection(Collections.BROADCASTS).find(new Document("consultantId", consultantId)
                .append("title", title).append("status", 0)).first() != null) throw new IllegalStateException("TITLE_EXIST");
        if (mongo.getCollection(Collections.BROADCASTS).find(new Document("consultantId", consultantId)
                .append("status", 1)).first() != null) throw new IllegalStateException("ALREADY_LIVE");
        if (!Boolean.TRUE.equals(actor.get("goLive"))) throw new IllegalStateException("NOT_ELIGIBLE_FOR_LIVE");
        if ("live".equals(eventType) && busyForLive(actor)) throw new IllegalStateException("CONSULTANT_BUSY_IN_CALL");

        Date day = indiaDayStart(LocalDate.now(INDIA));
        Date tomorrow = indiaDayStart(LocalDate.now(INDIA).plusDays(1));
        long completedToday = mongo.getCollection(Collections.BROADCASTS).countDocuments(
                new Document("consultantId", consultantId).append("status", 2)
                        .append("createdAt", new Document("$gte", day).append("$lt", tomorrow)));
        if (completedToday > dailyBroadcastLimit) throw new IllegalStateException("BROADCAST_EXCEED");

        Document event = new Document(rawInput);
        event.remove("consultantId");
        event.put("consultantId", consultantId);
        event.put("eventDate", day);
        event.put("status", event.getOrDefault("status", 0));
        event.put("isComplete", false);
        event.put("createdAt", new Date());
        event.put("updatedAt", new Date());
        if (!blank(eventImageKey)) event.put("image", eventImageKey);
        Document duplicateTime = mongo.getCollection(Collections.BROADCASTS).find(new Document("consultantId", consultantId)
                .append("eventDate", day).append("eventTime", event.get("eventTime"))).first();
        if (duplicateTime != null) throw new IllegalStateException("EVENT_DATE_EXIST");
        mongo.getCollection(Collections.BROADCASTS).insertOne(event);
        outbox.enqueue("CONSULTANT_EVENT:" + event.getObjectId("_id"), "CONSULTANT_EVENT_CREATED",
                new Document("consultantId", consultantId.toHexString())
                        .append("eventId", event.getObjectId("_id").toHexString())
                        .append("eventType", eventType));
        return event;
    }

    @Transactional(transactionManager = "mongoTransactionManager")
    public Map<String, Object> listEvents(Document actor, int page, int limit, String search) {
        ObjectId consultantId = actorId(actor);
        int safeLimit = clamp(limit, 1, 100);
        Date day = indiaDayStart(LocalDate.now(INDIA));
        Date tomorrow = indiaDayStart(LocalDate.now(INDIA).plusDays(1));
        Document match = new Document("consultantId", consultantId)
                .append("createdAt", new Document("$gte", day).append("$lt", tomorrow));
        if (!blank(search)) match.put("title", Pattern.compile(Pattern.quote(search.trim()), Pattern.CASE_INSENSITIVE));
        List<Document> list = mongo.getCollection(Collections.BROADCASTS).find(match)
                .sort(new Document("eventDate", -1).append("eventTime", -1))
                .skip(Math.max(0, page - 1) * safeLimit).limit(safeLimit).into(new ArrayList<>());
        long total = mongo.getCollection(Collections.BROADCASTS).countDocuments(match);
        for (Document pending : mongo.getCollection(Collections.BROADCASTS)
                .find(new Document(match).append("status", 0))) {
            if (scheduledEventExpired(pending)) {
                mongo.getCollection(Collections.BROADCASTS).updateOne(new Document("_id", pending.get("_id")),
                        new Document("$set", new Document("status", 3).append("updatedAt", new Date())));
            }
        }
        return Map.of("list", list, "total", total);
    }

    @Transactional(transactionManager = "mongoTransactionManager")
    public void completeEvents(Document actor) {
        ObjectId consultantId = actorId(actor);
        for (Document event : mongo.getCollection(Collections.BROADCASTS)
                .find(new Document("consultantId", consultantId).append("status", 1))) {
            mongo.getCollection(Collections.BROADCASTS).updateOne(
                    new Document("_id", event.get("_id")).append("consultantId", consultantId).append("status", 1),
                    new Document("$set", new Document("status", 2).append("isComplete", true)
                            .append("updatedAt", new Date())));
            mongo.getCollection(Collections.COMMENTS).deleteMany(new Document("broadcastId", event.get("_id")));
        }
    }

    private void assertUniqueIdentity(ObjectId actorId, String mobile, String email, String userName) {
        if (!blank(mobile) && mongo.getCollection(Collections.CONSULTANTS).find(new Document("_id", new Document("$ne", actorId))
                .append("$or", List.of(new Document("details.phone", mobile), new Document("mobile", mobile)))
                .append("isDeleted", false)).first() != null) throw new IllegalStateException("MOBILE_NUMBER_EXIST");
        if (!blank(email) && mongo.getCollection(Collections.CONSULTANTS).find(new Document("_id", new Document("$ne", actorId))
                .append("$or", List.of(new Document("details.email", email), new Document("email", email)))
                .append("isDeleted", false)).first() != null) throw new IllegalStateException("EMAIL_EXIST");
        if (!blank(userName) && mongo.getCollection(Collections.CONSULTANTS).find(new Document("_id", new Document("$ne", actorId))
                .append("userName", userName).append("isDeleted", false)).first() != null) {
            throw new IllegalStateException("USER_NAME_EXIST");
        }
    }

    private boolean busyForLive(Document actor) {
        ObjectId id = actorId(actor);
        for (Document request : mongo.getCollection(Collections.CONSULTANT_FORM_REQUESTS).find(
                new Document("consultantId", id).append("isConsultantCompleted", new Document("$in", List.of("waiting", "progress"))))) {
            String mode = text(request.get("typeOfConsult"));
            if ("chat".equals(mode) && Boolean.TRUE.equals(actor.get("isChatLive"))) return true;
            if ("audio".equals(mode) && Boolean.TRUE.equals(actor.get("isCallLive"))) return true;
            if ("video".equals(mode) && Boolean.TRUE.equals(actor.get("isVideoLive"))) return true;
        }
        return false;
    }

    private boolean scheduledEventExpired(Document event) {
        Date date = event.get("eventDate") instanceof Date d ? d : null;
        String time = text(event.get("eventTime"));
        if (date == null || blank(time)) return false;
        try {
            LocalDate localDate = date.toInstant().atZone(INDIA).toLocalDate();
            LocalTime localTime = LocalTime.parse(time, DateTimeFormatter.ofPattern("H:mm"));
            return Instant.now().isAfter(LocalDateTime.of(localDate, localTime).atZone(INDIA)
                    .toInstant().plus(Duration.ofMinutes(5)));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private void enforcePriceCooldown(double currentPrice, Document lastApproved) {
        if (lastApproved == null) return;
        Date date = lastApproved.get("updatedAt") instanceof Date d ? d : lastApproved.getDate("createdAt");
        if (date == null) return;
        long days = Math.max(0, Duration.between(date.toInstant(), Instant.now()).toDays());
        if (currentPrice < 20 && days < 30) throw new IllegalStateException("FIRST_CONDITION_ERROR");
        if (currentPrice > 20 && currentPrice < 30 && days < 90) throw new IllegalStateException("SECOND_CONDITION_ERROR");
        if (currentPrice >= 30 && days < 180) throw new IllegalStateException("THIRD_CONDITION_ERROR");
    }

    private double price(Object value) {
        if (value instanceof Document d) return doubleNumber(d.get("default"));
        if (value instanceof Map<?, ?> map) return doubleNumber(map.get("default"));
        return doubleNumber(value);
    }

    private Date indiaDayStart(LocalDate day) { return Date.from(day.atStartOfDay(INDIA).toInstant()); }
    private Object parseDate(Object value) {
        String text = text(value);
        try { return Date.from(Instant.parse(text)); }
        catch (Exception ignored) {
            try { return Date.from(LocalDate.parse(text).atStartOfDay(ZoneOffset.UTC).toInstant()); }
            catch (Exception invalid) { throw new IllegalArgumentException("INVALID_DATE_OF_BIRTH"); }
        }
    }
    private Object normalizeList(Object value) {
        if (value instanceof List<?>) return value;
        if (value == null || blank(text(value))) return List.of();
        return List.of(value);
    }
    private void putSingleton(Document target, String field, Object value) {
        if (value != null && !blank(text(value))) target.put(field, List.of(value));
    }
    private void copyIfPresent(Map<String, Object> input, Document target, String... fields) {
        for (String field : fields) if (input.containsKey(field)) target.put(field, input.get(field));
    }
    private void require(Map<String, Object> input, String... fields) {
        for (String field : fields) if (blank(text(input.get(field)))) throw new IllegalArgumentException(field + " is required");
    }
    private void putNonBlank(Document target, String field, String value) { if (!blank(value)) target.put(field, value); }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException error) { throw new IllegalArgumentException("INVALID_REQUEST_DATA", error); }
    }
    private String display(Document actor) {
        String name = text(actor.get("accountName"));
        if (blank(name)) name = text(actor.get("name"));
        if (blank(name)) name = text(doc(actor.get("details")).get("name"));
        return name;
    }
    private boolean containsId(Object value, ObjectId id) {
        if (!(value instanceof List<?> list)) return false;
        return list.stream().anyMatch(item -> id.equals(item) || id.toHexString().equals(text(item)));
    }
    private ObjectId actorId(Document actor) {
        if (actor == null) throw new IllegalStateException("Invalid token");
        return objectId(actor.get("_id"), "CONSULTANT_NOT_EXIST");
    }
    private ObjectId objectId(Object value, String error) {
        if (value instanceof ObjectId id) return id;
        if (value != null && ObjectId.isValid(String.valueOf(value))) return new ObjectId(String.valueOf(value));
        throw new IllegalArgumentException(error);
    }
    @SuppressWarnings("unchecked")
    private Document doc(Object value) {
        if (value instanceof Document d) return new Document(d);
        if (value instanceof Map<?, ?> map) {
            Document d = new Document(); map.forEach((key, val) -> d.put(String.valueOf(key), val)); return d;
        }
        return new Document();
    }
    private boolean bool(Object value, String error) {
        if (value instanceof Boolean b) return b;
        if (value != null && Set.of("true", "false").contains(text(value).toLowerCase(Locale.ROOT))) {
            return Boolean.parseBoolean(text(value));
        }
        throw new IllegalArgumentException(error);
    }
    private Number number(Object value, String error) {
        if (value instanceof Number n) return n;
        try { return Double.parseDouble(text(value)); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException(error); }
    }
    private int integer(Object value, String error) {
        if (value instanceof Number n) return n.intValue();
        if (value == null || blank(text(value))) return 0;
        try { return Integer.parseInt(text(value)); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException(error); }
    }
    private double doubleNumber(Object value) {
        if (value instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(text(value)); } catch (Exception ignored) { return 0; }
    }
    private int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
    private String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private boolean blank(String value) { return value == null || value.isBlank(); }
}
