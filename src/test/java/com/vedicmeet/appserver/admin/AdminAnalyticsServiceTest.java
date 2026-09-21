package com.vedicmeet.appserver.admin;

import com.mongodb.client.AggregateIterable;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminAnalyticsServiceTest {

    private static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");

    @Mock
    MongoTemplate mongo;

    @Mock
    AdminMongoSupport support;

    @Mock
    MongoCollection<Document> collection;

    @Mock
    FindIterable<Document> findIterable;

    @Mock
    AggregateIterable<Document> aggregateIterable;

    AdminAnalyticsService service;

    @BeforeEach
    void setUp() {
        service = new AdminAnalyticsService(mongo, support);
    }

    @SuppressWarnings("unchecked")
    private void wireFindChain(List<Document> result) {
        when(mongo.getCollection(anyString())).thenReturn(collection);
        when(collection.find(any(Bson.class))).thenReturn(findIterable);
        lenient().when(findIterable.sort(any(Bson.class))).thenReturn(findIterable);
        lenient().when(findIterable.skip(org.mockito.ArgumentMatchers.anyInt())).thenReturn(findIterable);
        lenient().when(findIterable.limit(org.mockito.ArgumentMatchers.anyInt())).thenReturn(findIterable);
        lenient().when(findIterable.projection(any(Bson.class))).thenReturn(findIterable);
        lenient().when(findIterable.into(any())).thenReturn(new ArrayList<>(result));
        lenient().when(collection.countDocuments(any(Bson.class))).thenReturn((long) result.size());
    }

    @SuppressWarnings("unchecked")
    private void wireAggregateChain(List<Document> result) {
        when(mongo.getCollection(anyString())).thenReturn(collection);
        when(collection.aggregate(anyList())).thenReturn(aggregateIterable);
        when(aggregateIterable.into(any())).thenReturn(new ArrayList<>(result));
    }

    // ── utc-date-helper quirks (Asia/Kolkata despite the "UTC" name) ─────

    @Test
    void getUTCDateRangeRejectsUnsupportedTimeRange() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> AdminAnalyticsService.getUTCDateRange("bogus", null, null, ZonedDateTime.now(KOLKATA)));
        assertEquals("Unsupported timeRange: bogus", error.getMessage());
    }

    @Test
    void getUTCDateRangeCustomRequiresBothDates() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> AdminAnalyticsService.getUTCDateRange("custom", "2026-01-01", null, ZonedDateTime.now(KOLKATA)));
        assertEquals("Custom date range requires both startDate and endDate", error.getMessage());
    }

    @Test
    void getUTCDateRangeTodayIsKolkataStartAndEndOfDay() {
        ZonedDateTime now = ZonedDateTime.of(2026, 1, 15, 10, 30, 0, 0, KOLKATA);
        Date[] range = AdminAnalyticsService.getUTCDateRange("today", null, null, now);

        assertEquals(LocalDate.of(2026, 1, 15).atStartOfDay(KOLKATA).toInstant(), range[0].toInstant());
        assertEquals(LocalDate.of(2026, 1, 15).atTime(23, 59, 59, 999_000_000).atZone(KOLKATA).toInstant(),
                range[1].toInstant());
    }

    @Test
    void getUTCDateRangeThisMonthEndsAtNowNotEndOfMonth() {
        // FAITHFUL(node-quirk): thisMonth end = endOfDay(now), NOT endOfMonth.
        ZonedDateTime now = ZonedDateTime.of(2026, 1, 15, 10, 30, 0, 0, KOLKATA);
        Date[] range = AdminAnalyticsService.getUTCDateRange("thisMonth", null, null, now);

        assertEquals(LocalDate.of(2026, 1, 1).atStartOfDay(KOLKATA).toInstant(), range[0].toInstant());
        assertEquals(LocalDate.of(2026, 1, 15).atTime(23, 59, 59, 999_000_000).atZone(KOLKATA).toInstant(),
                range[1].toInstant());
    }

    // ── /logs faithful query quirks ─────────────────────────────────────

    @Test
    void logsBuildsUnescapedRegexAndHasUserIdOverwrite() {
        wireFindChain(List.of());

        Map<String, String> query = new HashMap<>();
        query.put("source", "app");
        query.put("event_name", "pay.*ment");   // regex metachars must survive un-escaped
        query.put("user_id", "U1");
        query.put("has_user_id", "true");        // overwrites the user_id equality above

        service.logs(query);

        Document built = (Document) firstFindFilter();
        assertEquals("app", built.getString("source"));

        Document eventName = (Document) built.get("event_name");
        assertEquals("pay.*ment", eventName.getString("$regex"));
        assertEquals("i", eventName.getString("$options"));

        Document userId = (Document) built.get("user_id");
        assertTrue(userId.getBoolean("$exists"));
        assertTrue(userId.containsKey("$ne"));
    }

    private Object firstFindFilter() {
        ArgumentCaptor<Bson> filter = ArgumentCaptor.forClass(Bson.class);
        org.mockito.Mockito.verify(collection, org.mockito.Mockito.atLeastOnce()).find(filter.capture());
        return filter.getAllValues().get(0);
    }

    // ── unique-devices faithful duplicate-key ($ne:'') quirk ────────────

    @Test
    @SuppressWarnings("unchecked")
    void uniqueDevicesPipelineIsValidAndKeepsNeEmptyStringOnly() {
        wireAggregateChain(List.of());
        ArgumentCaptor<List> pipeline = ArgumentCaptor.forClass(List.class);

        service.uniqueDevices(new HashMap<>());

        org.mockito.Mockito.verify(collection).aggregate(pipeline.capture());
        List<Document> stages = pipeline.getValue();
        assertEquals(4, stages.size());
        assertTrue(stages.get(0).containsKey("$match"));
        assertTrue(stages.get(1).containsKey("$group"));
        assertTrue(stages.get(2).containsKey("$project"));
        assertTrue(stages.get(3).containsKey("$sort"));

        Document match = (Document) stages.get(0).get("$match");
        Document deviceId = (Document) match.get("device_info.device_id");
        assertTrue(deviceId.getBoolean("$exists"));
        assertEquals("", deviceId.get("$ne"));   // $ne:null was overwritten by $ne:'' in Node

        for (Document stage : stages) {
            assertNotNull(Document.parse(stage.toJson()));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void uniqueUsersPipelineFiltersExistingUserIds() {
        wireAggregateChain(List.of());
        ArgumentCaptor<List> pipeline = ArgumentCaptor.forClass(List.class);

        service.uniqueUsers(new HashMap<>());

        org.mockito.Mockito.verify(collection).aggregate(pipeline.capture());
        List<Document> stages = pipeline.getValue();
        assertEquals(4, stages.size());

        Document match = (Document) stages.get(0).get("$match");
        Document userId = (Document) match.get("user_id");
        assertTrue(userId.getBoolean("$exists"));
        assertFalse(userId.containsKey("$ne") && "".equals(userId.get("$ne")));

        for (Document stage : stages) {
            assertNotNull(Document.parse(stage.toJson()));
        }
    }

    // ── CSV export header line ──────────────────────────────────────────

    @Test
    void exportReturnsHeaderRowWhenNoLogs() {
        wireFindChain(List.of());
        String csv = service.export(new HashMap<>());
        assertEquals("Event Name,Event Type,User ID,Source,UTM Source,UTM Medium,UTM Campaign,Platform,"
                        + "Device Type,Device Model,Device Manufacturer,OS Version,App Version,App Build,Device ID,"
                        + "Screen Resolution,Is Jailbroken,Carrier,Network Type,Install Status,First Install Timestamp,"
                        + "Last Install Timestamp,Uninstall Timestamp,Install Count,IP Address,Created At",
                csv);
    }

    // ── /logs/:id not found ─────────────────────────────────────────────

    @Test
    void logThrowsWhenNotFound() {
        when(mongo.getCollection(anyString())).thenReturn(collection);
        when(collection.find(any(Bson.class))).thenReturn(findIterable);
        when(findIterable.first()).thenReturn(null);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.log("6560000000000000000000aa"));
        assertEquals("Analytics log not found", error.getMessage());
    }
}
