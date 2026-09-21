package com.vedicmeet.appserver.content;

import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.vedicmeet.appserver.config.AppConstants;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import com.vedicmeet.appserver.media.MediaUploadService;
import com.vedicmeet.appserver.notification.PushNotificationService;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Mobile parity for Node {@code utils/classes/task-remedy.js}. */
@Service
public class TaskRemedyService {
    private final MongoTemplate mongo;
    private final TaskReminderService reminders;
    private final PushNotificationService push;
    private final MediaUploadService mediaUpload;
    private final AppConstants constants;

    public TaskRemedyService(MongoTemplate mongo, TaskReminderService reminders,
                             PushNotificationService push, MediaUploadService mediaUpload,
                             AppConstants constants) {
        this.mongo = mongo;
        this.reminders = reminders;
        this.push = push;
        this.mediaUpload = mediaUpload;
        this.constants = constants;
    }

    public Document addTask(Map<String, Object> input, String userType, Document actor) {
        required(input, "title");
        String reminderType = required(input, "typeOfReminder");
        if (!List.of("task", "remedy").contains(reminderType))
            throw new IllegalArgumentException("typeOfReminder must be one of task, remedy");
        if ("remedy".equals(reminderType)) required(input, "orderNotesRemedyId");
        ObjectId actorId = actorId(actor);
        ObjectId id = new ObjectId();
        List<String> dates = normalizedDates(input.get("dates"));
        List<Document> timings = normalizedTimings(input.get("timings"));
        Document task = copy(input);
        task.put("_id", id);
        task.put("dates", dates);
        task.put("remainingDates", new ArrayList<>(dates));
        task.put("timings", timings);
        task.put("isCompleted", false);
        if ("user".equals(userType)) task.put("userId", actorId);
        else if ("cons".equals(userType) || "consultant".equals(userType)) task.put("consultantId", actorId);
        convertId(task, "orderNotesRemedyId");
        Date now = new Date();
        task.put("createdAt", now);
        task.put("updatedAt", now);
        List<String> jobs = reminders.schedule(actorId, id, dates, timings, text(input, "earlyReminder"),
                tokens(actor), normalizeUserType(userType), reminderType, text(input, "title"));
        task.put("jobId", jobs);
        mongo.getCollection(Collections.TASK_REMEDIES).insertOne(task);
        return task;
    }

    public void editTask(Map<String, Object> input, String userType, Document actor) {
        ObjectId id = objectId(required(input, "taskRemedyId"), "taskRemedyId");
        Document current = mongo.getCollection(Collections.TASK_REMEDIES)
                .find(new Document("_id", id)).first();
        if (current == null) throw new IllegalArgumentException("TASK_REMEDY_NOT_EXIST");
        Document changes = copy(input);
        changes.remove("taskRemedyId");
        if (input.containsKey("timings")) changes.put("timings", normalizedTimings(input.get("timings")));
        if (input.containsKey("dates")) {
            List<String> dates = normalizedDates(input.get("dates"));
            changes.put("dates", dates);
            changes.put("remainingDates", new ArrayList<>(dates));
        }
        convertId(changes, "orderNotesRemedyId");
        changes.put("updatedAt", new Date());
        mongo.getCollection(Collections.TASK_REMEDIES).updateOne(
                new Document("_id", id), new Document("$set", changes));
        Document merged = mongo.getCollection(Collections.TASK_REMEDIES).find(new Document("_id", id)).first();
        reminders.cancel(id);
        List<String> jobs = reminders.schedule(actorId(actor), id,
                merged.getList("dates", String.class, List.of()),
                merged.getList("timings", Document.class, List.of()),
                merged.getString("earlyReminder"), tokens(actor), normalizeUserType(userType),
                merged.getString("typeOfReminder"), merged.getString("title"));
        mongo.getCollection(Collections.TASK_REMEDIES).updateOne(new Document("_id", id),
                new Document("$set", new Document("jobId", jobs).append("updatedAt", new Date())));
    }

    public Document details(String taskRemedyId) {
        ObjectId id = objectId(taskRemedyId, "taskRemedyId");
        Document task = mongo.getCollection(Collections.TASK_REMEDIES)
                .find(new Document("_id", id)).first();
        if (task == null) throw new IllegalArgumentException("TASK_REMEDY_NOT_EXIST");
        return refreshUpcoming(task);
    }

    public void checkUncheck(String taskRemedyId, String timeId, Number status, Document actor) {
        ObjectId id = objectId(taskRemedyId, "taskRemedyId");
        ObjectId timingId = objectId(timeId, "timeId");
        if (status == null) throw new IllegalArgumentException("status is required");
        Document current = mongo.getCollection(Collections.TASK_REMEDIES)
                .find(new Document("_id", id)).first();
        if (current == null) throw new IllegalArgumentException("Task remedy does not exists");
        Document updated = mongo.getCollection(Collections.TASK_REMEDIES).findOneAndUpdate(
                new Document("_id", id).append("timings._id", timingId),
                new Document("$set", new Document("timings.$.status", status)
                        .append("isCompleted", true).append("updatedAt", new Date())),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        if (updated != null) {
            List<Document> timings = updated.getList("timings", Document.class, List.of());
            if (!timings.isEmpty() && Integer.valueOf(3).equals(number(timings.get(timings.size() - 1).get("status")))) {
                for (String token : tokens(actor)) push.sendNotificationAndCons("user", token,
                        "Congratulations! You've completed your " + updated.getString("typeOfReminder"),
                        Map.of("isConsAvailable", "true"), "Vedic Meet", "");
            }
        }
    }

    public Document deleteTask(String taskRemedyId) {
        ObjectId id = objectId(taskRemedyId, "taskRemedyId");
        Document task = mongo.getCollection(Collections.TASK_REMEDIES)
                .find(new Document("_id", id)).first();
        if (task == null) throw new IllegalArgumentException("Task remedy does not exists");
        reminders.cancel(id);
        return mongo.getCollection(Collections.TASK_REMEDIES).findOneAndDelete(new Document("_id", id));
    }

    public Map<String, Object> listTasks(Document actor, Integer pageParam, Integer limitParam, String search) {
        int page = pageParam == null ? 1 : Math.max(1, pageParam);
        int limit = limitParam == null ? 10 : Math.max(1, limitParam);
        ObjectId id = actorId(actor);
        Document filter = new Document("$or", List.of(new Document("userId", id),
                new Document("consultantId", id)));
        if (search != null && !search.isBlank())
            filter.append("title", Pattern.compile(Pattern.quote(search.trim()), Pattern.CASE_INSENSITIVE));
        Document projection = new Document("title", 1).append("createdAt", 1)
                .append("typeOfReminder", 1).append("isCompleted", 1)
                .append("orderNotesRemedyId", 1).append("dates", 1).append("timings", 1)
                .append("description", 1);
        List<Document> list = mongo.getCollection(Collections.TASK_REMEDIES).find(filter)
                .projection(projection).sort(new Document("createdAt", -1))
                .skip((page - 1) * limit).limit(limit).into(new ArrayList<>());
        for (Document task : list) populateOrderNote(task);
        return result(list, mongo.getCollection(Collections.TASK_REMEDIES).countDocuments(filter));
    }

    public Document addConsultantTask(Map<String, Object> input, Document actor) {
        String title = required(input, "title");
        Instant start = normalizedDate(input.get("startDate"));
        Instant end = normalizedDate(input.get("endDate"));
        if (end.isBefore(start)) throw new IllegalArgumentException("END_GREATER_START");
        String startTime = required(input, "startTime");
        String endTime = required(input, "endTime");
        if (start.equals(end) && !isStartBefore(startTime, endTime))
            throw new IllegalArgumentException("END_TIME_GREATER_START");
        ObjectId id = new ObjectId();
        ObjectId owner = actorId(actor);
        Document task = copy(input);
        task.put("_id", id);
        task.put("consultantId", owner);
        task.put("startDate", Date.from(start));
        task.put("endDate", Date.from(end));
        task.put("isCompleted", false);
        Date now = new Date();
        task.put("createdAt", now);
        task.put("updatedAt", now);
        List<String> dates = allDates(start, end, Boolean.TRUE.equals(input.get("isAllDay")));
        List<Document> timing = List.of(new Document("_id", new ObjectId()).append("time", startTime).append("status", 0));
        task.put("jobId", reminders.schedule(owner, id, dates, timing, text(input, "earlyReminder"),
                tokens(actor), "cons", "task", title));
        mongo.getCollection(Collections.TASKS).insertOne(task);
        return task;
    }

    public Document editConsultantTask(Map<String, Object> input) {
        ObjectId id = objectId(required(input, "taskId"), "taskId");
        Document current = mongo.getCollection(Collections.TASKS).find(new Document("_id", id)).first();
        if (current == null) throw new IllegalArgumentException("TASK_NOT_EXIST");
        Instant start = input.containsKey("startDate") ? normalizedDate(input.get("startDate"))
                : dateInstant(current.get("startDate"));
        Instant end = input.containsKey("endDate") ? normalizedDate(input.get("endDate"))
                : dateInstant(current.get("endDate"));
        if (end.isBefore(start)) throw new IllegalArgumentException("END_GREATER_START");
        String startTime = input.containsKey("startTime") ? text(input, "startTime") : current.getString("startTime");
        String endTime = input.containsKey("endTime") ? text(input, "endTime") : current.getString("endTime");
        if (end.equals(start) && !isStartBefore(startTime, endTime))
            throw new IllegalArgumentException("END_TIME_GREATER_START");
        Document changes = copy(input);
        changes.remove("taskId");
        if (input.containsKey("startDate")) changes.put("startDate", Date.from(start));
        if (input.containsKey("endDate")) changes.put("endDate", Date.from(end));
        changes.put("updatedAt", new Date());
        return mongo.getCollection(Collections.TASKS).findOneAndUpdate(new Document("_id", id),
                new Document("$set", changes), new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
    }

    public Document deleteConsultantTask(String taskId) {
        ObjectId id = objectId(taskId, "taskId");
        Document task = mongo.getCollection(Collections.TASKS).find(new Document("_id", id)).first();
        if (task == null) throw new IllegalArgumentException("TASK_NOT_EXIST");
        reminders.cancel(id);
        return mongo.getCollection(Collections.TASKS).findOneAndDelete(new Document("_id", id));
    }

    public Document consultantTaskDetails(String taskId, String date) {
        ObjectId id = objectId(taskId, "taskId");
        LocalDate day = date == null || date.isBlank() ? LocalDate.now(ZoneOffset.UTC) : parseDay(date);
        Date start = Date.from(day.atStartOfDay().toInstant(ZoneOffset.UTC));
        Date end = Date.from(day.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).minusMillis(1));
        Document task = mongo.getCollection(Collections.TASKS).find(new Document("_id", id)
                .append("startDate", new Document("$lte", end))
                .append("endDate", new Document("$gte", start))).first();
        if (task == null) throw new IllegalArgumentException("TASK_NOT_EXIST");
        return task;
    }

    public Map<String, Object> consultantTasks(Document actor, String type, String fromDate,
                                                String toDate, Integer pageParam, Integer limitParam) {
        int page = pageParam == null ? 1 : Math.max(1, pageParam);
        int limit = limitParam == null ? 10 : Math.max(1, limitParam);
        ObjectId id = actorId(actor);
        Date today = Date.from(LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC));
        mongo.getCollection(Collections.TASKS).updateMany(new Document("consultantId", id)
                        .append("isCompleted", false).append("endDate", new Document("$lt", today)),
                new Document("$set", new Document("isCompleted", true).append("updatedAt", new Date())));
        Document filter = new Document("consultantId", id);
        if ("dateFilter".equals(type)) {
            Date from = Date.from(normalizedDate(fromDate));
            Date to = Date.from(normalizedDate(toDate));
            filter.append("$or", List.of(
                    new Document("startDate", new Document("$gte", from).append("$lte", to)),
                    new Document("endDate", new Document("$gte", from).append("$lte", to))));
        }
        List<Document> list = mongo.getCollection(Collections.TASKS).find(filter)
                .sort(new Document("createdAt", -1)).skip((page - 1) * limit).limit(limit)
                .into(new ArrayList<>());
        return result(list, mongo.getCollection(Collections.TASKS).countDocuments(filter));
    }

    public Document addOrderRemedy(Map<String, Object> input, Document consultant,
                                   MultipartFile attachment) {
        ObjectId waitlistId = objectId(required(input, "consultantRequestFormId"), "consultantRequestFormId");
        Document waitlist = mongo.getCollection(Collections.WAITLISTS)
                .find(new Document("_id", waitlistId)).first();
        if (waitlist == null) throw new IllegalArgumentException("ORDER_NOT_EXIST");
        ObjectId consultantId = actorId(consultant);
        if (!sameId(waitlist.get("consultant_id"), consultantId))
            throw new IllegalArgumentException("CONSULTANT_ORDER_NOT_SAME_AS_LOGIN");
        if (!"completed".equals(waitlist.getString("status")))
            throw new IllegalArgumentException("ORDER_NOT_COMPLETE");
        String type = required(input, "type");
        if (!List.of("NOTES", "REMEDY").contains(type)) throw new IllegalArgumentException("INVALID_TYPE");
        Document remedy = copy(input);
        remedy.put("_id", new ObjectId());
        remedy.put("consultantRequestFormId", waitlistId);
        remedy.put("consultantId", consultantId);
        remedy.put("userId", objectId(required(input, "userId"), "userId"));
        if (attachment != null && !attachment.isEmpty()) remedy.put("attachment", mediaUpload.upload(attachment, "order"));
        else remedy.putIfAbsent("attachment", "");
        remedy.put("status", true);
        Date now = new Date();
        remedy.put("createdAt", now);
        remedy.put("updatedAt", now);
        mongo.getCollection(Collections.ORDER_REMEDY_NOTES).insertOne(remedy);
        sendRemedyNotification(remedy, consultant, waitlist);
        return remedy;
    }

    public Map<String, Object> orderRemedies(Document actor, String actorType, String type,
                                             String requestId, Integer pageParam, Integer limitParam) {
        int page = pageParam == null ? 1 : Math.max(1, pageParam);
        int limit = limitParam == null ? 10 : Math.max(1, limitParam);
        Document filter;
        boolean user = "user".equals(actorType);
        if (user) filter = new Document("type", new Document("$in", List.of("REMEDY", "AREAOFCONCERN")))
                .append("userId", actorId(actor));
        else filter = new Document("type", type)
                .append("consultantRequestFormId", objectId(requestId, "consultantRequestFormId"));
        List<Document> list = mongo.getCollection(Collections.ORDER_REMEDY_NOTES).find(filter)
                .projection(new Document("type", 1).append("title", 1).append("description", 1)
                        .append("areaOfConcern", 1).append("remedy", 1).append("attachment", 1)
                        .append("consultantId", 1).append("createdAt", 1))
                .sort(new Document("createdAt", -1)).skip((page - 1) * limit).limit(limit)
                .into(new ArrayList<>());
        for (Document row : list) {
            String attachment = row.getString("attachment");
            row.put("attachment", attachment == null || attachment.isBlank() ? "" : constants.mediaUrl + attachment);
            if (user) populateConsultant(row);
        }
        // Node returns page length rather than facet count; preserve it.
        return Map.of("list", list, "total", list.size());
    }

    public Map<String, Object> areaOfConcern(String type, String areaId, String remedyId) {
        List<Document> list = new ArrayList<>();
        String description = "";
        if ("AREAOFCONCERN".equals(type)) {
            list = mongo.getCollection(Collections.AREA_OF_CONCERNS)
                    .find(new Document("status", true)).into(new ArrayList<>());
        } else if ("REMEDY".equals(type)) {
            list = mongo.getCollection(Collections.AREA_OF_CONCERN_REMEDIES)
                    .find(new Document("areaOfConcernId", objectId(areaId, "areaOfConcernId"))
                            .append("status", true)).into(new ArrayList<>());
        } else if ("DESCRIPTION".equals(type)) {
            Document remedy = mongo.getCollection(Collections.AREA_OF_CONCERN_REMEDIES)
                    .find(new Document("_id", objectId(remedyId, "areaOfConcernRemedyId"))
                            .append("status", true)).projection(new Document("description", 1)).first();
            if (remedy != null && remedy.getString("description") != null) description = remedy.getString("description");
        } else throw new IllegalArgumentException("INVALID_TYPE");
        return Map.of("list", list, "description", description);
    }

    private Document refreshUpcoming(Document task) {
        List<String> dates = task.getList("remainingDates", String.class, List.of());
        String today = LocalDate.now(ZoneOffset.UTC).toString();
        List<String> remaining = dates.stream().filter(d -> !parseDay(d).isBefore(LocalDate.parse(today))).sorted().toList();
        if (remaining.size() != dates.size()) {
            List<Document> timings = task.getList("timings", Document.class, List.of());
            timings.forEach(t -> t.put("status", 0));
            Document set = new Document("remainingDates", remaining).append("timings", timings)
                    .append("updatedAt", new Date());
            if (remaining.isEmpty()) set.append("isCompleted", true);
            task = mongo.getCollection(Collections.TASK_REMEDIES).findOneAndUpdate(
                    new Document("_id", task.getObjectId("_id")), new Document("$set", set),
                    new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        } else if (remaining.isEmpty() && !Boolean.TRUE.equals(task.getBoolean("isCompleted"))) {
            task = mongo.getCollection(Collections.TASK_REMEDIES).findOneAndUpdate(
                    new Document("_id", task.getObjectId("_id")),
                    new Document("$set", new Document("isCompleted", true).append("updatedAt", new Date())),
                    new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        }
        return task;
    }

    private void populateOrderNote(Document task) {
        Object id = task.get("orderNotesRemedyId");
        if (id == null) return;
        Document note = mongo.getCollection(Collections.ORDER_REMEDY_NOTES).find(new Document("_id", id))
                .projection(new Document("title", 1).append("description", 1).append("areaOfConcern", 1)
                        .append("remedy", 1).append("type", 1).append("dates", 1).append("timings", 1)).first();
        task.put("orderNotesRemedyId", note);
    }

    private void populateConsultant(Document row) {
        Object id = row.get("consultantId");
        Document consultant = mongo.getCollection(Collections.CONSULTANTS).find(new Document("_id", id))
                .projection(new Document("name", 1).append("userName", 1).append("userId", 1)
                        .append("email", 1).append("image", 1)).first();
        if (consultant == null) return;
        String userName = consultant.getString("userName");
        consultant.put("name", userName == null || userName.isBlank() ? consultant.getString("name") : userName);
        String image = consultant.getString("image");
        consultant.put("profileImage", image == null || image.isBlank() ? "" : constants.mediaUrl + image);
        row.put("consDetails", consultant);
    }

    private void sendRemedyNotification(Document remedy, Document consultant, Document waitlist) {
        Object userId = waitlist.get("user_id");
        Document user = mongo.getCollection(Collections.USERS).find(new Document("_id", userId)).first();
        if (user == null) return;
        String consultantName = consultant.getString("accountName");
        String body = "Your consultant " + consultantName + " has suggested a remedy for you.";
        Map<String, Object> data = Map.of("screen", "Remedies", "consultantId", actorId(consultant).toHexString(),
                "remedyId", remedy.getObjectId("_id").toHexString());
        for (String token : tokens(user)) push.sendNotificationAndCons("user", token, body, data,
                "Vedic Meet", "");
        Date now = new Date();
        mongo.getCollection(Collections.NOTIFICATIONS).insertOne(new Document("receiverId", userId)
                .append("message", body).append("title", "Vedic Meet").append("userType", "user")
                .append("senderType", "system").append("type", "other").append("data", data)
                .append("readByReceiver", new ArrayList<>()).append("createdAt", now).append("updatedAt", now));
    }

    private List<String> allDates(Instant start, Instant end, boolean allDay) {
        LocalDate from = start.atOffset(ZoneOffset.UTC).toLocalDate();
        LocalDate to = end.atOffset(ZoneOffset.UTC).toLocalDate();
        List<String> dates = new ArrayList<>();
        if (allDay) for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) dates.add(isoDay(d));
        else { dates.add(isoDay(from)); dates.add(isoDay(to)); }
        return dates;
    }

    private List<String> normalizedDates(Object value) {
        if (value == null) throw new IllegalArgumentException("dates is required");
        List<?> values = value instanceof Iterable<?> iterable ? toList(iterable) : List.of(value);
        List<String> dates = new ArrayList<>();
        for (Object item : values) dates.add(isoDay(normalizedDate(item).atOffset(ZoneOffset.UTC).toLocalDate()));
        dates.sort(String::compareTo);
        return dates;
    }

    private List<Document> normalizedTimings(Object value) {
        if (value == null) throw new IllegalArgumentException("timings is required");
        List<?> values = value instanceof Iterable<?> iterable ? toList(iterable) : List.of(value);
        List<Document> timings = new ArrayList<>();
        for (Object item : values) {
            String time;
            Number status = 0;
            Object existingId = null;
            if (item instanceof Map<?, ?> map) {
                time = String.valueOf(map.get("time"));
                if (map.get("status") instanceof Number n) status = n;
                existingId = map.get("_id");
            } else time = String.valueOf(item);
            Document timing = new Document("_id", existingId == null ? new ObjectId() : existingId)
                    .append("time", time).append("status", status);
            timings.add(timing);
        }
        return timings;
    }

    private Instant normalizedDate(Object value) {
        if (value == null) throw new IllegalArgumentException("date is required");
        if (value instanceof Date date) return date.toInstant().atOffset(ZoneOffset.UTC).toLocalDate()
                .atStartOfDay().toInstant(ZoneOffset.UTC);
        LocalDate day = parseDay(String.valueOf(value));
        return day.atStartOfDay().toInstant(ZoneOffset.UTC);
    }

    private LocalDate parseDay(String value) {
        try { return Instant.parse(value).atOffset(ZoneOffset.UTC).toLocalDate(); }
        catch (DateTimeParseException ignored) { }
        try { return OffsetDateTime.parse(value).toLocalDate(); }
        catch (DateTimeParseException ignored) { }
        if (value.length() >= 10) return LocalDate.parse(value.substring(0, 10));
        throw new IllegalArgumentException("INVALID_DATE");
    }

    private boolean isStartBefore(String start, String end) {
        return parseTime(start).isBefore(parseTime(end));
    }

    private LocalTime parseTime(String value) {
        if (value == null) throw new IllegalArgumentException("INVALID_TIME");
        for (DateTimeFormatter f : List.of(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH),
                DateTimeFormatter.ofPattern("H:mm", Locale.ENGLISH))) {
            try { return LocalTime.parse(value.trim().toUpperCase(Locale.ENGLISH), f); }
            catch (DateTimeParseException ignored) { }
        }
        throw new IllegalArgumentException("INVALID_TIME");
    }

    private Instant normalizedDate(String value) { return normalizedDate((Object) value); }

    private Instant dateInstant(Object value) {
        if (value instanceof Date date) return date.toInstant();
        return normalizedDate(value);
    }

    private String isoDay(LocalDate day) { return day.atStartOfDay().toInstant(ZoneOffset.UTC).toString(); }

    private List<?> toList(Iterable<?> values) {
        List<Object> out = new ArrayList<>();
        values.forEach(out::add);
        return out;
    }

    @SuppressWarnings("unchecked")
    private Document copy(Map<String, Object> input) {
        return input == null ? new Document() : new Document((Map<String, Object>) new LinkedHashMap<>(input));
    }

    private void convertId(Document document, String key) {
        Object value = document.get(key);
        if (value != null && !(value instanceof ObjectId)) document.put(key, objectId(String.valueOf(value), key));
    }

    private List<String> tokens(Document user) {
        Object device = user.get("device");
        Object value = device instanceof Document d ? d.get("fcmToken")
                : device instanceof Map<?, ?> map ? map.get("fcmToken") : null;
        if (value instanceof Iterable<?> iterable) {
            List<String> out = new ArrayList<>();
            for (Object token : iterable) if (token != null && !String.valueOf(token).isBlank()) out.add(String.valueOf(token));
            return out;
        }
        return List.of();
    }

    private String normalizeUserType(String value) {
        return "consultant".equals(value) ? "cons" : value == null ? "user" : value;
    }

    private Integer number(Object value) { return value instanceof Number n ? n.intValue() : null; }

    private Map<String, Object> result(List<Document> list, long total) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", list);
        result.put("total", total);
        return result;
    }

    private ObjectId actorId(Document actor) {
        Object value = actor == null ? null : actor.get("_id");
        if (value instanceof ObjectId id) return id;
        if (value != null && ObjectId.isValid(String.valueOf(value))) return new ObjectId(String.valueOf(value));
        throw new IllegalArgumentException("ACCOUNT_NOT_FOUND");
    }

    private ObjectId objectId(String value, String field) {
        if (value == null || !ObjectId.isValid(value)) throw new IllegalArgumentException(field + " is required");
        return new ObjectId(value);
    }

    private boolean sameId(Object left, Object right) {
        return left != null && right != null && String.valueOf(left).equals(String.valueOf(right));
    }

    private String required(Map<String, Object> input, String key) {
        String value = text(input, key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(key + " is required");
        return value;
    }

    private String text(Map<String, Object> input, String key) {
        Object value = input == null ? null : input.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
