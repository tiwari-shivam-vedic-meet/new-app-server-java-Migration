package com.vedicmeet.appserver.payment;

import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.vedicmeet.appserver.payment.PaymentReconciliationService.ReconStatus;
import static org.junit.jupiter.api.Assertions.*;

/** Pins the faithful reconciliation logic of {@link PaymentReconciliationService} (Node worker). */
class PaymentReconciliationServiceTest {

    private final PaymentReconciliationService svc = new PaymentReconciliationService(new Fake());

    @Test
    void parsePaytm_successCodes() {
        assertEquals(ReconStatus.ERROR, svc.parsePaytmStatus(null));
        assertEquals(ReconStatus.ERROR, svc.parsePaytmStatus(Map.of("success", false)));
        assertEquals(ReconStatus.SUCCESS, svc.parsePaytmStatus(verify("TXN_SUCCESS", "")));
        assertEquals(ReconStatus.SUCCESS, svc.parsePaytmStatus(verify("", "01")));
        assertEquals(ReconStatus.PENDING, svc.parsePaytmStatus(verify("PENDING", "")));
        assertEquals(ReconStatus.PENDING, svc.parsePaytmStatus(verify("", "402")));
        assertEquals(ReconStatus.FAILED, svc.parsePaytmStatus(verify("TXN_FAILURE", "227")));
    }

    @Test
    void mapPhonePe_states() {
        assertEquals(ReconStatus.ERROR, svc.mapPhonePeStatus(null));
        assertEquals(ReconStatus.SUCCESS, svc.mapPhonePeStatus(Map.of("success", true, "data", Map.of("state", "COMPLETED"))));
        assertEquals(ReconStatus.SUCCESS, svc.mapPhonePeStatus(Map.of("success", false, "data", Map.of("state", "paid"))));
        assertEquals(ReconStatus.FAILED, svc.mapPhonePeStatus(Map.of("success", true, "data", Map.of("state", "FAILED"))));
        assertEquals(ReconStatus.PENDING, svc.mapPhonePeStatus(Map.of("success", true, "data", Map.of("state", "CREATED"))));
    }

    @Test
    void processOne_phonePeSuccess_confirmsClaimsAndFiresMeta() {
        Fake s = new Fake();
        s.phonePe = ReconStatus.SUCCESS;
        s.claim = true;
        new PaymentReconciliationService(s).processOne(tx("phonepe"));
        assertTrue(s.confirmed);
        assertTrue(s.metaFired);
    }

    @Test
    void processOne_pending_doesNothingElse() {
        Fake s = new Fake();
        s.phonePe = ReconStatus.PENDING;
        new PaymentReconciliationService(s).processOne(tx("phonepe"));
        assertFalse(s.confirmed);
        assertFalse(s.markedFailed);
    }

    @Test
    void processOne_failed_marksFailed_noConfirm() {
        Fake s = new Fake();
        s.phonePe = ReconStatus.FAILED;
        new PaymentReconciliationService(s).processOne(tx("phonepe"));
        assertTrue(s.markedFailed);
        assertFalse(s.confirmed);
    }

    @Test
    void processOne_successButMetaAlreadyFired_confirmsButNoSecondEvent() {
        Fake s = new Fake();
        s.phonePe = ReconStatus.SUCCESS;
        s.alreadyFired = true;
        new PaymentReconciliationService(s).processOne(tx("phonepe"));
        assertTrue(s.confirmed);
        assertFalse(s.metaFired, "no 2nd Meta event when already fired");
    }

    @Test
    void processOne_unknownGateway_fallsBackPhonePeThenPaytm() {
        Fake s = new Fake();
        s.phonePe = ReconStatus.PENDING;   // phonePe inconclusive
        s.paytm = ReconStatus.SUCCESS;     // paytm says success
        s.claim = true;
        new PaymentReconciliationService(s).processOne(tx(null));
        assertTrue(s.confirmed, "falls back to paytm and confirms");
    }

    private Document tx(String gateway) {
        Document d = new Document("_id", "T1").append("orderId", "ORD").append("userId", "U1");
        if (gateway != null) d.append("paymentGateway", gateway);
        return d;
    }

    private Map<String, Object> verify(String status, String code) {
        return Map.of("success", true, "data", Map.of("resultInfo",
                Map.of("resultStatus", status, "resultCode", code)));
    }

    static class Fake implements ReconciliationStore {
        ReconStatus phonePe = ReconStatus.ERROR, paytm = ReconStatus.ERROR;
        boolean claim, alreadyFired, confirmed, markedFailed, metaFired;

        @Override public List<Document> getPendingPayments() { return List.of(); }
        @Override public void incrementRetryCount(Object id) { }
        @Override public ReconStatus phonePeStatus(String orderId) { return phonePe; }
        @Override public ReconStatus paytmStatus(String orderId) { return paytm; }
        @Override public void markTransactionFailed(Object id) { markedFailed = true; }
        @Override public void confirmPayment(String orderId, String userId, String paymentId) { confirmed = true; }
        @Override public boolean isMetaEventFired(Object id) { return alreadyFired; }
        @Override public boolean claimMetaEvent(Object id) { return claim; }
        @Override public void fireMetaPurchaseEvent(Document tx) { metaFired = true; }
    }
}
