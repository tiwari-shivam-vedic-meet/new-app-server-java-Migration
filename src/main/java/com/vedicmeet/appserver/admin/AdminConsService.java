package com.vedicmeet.appserver.admin;

import com.vedicmeet.appserver.config.AppConstants.Collections;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AdminConsService {

    private final MongoTemplate mongo;
    private final AdminMongoSupport support;

    public AdminConsService(MongoTemplate mongo, AdminMongoSupport support) {
        this.mongo = mongo;
        this.support = support;
    }

    /** Faithful port of admin/cons.js GET '' (list). Node mounts this route WITHOUT adminAuthMiddleware. */
    public Map<String, Object> list(Map<String, String> query) {
        // FAITHFUL(node-quirk): cons.js defaults page=0 (0-indexed) and pageSize=1000; skip = page * pageSize.
        int page = intOf(query.get("page"), 0);
        int pageSize = intOf(query.get("pageSize"), 1000);
        int skip = page * pageSize;

        String filterField = query.get("filterField");
        String filterOperator = query.get("filterOperator");
        String filterValue = query.get("filterValue");
        String sortField = query.get("sortField");
        String sortDirection = query.get("sortDirection");

        Document filterQuery = new Document();
        if (truthy(filterField) && truthy(filterOperator) && truthy(filterValue)) {
            switch (filterOperator) {
                case "equals" -> filterQuery.put(filterField, filterValue);
                // FAITHFUL(node-quirk): cons.js passes filterValue straight into $regex without escaping.
                case "contains" -> filterQuery.put(filterField, new Document("$regex", filterValue).append("$options", "i"));
                case "startsWith" -> filterQuery.put(filterField, new Document("$regex", "^" + filterValue).append("$options", "i"));
                case "endsWith" -> filterQuery.put(filterField, new Document("$regex", filterValue + "$").append("$options", "i"));
                default -> { }
            }
        }

        Document sortQuery = new Document();
        if (truthy(sortField) && truthy(sortDirection) && !"null".equals(sortField) && !"null".equals(sortDirection)) {
            sortQuery.put(sortField, "asc".equalsIgnoreCase(sortDirection) ? 1 : -1);
        }

        List<Document> list = mongo.getCollection(Collections.CONSULTANTS).find(filterQuery)
                .sort(sortQuery).skip(skip).limit(pageSize).into(new ArrayList<>());
        long totalCount = mongo.getCollection(Collections.CONSULTANTS).countDocuments(filterQuery);
        long pageCount = pageSize == 0 ? 0 : (long) Math.ceil((double) totalCount / pageSize);

        Map<String, Object> pagination = new LinkedHashMap<>();
        pagination.put("pageCount", pageCount);
        pagination.put("pageNumber", page);
        pagination.put("totalDocuments", totalCount);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("list", list);
        data.put("pagination", pagination);
        return data;
    }

    private boolean truthy(String value) {
        return value != null && !value.isEmpty();
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
