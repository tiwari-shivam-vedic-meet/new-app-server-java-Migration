package com.vedicmeet.appserver.content;

import com.mongodb.client.model.FindOneAndDeleteOptions;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/** Port of Node IntakeService with the same ten-form cap, scoped safely to the current user. */
@Service
public class IntakeService {
    private final MongoTemplate mongo;
    public IntakeService(MongoTemplate mongo) { this.mongo = mongo; }

    public Map<String, Object> list(Document user, Integer pageParam, Integer limitParam,
                                    String search) {
        int page = pageParam == null ? 1 : pageParam;
        int limit = limitParam == null ? 10 : limitParam;
        if (page < 1 || limit < 1) throw new IllegalArgumentException("Invalid pagination");
        Document filter = new Document("userId", user.get("_id"));
        if (search != null && !search.isEmpty()) filter.append("title",
                new Document("$regex", ".*" + search + ".*").append("$options", "i"));
        var list = mongo.getCollection(Collections.USER_INTAKE_FORMS).find(filter)
                .sort(new Document("createdAt", -1)).skip((page - 1) * limit).limit(limit)
                .into(new ArrayList<>());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", list);
        result.put("total", mongo.getCollection(Collections.USER_INTAKE_FORMS).countDocuments(filter));
        return result;
    }

    public void addOrUpdate(Map<String, Object> input, Document user) {
        Document data = input == null ? new Document() : new Document(input);
        Object intakeId = data.remove("intakeId");
        data.put("userId", user.get("_id"));
        data.put("updatedAt", new Date());
        if (intakeId != null) {
            mongo.getCollection(Collections.USER_INTAKE_FORMS).updateOne(
                    new Document("_id", id(intakeId)).append("userId", user.get("_id")),
                    new Document("$set", data));
            return;
        }
        data.put("createdAt", data.get("updatedAt"));
        mongo.getCollection(Collections.USER_INTAKE_FORMS).insertOne(data);
        long count = mongo.getCollection(Collections.USER_INTAKE_FORMS)
                .countDocuments(new Document("userId", user.get("_id")));
        if (count >= 10) {
            // Node used findOneAndDelete({}), which could delete another user's form. Preserve the
            // intended ten-form behavior while fixing only that cross-account data-loss defect.
            mongo.getCollection(Collections.USER_INTAKE_FORMS).findOneAndDelete(
                    new Document("userId", user.get("_id")),
                    new FindOneAndDeleteOptions().sort(new Document("_id", 1)));
        }
    }

    private Object id(Object value) {
        String text = String.valueOf(value);
        return org.bson.types.ObjectId.isValid(text) ? new org.bson.types.ObjectId(text) : value;
    }
}
