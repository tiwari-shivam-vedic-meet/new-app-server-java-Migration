package com.vedicmeet.appserver.consultant;

import org.bson.types.ObjectId;

import java.util.ArrayList;
import java.util.List;

/** Package-local BSON id compatibility helpers for legacy fields stored as either String or ObjectId. */
final class MongoIds {
    private MongoIds() {}

    static Object id(Object raw) {
        if (raw instanceof ObjectId) return raw;
        String value = raw == null ? "" : String.valueOf(raw);
        return ObjectId.isValid(value) ? new ObjectId(value) : raw;
    }

    static List<Object> variants(Object raw) {
        List<Object> values = new ArrayList<>();
        if (raw == null) return values;
        values.add(raw);
        String value = String.valueOf(raw);
        if (ObjectId.isValid(value) && !(raw instanceof ObjectId)) values.add(new ObjectId(value));
        if (raw instanceof ObjectId) values.add(value);
        return values;
    }
}
