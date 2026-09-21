package com.vedicmeet.appserver.payment;

import org.bson.Document;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * ⚠ SHADOW-ONLY / MONEY. FAITHFUL port of the payment reconciliation worker
 * (node-production-bf308ee utils/functions/pending-meta-purchase-worker.js) — polls INITIATED
 * PhonePe/Paytm transactions, checks the gateway, and on success confirms the payment (idempotently via
 * {@link PaymentConfirmService}) then fires the Meta purchase event exactly once (atomic claim).
 *
 * <p>The status-parsing ({@link #parsePaytmStatus}, {@link #mapPhonePeStatus}) and the per-transaction
 * decision flow ({@link #processOne}) are ported exactly and unit-tested. The gateway status calls, the
 * Meta event, and the wallet-crediting confirm are behind {@link ReconciliationStore} so nothing fires in
 * a shadow launch; the scheduler that drives it is gated OFF by default (see CronScheduler).</p>
 */
@Service
public class PaymentReconciliationService {

    public enum ReconStatus { SUCCESS, FAILED, PENDING, ERROR }

    private final ReconciliationStore store;

    public PaymentReconciliationService(ReconciliationStore store) {
        this.store = store;
    }

    /** runPendingMetaPurchaseWorker: fetch the pending batch, process each, count. */
    public int runReconciliation() {
        List<Document> pending = store.getPendingPayments();
        int processed = 0;
        for (Document tx : pending) {
            try {
                processOne(tx);
                processed++;
            } catch (RuntimeException e) {
                // Node logs and continues to the next tx.
            }
        }
        return processed;
    }

    /** processOne: retry++, gateway dispatch, then pending/failed/error/success handling + single Meta fire. */
    public void processOne(Document tx) {
        Object txId = tx.get("_id");
        String orderId = str(tx.get("orderId"));
        String userId = str(tx.get("userId"));
        String gateway = tx.get("paymentGateway") == null ? "" : str(tx.get("paymentGateway")).toLowerCase();

        store.incrementRetryCount(txId);

        ReconStatus status;
        if ("phonepe".equals(gateway)) {
            status = store.phonePeStatus(orderId);
        } else if ("paytm".equals(gateway)) {
            status = store.paytmStatus(orderId);
        } else {
            status = store.phonePeStatus(orderId);
            if (status == ReconStatus.PENDING || status == ReconStatus.ERROR) {
                status = store.paytmStatus(orderId);
            }
            if (status == ReconStatus.PENDING || status == ReconStatus.ERROR) {
                return; // give up this run
            }
        }

        if (status == ReconStatus.PENDING) {
            return; // retry next run
        }
        if (status == ReconStatus.FAILED) {
            store.markTransactionFailed(txId);
            return;
        }
        if (status == ReconStatus.ERROR) {
            return; // will retry
        }

        // status == SUCCESS
        try {
            store.confirmPayment(orderId, userId, str(tx.get("paymentId")));
        } catch (RuntimeException e) {
            return;
        }

        if (store.isMetaEventFired(txId)) {
            return; // already fired — never a 2nd event
        }
        if (!store.isMetaPublisherReady()) {
            return; // do not burn the at-most-once claim while the external adapter is disabled
        }
        if (!store.claimMetaEvent(txId)) {
            return; // another process claimed it
        }
        store.fireMetaPurchaseEvent(tx);
    }

    /** parsePaytmStatus: TXN_SUCCESS/01 → success, PENDING/402 → pending, else failed; non-success → error. */
    public ReconStatus parsePaytmStatus(Map<String, Object> verifyResult) {
        if (verifyResult == null || !Boolean.TRUE.equals(verifyResult.get("success"))) {
            return ReconStatus.ERROR;
        }
        Map<String, Object> data = asMap(verifyResult.get("data"));
        Map<String, Object> resultInfo = asMap(data.get("resultInfo"));
        String resultStatus = str(resultInfo.get("resultStatus"));
        resultStatus = resultStatus == null ? "" : resultStatus.toUpperCase();
        String resultCode = resultInfo.get("resultCode") == null ? "" : String.valueOf(resultInfo.get("resultCode"));

        if ("TXN_SUCCESS".equals(resultStatus) || "01".equals(resultCode)) return ReconStatus.SUCCESS;
        if ("PENDING".equals(resultStatus) || "402".equals(resultCode)) return ReconStatus.PENDING;
        return ReconStatus.FAILED;
    }

    /** mapPhonePeStatus: (success && state COMPLETED) || state paid → success; FAILED → failed; else pending. */
    public ReconStatus mapPhonePeStatus(Map<String, Object> result) {
        if (result == null) return ReconStatus.ERROR;
        Map<String, Object> data = asMap(result.get("data"));
        String state = str(data.get("state"));
        // Node precedence: (result.success && state==='COMPLETED') || state==='paid'
        if ((Boolean.TRUE.equals(result.get("success")) && "COMPLETED".equals(state)) || "paid".equals(state)) {
            return ReconStatus.SUCCESS;
        }
        if ("FAILED".equals(state)) return ReconStatus.FAILED;
        return ReconStatus.PENDING;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : Map.of();
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
