package com.vedicmeet.appserver.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import com.vedicmeet.appserver.crypto.CryptoService;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PaymentOrderServiceTest {

    @Test
    void initiate_gstInclusive_preservesNodeSnapshotMath() {
        Fixture f = fixture();
        f.seedResult(new Document("data", new Document("minimumPaymentAmount", 50)
                .append("maximumPaymentAmount", 9999).append("isGstInclusive", true)));
        f.masterResult(new Document("payment", new Document("GST", 18).append("SGST", 9).append("IGST", 18)));
        when(f.crypto.encrypt(any())).thenReturn("encrypted");

        String result = f.service().initiate(Map.of("paidAmount", "118.00", "coins", "118"), f.user);
        assertEquals("encrypted", result);

        ArgumentCaptor<Document> row = ArgumentCaptor.forClass(Document.class);
        verify(f.saved).insertOne(row.capture());
        assertEquals("100.00", row.getValue().get("rechargeAmount"));
        assertEquals("18.00", row.getValue().get("finalGst"));
        assertEquals(f.userId, row.getValue().get("userId"));
        verify(f.saved).deleteMany(new Document("userId", f.userId));
    }

    @Test
    void initiate_outsideConfiguredLimits_rejectsBeforeSnapshotWrite() {
        Fixture f = fixture();
        f.seedResult(new Document("data", new Document("minimumPaymentAmount", 50)
                .append("maximumPaymentAmount", 100)));
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> f.service().initiate(Map.of("paidAmount", 101), f.user));
        assertEquals("Maximum payment amount is 100", failure.getMessage());
        verify(f.saved, never()).insertOne(any(Document.class));
    }

    @Test
    void create_tamperedAmount_rejectsBeforeCallingGateway() {
        Fixture f = fixture();
        ObjectId initiateId = new ObjectId();
        f.savedResult(new Document("_id", initiateId).append("userId", f.userId)
                .append("paidAmount", "100.00").append("gst", "18")
                .append("sgst", "9").append("igst", "18").append("coins", "100"));
        Map<String, Object> input = paymentInput(initiateId);
        input.put("paidAmount", "99.00");

        assertThrows(IllegalArgumentException.class, () -> f.service().create(input, f.user));
        verifyNoInteractions(f.razorpay);
        verify(f.transactions, never()).insertOne(any(Document.class));
    }

    @Test
    void create_validSnapshot_persistsInitiatedAndDeletesOneTimeSnapshot() {
        Fixture f = fixture();
        ObjectId initiateId = new ObjectId();
        f.savedResult(new Document("_id", initiateId).append("userId", f.userId)
                .append("paidAmount", "100.00").append("gst", "18")
                .append("sgst", "9").append("igst", "18").append("coins", "100"));
        when(f.razorpay.createOrder(eq(100.0), eq("INR"), eq(f.userId.toHexString())))
                .thenReturn(Map.of("id", "order_TEST", "amount", 10000,
                        "currency", "INR", "created_at", 123456));
        when(f.crypto.encrypt(any())).thenReturn("encrypted");

        assertEquals("encrypted", f.service().create(paymentInput(initiateId), f.user));
        ArgumentCaptor<Document> tx = ArgumentCaptor.forClass(Document.class);
        verify(f.transactions).insertOne(tx.capture());
        assertEquals("INITIATED", tx.getValue().get("status"));
        assertEquals("order_TEST", tx.getValue().get("orderId"));
        assertEquals("100.00", tx.getValue().get("paidAmount"));
        verify(f.saved).deleteOne(new Document("_id", initiateId));
    }

    private static Map<String, Object> paymentInput(ObjectId initiateId) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("orderInitiateId", initiateId.toHexString());
        input.put("paidAmount", "100.00"); input.put("gst", "18");
        input.put("sgst", "9"); input.put("igst", "18");
        input.put("coins", "100"); input.put("currency", "INR");
        input.put("paymentMethod", "razorpay"); input.put("platform", "android");
        return input;
    }

    @SuppressWarnings("unchecked")
    private static Fixture fixture() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> seed = mock(MongoCollection.class);
        MongoCollection<Document> master = mock(MongoCollection.class);
        MongoCollection<Document> saved = mock(MongoCollection.class);
        MongoCollection<Document> transactions = mock(MongoCollection.class);
        when(mongo.getCollection(Collections.SEED_MASTERS)).thenReturn(seed);
        when(mongo.getCollection(Collections.MASTERS)).thenReturn(master);
        when(mongo.getCollection(Collections.SAVE_INITIAL_PAYMENTS)).thenReturn(saved);
        when(mongo.getCollection(Collections.TRANSACTIONS)).thenReturn(transactions);
        return new Fixture(mongo, seed, master, saved, transactions,
                mock(RazorpayOrderService.class), mock(CryptoService.class));
    }

    private static class Fixture {
        final MongoTemplate mongo;
        final MongoCollection<Document> seed;
        final MongoCollection<Document> master;
        final MongoCollection<Document> saved;
        final MongoCollection<Document> transactions;
        final RazorpayOrderService razorpay;
        final CryptoService crypto;
        final Document user;
        final ObjectId userId;

        private static FindIterable<Document> result(Document document) {
            @SuppressWarnings("unchecked") FindIterable<Document> iterable = mock(FindIterable.class);
            when(iterable.projection(any(Bson.class))).thenReturn(iterable);
            when(iterable.first()).thenReturn(document);
            return iterable;
        }
        void seedResult(Document d) {
            FindIterable<Document> iterable = result(d);
            when(seed.find(any(Bson.class))).thenReturn(iterable);
        }
        void masterResult(Document d) {
            FindIterable<Document> iterable = result(d);
            when(master.find()).thenReturn(iterable);
        }
        void savedResult(Document d) {
            FindIterable<Document> iterable = result(d);
            when(saved.find(any(Bson.class))).thenReturn(iterable);
        }
        PaymentOrderService service() {
            return new PaymentOrderService(mongo, razorpay, crypto, new ObjectMapper());
        }
        Fixture(MongoTemplate mongo, MongoCollection<Document> seed,
                        MongoCollection<Document> master, MongoCollection<Document> saved,
                        MongoCollection<Document> transactions, RazorpayOrderService razorpay,
                        CryptoService crypto) {
            this.mongo = mongo; this.seed = seed; this.master = master; this.saved = saved;
            this.transactions = transactions; this.razorpay = razorpay; this.crypto = crypto;
            this.user = new Document("_id", new ObjectId());
            this.userId = (ObjectId) this.user.get("_id");
        }
    }
}
