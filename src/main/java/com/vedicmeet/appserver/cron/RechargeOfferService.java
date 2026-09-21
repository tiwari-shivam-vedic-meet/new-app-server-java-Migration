package com.vedicmeet.appserver.cron;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Date;

/**
 * FAITHFUL port of the Node cron {@code disableExpiredRechargeOffers} (utils/cron/disableExpiredRechargeOffer.js).
 *
 * <p>Exactly Node's {@code updateMany({type:'recharge', rechargeOfferExpiry:{$lte:now}, status:true},
 * {$set:{status:false}})}. The filter/update are built here (the testable logic); the write is behind
 * {@link RechargeOfferStore}. This is a WRITE to the shared DB, so it stays gated — it only runs when the
 * cron scheduler is explicitly enabled ({@code vedicmeet.cron.enabled=true}) and holds the Redis cron lock.</p>
 */
@Service
public class RechargeOfferService {

    private static final Logger log = LoggerFactory.getLogger(RechargeOfferService.class);

    private final RechargeOfferStore store;

    public RechargeOfferService(RechargeOfferStore store) {
        this.store = store;
    }

    public long disableExpiredRechargeOffers(Date now) {
        Document filter = new Document("type", "recharge")
                .append("rechargeOfferExpiry", new Document("$lte", now))
                .append("status", true);
        Document update = new Document("$set", new Document("status", false));
        long modified = store.updateMany(filter, update);
        log.info("[CRON] Disabled {} expired recharge offers.", modified);
        return modified;
    }
}
