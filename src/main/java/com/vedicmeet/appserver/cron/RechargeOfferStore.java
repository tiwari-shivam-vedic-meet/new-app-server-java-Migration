package com.vedicmeet.appserver.cron;

import com.mongodb.client.result.UpdateResult;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Repository;

/** Port seam for {@link RechargeOfferService}: the {@code membership_discounts} updateMany. */
public interface RechargeOfferStore {

    long updateMany(Document filter, Document update);

    @Repository
    class Mongo implements RechargeOfferStore {
        private final MongoTemplate mongo;

        public Mongo(MongoTemplate mongo) {
            this.mongo = mongo;
        }

        @Override
        public long updateMany(Document filter, Document update) {
            UpdateResult r = mongo.getCollection(Collections.MEMBERSHIP_DISCOUNTS).updateMany(filter, update);
            return r.getModifiedCount();
        }
    }
}
