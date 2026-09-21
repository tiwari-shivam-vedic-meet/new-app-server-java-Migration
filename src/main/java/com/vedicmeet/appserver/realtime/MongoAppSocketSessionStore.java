package com.vedicmeet.appserver.realtime;

import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.vedicmeet.appserver.call.CallIntegrationOutboxService;
import com.vedicmeet.appserver.config.AppConstants;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import com.vedicmeet.appserver.discovery.TimerReadService;
import com.vedicmeet.appserver.security.Role;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Mongo/Redis implementation of the remaining {@code /app} namespace session operations. */
@Repository
public class MongoAppSocketSessionStore implements AppSocketSessionStore {

    private static final List<String> ACTIVE_USER_WAITLIST =
            List.of("waiting", "blocked", "missed", "initiated");

    private final MongoTemplate mongo;
    private final TimerReadService timers;
    private final AppConstants constants;
    private final CallIntegrationOutboxService outbox;

    public MongoAppSocketSessionStore(MongoTemplate mongo, TimerReadService timers,
                                      AppConstants constants, CallIntegrationOutboxService outbox) {
        this.mongo = mongo;
        this.timers = timers;
        this.constants = constants;
        this.outbox = outbox;
    }

    @Override
    public Participants participants(String waitlistId, String role, String actorId) {
        Document filter = idFilter(waitlistId);
        if (Role.USER.equals(role)) filter.append("user_id", actorId);
        else if (Role.CONSULTANT.equals(role)) filter.append("consultant_id", actorId);
        else return null;
        Document waitlist = col(Collections.WAITLISTS).find(filter).first();
        return waitlist == null ? null : new Participants(waitlist,
                text(waitlist.get("user_id")), text(waitlist.get("consultant_id")));
    }

    @Override
    public Participants activeParticipants(String role, String actorId, String targetId) {
        Document filter = new Document("status", "progress");
        if (Role.USER.equals(role)) filter.append("user_id", actorId).append("consultant_id", targetId);
        else if (Role.CONSULTANT.equals(role)) filter.append("consultant_id", actorId).append("user_id", targetId);
        else return null;
        Document waitlist = col(Collections.WAITLISTS).find(filter).first();
        return waitlist == null ? null : new Participants(waitlist,
                text(waitlist.get("user_id")), text(waitlist.get("consultant_id")));
    }

    @Override
    public Document consultantDetails(String consultantId) {
        return col(Collections.CONSULTANTS).find(new Document("_id", id(consultantId)))
                .projection(new Document("_id", 1).append("name", 1).append("accountName", 1)
                        .append("profileImage", 1)).first();
    }

    @Override public Document sessionUser(String userId) {
        return withMedia(col(Collections.USERS).find(new Document("_id", id(userId))).first());
    }

    @Override public Document sessionConsultant(String consultantId) {
        return withMedia(col(Collections.CONSULTANTS).find(new Document("_id", id(consultantId))).first());
    }

    @Override
    public List<Document> blockedChatRanges(String userId, String consultantId) {
        List<Document> rows = col(Collections.WAITLISTS).find(new Document("user_id", userId)
                        .append("consultant_id", consultantId).append("status", "completed")
                        .append("session_info.canConsultantAccessHistory", false)
                        .append("timeLap.startTime", new Document("$exists", true))
                        .append("timeLap.endTime", new Document("$exists", true)))
                .projection(new Document("timeLap.startTime", 1).append("timeLap.endTime", 1))
                .into(new ArrayList<>());
        List<Document> result = new ArrayList<>();
        for (Document row : rows) {
            Document lap = doc(row.get("timeLap"));
            result.add(new Document("startTime", lap.get("startTime"))
                    .append("endTime", lap.get("endTime")));
        }
        return result;
    }

    @Override
    public List<Document> userWaitlists(String userId) {
        List<Document> waitlists = col(Collections.WAITLISTS).find(new Document("user_id", userId)
                        .append("status", new Document("$in", ACTIVE_USER_WAITLIST))
                        .append("used_for", "private_call"))
                .into(new ArrayList<>());
        List<Document> result = new ArrayList<>();
        for (Document entry : waitlists) {
            String consultantId = text(entry.get("consultant_id"));
            Document consultant = consultantDetails(consultantId);
            Document busy = col(Collections.WAITLISTS).find(new Document("consultant_id", consultantId)
                    .append("status", "progress")).projection(new Document("_id", 1)
                    .append("used_for", 1)).first();
            long currentRemaining = 0;
            boolean consultantBusy = false;
            String currentType = null;
            if (busy != null) {
                String timerId = text(busy.get("_id")) + ":duration";
                long remaining = timers.getRemainingTime(timerId);
                if (timers.isTimerActive(timerId) && remaining > 0) {
                    consultantBusy = true;
                    currentRemaining = remaining;
                    // Node uses the queued row's type here, not the progressing row's type.
                    currentType = entry.getString("used_for");
                }
            }

            List<Document> queue = col(Collections.WAITLISTS).find(new Document("consultant_id", consultantId)
                            .append("status", "waiting")
                            .append("used_for", new Document("$in", List.of("private_call", "session")))
                            .append("user_id", new Document("$ne", userId)))
                    .sort(new Document("priority", -1).append("createdAt", -1))
                    .into(new ArrayList<>());
            long totalWait = queue.stream().mapToLong(row -> whole(row.get("requested_time"))).sum();

            // Production Node excludes the current user before searching this list, so position is
            // always zero. Preserve that response quirk until the mobile contract is versioned.
            int userPosition = 0;
            long estimated = currentRemaining;
            Document consultantInfo = consultant == null ? new Document() : consultant;
            Object image = media(consultantInfo.get("profileImage"));
            Document state = new Document("hasActiveSession", consultantBusy)
                    .append("sessionType", currentType).append("waitlistQueueLength", queue.size())
                    .append("estimatedWaitTime", estimated)
                    .append("currentSessionTimeRemaining", currentRemaining)
                    .append("userPositionInQueue", userPosition);
            result.add(new Document("waitlistId", entry.get("_id"))
                    .append("consultant_id", consultantId)
                    .append("consultant_name", consultantInfo.get("accountName"))
                    .append("consultant_image", image)
                    .append("consultant_sessionsStatus", consultantInfo.get("sessionsStatus"))
                    .append("status", entry.get("status")).append("used_for", entry.get("used_for"))
                    .append("requested_time", entry.get("requested_time"))
                    .append("session_info", entry.get("session_info"))
                    .append("threadId", entry.get("threadId")).append("position", userPosition)
                    // Node also double-adds currentRemainingTime; this is wire-compatible on purpose.
                    .append("estimatedWaitTime", estimated + currentRemaining)
                    .append("currentSessionRemainingTime", currentRemaining)
                    .append("totalWaitlistTime", totalWait).append("isConsultantBusy", consultantBusy)
                    .append("queueLength", queue.size()).append("detailedState", state));
        }
        return result;
    }

    @Override
    public Document progressingUserWaitlist(String userId) {
        Document waitlist = col(Collections.WAITLISTS).find(new Document("user_id", userId)
                .append("status", "progress")
                .append("used_for", new Document("$in", List.of("private_call", "session")))).first();
        return enrichProgress(waitlist, true);
    }

    @Override
    public Document progressingConsultantWaitlist(String consultantId) {
        Document waitlist = col(Collections.WAITLISTS).find(new Document("consultant_id", consultantId)
                .append("status", "progress")).first();
        return enrichProgress(waitlist, false);
    }

    @Override
    public List<Document> fixedSessionWaitlists(String userId) {
        return col(Collections.FIXED_SESSION_WAITLISTS).find(new Document("user_id", id(userId))
                        .append("for", "fixed_session").append("status", "waiting"))
                .sort(new Document("createdAt", -1)).into(new ArrayList<>());
    }

    @Override
    public boolean appendSessionSwitchLog(String waitlistId, String role, String actorId, String channel) {
        Document filter = idFilter(waitlistId).append("status", "progress");
        if (Role.USER.equals(role)) filter.append("user_id", actorId);
        else if (Role.CONSULTANT.equals(role)) filter.append("consultant_id", actorId);
        else return false;
        return col(Collections.WAITLISTS).updateOne(filter,
                new Document("$push", new Document("logs", new Document("callStatus",
                        "Session switched to " + channel).append("timestamp", new Date())))).getModifiedCount() == 1;
    }

    @Override
    @Transactional(transactionManager = "mongoTransactionManager")
    public Document cancelWaitlist(String waitlistId, String userId, String reason) {
        Date now = new Date();
        Document entry = col(Collections.WAITLISTS).findOneAndUpdate(idFilter(waitlistId)
                        .append("user_id", userId)
                        .append("status", new Document("$in", List.of("waiting", "missed"))),
                new Document("$set", new Document("status", "canceled").append("updatedAt", now))
                        .append("$push", new Document("logs", new Document("callStatus",
                                blank(reason) ? "cancelled : user cancelled" : reason)
                                .append("cancelledBy", "user").append("timestamp", now))),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        if (entry == null) return null;

        restoreSystemOffer(entry);
        refundScheduledWaitlist(entry, userId);

        String consultantId = text(entry.get("consultant_id"));
        if (!consultantId.isBlank()) {
            Document form = doc(entry.get("request_form"));
            String name = (text(form.get("firstName")) + " " + text(form.get("lastName"))).trim();
            if (name.isBlank()) name = "User";
            Document notification = new Document("targetId", consultantId).append("targetType", "cons")
                    .append("title", "Waitlist Update")
                    .append("body", name + " has left your waitlist.")
                    .append("data", new Document("screen", "waitlist"));
            outbox.enqueue("APP_PUSH:WAITLIST_CANCEL:" + waitlistId,
                    "APP_PUSH_NOTIFICATION", notification);
        }
        outbox.enqueue("APP_INTERAKT:WAITLIST_CANCEL:" + waitlistId, "APP_INTERAKT_EVENT",
                new Document("eventName", "cancel_consultation_by_user")
                        .append("userId", userId).append("phone", "")
                        .append("eventProperties", new Document("consultant_id", consultantId)));
        return entry;
    }

    @Override
    @Transactional(transactionManager = "mongoTransactionManager")
    public Document cancelFixedSession(String waitlistId, String userId, String reason) {
        Date now = new Date();
        Document entry = col(Collections.FIXED_SESSION_WAITLISTS).findOneAndUpdate(idFilter(waitlistId)
                        .append("user_id", id(userId)).append("status", "waiting"),
                new Document("$set", new Document("status", "canceled").append("logs", List.of(
                        new Document("callStatus", blank(reason) ? "cancelled : user cancelled" : reason)
                                .append("cancelledBy", "user").append("timestamp", now)))
                        .append("updatedAt", now)),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        if (entry == null) return null;
        double refund = number(doc(doc(entry.get("waitlistCopy")).get("session_info")).get("holdAmount"));
        if (refund > 0) refund(userId, waitlistId, refund);
        return entry;
    }

    @Override
    public Document rejoinWaitlist(String waitlistId, String consultantId, String userId) {
        Document participant = idFilter(waitlistId).append("user_id", userId)
                .append("consultant_id", consultantId);
        Document active = col(Collections.WAITLISTS).find(new Document(participant)
                .append("status", new Document("$in", List.of("waiting", "progress")))).first();
        if (active != null) return active;
        return col(Collections.WAITLISTS).findOneAndUpdate(new Document(participant)
                        .append("status", new Document("$in", List.of("missed", "canceled", "blocked"))),
                new Document("$set", new Document("status", "waiting").append("updatedAt", new Date()))
                        .append("$push", new Document("logs", new Document("callStatus", "user_joined_waitlist")
                                .append("timestamp", new Date()))),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
    }

    @Override
    @Transactional(transactionManager = "mongoTransactionManager")
    public MessageClaim claimWaitlistMessage(String waitlistId, String role, String actorId, String message) {
        if (!Role.CONSULTANT.equals(role)) return null;
        Document claimed = col(Collections.WAITLISTS).findOneAndUpdate(idFilter(waitlistId)
                        .append("consultant_id", actorId)
                        .append("session_info.messageLimit", new Document("$gt", 0)),
                new Document("$inc", new Document("session_info.messageLimit", -1))
                        .append("$set", new Document("updatedAt", new Date())),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        if (claimed == null) return null;
        int remaining = (int) whole(doc(claimed.get("session_info")).get("messageLimit"));
        Document consultant = consultantDetails(actorId);
        String consultantName = consultant == null ? "Consultant" : text(consultant.get("accountName"));
        if (consultantName.isBlank()) consultantName = "Consultant";
        String body = consultantName + " : " + (blank(message) ? "Sent a message" : message);
        Document data = new Document("screen", "OnCallEndScreen")
                .append("params", new Document("waitlistId", waitlistId)
                        .append("roomId", claimed.get("threadId")).append("channel", "chat")
                        .append("callStatus", claimed.get("status")))
                .append("waitlistId", waitlistId).append("roomId", claimed.get("threadId"))
                .append("channel", "chat").append("callStatus", claimed.get("status"))
                .append("permissions", postChatPermissions());
        outbox.enqueue("APP_PUSH:POST_CHAT:" + waitlistId + ":" + remaining,
                "APP_PUSH_NOTIFICATION", new Document("targetId", claimed.get("user_id"))
                        .append("targetType", "user").append("title", "Vedic Meet")
                        .append("body", body).append("data", data));
        return new MessageClaim(claimed, remaining);
    }

    @Override
    @Transactional(transactionManager = "mongoTransactionManager")
    public MessageClaim claimLeaveMessage(String consultantId, String userId, String role, String actorId) {
        String limitField;
        String targetType;
        String targetId;
        if (Role.USER.equals(role) && userId.equals(actorId)) {
            limitField = "userMessageLimit";
            targetType = "cons";
            targetId = consultantId;
        } else if (Role.CONSULTANT.equals(role) && consultantId.equals(actorId)) {
            limitField = "consMessageLimit";
            targetType = "user";
            targetId = userId;
        } else return null;

        Document mapping = col(Collections.CONSULTANT_LEAVE_MESSAGE_MAPPINGS).findOneAndUpdate(
                new Document("user_id", userId).append("consultant_id", consultantId)
                        .append("isDeleted", false).append(limitField, new Document("$gt", 0)),
                new Document("$inc", new Document(limitField, -1)).append("$set",
                        new Document("updatedAt", new Date())),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        if (mapping == null) return null;
        int remaining = (int) whole(mapping.get(limitField));
        Document data = new Document("screen", "LeaveAMessageChatScreen")
                .append("threadId", mapping.get("thread_id")).append("consultantId", consultantId)
                .append("waitlistId", mapping.get("waitlist_id")).append("userId", userId)
                .append("fromScreen", "useSupportList");
        String title = Role.USER.equals(role) ? "User sent a message!" : "Vedic Meet";
        String body;
        if (Role.USER.equals(role)) body = "User has sent a message to you";
        else {
            Document consultant = consultantDetails(consultantId);
            String name = consultant == null ? "Consultant" : text(consultant.get("accountName"));
            body = (name.isBlank() ? "Consultant" : name) + " has left a message for you.";
        }
        outbox.enqueue("APP_PUSH:LEAVE_MESSAGE:" + text(mapping.get("_id")) + ":" + role + ":" + remaining,
                "APP_PUSH_NOTIFICATION", new Document("targetId", targetId)
                        .append("targetType", targetType).append("title", title)
                        .append("body", body).append("data", data));
        return new MessageClaim(mapping, remaining);
    }

    @Override
    public void updatePresence(String role, String actorId, String status, double connectionSeconds) {
        if (!Role.USER.equals(role) && !Role.CONSULTANT.equals(role)) return;
        String collection = Role.USER.equals(role) ? Collections.USERS : Collections.CONSULTANTS;
        Date now = new Date();
        col(collection).updateOne(new Document("_id", id(actorId)),
                new Document("$set", new Document("isActive", status).append("updatedAt", now))
                        .append("$push", new Document("logs.app", new Document("$each", List.of(
                                new Document("timestamp", now).append("status", status)))
                                .append("$slice", -20))));
        if (!"offline".equals(status) || connectionSeconds <= 0) return;
        updateDailyPresence(role, actorId, connectionSeconds, now);
    }

    private void updateDailyPresence(String role, String actorId, double seconds, Date now) {
        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        String day = today.getDayOfMonth() + "-" + today.getMonthValue() + "-" + today.getYear();
        Document key = new Document("userType", Role.CONSULTANT.equals(role) ? "cons" : "user")
                .append("userID", id(actorId)).append("for", "app_online").append("day", day);
        Date eligibleBefore = new Date(now.getTime() - 60_000L);
        Document updated = col(Collections.STATS).findOneAndUpdate(new Document(key).append("$or", List.of(
                        new Document("updatedAt", new Document("$lte", eligibleBefore)),
                        new Document("updatedAt", new Document("$exists", false)))),
                new Document("$inc", new Document("totalTime", seconds))
                        .append("$push", new Document("activity", new Document("totalTime", seconds)
                                .append("at", now))).append("$set", new Document("updatedAt", now)),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        if (updated != null || col(Collections.STATS).countDocuments(key) > 0) return;
        try {
            col(Collections.STATS).insertOne(new Document(key).append("totalTime", seconds)
                    .append("activity", List.of(new Document("totalTime", seconds).append("at", now)))
                    .append("createdAt", now).append("updatedAt", now));
        } catch (MongoWriteException duplicate) {
            if (duplicate.getError().getCode() != 11000) throw duplicate;
        }
    }

    private Document enrichProgress(Document waitlist, boolean includeConsultant) {
        if (waitlist == null) return null;
        String timerId = text(waitlist.get("_id")) + ":duration";
        long remaining = timers.getRemainingTime(timerId);
        if (!timers.isTimerActive(timerId)) return null;
        Document copy = new Document(waitlist);
        copy.append("accessbilities", accessibilities(waitlist));
        if (includeConsultant) {
            Document consultant = consultantDetails(text(waitlist.get("consultant_id")));
            Document info = new Document("name", consultant == null ? null : consultant.get("accountName"))
                    .append("profileImage", consultant == null ? null : media(consultant.get("profileImage")));
            copy.append("consultantDetails", consultant == null ? List.of() : List.of(consultant))
                    .append("consultantInfo", info);
        }
        return new Document("remainingTime", remaining).append("waitlist", copy);
    }

    private List<String> accessibilities(Document waitlist) {
        List<String> result = new ArrayList<>(List.of("chat"));
        Document session = doc(waitlist.get("session_info"));
        Document coupon = doc(waitlist.get("coupon"));
        String couponId = text(coupon.get("_id"));
        String mode = text(session.get("mode"));
        if ("67d195c835f0c73d7a488f7d".equals(couponId)) return result;
        if ("session".equals(waitlist.getString("used_for")) || "video".equals(mode)) {
            result.add("audio"); result.add("video");
        } else if ("audio".equals(mode) || "chat".equals(mode)
                || "680218a0e65f3c49bcc45cc2".equals(couponId)) result.add("audio");
        return result;
    }

    private void restoreSystemOffer(Document waitlist) {
        Document coupon = doc(waitlist.get("coupon"));
        Object offerId = coupon.get("offerId");
        if (offerId == null) return;
        Document filter = new Document("userId", id(waitlist.get("user_id")))
                .append("offerRuleId", id(offerId));
        if (coupon.get("variantId") != null) filter.append("variantId", id(coupon.get("variantId")));
        Document state = col(Collections.USER_OFFER_STATES).find(filter).first();
        if (state == null) return;
        int times = Math.max(0, (int) whole(state.get("timesUsed")) - 1);
        col(Collections.USER_OFFER_STATES).updateOne(new Document("_id", state.get("_id")),
                new Document("$set", new Document("timesUsed", times).append("isActive", true)
                        .append("isExhausted", false).append("updatedAt", new Date())));
    }

    private void refundScheduledWaitlist(Document entry, String userId) {
        if (!"session".equals(entry.getString("used_for"))) return;
        Document session = doc(entry.get("session_info"));
        double refund = number(doc(session.get("sessionMeta")).get("sessionTimeInMinutes"))
                * number(session.get("price"));
        if (refund > 0) refund(userId, text(entry.get("_id")), refund);
    }

    private void refund(String userId, String waitlistId, double amount) {
        Date now = new Date();
        col(Collections.USERS).updateOne(new Document("_id", id(userId)),
                new Document("$inc", new Document("wallet", amount)));
        col(Collections.WALLET_TRANSACTIONS).insertOne(new Document("userId", id(userId))
                .append("userType", "user").append("transactionFor", "refund")
                .append("coins", amount).append("transactionType", 0)
                .append("isConsTransfer", false).append("meta", new Document("sessionId", waitlistId))
                .append("createdAt", now).append("updatedAt", now));
    }

    private Document postChatPermissions() {
        return new Document("canShareFiles", false).append("canUserPost", false)
                .append("canShareAudio", false).append("canShareVideo", false)
                .append("canAccessGallery", true).append("canAccessEndCall", false);
    }

    private Object media(Object value) {
        if (value == null || text(value).isBlank()) return value;
        String image = text(value);
        return image.matches("(?i)^https?://.*") ? image : constants.mediaUrl + image.replaceFirst("^/", "");
    }

    private Document withMedia(Document source) {
        if (source == null) return null;
        Document copy = new Document(source);
        Object image = copy.get("profileImage");
        // Node get-session-details only treats https as already absolute.
        if (image != null && !text(image).isBlank() && !text(image).startsWith("https")) {
            copy.put("profileImage", constants.mediaUrl + text(image).replaceFirst("^/", ""));
        }
        return copy;
    }

    private MongoCollection<Document> col(String name) { return mongo.getCollection(name); }
    private Document idFilter(String value) { return new Document("_id", id(value)); }
    private Object id(Object value) {
        if (value instanceof ObjectId) return value;
        String candidate = text(value);
        return ObjectId.isValid(candidate) ? new ObjectId(candidate) : value;
    }
    private Document doc(Object value) { return value instanceof Document d ? d : new Document(); }
    private String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private boolean blank(String value) { return value == null || value.isBlank(); }
    private long whole(Object value) {
        if (value instanceof Number n) return n.longValue();
        try { return value == null ? 0 : Long.parseLong(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return 0; }
    }
    private double number(Object value) {
        if (value instanceof Number n) return n.doubleValue();
        try { return value == null ? 0 : Double.parseDouble(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return 0; }
    }
}
