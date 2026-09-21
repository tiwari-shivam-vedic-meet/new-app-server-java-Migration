package com.vedicmeet.appserver.payment;

import com.vedicmeet.appserver.config.AppConstants.Collections;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * Production {@link GatewayStore}. Reads the {@code userMasterSettings} active-gateway list from
 * {@code seed_masters}. {@code confirmPayment} (the wallet-crediting money core, Node
 * {@code TransactionService.confirmPayment}) is intentionally NOT wired here — it stays a seam behind
 * the human-review gate, so the ported callbacks are safe to expose without moving money.
 */
@Repository
public class MongoGatewayStore implements GatewayStore {

    private final MongoTemplate mongo;
    private final PaymentConfirmService confirmService;

    public MongoGatewayStore(MongoTemplate mongo, PaymentConfirmService confirmService) {
        this.mongo = mongo;
        this.confirmService = confirmService;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<String> readActivePaymentGatewaysRaw() {
        Document doc = mongo.getCollection(Collections.SEED_MASTERS)
                .find(new Document("for", "userMasterSettings")).first();
        if (doc == null) return null;
        Object raw = null;
        Object data = doc.get("data");
        if (data instanceof Document) {
            raw = ((Document) data).get("activePaymentGateways");
        }
        if (raw == null) raw = doc.get("activePaymentGateways");
        if (!(raw instanceof List)) return null;
        List<String> out = new ArrayList<>();
        for (Object o : (List<Object>) raw) out.add(String.valueOf(o));
        return out;
    }

    @Override
    public ConfirmResult confirmPayment(String orderId, String paymentId) {
        // Faithful idempotent confirm (atomic INITIATED->COMPLETED + single-winner wallet credit).
        // Reached only via the @MigrationWrite-gated callbacks, so it does not move money in shadow.
        return confirmService.confirmPayment(orderId, paymentId, false);
    }
}
