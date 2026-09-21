package com.vedicmeet.appserver.content;

import com.vedicmeet.appserver.config.AppConstants.Collections;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

/**
 * Faithful port of Node CMSService.cmsDetails (utils/classes/cms.js), served by
 * GET /v1/cms/details (a PUBLIC route in Node — no auth middleware).
 *
 * Node logic, verbatim:
 *   userType = input.userType == 'user' ? 'user' : 'cons'
 *   params   = { userType, type: parseInt(input.type) }
 *   return cms.findOne(params)
 *
 * parseInt(undefined) is NaN in Node, which matches nothing -> null. Here, an
 * absent/invalid type likewise yields no match (null).
 */
@Service
public class CmsService {

    private final MongoTemplate mongo;

    public CmsService(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    public Document cmsDetails(String userTypeParam, String typeParam) {
        String userType = "user".equals(userTypeParam) ? "user" : "cons";

        Integer type = parseIntOrNull(typeParam);
        if (type == null) {
            return null; // parseInt(NaN) matches nothing in Node
        }

        Document params = new Document("userType", userType).append("type", type);
        return mongo.getCollection(Collections.CMS).find(params).first();
    }

    private Integer parseIntOrNull(String s) {
        if (s == null) return null;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
