package com.vedicmeet.appserver.admin;

import com.vedicmeet.appserver.call.CallCompletionService;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Map;

/** Port of admin/waitlist.js, including the settlement-owning admin end action. */
@Service
public class AdminWaitlistService {

    private final MongoTemplate mongo;
    private final AdminMongoSupport db;
    private final CallCompletionService completion;
    private final boolean callExecutionEnabled;

    public AdminWaitlistService(MongoTemplate mongo, AdminMongoSupport db,
                                CallCompletionService completion,
                                @Value("${vedicmeet.migration.call-execution-enabled:false}") boolean callExecutionEnabled) {
        this.mongo = mongo;
        this.db = db;
        this.completion = completion;
        this.callExecutionEnabled = callExecutionEnabled;
    }

    public Map<String, Object> grid(int page, int pageSize, String sortField, String sortDirection,
                                    String filterField, String filterOperator, String filterValue) {
        int limit = db.safeLimit(pageSize <= 0 ? 1000 : pageSize);
        Document filter = db.dynamicFilter(filterField, filterOperator, filterValue);
        var rows = mongo.getCollection(Collections.WAITLISTS).find(filter)
                .sort(db.dynamicSort(sortField, sortDirection)).skip(Math.max(0, page) * limit)
                .limit(limit).into(new ArrayList<>());
        long total = mongo.getCollection(Collections.WAITLISTS).countDocuments(filter);
        return Map.of("list", rows, "pagination", Map.of("pageCount", (long) Math.ceil(total / (double) limit),
                "pageNumber", Math.max(0, page), "totalDocuments", total));
    }

    public CallCompletionService.Result end(String waitlistId) {
        if (!callExecutionEnabled) throw new IllegalStateException("CALL_EXECUTION_DISABLED");
        Document waitlist = mongo.getCollection(Collections.WAITLISTS)
                .find(new Document("_id", db.id(waitlistId)).append("status", "progress"))
                .projection(new Document("user_id", 1).append("consultant_id", 1)).first();
        if (waitlist == null) throw new IllegalStateException("Waitlist not found");
        CallCompletionService.Result result = completion.complete(waitlistId,
                String.valueOf(waitlist.get("user_id")), String.valueOf(waitlist.get("consultant_id")),
                "completed", "admin");
        if (result.outcome() != CallCompletionService.Outcome.COMPLETED
                && result.outcome() != CallCompletionService.Outcome.DUPLICATE) {
            throw new IllegalStateException("WAITLIST_END_" + result.outcome());
        }
        return result;
    }
}
