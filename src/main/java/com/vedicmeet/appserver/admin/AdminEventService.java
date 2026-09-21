package com.vedicmeet.appserver.admin;

import com.vedicmeet.appserver.config.AppConstants;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AdminEventService {

    private final MongoTemplate mongo;
    private final AdminMongoSupport support;
    private final AppConstants constants;

    public AdminEventService(MongoTemplate mongo, AdminMongoSupport support, AppConstants constants) {
        this.mongo = mongo;
        this.support = support;
        this.constants = constants;
    }

    /** Faithful port of EventBroadcastService.list (utils/classes/event-broadcast-service.js). */
    public Map<String, Object> list(Map<String, String> query) {
        int page = intOf(query.get("page"), 1);
        int limit = intOf(query.get("limit"), 10);
        String search = query.getOrDefault("search", "");
        String status = query.getOrDefault("status", "");
        int skipIndex = (page - 1) * limit;

        Document params = new Document();
        if (status != null && !status.isEmpty()) {
            try {
                params.put("status", Integer.parseInt(status.trim()));
            } catch (NumberFormatException ignored) {
                params.put("status", Double.NaN); // FAITHFUL(node-quirk): Node parseInt(status) -> NaN matches nothing
            }
        }

        Document searchMatch = new Document();
        if (search != null && !search.isEmpty()) {
            String s = search.trim();
            searchMatch.put("$or", List.of(
                    new Document("title", regex(s)),
                    new Document("consultantDetails.name", regex(s)),
                    new Document("consultantDetails.email", regex(s))));
        }

        List<Document> pipeline = new ArrayList<>();
        pipeline.add(new Document("$match", params));
        // FAITHFUL(node-quirk): profileImage is a $cond returning "" or a bare $concat[MEDIA_URL] (image path is NOT appended).
        pipeline.add(Document.parse("{ $lookup: { from: 'consultants', let: { consId: '$consultantId' }, pipeline: [ "
                + "{ $match: { $expr: { $eq: [ '$$consId', '$_id' ] } } }, "
                + "{ $project: { name: { $cond: [ { $eq: [ '$userName', '' ] }, '$name', '$userName' ] }, userId: 1, email: 1, "
                + "profileImage: { $cond: [ { $eq: [ '$image', '' ] }, '', { $concat: [ " + quote(constants.mediaUrl) + " ] } ] } } } "
                + "], as: 'consultantDetails' } }"));
        pipeline.add(Document.parse("{ $unwind: '$consultantDetails' }"));
        pipeline.add(new Document("$match", searchMatch));
        pipeline.add(Document.parse("{ $project: { consultantDetails: 1, eventDate: 1, eventTime: 1, eventName: '$title', "
                + "likeCount: { $size: '$likes' }, joinedUserListCount: { $size: '$joinedUserList' }, status: 1 } }"));
        pipeline.add(Document.parse("{ $facet: { list: [ { $sort: { eventDate: -1, eventTime: -1 } }, { $skip: " + skipIndex
                + " }, { $limit: " + limit + " } ], count: [ { $count: 'total' } ] } }"));

        List<Document> aggregated = mongo.getCollection(Collections.BROADCASTS).aggregate(pipeline).into(new ArrayList<>());
        Document facet = aggregated.isEmpty() ? new Document() : aggregated.get(0);
        List<Document> list = asDocList(facet.get("list"));
        List<Document> count = asDocList(facet.get("count"));
        long total = 0;
        if (!count.isEmpty() && count.get(0).get("total") instanceof Number number) {
            total = number.longValue();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", list);
        result.put("total", total);
        return result;
    }

    /** Faithful port of EventBroadcastService.sendMessages. Gated by @MigrationWrite on the controller. */
    public Map<String, Object> sendMessages(Map<String, Object> body) {
        Object broadcastId = body == null ? null : body.get("broadcastId");
        Document exist = mongo.getCollection(Collections.BROADCASTS)
                .find(new Document("_id", support.id(broadcastId))).first();
        if (exist == null) throw new IllegalStateException("BROADCAST_NOT_EXIST");
        // Node: BroadcastMessageModel.create(input) inserts the request body verbatim.
        // FAITHFUL-NOTE: Mongoose schema defaults (__v/timestamps) applied by the ODM are not replicated here.
        Document message = new Document(body == null ? Map.of() : body);
        message.put("_id", new ObjectId());
        mongo.getCollection(Collections.BROADCAST_MESSAGES).insertOne(message);
        return message;
    }

    private Document regex(String value) {
        return new Document("$regex", ".*" + value + ".*").append("$options", "i");
    }

    private String quote(String value) {
        return "'" + (value == null ? "" : value.replace("\\", "\\\\").replace("'", "\\'")) + "'";
    }

    @SuppressWarnings("unchecked")
    private List<Document> asDocList(Object value) {
        return value instanceof List ? (List<Document>) value : new ArrayList<>();
    }

    private int intOf(String value, int fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
