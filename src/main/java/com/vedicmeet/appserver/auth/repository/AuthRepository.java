package com.vedicmeet.appserver.auth.repository;

import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Sorts;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Repository;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Raw-document repository preserving the schemaless Mongoose collection and field layout. */
@Repository
public class AuthRepository {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String REFER_ALPHABET = "1V2E3D4I5C";

    private final MongoTemplate mongo;

    public AuthRepository(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    public Document findUserByPhone(String phone, String prefix, boolean includeDeleted) {
        Document filter = new Document("details.phone", phone).append("details.phonePrefix", prefix);
        if (!includeDeleted) filter.append("isDeleted", false);
        return collection(Collections.USERS).find(filter).first();
    }

    public Document findUserByEmail(String email, boolean includeDeleted) {
        Document filter = identityEmail(email);
        if (!includeDeleted) filter.append("isDeleted", false);
        return collection(Collections.USERS).find(filter).first();
    }

    public Document findConsultantByPhone(String phone, String prefix, boolean includeDeleted) {
        Document filter = new Document("details.phone", phone).append("details.phonePrefix", prefix);
        if (!includeDeleted) filter.append("isDeleted", false);
        return collection(Collections.CONSULTANTS).find(filter).first();
    }

    public Document findConsultantByPhone(String phone, boolean includeDeleted) {
        Document filter = new Document("details.phone", phone);
        if (!includeDeleted) filter.append("isDeleted", false);
        return collection(Collections.CONSULTANTS).find(filter).first();
    }

    public Document findConsultantByEmail(String email, boolean includeDeleted) {
        Document filter = identityEmail(email);
        if (!includeDeleted) filter.append("isDeleted", false);
        return collection(Collections.CONSULTANTS).find(filter).first();
    }

    public Document findConsultantByUsername(String username) {
        return collection(Collections.CONSULTANTS)
                .find(new Document("userName", username).append("isDeleted", false)).first();
    }

    public Document findConsultantByReferCode(String referCode) {
        return collection(Collections.CONSULTANTS).find(new Document("referId", referCode)).first();
    }

    public Document findUserById(Object id) { return findById(Collections.USERS, id); }
    public Document findConsultantById(Object id) { return findById(Collections.CONSULTANTS, id); }
    public Document findAdminById(Object id) { return findById(Collections.ADMINS, id); }

    public Document findAdminByEmail(String email) {
        return collection(Collections.ADMINS).find(new Document("email", email)).first();
    }

    public long countAdminsByRole(String role) {
        return collection(Collections.ADMINS).countDocuments(new Document("role", role));
    }

    public Document insertUser(Document user) { return insert(Collections.USERS, user); }
    public Document insertConsultant(Document consultant) { return insert(Collections.CONSULTANTS, consultant); }
    public Document insertAdmin(Document admin) { return insert(Collections.ADMINS, admin); }

    public void createWalletForUser(ObjectId userId) {
        insert(Collections.WALLETS, new Document("userId", userId)
                .append("userType", "user").append("coins", "").append("paidAmount", 0)
                .append("savedAmount", 0).append("status", true));
    }

    public void createWalletForConsultant(ObjectId consultantId) {
        insert(Collections.WALLETS, new Document("consultantId", consultantId)
                .append("userType", "cons").append("coins", "").append("paidAmount", 0)
                .append("savedAmount", 0).append("status", true));
    }

    public void createConsultantBoost(ObjectId consultantId) {
        insert(Collections.CONSULTANT_BOOSTS, new Document("consultantId", consultantId)
                .append("callActive", false).append("chatActive", false).append("videoActive", false)
                .append("startTime", "00:00:00").append("totalBoostDuration", 0)
                .append("lastBoostDate", Date.from(Instant.now())));
    }

    public void createConsultantReferral(ObjectId referBy, ObjectId referTo, String referCode) {
        Document existing = collection(Collections.REFERS)
                .find(new Document("referBy", referBy).append("referTo", referTo)).first();
        if (existing != null) return;
        insert(Collections.REFERS, new Document("userType", "cons")
                .append("referBy", referBy).append("referTo", referTo).append("points", "50")
                .append("referCode", referCode).append("referStatus", 1).append("status", true));
    }

    public void ensureFamilyCommunityMapping(ObjectId userId) {
        Document filter = new Document("owner", userId).append("for", "family");
        if (collection(Collections.COMMUNITY_MAPPINGS).find(filter).first() == null) {
            insert(Collections.COMMUNITY_MAPPINGS,
                    new Document(filter).append("list", new ArrayList<>()));
        }
    }

    public void saveNotification(ObjectId receiverId, String title, String message, Map<String, Object> data) {
        insert(Collections.NOTIFICATION_RECORDS, new Document("receiverId", receiverId)
                .append("message", message).append("title", title).append("userType", "user")
                .append("senderType", "system").append("type", "other")
                .append("data", data == null ? new Document() : new Document(data)).append("isRead", false));
    }

    /** Atomic device-limit check and registration; prevents two simultaneous logins bypassing max=15. */
    public Document registerDevice(String collection, ObjectId id, String deviceToken, String fcmToken,
                                   String deviceUuid, String deviceType, String voipToken, int limit) {
        List<Object> empty = List.of();
        Document size = new Document("$size", new Document("$ifNull", List.of("$deviceToken", empty)));
        Document underLimit = new Document("$expr", new Document("$lt", List.of(size, limit)));
        Document filter = new Document("_id", id).append("$or", List.of(
                new Document("deviceToken", deviceToken), underLimit));

        Document addToSet = new Document();
        if (notBlank(deviceToken)) {
            addToSet.append("deviceToken", deviceToken).append("device.deviceToken", deviceToken);
        }
        if (notBlank(fcmToken)) {
            addToSet.append("fcmToken", fcmToken).append("device.fcmToken", fcmToken);
        }
        if (notBlank(deviceUuid)) addToSet.append("device.uuid", deviceUuid);

        Document set = new Document("updatedAt", Date.from(Instant.now()));
        if (deviceType != null) set.append("deviceType", deviceType);
        if (voipToken != null) set.append("voipToken", voipToken).append("device.voipToken", voipToken);

        Document update = new Document("$set", set);
        if (!addToSet.isEmpty()) update.append("$addToSet", addToSet);
        return collection(collection).findOneAndUpdate(filter, update,
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
    }

    public Document logout(String collection, ObjectId id, String from, String fcmToken, String deviceToken) {
        Document update;
        if ("all".equals(from)) {
            update = new Document("$set", new Document("deviceToken", List.of())
                    .append("fcmToken", List.of()).append("voipToken", "")
                    .append("device.fcmToken", List.of()).append("device.deviceToken", List.of())
                    .append("device.uuid", List.of()).append("device.voipToken", null)
                    .append("isChatLive", false).append("isCallLive", false).append("isVideoLive", false))
                    .append("$inc", new Document("authTokenVersion", 1));
        } else {
            update = new Document("$set", new Document("voipToken", "")
                    .append("isChatLive", false).append("isCallLive", false).append("isVideoLive", false))
                    .append("$pull", new Document("fcmToken", fcmToken)
                            .append("deviceToken", deviceToken)
                            .append("device.fcmToken", fcmToken)
                            .append("device.deviceToken", deviceToken));
        }
        return collection(collection).findOneAndUpdate(new Document("_id", id), update,
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
    }

    public Document softDeleteUser(ObjectId id) {
        return collection(Collections.USERS).findOneAndUpdate(new Document("_id", id),
                new Document("$set", new Document("isDeleted", true).append("wallet", 0)
                        .append("device.fcmToken", List.of()).append("device.deviceToken", List.of())
                        .append("device.uuid", List.of()).append("device.voipToken", ""))
                        .append("$inc", new Document("authTokenVersion", 1)),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
    }

    public Document softDeleteConsultant(ObjectId id) {
        return collection(Collections.CONSULTANTS).findOneAndUpdate(new Document("_id", id),
                new Document("$set", new Document("isDeleted", true)
                        .append("device.fcmToken", List.of()).append("device.deviceToken", List.of())
                        .append("device.uuid", List.of()).append("device.voipToken", "")
                        .append("isChatLive", false).append("isCallLive", false)
                        .append("isVideoLive", false).append("isOnline", false))
                        .append("$inc", new Document("authTokenVersion", 1)),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
    }

    public void cancelUserWaitlists(ObjectId userId) {
        collection(Collections.WAITLISTS).updateMany(
                new Document("user_id", userId.toHexString())
                        .append("status", new Document("$in", List.of("waiting", "missed"))),
                new Document("$set", new Document("status", "canceled"))
                        .append("$push", new Document("logs", new Document("callStatus", "cancelled due to account deletion")
                                .append("callEndedBy", "user").append("timestamp", Date.from(Instant.now())))));
    }

    public void cancelConsultantRequests(ObjectId consultantId) {
        collection(Collections.CONSULTANT_FORM_REQUESTS).updateMany(
                new Document("consultantId", consultantId).append("isConsultantCompleted", "waiting"),
                new Document("$set", new Document("isConsultantCompleted", "cancel")));
    }

    public Document approveConsultant(ObjectId id, boolean approve) {
        if (!approve) return collection(Collections.CONSULTANTS).findOneAndDelete(new Document("_id", id));
        return collection(Collections.CONSULTANTS).findOneAndUpdate(new Document("_id", id),
                new Document("$set", new Document("isAdminVerify", true).append("updatedAt", Date.from(Instant.now()))),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
    }

    public void markConsultantPrimaryPhoneVerified(ObjectId id) {
        collection(Collections.CONSULTANTS).updateOne(new Document("_id", id),
                new Document("$set", new Document("details.primaryMobileVerify", true)
                        .append("updatedAt", Date.from(Instant.now()))));
    }

    public Document updateAdmin(ObjectId id, Document set) {
        set.put("updatedAt", Date.from(Instant.now()));
        return collection(Collections.ADMINS).findOneAndUpdate(new Document("_id", id),
                new Document("$set", set), new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
    }

    public Document setAdminStatus(ObjectId id, boolean status) {
        Document update = new Document("$set", new Document("status", status)
                .append("updatedAt", Date.from(Instant.now())));
        if (!status) update.append("$inc", new Document("authTokenVersion", 1));
        return collection(Collections.ADMINS).findOneAndUpdate(new Document("_id", id), update,
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
    }

    public Document updateAdminPassword(ObjectId id, String passwordHash, boolean incrementVersion) {
        Document update = new Document("$set", new Document("password", passwordHash)
                .append("updatedAt", Date.from(Instant.now())));
        if (incrementVersion) update.append("$inc", new Document("authTokenVersion", 1));
        return collection(Collections.ADMINS).findOneAndUpdate(new Document("_id", id), update,
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
    }

    public Document savePasswordReset(ObjectId id, String nonceHash, Date expiresAt) {
        return updateAdmin(id, new Document("passwordResetNonceHash", nonceHash)
                .append("passwordResetExpiresAt", expiresAt));
    }

    public Document consumePasswordReset(ObjectId id, String nonceHash, String passwordHash) {
        Document filter = new Document("_id", id).append("passwordResetNonceHash", nonceHash)
                .append("passwordResetExpiresAt", new Document("$gt", Date.from(Instant.now())));
        Document update = new Document("$set", new Document("password", passwordHash)
                .append("updatedAt", Date.from(Instant.now())))
                .append("$unset", new Document("passwordResetNonceHash", "")
                        .append("passwordResetExpiresAt", ""))
                .append("$inc", new Document("authTokenVersion", 1));
        return collection(Collections.ADMINS).findOneAndUpdate(filter, update,
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
    }

    public List<Document> listSubAdmins(int page, int limit, String search, Boolean status) {
        Document filter = new Document("role", "sub-admin");
        if (status != null) filter.append("status", status);
        // Preserve Node admin.js: search is an AND across email and name (even though OR may be nicer UX).
        if (notBlank(search)) filter
                .append("name", new Document("$regex", ".*" + search + ".*").append("$options", "i"))
                .append("email", new Document("$regex", ".*" + search + ".*").append("$options", "i"));
        FindIterable<Document> result = collection(Collections.ADMINS).find(filter)
                .sort(Sorts.descending("createdAt")).skip(Math.max(0, page - 1) * limit).limit(limit);
        return result.into(new ArrayList<>());
    }

    public long countSubAdmins(String search, Boolean status) {
        Document filter = new Document("role", "sub-admin");
        if (status != null) filter.append("status", status);
        if (notBlank(search)) filter
                .append("name", new Document("$regex", ".*" + search + ".*").append("$options", "i"))
                .append("email", new Document("$regex", ".*" + search + ".*").append("$options", "i"));
        return collection(Collections.ADMINS).countDocuments(filter);
    }

    public long completedConsultationsForDevice(String deviceUuid) {
        return collection(Collections.WAITLISTS)
                .countDocuments(new Document("deviceUsedToken", deviceUuid).append("status", "completed"));
    }

    public Document appVersion(String userType, String deviceType) {
        return collection(Collections.APP_VERSION_MODELS)
                .find(new Document("userType", userType).append("deviceType", deviceType)).first();
    }

    public String nextUserId() {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        Instant start = now.withDayOfMonth(1).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant end = now.plusMonths(1).withDayOfMonth(1).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant();
        long serial = collection(Collections.USERS).countDocuments(new Document("createdAt",
                new Document("$gte", Date.from(start)).append("$lt", Date.from(end)))) + 1;
        return "VM" + String.format("%02d", now.getYear() % 100) + now.getMonthValue() + serial;
    }

    public String nextConsultantId() {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        long serial = collection(Collections.CONSULTANTS).countDocuments() + 1;
        String month = now.getMonth().getDisplayName(TextStyle.SHORT, Locale.US).toUpperCase(Locale.ROOT);
        return String.format("%02d", now.getYear() % 100) + month + serial;
    }

    public String generateReferCode(int length) {
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < length; i++) value.append(REFER_ALPHABET.charAt(RANDOM.nextInt(REFER_ALPHABET.length())));
        String millis = String.valueOf(System.currentTimeMillis());
        return value.append(millis.charAt(millis.length() - 1)).append(millis.charAt(millis.length() - 2)).toString();
    }

    private Document identityEmail(String email) {
        return new Document("$or", List.of(new Document("details.email", email), new Document("email", email)));
    }

    private Document findById(String collection, Object id) {
        Object canonical = objectId(id);
        return canonical == null ? null : collection(collection).find(new Document("_id", canonical)).first();
    }

    private Document insert(String collection, Document document) {
        Date now = Date.from(Instant.now());
        document.putIfAbsent("createdAt", now);
        document.putIfAbsent("updatedAt", now);
        collection(collection).insertOne(document);
        return document;
    }

    private MongoCollection<Document> collection(String name) { return mongo.getCollection(name); }

    private Object objectId(Object value) {
        if (value instanceof ObjectId) return value;
        if (value != null && ObjectId.isValid(String.valueOf(value))) return new ObjectId(String.valueOf(value));
        return null;
    }

    private boolean notBlank(String value) { return value != null && !value.isBlank(); }
}
