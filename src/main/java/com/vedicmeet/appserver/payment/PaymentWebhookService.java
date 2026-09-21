package com.vedicmeet.appserver.payment;

import org.bson.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ⚠⚠ HIGH-RISK / SHADOW-ONLY — HUMAN REVIEW REQUIRED BEFORE ENABLING ⚠⚠
 *
 * Faithful port of Node {@code TransactionService.confirmPaymentWithWebhook} + the credit half of
 * {@code creditAndDebitOnWallet} (utils/classes/transaction.js) — the Razorpay
 * {@code POST /confirm/webhook} money path. This class is NOT wired to any live route. Per
 * COPILOT_HANDOFF.md Prompt D it must run in shadow and be reconciled against Node before it can
 * become a source of truth. Do NOT cut over.
 *
 * Idempotency contract (mirrored from tests-integration/payment/payment-webhook.test.js):
 *   1. captured + tx INITIATED  → credit user.wallet ONCE, write ONE ledger row, mark COMPLETED,
 *      return the invoice email/payload. The credit is guarded by the ATOMIC
 *      INITIATED→COMPLETED transition ({@link WalletGateway#atomicCompleteInitiated}); only one
 *      concurrent/duplicate webhook can win it.
 *   2. duplicate delivery (tx already COMPLETED/paid, or the atomic update returns null)
 *      → NO wallet credit, NO ledger row, but the webhook IS still audited, and no email.
 *   3. non-captured status → mark tx FAILED, no credit, no email.
 *   4. unknown order → throw "Order is not present or this order id and user" (audit already written).
 *
 * The webhook audit row is written FIRST, unconditionally, exactly like Node.
 *
 * NOTE: the credited wallet here is the PLAIN `user.wallet` number (what the Node webhook path and
 * the Jest test use) — NOT the AES-encrypted `coins` wallet used by the consultation-billing path.
 */
@Service
public class PaymentWebhookService {

    private final WalletGateway gateway;
    private final TransactionTemplate transactionTemplate;
    private final RechargeCouponRewardService couponRewards;

    @Autowired
    public PaymentWebhookService(WalletGateway gateway, MongoTransactionManager transactionManager,
                                 RechargeCouponRewardService couponRewards) {
        this.gateway = gateway;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.couponRewards = couponRewards;
    }

    /** Unit-test constructor: production always uses the transactional constructor above. */
    PaymentWebhookService(WalletGateway gateway) {
        this.gateway = gateway;
        this.transactionTemplate = null;
        this.couponRewards = null;
    }

    @SuppressWarnings("unchecked")
    public WebhookResult confirmPaymentWithWebhook(Map<String, Object> requestData) {
        // (1) always audit the raw webhook first — even for unknown orders / duplicates.
        gateway.saveWebhookAudit(requestData);

        if (transactionTemplate == null) {
            return processPayment(requestData);
        }
        return transactionTemplate.execute(status -> processPayment(requestData));
    }

    private WebhookResult processPayment(Map<String, Object> requestData) {

        Map<String, Object> entity = paymentEntity(requestData);
        String orderId = str(entity.get("order_id"));
        String paymentId = str(entity.get("id"));
        String status = str(entity.get("status"));

        Document tx = gateway.findTransactionByOrderId(orderId);
        if (tx == null) throw new RuntimeException("Order is not present or this order id and user");

        if ("captured".equals(status)) {
            String txStatus = tx.getString("status");
            if ("COMPLETED".equals(txStatus) || "paid".equals(txStatus)) {
                return WebhookResult.alreadyProcessed();
            }

            // (2) atomic INITIATED -> COMPLETED. Losers of the race get null and must not credit.
            Document updated = gateway.atomicCompleteInitiated(tx.get("_id"), paymentId);
            if (updated == null) return WebhookResult.alreadyProcessed();

            double coins = num(updated.get("coins"));
            gateway.creditUserWallet(updated.get("userId"), coins);
            gateway.insertWalletLedger(buildLedger(updated, coins));
            if (couponRewards != null) couponRewards.process(updated);

            Document user = gateway.findUser(updated.get("userId"));
            String email = userEmail(user);
            return WebhookResult.credited(email, buildInvoicePayload(updated, user, entity));
        } else {
            return gateway.atomicFailInitiated(tx.get("_id"), paymentId)
                    ? WebhookResult.failed() : WebhookResult.alreadyProcessed();
        }
    }

    /** payloadForWallet for the credit branch (transactionType 0). coins = coins + extraCoins(0). */
    private Document buildLedger(Document tx, double coins) {
        return new Document("userId", tx.get("userId"))
                .append("consultantId", null)
                .append("transactionId", tx.get("_id"))
                .append("walletDeductReason", "")
                .append("transactionFor", "topup")
                .append("userType", "user")
                .append("coins", coins)
                .append("totalAmountPayToPlateform", 0)
                .append("startDate", new Date())
                .append("endDate", new Date())
                .append("planDuration", 1)
                .append("discountPercentage", 0)
                .append("transactionType", 0)
                .append("isConsTransfer", false)
                .append("invoiceURI", null)
                .append("meta", null);
    }

    /** Faithful GST/invoice payload (Delhi → CGST+SGST 9/9, else IGST 18). Used only for the email. */
    private Map<String, Object> buildInvoicePayload(Document tx, Document user, Map<String, Object> entity) {
        Document details = user != null && user.get("details") instanceof Document
                ? (Document) user.get("details") : new Document();
        double baseAmount = num(tx.get("paidAmount"));
        String state = str(details.get("state"));
        String address = str(details.get("address"));
        boolean isDelhi = (state != null && state.trim().toLowerCase().contains("delhi"))
                || (address != null && address.trim().toLowerCase().contains("delhi"));
        double cgst = 0, sgst = 0, igst = 0, total;
        if (isDelhi) {
            cgst = baseAmount * 9 / 100;
            sgst = baseAmount * 9 / 100;
            total = baseAmount + cgst + sgst;
        } else {
            igst = baseAmount * 18 / 100;
            total = baseAmount + igst;
        }
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("NAME", user == null ? null : user.get("name"));
        p.put("EMAIL", details.get("email") == null ? "" : details.get("email"));
        p.put("ADDRESS", details.get("address") == null ? "" : details.get("address"));
        p.put("MOBILE", str(details.get("phonePrefix")) + " " + str(details.get("phone")));
        p.put("PAYMENT_TYPE", entity.get("method"));
        p.put("GST", 18);
        p.put("PAID_AMOUNT", tx.get("paidAmount"));
        p.put("COINS", tx.get("coins"));
        p.put("CGST", cgst);
        p.put("SGST", sgst);
        p.put("IGST", igst);
        p.put("STATE", details.get("state") == null ? "" : details.get("state"));
        p.put("TOTAL_AMOUNT", Math.round(total * 100.0) / 100.0);
        return p;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> paymentEntity(Map<String, Object> requestData) {
        Object payload = requestData.get("payload");
        if (payload instanceof Map) {
            Object payment = ((Map<String, Object>) payload).get("payment");
            if (payment instanceof Map) {
                Object entity = ((Map<String, Object>) payment).get("entity");
                if (entity instanceof Map) return (Map<String, Object>) entity;
            }
        }
        return new LinkedHashMap<>();
    }

    private String userEmail(Document user) {
        if (user != null && user.get("details") instanceof Document) {
            Object email = ((Document) user.get("details")).get("email");
            return email == null ? null : String.valueOf(email);
        }
        return null;
    }

    private double num(Object v) {
        if (v instanceof Number) return ((Number) v).doubleValue();
        try { return v == null ? 0 : Double.parseDouble(String.valueOf(v)); } catch (Exception e) { return 0; }
    }

    private String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
