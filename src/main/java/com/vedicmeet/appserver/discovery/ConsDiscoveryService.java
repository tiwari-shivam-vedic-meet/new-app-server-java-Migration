package com.vedicmeet.appserver.discovery;

import com.vedicmeet.appserver.config.AppConstants.Collections;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Faithful port of the consultant-discovery READ handlers in Node
 * rest-apis/modules/user/cons.js. Each method reproduces the Node aggregation
 * exactly (programmatic pipeline, not Document.parse, since these are compact).
 *
 * These are gated behind the contract-test harness (diff vs Node on the TEST
 * server) before being enabled on /v2, exactly like the already-ported banner.
 */
@Service
public class ConsDiscoveryService {

    private final MongoTemplate mongo;

    public ConsDiscoveryService(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    /**
     * Port of GET /cons/get-list (cons.js L19-123).
     * NOTE: Node responds with the raw shape { success:true, data:{ list, pagination } }
     * (NOT the {code,message,result} envelope) — reproduced exactly so the harness diff matches.
     *
     * @param userId the caller's _id (from AuthUserService.load) — used in the block-check $lookup.
     */
    public Map<String, Object> getList(Object userId, Integer pageParam, Integer pageSizeParam,
                                       String sortField, String sortDirection,
                                       String filterField, String filterOperator, String filterValue) {
        int page = pageParam == null ? 0 : pageParam;
        int pageSize = pageSizeParam == null ? 1000 : pageSizeParam;
        int skip = page * pageSize;

        // Build filter query — Node always constrains canGoOffline:false.
        Document filterQuery = new Document("sessionsStatus.canGoOffline", false);
        if (notBlank(filterField) && notBlank(filterOperator) && notBlank(filterValue)) {
            switch (filterOperator) {
                case "equals":
                    filterQuery.append(filterField, filterValue);
                    break;
                case "contains":
                    filterQuery.append(filterField, new Document("$regex", filterValue).append("$options", "i"));
                    break;
                case "startsWith":
                    filterQuery.append(filterField, new Document("$regex", "^" + filterValue).append("$options", "i"));
                    break;
                case "endsWith":
                    filterQuery.append(filterField, new Document("$regex", filterValue + "$").append("$options", "i"));
                    break;
                default:
                    break;
            }
        }

        // Build sort query — insertion order preserved (matches JS object key order).
        Document sortQuery = new Document("quickStats.first_purchase", 1).append("created_at", -1);
        if (notBlank(sortField) && notBlank(sortDirection)
                && !"null".equals(sortField) && !"null".equals(sortDirection)) {
            sortQuery.append(sortField, "asc".equals(sortDirection.toLowerCase()) ? 1 : -1);
        }

        List<Document> lookupPipeline = Arrays.asList(
                new Document("$match", new Document("$expr", new Document("$and", Arrays.asList(
                        new Document("$eq", Arrays.asList("$userId", userId)),
                        new Document("$eq", Arrays.asList("$consId", "$$consultantId")))))));

        List<Document> pipeline = Arrays.asList(
                new Document("$match", filterQuery),
                new Document("$lookup", new Document("from", Collections.USER_CONS_RELS)
                        .append("let", new Document("consultantId", "$_id"))
                        .append("pipeline", lookupPipeline)
                        .append("as", "userConsRel")),
                new Document("$match", new Document("$or", Arrays.asList(
                        new Document("userConsRel", new Document("$size", 0)),
                        new Document("userConsRel.forCons.isUserBlocked", new Document("$ne", true))))),
                new Document("$project", new Document("_id", 1).append("accountName", 1)
                        .append("details", 1).append("isActive", 1).append("price", 1)
                        .append("sessionsStatus", 1).append("profileImage", 1)
                        .append("quickStats", 1).append("created_at", 1)),
                new Document("$sort", sortQuery),
                new Document("$skip", skip),
                new Document("$limit", pageSize));

        List<Document> list = mongo.getCollection(Collections.CONSULTANTS)
                .aggregate(pipeline).into(new ArrayList<>());
        long totalCount = mongo.getCollection(Collections.CONSULTANTS).countDocuments(filterQuery);
        long pageCount = (long) Math.ceil((double) totalCount / pageSize);

        Map<String, Object> pagination = new LinkedHashMap<>();
        pagination.put("pageCount", pageCount);
        pagination.put("pageNumber", page);
        pagination.put("totalDocuments", totalCount);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("list", list);
        data.put("pagination", pagination);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("data", data);
        return response;
    }

    private boolean notBlank(String s) {
        return s != null && !s.isEmpty();
    }
}
