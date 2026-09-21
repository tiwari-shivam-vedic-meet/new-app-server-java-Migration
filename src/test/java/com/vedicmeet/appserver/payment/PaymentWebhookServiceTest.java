package com.vedicmeet.appserver.payment;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Idempotency contract for {@link PaymentWebhookService}, mirrored 1:1 from the Node Jest suite
 * tests-integration/payment/payment-webhook.test.js. Runs against an in-memory {@link FakeWalletGateway}
 * so the money logic is verified deterministically without a Mongo (there is no embedded Mongo here).
 *
 * This is the human-review gate for Prompt D: the service is shadow-only and unwired; these tests
 * pin the exact credit-once / duplicate-no-op / audit-always / failed / unknown behaviour a reviewer
 * must sign off before it can go live.
 */
class PaymentWebhookServiceTest {

    private static Map<String, Object> webhookBody(String orderId, String paymentId, String status) {
        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("id", paymentId);
        entity.put("order_id", orderId);
        entity.put("status", status);
        entity.put("method", "upi");
        Map<String, Object> payment = new LinkedHashMap<>();
        payment.put("entity", entity);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("payment", payment);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("event", "captured".equals(status) ? "payment.captured" : "payment.failed");
        body.put("payload", payload);
        return body;
    }

    @Test
    void capturedPayment_creditsWalletOnce_writesLedgerAndAudit_returnsInvoiceEmail() {
        FakeWalletGateway gw = new FakeWalletGateway();
        ObjectId userId = new ObjectId();
        gw.seedUser(userId, 0, "buyer@test.local", "Haryana");
        gw.seedTransaction("order_W1", userId, 100, 100);
        PaymentWebhookService service = new PaymentWebhookService(gw);

        WebhookResult res = service.confirmPaymentWithWebhook(webhookBody("order_W1", "pay_W1", "captured"));

        assertEquals(WebhookResult.Kind.CREDITED, res.kind());
        assertEquals("buyer@test.local", res.email());
        assertEquals(100.0, gw.walletOf(userId));
        assertEquals(1, gw.ledgers.size());
        assertEquals(100.0, ((Number) gw.ledgers.get(0).get("coins")).doubleValue());
        assertEquals(1, gw.audits.size());
        Document tx = gw.txByOrder("order_W1");
        assertEquals("COMPLETED", tx.getString("status"));
        assertEquals("pay_W1", tx.getString("paymentId"));
    }

    @Test
    void duplicateDelivery_secondWebhookIsWalletNoOp_butStillAudited() {
        FakeWalletGateway gw = new FakeWalletGateway();
        ObjectId userId = new ObjectId();
        gw.seedUser(userId, 0, "buyer@test.local", "Haryana");
        gw.seedTransaction("order_W2", userId, 100, 100);
        PaymentWebhookService service = new PaymentWebhookService(gw);

        service.confirmPaymentWithWebhook(webhookBody("order_W2", "pay_W2", "captured"));
        WebhookResult res2 = service.confirmPaymentWithWebhook(webhookBody("order_W2", "pay_W2", "captured"));

        assertEquals(WebhookResult.Kind.ALREADY_PROCESSED, res2.kind());
        assertNull(res2.email(), "duplicate must not re-send the invoice email");
        assertEquals(100.0, gw.walletOf(userId), "wallet credited exactly once");
        assertEquals(1, gw.ledgers.size(), "exactly one ledger row");
        assertEquals(2, gw.audits.size(), "both deliveries are audited");
    }

    @Test
    void nonCapturedStatus_marksTransactionFailed_noCredit_noEmail() {
        FakeWalletGateway gw = new FakeWalletGateway();
        ObjectId userId = new ObjectId();
        gw.seedUser(userId, 0, "buyer@test.local", "Haryana");
        gw.seedTransaction("order_W4", userId, 100, 100);
        PaymentWebhookService service = new PaymentWebhookService(gw);

        WebhookResult res = service.confirmPaymentWithWebhook(webhookBody("order_W4", "pay_W4", "failed"));

        assertEquals(WebhookResult.Kind.FAILED, res.kind());
        assertNull(res.email());
        assertEquals(0.0, gw.walletOf(userId));
        assertEquals(0, gw.ledgers.size());
        assertEquals("FAILED", gw.txByOrder("order_W4").getString("status"));
    }

    @Test
    void lateFailureAfterCompletion_doesNotOverwriteCompletedTransaction() {
        FakeWalletGateway gw = new FakeWalletGateway();
        ObjectId userId = new ObjectId();
        gw.seedUser(userId, 100, "buyer@test.local", "Haryana");
        gw.seedTransaction("order_LATE", userId, 100, 100);
        gw.txByOrder("order_LATE").put("status", "COMPLETED");
        PaymentWebhookService service = new PaymentWebhookService(gw);

        WebhookResult result = service.confirmPaymentWithWebhook(
                webhookBody("order_LATE", "pay_late_failure", "failed"));

        assertEquals(WebhookResult.Kind.ALREADY_PROCESSED, result.kind());
        assertEquals("COMPLETED", gw.txByOrder("order_LATE").getString("status"));
        assertEquals(100.0, gw.walletOf(userId));
    }

    @Test
    void unknownOrder_throws_noWalletMutation_butAuditWritten() {
        FakeWalletGateway gw = new FakeWalletGateway();
        PaymentWebhookService service = new PaymentWebhookService(gw);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.confirmPaymentWithWebhook(webhookBody("order_UNKNOWN", "pay_X", "captured")));
        assertTrue(ex.getMessage().contains("Order is not present"));
        assertEquals(0, gw.ledgers.size());
        assertEquals(1, gw.audits.size(), "the webhook is audited before the order lookup, like Node");
    }

    @Test
    void alreadyCompletedTransaction_preCheckShortCircuits_noSecondCredit() {
        FakeWalletGateway gw = new FakeWalletGateway();
        ObjectId userId = new ObjectId();
        gw.seedUser(userId, 100, "buyer@test.local", "Haryana");
        gw.seedTransaction("order_W5", userId, 100, 100);
        gw.txByOrder("order_W5").put("status", "COMPLETED"); // already processed earlier
        PaymentWebhookService service = new PaymentWebhookService(gw);

        WebhookResult res = service.confirmPaymentWithWebhook(webhookBody("order_W5", "pay_W5", "captured"));

        assertEquals(WebhookResult.Kind.ALREADY_PROCESSED, res.kind());
        assertEquals(100.0, gw.walletOf(userId));
        assertEquals(0, gw.ledgers.size());
        assertEquals(1, gw.audits.size());
    }

    // ---- in-memory gateway ----

    static class FakeWalletGateway implements WalletGateway {
        final Map<Object, Document> txById = new LinkedHashMap<>();
        final Map<String, Object> orderToId = new LinkedHashMap<>();
        final Map<Object, Document> users = new LinkedHashMap<>();
        final Map<Object, Double> wallets = new LinkedHashMap<>();
        final List<Document> ledgers = new CopyOnWriteArrayList<>();
        final List<Map<String, Object>> audits = new CopyOnWriteArrayList<>();

        void seedUser(Object userId, double wallet, String email, String state) {
            Document details = new Document("email", email).append("state", state)
                    .append("address", "").append("phone", "9000000001").append("phonePrefix", "+91");
            users.put(userId, new Document("_id", userId).append("name", "Buyer")
                    .append("wallet", wallet).append("details", details));
            wallets.put(userId, wallet);
        }

        void seedTransaction(String orderId, Object userId, double coins, double paidAmount) {
            ObjectId id = new ObjectId();
            Document tx = new Document("_id", id).append("orderId", orderId).append("userId", userId)
                    .append("coins", coins).append("paidAmount", paidAmount).append("status", "INITIATED");
            txById.put(id, tx);
            orderToId.put(orderId, id);
        }

        Document txByOrder(String orderId) { return txById.get(orderToId.get(orderId)); }
        double walletOf(Object userId) { return wallets.getOrDefault(userId, 0.0); }

        @Override public void saveWebhookAudit(Map<String, Object> requestData) { audits.add(requestData); }

        @Override public Document findTransactionByOrderId(String orderId) {
            Object id = orderToId.get(orderId);
            return id == null ? null : txById.get(id);
        }

        @Override public Document atomicCompleteInitiated(Object transactionId, String paymentId) {
            Document tx = txById.get(transactionId);
            if (tx == null || !"INITIATED".equals(tx.getString("status"))) return null;
            tx.put("status", "COMPLETED");
            tx.put("paymentId", paymentId);
            return tx;
        }

        @Override public boolean atomicFailInitiated(Object transactionId, String paymentId) {
            Document tx = txById.get(transactionId);
            if (tx == null || !"INITIATED".equals(tx.getString("status"))) return false;
            tx.put("status", "FAILED");
            tx.put("paymentId", paymentId);
            return true;
        }

        @Override public Document findUser(Object userId) { return users.get(userId); }

        @Override public double creditUserWallet(Object userId, double coins) {
            double n = wallets.getOrDefault(userId, 0.0) + coins;
            wallets.put(userId, n);
            return n;
        }

        @Override public void insertWalletLedger(Document payloadForWallet) { ledgers.add(payloadForWallet); }
    }
}
