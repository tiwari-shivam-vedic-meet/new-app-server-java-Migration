package com.vedicmeet.appserver.cron;

import com.mongodb.client.FindIterable;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.vedicmeet.appserver.call.CallIntegrationOutboxService;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import com.vedicmeet.appserver.integrations.InteraktClient;
import com.vedicmeet.appserver.integrations.PabblyClient;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Safe port of the active business jobs in Node {@code utils/functions/jobs.js}.
 *
 * <p>All provider work is written to the existing durable Java integration outbox. Call retries use
 * an atomic missed-to-waiting claim before enqueueing, preventing two Java scheduler instances from
 * initiating the same retry. The enclosing scheduler and every high-risk job have independent
 * default-off switches.</p>
 */
@Service
public class LegacyCronService {

    public record MaintenanceResult(long consultants, long mappings) {}

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final MongoTemplate mongo;
    private final CallIntegrationOutboxService outbox;
    private final PabblyClient pabbly;
    private final InteraktClient interakt;
    private final Clock clock;
    private final int retryFirstMinutes;
    private final int retrySecondHours;
    private final int retryBusyDeferMinutes;
    private final int retryBatchLimit;
    private final int uninstallInactiveDays;
    private final int uninstallBatchLimit;
    private final String freeCouponCode;

    @Autowired
    public LegacyCronService(MongoTemplate mongo, CallIntegrationOutboxService outbox,
                             PabblyClient pabbly, InteraktClient interakt,
                             @Value("${vedicmeet.cron.missed-retry-first-minutes:30}") int retryFirstMinutes,
                             @Value("${vedicmeet.cron.missed-retry-second-hours:24}") int retrySecondHours,
                             @Value("${vedicmeet.cron.missed-retry-busy-defer-minutes:5}") int retryBusyDeferMinutes,
                             @Value("${vedicmeet.cron.missed-retry-batch-limit:100}") int retryBatchLimit,
                             @Value("${vedicmeet.cron.uninstall-inactive-days:7}") int uninstallInactiveDays,
                             @Value("${vedicmeet.cron.uninstall-batch-limit:1000}") int uninstallBatchLimit,
                             @Value("${FREE_CHAT_COUPON_CODE:FREE5MINUTES}") String freeCouponCode) {
        this(mongo, outbox, pabbly, interakt, Clock.systemUTC(), retryFirstMinutes,
                retrySecondHours, retryBusyDeferMinutes, retryBatchLimit,
                uninstallInactiveDays, uninstallBatchLimit, freeCouponCode);
    }

    LegacyCronService(MongoTemplate mongo, CallIntegrationOutboxService outbox,
                      PabblyClient pabbly, InteraktClient interakt, Clock clock,
                      int retryFirstMinutes, int retrySecondHours,
                      int retryBusyDeferMinutes, int retryBatchLimit,
                      int uninstallInactiveDays, int uninstallBatchLimit,
                      String freeCouponCode) {
        this.mongo = mongo;
        this.outbox = outbox;
        this.pabbly = pabbly;
        this.interakt = interakt;
        this.clock = clock;
        this.retryFirstMinutes = retryFirstMinutes;
        this.retrySecondHours = retrySecondHours;
        this.retryBusyDeferMinutes = retryBusyDeferMinutes;
        this.retryBatchLimit = retryBatchLimit;
        this.uninstallInactiveDays = uninstallInactiveDays;
        this.uninstallBatchLimit = uninstallBatchLimit;
        this.freeCouponCode = freeCouponCode;
    }

    /** Node BusinessLogic.every24HoursOps. */
    public MaintenanceResult resetDailyLimits() {
        Date now = now();
        long consultants = mongo.getCollection(Collections.CONSULTANTS).updateMany(new Document(),
                new Document("$set", new Document("limit.liveSessions.current", 0)
                        .append("updated_at", now))).getModifiedCount();
        long mappings = mongo.getCollection(Collections.CONSULTANT_LEAVE_MESSAGE_MAPPINGS)
                .updateMany(new Document(), new Document("$set", new Document("userMessageLimit", 5)
                        .append("consMessageLimit", 5))).getModifiedCount();
        return new MaintenanceResult(consultants, mappings);
    }

    /** Node BusinessLogic.everyMonthEndOps. */
    public long resetMonthlyFlagLimits() {
        return mongo.getCollection(Collections.CONSULTANTS).updateMany(new Document(),
                new Document("$set", new Document("limit.flags.current", 0)
                        .append("updated_at", now()))).getModifiedCount();
    }

    /** Node consultant-availability-status-reset, with an atomic claim and durable call dispatch. */
    public int retryMissedCalls() {
        Date current = now();
        Date firstCutoff = Date.from(current.toInstant().minusSeconds(retryFirstMinutes * 60L));
        Date secondCutoff = Date.from(current.toInstant().minusSeconds(retrySecondHours * 3600L));
        Document due = new Document("used_for", "private_call").append("status", "missed")
                .append("$or", List.of(
                        new Document("session_info.nextRetryAt", new Document("$lte", current))
                                .append("session_info.missedRetryCount", new Document("$lt", 2)),
                        new Document("session_info.nextRetryAt", new Document("$exists", false))
                                .append("$or", List.of(
                                        new Document("updatedAt", new Document("$lte", firstCutoff))
                                                .append("session_info.isTriedAgainAfterMissed", new Document("$ne", true)),
                                        new Document("session_info.isTriedAgainAfterMissed", true)
                                                .append("session_info.firstMissedAt", new Document("$lte", secondCutoff))))));

        int enqueued = 0;
        for (Document waitlist : mongo.getCollection(Collections.WAITLISTS).find(due)
                .sort(new Document("session_info.nextRetryAt", 1)).limit(retryBatchLimit)) {
            Object userId = waitlist.get("user_id");
            Object consultantId = waitlist.get("consultant_id");
            Object waitlistId = waitlist.get("_id");
            if (userId == null || consultantId == null || waitlistId == null) continue;

            Document busy = mongo.getCollection(Collections.WAITLISTS).find(new Document("$or", List.of(
                    new Document("user_id", new Document("$in", idVariants(userId))).append("status", "progress"),
                    new Document("consultant_id", new Document("$in", idVariants(consultantId))).append("status", "progress"))))
                    .projection(new Document("_id", 1)).first();
            if (busy != null) {
                mongo.getCollection(Collections.WAITLISTS).updateOne(new Document("_id", waitlistId)
                                .append("status", "missed"),
                        new Document("$set", new Document("session_info.nextRetryAt",
                                Date.from(current.toInstant().plusSeconds(retryBusyDeferMinutes * 60L)))));
                continue;
            }

            if ("first_purchase".equals(text(doc(waitlist.get("coupon")).get("type")))) {
                Document used = mongo.getCollection(Collections.WAITLISTS).find(
                        new Document("user_id", new Document("$in", idVariants(userId)))
                                .append("status", "completed").append("coupon.type", "first_purchase"))
                        .projection(new Document("_id", 1)).first();
                if (used != null) {
                    mongo.getCollection(Collections.WAITLISTS).updateOne(new Document("_id", waitlistId)
                                    .append("status", "missed"),
                            new Document("$set", new Document("status", "canceled").append("updatedAt", current))
                                    .append("$push", new Document("logs", new Document("callStatus",
                                            "cancelled due to 1st purchase offer already used")
                                            .append("timestamp", current))));
                    continue;
                }
            }

            Document consultant = findById(Collections.CONSULTANTS, consultantId);
            String mode = text(doc(waitlist.get("session_info")).get("mode"));
            if (mode.isBlank()) mode = "chat";
            if (consultant == null || !modeAvailable(consultant, mode)) continue;

            Document session = doc(waitlist.get("session_info"));
            int nextCount = number(session.get("missedRetryCount")) + 1;
            Date firstMissed = date(session.get("firstMissedAt"));
            if (firstMissed == null) firstMissed = date(waitlist.get("updatedAt"));
            if (firstMissed == null) firstMissed = current;
            Object nextRetry = nextCount < 2
                    ? Date.from(firstMissed.toInstant().plusSeconds(retrySecondHours * 3600L)) : null;

            Document claimed = mongo.getCollection(Collections.WAITLISTS).findOneAndUpdate(
                    new Document("_id", waitlistId).append("status", "missed"),
                    new Document("$set", new Document("status", "waiting").append("updatedAt", current)
                            .append("session_info.missedRetryCount", nextCount)
                            .append("session_info.isTriedAgainAfterMissed", true)
                            .append("session_info.firstMissedAt", firstMissed)
                            .append("session_info.nextRetryAt", nextRetry))
                            .append("$push", new Document("logs", new Document("callStatus",
                                    "missed call retry claimed by java")
                                    .append("timestamp", current))),
                    new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
            if (claimed == null) continue;

            String id = text(waitlistId);
            outbox.enqueue("CALL_INITIATE:missed-retry:" + id + ":" + nextCount, "CALL_INITIATE",
                    new Document("userId", text(userId)).append("consultantId", text(consultantId))
                            .append("waitlistId", id).append("callMode", mode).append("callBy", "system"));
            enqueued++;
        }
        return enqueued;
    }

    /** Node session-book-notify: 10-minute reminder plus exact-minute fixed-session call. */
    public int processFixedSessions() {
        LocalDateTime current = LocalDateTime.ofInstant(clock.instant(), IST).withSecond(0).withNano(0);
        String exact = current.format(DateTimeFormatter.ofPattern("HH:mm"));
        String tenMinutes = current.plusMinutes(10).format(DateTimeFormatter.ofPattern("HH:mm"));
        int jobs = 0;

        Set<String> callUsers = new HashSet<>();
        for (Document session : fixedSessionsAt(exact)) {
            String userId = text(session.get("user_id"));
            if (!callUsers.add(userId)) continue;
            String consultantId = text(session.get("consultant_id"));
            String waitlistId = text(session.get("waitlist_id"));
            if (userId.isBlank() || consultantId.isBlank() || waitlistId.isBlank()) continue;
            outbox.enqueue("CALL_INITIATE:fixed:" + waitlistId, "CALL_INITIATE",
                    new Document("userId", userId).append("consultantId", consultantId)
                            .append("waitlistId", waitlistId).append("callMode", "session")
                            .append("callBy", "system"));
            jobs++;
        }

        Set<String> reminderUsers = new HashSet<>();
        for (Document session : fixedSessionsAt(tenMinutes)) {
            String userId = text(session.get("user_id"));
            if (!reminderUsers.add(userId) || userId.isBlank()) continue;
            enqueuePush("fixed-reminder:" + text(session.get("_id")) + ":" + tenMinutes,
                    "user", userId, "Your session starts in 10 minutes",
                    "Please be ready for your consultation session.",
                    new Document("type", "session_start").append("sessionTime", tenMinutes));
            jobs++;
        }
        return jobs;
    }

    /** Node consultant-next-available-time-notifier, de-duplicated per consultant/mode/time. */
    public int notifyUpcomingConsultants() {
        LocalDateTime current = LocalDateTime.ofInstant(clock.instant(), IST);
        String start = current.plusMinutes(10).format(MINUTE);
        String end = current.plusMinutes(15).format(MINUTE);
        Document window = new Document("$gte", start).append("$lt", end);
        Document query = new Document("$or", List.of(
                new Document("sessionNextAvailableTime.isChatLive", window),
                new Document("sessionNextAvailableTime.isVoiceLive", window),
                new Document("sessionNextAvailableTime.isVideoLive", window)));
        int jobs = 0;
        for (Document consultant : mongo.getCollection(Collections.CONSULTANTS).find(query)) {
            String consultantId = text(consultant.get("_id"));
            Document next = doc(consultant.get("sessionNextAvailableTime"));
            for (Map.Entry<String, String> mode : Map.of(
                    "isChatLive", "chat", "isVoiceLive", "voice", "isVideoLive", "video").entrySet()) {
                String time = text(next.get(mode.getKey()));
                if (time.compareTo(start) < 0 || time.compareTo(end) >= 0) continue;
                enqueuePush("next-available:cons:" + consultantId + ":" + mode.getValue() + ":" + time,
                        "cons", consultantId, "Your session starts in 10 minutes",
                        "Please be ready for your consultation session.",
                        new Document("type", "session_start").append("sessionTime", time));
                jobs++;
                for (Document follow : mongo.getCollection(Collections.FOLLOWS).find(
                        new Document("consultantId", new Document("$in", idVariants(consultant.get("_id"))))
                                .append("status", true))) {
                    String userId = text(follow.get("userId"));
                    if (userId.isBlank()) continue;
                    enqueuePush("next-available:user:" + consultantId + ":" + userId + ":"
                                    + mode.getValue() + ":" + time,
                            "user", userId, "Consultant available soon",
                            displayName(consultant) + " will be available shortly.",
                            new Document("screen", "ConsultantDetailPage")
                                    .append("consultantId", consultantId));
                    jobs++;
                }
            }
        }
        return jobs;
    }

    /** Node uninstall-detection.js, bounded so one run cannot monopolize the scheduler. */
    public int detectAppUninstalls() {
        Date threshold = Date.from(clock.instant().minusSeconds(uninstallInactiveDays * 86400L));
        List<String> deviceIds = mongo.getCollection(Collections.ANALYTICS_LOGS).distinct(
                "device_info.device_id",
                new Document("event_type", "app_install")
                        .append("install_status", new Document("$in", List.of("installed", "reinstalled")))
                        .append("device_info.device_id", new Document("$exists", true).append("$ne", null)),
                String.class).into(new ArrayList<>());
        int detected = 0;
        for (String deviceId : deviceIds.stream().limit(uninstallBatchLimit).toList()) {
            Document deviceFilter = new Document("device_info.device_id", deviceId);
            if (mongo.getCollection(Collections.ANALYTICS_LOGS).find(new Document(deviceFilter)
                    .append("event_type", "app_uninstall").append("install_status", "uninstalled"))
                    .projection(new Document("_id", 1)).first() != null) continue;
            Document last = mongo.getCollection(Collections.ANALYTICS_LOGS).find(new Document(deviceFilter)
                            .append("event_type", new Document("$in", List.of(
                                    "app_activity", "app_install", "app_launch", "app_heartbeat"))))
                    .sort(new Document("timestamp", -1)).first();
            Date lastActivity = last == null ? null : date(last.get("timestamp"));
            if (lastActivity == null || !lastActivity.before(threshold)) continue;

            Document install = mongo.getCollection(Collections.ANALYTICS_LOGS).find(new Document(deviceFilter)
                    .append("event_type", "app_install")).sort(new Document("timestamp", -1)).first();
            Object userIdentity = last.get("user_id") != null ? last.get("user_id")
                    : last.get("userId") != null ? last.get("userId")
                    : install == null ? null : install.get("user_id");
            Date current = now();
            String eventId = "java-uninstall:" + deviceId;
            Document event = new Document("_id", eventId).append("event_name", "app_uninstall")
                    .append("event_type", "app_uninstall")
                    .append("event_params", new Document("device_id", deviceId)
                            .append("last_activity_timestamp", lastActivity.toInstant().toString())
                            .append("inactive_days", uninstallInactiveDays)
                            .append("detection_method", "inactivity_threshold")
                            .append("detected_at", current.toInstant().toString()))
                    .append("userId", userIdentity).append("platform", "mobile")
                    .append("timestamp", current)
                    .append("device_info", install != null ? install.get("device_info") : last.get("device_info"))
                    .append("install_status", "uninstalled").append("uninstall_timestamp", current)
                    .append("status", "success");
            try {
                mongo.getCollection(Collections.ANALYTICS_LOGS).insertOne(event);
            } catch (com.mongodb.MongoWriteException duplicate) {
                if (duplicate.getError().getCode() == 11000) continue;
                throw duplicate;
            }
            detected++;
            markUserUninstalledIfLastDevice(userIdentity, deviceId, threshold, current);
        }
        return detected;
    }

    /** Node hourly Pabbly job, but sends a minimal allow-listed payload instead of whole user records. */
    public int enqueueNewUsersForPabbly() {
        if (!pabbly.isReady()) return 0;
        Date end = now();
        Date start = Date.from(end.toInstant().minusSeconds(3600));
        int count = 0;
        for (Document user : mongo.getCollection(Collections.USERS).find(
                new Document("createdAt", new Document("$gte", start).append("$lt", end)))) {
            String userId = text(user.get("_id"));
            Document details = doc(user.get("details"));
            Document data = new Document("Name", displayName(user))
                    .append("Phone", text(details.get("phone"))).append("createdAt", user.get("createdAt"));
            enqueuePabbly("new-user:" + userId, data);
            count++;
        }
        return count;
    }

    /** Node noon/evening free-consult follow-up job for the supplied IST business window. */
    public int enqueueFreeConsultsForPabbly(LocalDateTime startIst, LocalDateTime endIst) {
        if (!pabbly.isReady()) return 0;
        Date start = Date.from(startIst.atZone(IST).toInstant());
        Date end = Date.from(endIst.atZone(IST).toInstant());
        LinkedHashMap<String, Document> latest = new LinkedHashMap<>();
        for (Document waitlist : mongo.getCollection(Collections.WAITLISTS).find(
                        new Document("status", "completed").append("coupon.code", freeCouponCode)
                                .append("updatedAt", new Document("$gte", start).append("$lt", end)))
                .sort(new Document("updatedAt", -1))) {
            latest.putIfAbsent(text(waitlist.get("user_id")), waitlist);
        }
        int count = 0;
        for (Map.Entry<String, Document> item : latest.entrySet()) {
            String userId = item.getKey();
            if (mongo.getCollection(Collections.WAITLISTS).find(
                    new Document("user_id", new Document("$in", idVariants(userId)))
                            .append("status", "completed").append("coupon.code", new Document("$ne", freeCouponCode)))
                    .projection(new Document("_id", 1)).first() != null) continue;
            Document user = findById(Collections.USERS, userId);
            if (user == null) continue;
            Document request = doc(item.getValue().get("request_form"));
            String name = (text(request.get("firstName")) + " " + text(request.get("lastName"))).trim();
            if (name.isBlank()) name = displayName(user);
            String phone = text(request.get("phoneNumber"));
            if (phone.isBlank()) phone = text(doc(user.get("details")).get("phone"));
            Document data = new Document("Name", name).append("Phone", phone)
                    .append("Problem", fallback(text(request.get("concern")), "N/A"));
            enqueuePabbly("free-consult:" + endIst.format(MINUTE) + ":" + userId, data);
            count++;
        }
        return count;
    }

    /** Node user-registered-last-6-hours-not-taken-consultation. */
    public int enqueueUnconsultedUsersForInterakt() {
        if (!interakt.isReady()) return 0;
        Date end = now();
        Date start = Date.from(end.toInstant().minusSeconds(6 * 3600L));
        int count = 0;
        for (Document user : mongo.getCollection(Collections.USERS).find(
                new Document("createdAt", new Document("$gt", start).append("$lte", end))
                        .append("isDeleted", false).append("status", true))) {
            String userId = text(user.get("_id"));
            if (hasCompletedConsultation(userId)) continue;
            if (enqueueInterakt("registered-no-consult", user,
                    "user_registered_but_after_6_hours_not_taken_consultation")) count++;
        }
        return count;
    }

    /** Node user-first-consult-done-but-second-not-taken-after-6-hours. */
    public int enqueueOneConsultUsersForInterakt() {
        if (!interakt.isReady()) return 0;
        Date end = now();
        Date start = Date.from(end.toInstant().minusSeconds(6 * 3600L));
        Set<String> candidates = new LinkedHashSet<>();
        for (Document waitlist : mongo.getCollection(Collections.WAITLISTS).find(
                new Document("status", "completed")
                        .append("createdAt", new Document("$gt", start).append("$lte", end)))) {
            candidates.add(text(waitlist.get("user_id")));
        }
        int count = 0;
        for (String userId : candidates) {
            long completed = mongo.getCollection(Collections.WAITLISTS).countDocuments(
                    new Document("status", "completed")
                            .append("user_id", new Document("$in", idVariants(userId))));
            if (completed >= 2) continue;
            Document user = findById(Collections.USERS, userId);
            if (user == null || Boolean.TRUE.equals(user.getBoolean("isDeleted"))
                    || !Boolean.TRUE.equals(user.getBoolean("status"))) continue;
            if (enqueueInterakt("first-consult-only", user,
                    "user_first_consult_done_but_second_not_taken_after_6_hours")) count++;
        }
        return count;
    }

    LocalDateTime nowIst() {
        return LocalDateTime.ofInstant(clock.instant(), IST);
    }

    private List<Document> fixedSessionsAt(String time) {
        return mongo.getCollection(Collections.FIXED_SESSION_WAITLISTS)
                .find(new Document("status", "waiting").append("preferredTimeAt", time))
                .sort(new Document("createdAt", 1)).into(new ArrayList<>());
    }

    private void markUserUninstalledIfLastDevice(Object userIdentity, String deviceId,
                                                  Date threshold, Date current) {
        if (userIdentity == null) return;
        Document user = mongo.getCollection(Collections.USERS).find(new Document("$or", List.of(
                new Document("_id", new Document("$in", idVariants(userIdentity))),
                new Document("details.phone", text(userIdentity))))).first();
        if (user == null) return;
        Object ids = user.get("deviceIds");
        List<?> otherIds = ids instanceof List<?> list
                ? list.stream().filter(id -> !deviceId.equals(text(id))).toList() : List.of();
        if (!otherIds.isEmpty()) {
            Document active = mongo.getCollection(Collections.ANALYTICS_LOGS).find(
                    new Document("device_info.device_id", new Document("$in", otherIds))
                            .append("event_type", new Document("$in", List.of(
                                    "app_activity", "app_install", "app_launch")))
                            .append("timestamp", new Document("$gte", threshold)))
                    .projection(new Document("_id", 1)).first();
            if (active != null) return;
        }
        mongo.getCollection(Collections.USERS).updateOne(new Document("_id", user.get("_id")),
                new Document("$set", new Document("appUninstalled", true)
                        .append("appUninstallTimestamp", current)));
    }

    private boolean enqueueInterakt(String key, Document user, String eventName) {
        String userId = text(user.get("_id"));
        String phone = text(doc(user.get("details")).get("phone"));
        if (phone.isBlank()) return false;
        Document traits = new Document("createdAt", user.get("createdAt"));
        outbox.enqueue("CRON_INTERAKT:" + key + ":" + userId, "CRON_INTERAKT_EVENT",
                new Document("eventName", eventName).append("userId", userId).append("phone", phone)
                        .append("traits", traits).append("eventProperties", new Document()));
        return true;
    }

    private boolean hasCompletedConsultation(String userId) {
        return mongo.getCollection(Collections.WAITLISTS).find(
                new Document("status", "completed")
                        .append("user_id", new Document("$in", idVariants(userId))))
                .projection(new Document("_id", 1)).first() != null;
    }

    private void enqueuePabbly(String key, Document data) {
        outbox.enqueue("CRON_PABBLY:" + key, "CRON_PABBLY_EVENT", new Document("data", data));
    }

    private void enqueuePush(String key, String targetType, String targetId, String title,
                             String body, Document data) {
        outbox.enqueue("CRON_PUSH:" + key, "APP_PUSH_NOTIFICATION",
                new Document("targetType", targetType).append("targetId", targetId)
                        .append("title", title).append("body", body).append("data", data));
    }

    private boolean modeAvailable(Document consultant, String mode) {
        Document sessions = doc(consultant.get("sessionsStatus"));
        return switch (mode) {
            case "chat" -> Boolean.TRUE.equals(sessions.getBoolean("isChatLive"));
            case "audio" -> Boolean.TRUE.equals(sessions.getBoolean("isVoiceLive"));
            case "video" -> Boolean.TRUE.equals(sessions.getBoolean("isVideoLive"));
            default -> false;
        };
    }

    private Document findById(String collection, Object raw) {
        return mongo.getCollection(collection).find(
                new Document("_id", new Document("$in", idVariants(raw)))).first();
    }

    private List<Object> idVariants(Object raw) {
        List<Object> values = new ArrayList<>();
        if (raw == null) return values;
        values.add(raw);
        String value = text(raw);
        if (ObjectId.isValid(value) && !(raw instanceof ObjectId)) values.add(new ObjectId(value));
        if (raw instanceof ObjectId) values.add(value);
        return values;
    }

    private Document doc(Object value) {
        if (value instanceof Document document) return document;
        if (value instanceof Map<?, ?> map) return new Document((Map<String, Object>) map);
        return new Document();
    }

    private String displayName(Document actor) {
        String value = text(actor.get("accountName"));
        if (value.isBlank()) value = text(actor.get("name"));
        return fallback(value, "N/A");
    }

    private Date now() { return Date.from(clock.instant()); }
    private Date date(Object value) { return value instanceof Date date ? date : null; }
    private int number(Object value) { return value instanceof Number number ? number.intValue() : 0; }
    private String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private String fallback(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }
}
