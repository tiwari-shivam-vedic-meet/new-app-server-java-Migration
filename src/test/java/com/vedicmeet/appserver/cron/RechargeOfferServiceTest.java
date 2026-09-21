package com.vedicmeet.appserver.cron;

import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

/** Pins the faithful filter/update of {@link RechargeOfferService} (Node disableExpiredRechargeOffers). */
class RechargeOfferServiceTest {

    @Test
    void buildsExactRechargeFilterAndStatusFalseUpdate() {
        Capture store = new Capture();
        store.result = 4;
        Date now = new Date(1_700_000_000_000L);

        long modified = new RechargeOfferService(store).disableExpiredRechargeOffers(now);

        assertEquals(4, modified);
        assertEquals("recharge", store.filter.getString("type"));
        assertEquals(Boolean.TRUE, store.filter.getBoolean("status"));
        Document expiry = (Document) store.filter.get("rechargeOfferExpiry");
        assertEquals(now, expiry.get("$lte"), "expiry $lte now");
        Document set = (Document) store.update.get("$set");
        assertEquals(Boolean.FALSE, set.getBoolean("status"), "sets status:false");
    }

    static class Capture implements RechargeOfferStore {
        Document filter, update;
        long result;

        @Override public long updateMany(Document filter, Document update) {
            this.filter = filter;
            this.update = update;
            return result;
        }
    }
}
