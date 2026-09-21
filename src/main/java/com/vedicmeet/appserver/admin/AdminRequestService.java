package com.vedicmeet.appserver.admin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
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
public class AdminRequestService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final MongoTemplate mongo;
    private final AdminMongoSupport support;
    private final AppConstants constants;

    public AdminRequestService(MongoTemplate mongo, AdminMongoSupport support, AppConstants constants) {
        this.mongo = mongo;
        this.support = support;
        this.constants = constants;
    }

    /** Faithful port of RequestService.list (utils/classes/request-service.js). */
    public Map<String, Object> list(Map<String, String> query) {
        int page = intOf(query.get("page"), 1);
        int limit = intOf(query.get("limit"), 10);
        String search = query.getOrDefault("search", "");
        int skipIndex = (page - 1) * limit;

        Document params = new Document();

        String requestType = query.getOrDefault("requestType", "");
        if ("PAN".equals(requestType)) requestType = "PANCARD";
        if (!requestType.isEmpty()) params.put("requestType", requestType);

        // FAITHFUL(node-quirk): default approveStatus is numeric 0 (falsy) vs "" so the filter is skipped unless a non-empty string is sent.
        String approveStatus = query.get("approveStatus");
        if (approveStatus != null && !approveStatus.isEmpty()) {
            try {
                params.put("approveStatus", Integer.parseInt(approveStatus.trim()));
            } catch (NumberFormatException ignored) {
                params.put("approveStatus", Double.NaN); // FAITHFUL: Node parseInt(non-numeric) -> NaN matches nothing
            }
        }

        String consultantId = query.getOrDefault("consultantId", "");
        if (!consultantId.isEmpty()) {
            try {
                params.put("consultantId", new ObjectId(consultantId));
            } catch (IllegalArgumentException ignored) {
                // FAITHFUL(node-quirk): invalid consultantId is caught/logged and the filter is skipped.
            }
        }

        Document nameSearch = new Document();
        if (search != null && !search.isEmpty()) {
            nameSearch.put("consultantDetails.name",
                    new Document("$regex", ".*" + search + ".*").append("$options", "i"));
        }

        List<Document> pipeline = new ArrayList<>();
        pipeline.add(new Document("$match", params));
        pipeline.add(Document.parse("{ $lookup: { from: 'consultants', let: { consultantId: '$consultantId' }, pipeline: [ "
                + "{ $match: { $expr: { $eq: [ '$$consultantId', '$_id' ] } } }, "
                + "{ $project: { _id: 0, email: 1, name: { $cond: [ { $eq: [ '$accountName', '' ] }, '$name', '$accountName' ] }, "
                + "userId: 1, phone: '$details.phone', profileImage: { $cond: { if: { $and: [ { $ne: [ '$profileImage', '' ] }, "
                + "{ $not: [ { $regexMatch: { input: '$profileImage', regex: '^https?://' } } ] } ] }, "
                + "then: { $concat: [ " + quote(constants.mediaUrl) + ", '$profileImage' ] }, else: '$profileImage' } } } } "
                + "], as: 'consultantDetails' } }"));
        pipeline.add(Document.parse("{ $unwind: { path: '$consultantDetails', preserveNullAndEmptyArrays: true } }"));
        pipeline.add(new Document("$match", nameSearch));
        pipeline.add(Document.parse("{ $facet: { list: [ "
                + "{ $project: { _id: 1, requestType: 1, consultantId: 1, currentData: 1, newData: 1, isApprove: 1, status: 1, "
                + "createdAt: 1, consName: '$consultantDetails.name', accountName: '$consultantDetails.accountName', "
                + "profileImage: '$consultantDetails.profileImage', email: '$consultantDetails.email', "
                + "phone: '$consultantDetails.phone', approveStatus: 1, review: 1, flagReason: 1, userName: 1 } }, "
                + "{ $sort: { createdAt: -1 } }, { $skip: " + skipIndex + " }, { $limit: " + limit + " } ], "
                + "count: [ { $count: 'total' } ] } }"));

        List<Document> aggregated = mongo.getCollection(Collections.REQUESTS).aggregate(pipeline).into(new ArrayList<>());
        Document facet = aggregated.isEmpty() ? new Document() : aggregated.get(0);
        List<Document> list = asDocList(facet.get("list"));
        long total = totalOf(facet.get("count"));

        List<Document> reqList = new ArrayList<>();
        for (Document item : list) {
            item.put("newData", parseJsonOrNull(item.get("newData")));
            item.put("currentData", parseJsonOrNull(item.get("currentData")));
            reqList.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        // FAITHFUL(node-quirk): the payload key is "reqList" (not "list").
        result.put("reqList", reqList);
        result.put("total", total);
        return result;
    }

    /** Faithful port of RequestService.details. */
    public Map<String, Object> details(Map<String, String> query) {
        Document details = mongo.getCollection(Collections.REQUESTS)
                .find(new Document("_id", support.id(query.get("requestId")))).first();
        if (details == null) throw new IllegalStateException("REQUEST_NOT_EXIST");
        Map<String, Object> reqDetails = new LinkedHashMap<>();
        reqDetails.put("currentData", parseJsonOrNull(details.get("currentData")));
        reqDetails.put("newData", parseJsonOrNull(details.get("newData")));
        reqDetails.put("requestId", details.get("_id"));
        reqDetails.put("approveStatus", truthyNumber(details.get("approveStatus")) ? details.get("approveStatus") : 0);
        return reqDetails;
    }

    /** Faithful port of RequestService.acceptAndReject. Gated by @MigrationWrite on the controller. */
    public Map<String, Object> acceptAndReject(Map<String, Object> body) {
        int approveStatus = intValue(body.get("approveStatus"));
        Document requestDetails = mongo.getCollection(Collections.REQUESTS)
                .find(new Document("_id", support.id(body.get("requestId")))).first();
        if (requestDetails == null) throw new IllegalStateException("REQUEST_NOT_EXIST");
        if (numberOf(requestDetails.get("approveStatus")) == 2) throw new IllegalStateException("REQUEST_ALREADY_APPROVED");
        if (numberOf(requestDetails.get("approveStatus")) == 3) throw new IllegalStateException("REQUEST_ALREADY_REJECTED");

        String requestType = str(requestDetails.get("requestType"));
        Object consId = requestDetails.get("consultantId");
        switch (requestType) {
            case "BANK" -> {
                if (approveStatus == 2) {
                    Document bankDetails = mongo.getCollection(Collections.BANKS)
                            .find(new Document("consultantId", consId).append("documentType", requestType)).first();
                    Document payload = parseDocOrNull(requestDetails.get("newData"));
                    payload.put("consultantId", consId); // FAITHFUL: null newData -> NPE, matching JS null property set
                    payload.put("documentType", requestType);
                    if (bankDetails != null) {
                        mongo.getCollection(Collections.BANKS).updateOne(new Document("_id", bankDetails.get("_id")),
                                new Document("$set", payload));
                    } else {
                        mongo.getCollection(Collections.BANKS).insertOne(withId(payload));
                    }
                }
            }
            case "PRICE" -> {
                Document payload = parseDocOrNull(requestDetails.get("newData"));
                if (approveStatus == 2) {
                    mongo.getCollection(Collections.CONSULTANTS).updateOne(new Document("_id", consId),
                            new Document("$set", new Document("price", opt(payload, "price")).append("lastPriceChangeDate", new Date())));
                }
            }
            case "PHONE" -> {
                Document payload = parseDocOrNull(requestDetails.get("newData"));
                Document consultantDetails = mongo.getCollection(Collections.CONSULTANTS)
                        .find(new Document("_id", consId)).first();
                Object secondaryMobile = consultantDetails == null ? null : nested(consultantDetails, "details", "secondaryMobile");
                // FAITHFUL(node-quirk): this throws even on reject, because the check precedes the approveStatus==2 guard.
                if (truthy(secondaryMobile)) throw new IllegalStateException("SECONDARY_MOBILE_ALREADY_EXISTS");
                if (approveStatus == 2) {
                    mongo.getCollection(Collections.CONSULTANTS).updateOne(new Document("_id", consId),
                            new Document("$set", new Document("details.secondaryMobile", opt(payload, "mobile"))
                                    .append("details.secondaryMobileCountryCode", opt(payload, "countryCode"))
                                    .append("details.secondaryuMobileVerify", true))); // FAITHFUL: production typo preserved
                }
            }
            case "PANCARD" -> {
                if (approveStatus == 2) {
                    Document panDetails = mongo.getCollection(Collections.BANKS)
                            .find(new Document("consultantId", consId).append("documentType", requestType)).first();
                    Document payload = parseDocOrNull(requestDetails.get("newData"));
                    payload.put("documentType", requestType); // FAITHFUL: null newData -> NPE, matching JS null property set
                    payload.put("consultantId", consId);
                    if (panDetails != null) {
                        mongo.getCollection(Collections.BANKS).updateOne(new Document("_id", panDetails.get("_id")),
                                new Document("$set", payload));
                    } else {
                        mongo.getCollection(Collections.BANKS).insertOne(withId(payload));
                    }
                }
            }
            case "FLAG" -> {
                if (approveStatus == 2) {
                    mongo.getCollection(Collections.REVIEW_AND_RATINGS).updateOne(
                            new Document("_id", support.id(requestDetails.get("ratingReviewId"))),
                            new Document("$set", new Document("status", false)));
                }
                if (approveStatus == 3) {
                    mongo.getCollection(Collections.REVIEW_AND_RATINGS).updateOne(
                            new Document("_id", support.id(requestDetails.get("ratingReviewId"))),
                            new Document("$set", new Document("isFlag", false)));
                }
            }
            default -> { }
        }

        return mongo.getCollection(Collections.REQUESTS).findOneAndUpdate(
                new Document("_id", requestDetails.get("_id")),
                new Document("$set", new Document("approveStatus", approveStatus)),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
    }

    /** Faithful port of RequestService.add. Files are not wired at the controller, so file branches are inert. */
    public Map<String, Object> add(Map<String, Object> body) {
        Document requestData = new Document();
        requestData.put("requestType", body.get("requestType"));
        requestData.put("consultantId", body.get("consultantId"));
        requestData.put("approveStatus", 2);

        String requestType = str(body.get("requestType"));
        switch (requestType) {
            case "BANK" -> {
                Document bankData = new Document();
                bankData.put("consultantId", body.get("consultantId"));
                bankData.put("documentType", body.get("requestType"));
                Object attachment = body.get("attachment"); // FAITHFUL: files not wired -> only body attachment is used
                bankData.put("bankName", body.get("bankName"));
                bankData.put("accountNumber", body.get("accountNumber"));
                bankData.put("ifsc", body.get("ifsc"));
                bankData.put("bankHolderName", body.get("bankHolderName"));
                Map<String, Object> nd = new LinkedHashMap<>();
                nd.put("bankName", body.get("bankName"));
                nd.put("accountNumber", body.get("accountNumber"));
                nd.put("ifsc", body.get("ifsc"));
                nd.put("bankHolderName", body.get("bankHolderName"));
                nd.put("attachment", attachment);
                requestData.put("newData", stringify(nd));
                requestData.put("currentData", stringify(nd));
                requestData = insertReturn(Collections.REQUESTS, requestData);
                mongo.getCollection(Collections.BANKS).insertOne(withId(bankData));
            }
            case "PANCARD" -> {
                Document panData = new Document();
                panData.put("consultantId", body.get("consultantId"));
                panData.put("documentType", body.get("requestType"));
                panData.put("panNumber", body.get("panNumber"));
                requestData.put("newData", stringify(panData));
                requestData.put("currentData", stringify(panData));
                requestData = insertReturn(Collections.REQUESTS, requestData);
                mongo.getCollection(Collections.BANKS).insertOne(withId(panData));
            }
            default -> { }
        }
        return requestData;
    }

    /** Faithful port of RequestService.update. Files are not wired at the controller, so file branches are inert. */
    public Map<String, Object> update(Map<String, Object> body) {
        Document requestDetails = mongo.getCollection(Collections.REQUESTS)
                .find(new Document("_id", support.id(body.get("requestId")))).first();
        if (requestDetails == null) throw new IllegalStateException("REQUEST_NOT_EXIST");
        Object consId = requestDetails.get("consultantId");
        Document newData = new Document();
        newData.put("requestType", body.get("requestType"));
        newData.put("consultantId", consId);
        newData.put("documentType", body.get("requestType"));
        newData.put("approveStatus", 2);

        String requestType = str(requestDetails.get("requestType"));
        switch (requestType) {
            case "BANK" -> {
                Document bankDetails = mongo.getCollection(Collections.BANKS)
                        .find(new Document("consultantId", consId).append("documentType", requestType)).first();
                Document payload = parseDocOrNull(requestDetails.get("newData"));
                payload.put("consultantId", consId); // FAITHFUL: null newData -> NPE, matching JS null property set
                payload.put("documentType", requestType);
                Object attachment = body.get("attachment");
                Map<String, Object> nd = new LinkedHashMap<>();
                nd.put("bankName", body.get("bankName"));
                nd.put("accountNumber", body.get("accountNumber"));
                nd.put("ifsc", body.get("ifsc"));
                nd.put("bankHolderName", body.get("bankHolderName"));
                nd.put("attachment", attachment);
                newData.put("newData", stringify(nd));
                if (bankDetails != null) {
                    mongo.getCollection(Collections.BANKS).updateOne(new Document("_id", bankDetails.get("_id")),
                            new Document("$set", payload));
                } else {
                    mongo.getCollection(Collections.BANKS).insertOne(withId(payload));
                }
            }
            case "PANCARD" -> {
                Document panDetails = mongo.getCollection(Collections.BANKS)
                        .find(new Document("consultantId", consId).append("documentType", requestType)).first();
                Document payload = parseDocOrNull(requestDetails.get("newData"));
                payload.put("documentType", requestType); // FAITHFUL: null newData -> NPE, matching JS null property set
                payload.put("consultantId", consId);
                Object panImage = body.get("panImage");
                Map<String, Object> nd = new LinkedHashMap<>();
                nd.put("panNumber", body.get("panNumber"));
                nd.put("panImage", panImage);
                newData.put("newData", stringify(nd));
                if (panDetails != null) {
                    mongo.getCollection(Collections.BANKS).updateOne(new Document("_id", panDetails.get("_id")),
                            new Document("$set", payload));
                } else {
                    mongo.getCollection(Collections.BANKS).insertOne(withId(payload));
                }
            }
            default -> { }
        }
        return mongo.getCollection(Collections.REQUESTS).findOneAndUpdate(
                new Document("_id", requestDetails.get("_id")), new Document("$set", newData),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
    }

    /** Faithful port of RequestService.getByConsultantId. */
    public Map<String, Object> getByConsultantId(String id) {
        Document details = mongo.getCollection(Collections.REQUESTS)
                .find(new Document("consultantId", support.id(id))).first();
        if (details == null) throw new IllegalStateException("REQUEST_NOT_EXIST");
        Map<String, Object> reqDetails = new LinkedHashMap<>();
        reqDetails.put("currentData", parseJsonOrNull(details.get("currentData")));
        reqDetails.put("newData", parseJsonOrNull(details.get("newData")));
        reqDetails.put("requestId", details.get("_id"));
        reqDetails.put("approveStatus", truthyNumber(details.get("approveStatus")) ? details.get("approveStatus") : 0);
        Document bankDetails = mongo.getCollection(Collections.BANKS)
                .find(new Document("consultantId", support.id(id)).append("documentType", "BANK")).first();
        Document panDetails = mongo.getCollection(Collections.BANKS)
                .find(new Document("consultantId", support.id(id)).append("documentType", "PANCARD")).first();
        reqDetails.put("bankDetails", bankDetails);
        reqDetails.put("panDetails", panDetails);
        return reqDetails;
    }

    private Object parseJsonOrNull(Object value) {
        if (!(value instanceof String s) || s.isEmpty()) return null;
        try {
            return JSON.readValue(s, Object.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e.getMessage()); // FAITHFUL: Node JSON.parse throws on malformed JSON
        }
    }

    private Document parseDocOrNull(Object value) {
        if (!(value instanceof String s) || s.isEmpty()) return null;
        return Document.parse(s);
    }

    private String stringify(Map<String, Object> map) {
        Map<String, Object> filtered = new LinkedHashMap<>();
        // FAITHFUL(node-quirk): JSON.stringify drops undefined-valued keys; Java null is treated the same way.
        map.forEach((k, v) -> { if (v != null) filtered.put(k, v); });
        try {
            return JSON.writeValueAsString(filtered);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e.getMessage());
        }
    }

    private Document insertReturn(String collection, Document doc) {
        Document withId = withId(doc);
        mongo.getCollection(collection).insertOne(withId);
        return withId;
    }

    private Document withId(Document doc) {
        if (doc.get("_id") == null) doc.put("_id", new ObjectId());
        return doc;
    }

    private Object opt(Document doc, String key) {
        return doc == null ? null : doc.get(key);
    }

    private Object nested(Document doc, String outer, String inner) {
        Object o = doc.get(outer);
        return o instanceof Document d ? d.get(inner) : null;
    }

    private boolean truthy(Object value) {
        if (value == null) return false;
        if (value instanceof String s) return !s.isEmpty();
        if (value instanceof Number n) return n.doubleValue() != 0;
        if (value instanceof Boolean b) return b;
        return true;
    }

    private boolean truthyNumber(Object value) {
        return value instanceof Number n && n.doubleValue() != 0;
    }

    private String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private double numberOf(Object value) {
        return value instanceof Number n ? n.doubleValue() : 0;
    }

    private int intValue(Object value) {
        if (value instanceof Number n) return n.intValue();
        return Integer.parseInt(String.valueOf(value)); // Node: parseInt(input.approveStatus)
    }

    private long totalOf(Object count) {
        List<Document> list = asDocList(count);
        if (!list.isEmpty() && list.get(0).get("total") instanceof Number number) {
            return number.longValue();
        }
        return 0;
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
