package com.vedicmeet.appserver.session;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Pure request-to-document mapper for Node POST /book-fixed-session. */
@Component
public class FixedSessionBookingFactory {

    static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Kolkata");

    public record Prepared(String claimId, double price, List<String> fcmTokens,
                           Document waitlistCopy, Document fixedSession) {}

    public Prepared prepare(Document user, Map<String, Object> request) {
        if (user == null || user.get("_id") == null) throw new IllegalStateException("Unauthorized");
        Document input = document(request);
        Document category = doc(input.get("category"));
        double price = positive(category.get("priceOffer"), "category.priceOffer");
        int minutes = positiveInt(category.get("consultationTimeInMinutes"),
                "category.consultationTimeInMinutes");
        double originalPrice = positive(category.get("price"), "category.price");

        String date = formattedDate(input.get("preferredDateAt"));
        TimeRange times = formattedTimes(input.get("preferredTimeAt"), minutes);
        String userId = text(user.get("_id"));
        Object categoryIdentity = category.get("_id") != null ? category.get("_id")
                : category.get("name") != null ? category.get("name") : category.get("title");
        String claimId = "FIXED_SESSION:" + userId + ":" + digest(date + "|" + times.start()
                + "|" + text(categoryIdentity) + "|" + minutes + "|" + price);

        Document waitlist = new Document("_id", new ObjectId()).append("orderId", null)
                .append("deviceUsedToken", input.get("device_id")).append("user_id", userId)
                .append("consultant_id", null).append("requested_time", minutes * 60L);
        addKundalis(waitlist, input, input.get("kundaliList"));
        String percentage = BigDecimal.valueOf(price / originalPrice * 100.0)
                .setScale(2, RoundingMode.HALF_UP).toPlainString();
        waitlist.append("coupon", null)
                .append("session_info", new Document("price", price / minutes)
                        .append("platformShare", null).append("mode", "chat")
                        .append("canConsultantAccessHistory", true).append("messageLimit", 3)
                        .append("isSessionExtended", false).append("holdAmount", price)
                        .append("bookType", "session").append("isTriedAgainAfterMissed", false)
                        .append("sessionMeta", new Document("isUserCancelled", false)
                                .append("isConsultantCancelled", false).append("startDate", date)
                                .append("timeFrom", times.start()).append("timeTo", times.end())
                                .append("sessionTimeInMinutes", minutes)
                                // Node names this discountPercentage but stores the paid/original ratio.
                                .append("discountPercentage", percentage)))
                .append("offerOnConsultant", null).append("used_for", "session")
                .append("status", "waiting").append("priority", 1).append("threadId", null)
                .append("logs", new ArrayList<>()).append("createdAt", new Date())
                .append("updatedAt", new Date());

        Document fixed = new Document("_id", new ObjectId()).append("waitlistCopy", waitlist)
                .append("user_id", user.get("_id")).append("consultant_id", null)
                .append("waitlist_id", null).append("preferredDateAt", date)
                .append("preferredTimeAt", times.start()).append("selectedLanguage", input.get("selectedLanguage"))
                .append("problemDescription", input.get("problemDescription")).append("category", category)
                .append("status", "waiting").append("for", "fixed_session")
                .append("javaClaimId", claimId).append("createdAt", new Date()).append("updatedAt", new Date());
        return new Prepared(claimId, price, normalizedTokens(user, text(input.get("fcmToken"))), waitlist, fixed);
    }

    private void addKundalis(Document waitlist, Document input, Object rawList) {
        if (!(rawList instanceof List<?> list)) return;
        int index = 0;
        for (Object raw : list) {
            Document kundali = doc(raw);
            String rawName = text(kundali.get("name")).trim();
            String first = rawName;
            String last = "";
            int separator = rawName.indexOf(' ');
            if (separator >= 0) {
                first = rawName.substring(0, separator).trim();
                last = rawName.substring(separator + 1).trim();
            }
            Document coordinates = doc(kundali.get("placeLatLong"));
            Document form = new Document("firstName", first).append("lastName", last)
                    .append("gender", text(kundali.get("gender")))
                    .append("dateOfBirth", text(kundali.get("dateOfBirth")))
                    .append("timeOfBirth", text(kundali.get("timeOfBirth")))
                    .append("placeOfBirth", text(kundali.get("placeOfBirth")))
                    .append("placeLatLong", new Document("lat", text(coordinates.get("lat")))
                            .append("long", text(coordinates.get("long"))))
                    .append("maritalStatus", text(kundali.get("maritalStatus")))
                    .append("concern", firstNonBlank(kundali.get("problemCategory"),
                            kundali.get("concern"), input.get("problemDescription")))
                    .append("language", firstNonBlank(input.get("selectedLanguage"), kundali.get("language")));
            waitlist.append(index == 0 ? "request_form" : "request_form" + index, form);
            index++;
        }
    }

    private List<String> normalizedTokens(Document user, String incoming) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        Object raw = doc(user.get("device")).get("fcmToken");
        if (raw instanceof List<?> list) for (Object value : list) {
            String token = text(value).trim();
            if (!token.isBlank()) values.add(token);
        }
        if (!incoming.isBlank()) {
            values.remove(incoming);
            values.add(incoming);
        }
        List<String> result = new ArrayList<>(values);
        return result.size() <= 3 ? result : new ArrayList<>(result.subList(result.size() - 3, result.size()));
    }

    private String formattedDate(Object raw) {
        if (raw == null || text(raw).isBlank()) return "";
        if (raw instanceof Date date) return date.toInstant().atZone(BUSINESS_ZONE).toLocalDate().toString();
        String value = text(raw).trim();
        try { return LocalDate.parse(value).toString(); } catch (DateTimeParseException ignored) { }
        return dateTime(raw).toLocalDate().toString();
    }

    private TimeRange formattedTimes(Object raw, int minutes) {
        if (raw == null || text(raw).isBlank()) return new TimeRange("", "");
        ZonedDateTime time = dateTime(raw).withSecond(0).withNano(0);
        int remainder = time.getMinute() % 5;
        if (remainder != 0) time = time.plusMinutes(5L - remainder);
        return new TimeRange(time.format(DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)),
                time.plusMinutes(minutes).format(DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)));
    }

    private ZonedDateTime dateTime(Object raw) {
        if (raw instanceof Date date) return date.toInstant().atZone(BUSINESS_ZONE);
        if (raw instanceof Number number) return Instant.ofEpochMilli(number.longValue()).atZone(BUSINESS_ZONE);
        String value = text(raw).trim();
        try { return Instant.parse(value).atZone(BUSINESS_ZONE); } catch (DateTimeParseException ignored) { }
        try { return OffsetDateTime.parse(value).atZoneSameInstant(BUSINESS_ZONE); }
        catch (DateTimeParseException ignored) { }
        try { return ZonedDateTime.parse(value).withZoneSameInstant(BUSINESS_ZONE); }
        catch (DateTimeParseException ignored) { }
        try { return LocalDateTime.parse(value).atZone(BUSINESS_ZONE); }
        catch (DateTimeParseException ignored) { }
        try { return LocalTime.parse(value).atDate(LocalDate.now(BUSINESS_ZONE)).atZone(BUSINESS_ZONE); }
        catch (DateTimeParseException ignored) { }
        throw new IllegalArgumentException("Invalid date/time value");
    }

    private String digest(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (int i = 0; i < 12; i++) result.append(String.format("%02x", hash[i]));
            return result.toString();
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    private Document document(Map<String, Object> value) { return value == null ? new Document() : new Document(value); }
    @SuppressWarnings("unchecked")
    private Document doc(Object value) {
        return value instanceof Document d ? new Document(d)
                : value instanceof Map<?, ?> map ? new Document((Map<String, Object>) map) : new Document();
    }
    private double positive(Object value, String field) {
        double result = number(value);
        if (result <= 0) throw new IllegalArgumentException(field + " must be greater than zero");
        return result;
    }
    private int positiveInt(Object value, String field) {
        int result = (int) Math.floor(number(value));
        if (result <= 0) throw new IllegalArgumentException(field + " must be greater than zero");
        return result;
    }
    private double number(Object value) {
        if (value instanceof Number n) return n.doubleValue();
        try { return value == null ? 0 : Double.parseDouble(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return 0; }
    }
    private String firstNonBlank(Object... values) {
        for (Object value : values) if (!text(value).isBlank()) return text(value);
        return "";
    }
    private String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private record TimeRange(String start, String end) {}
}
