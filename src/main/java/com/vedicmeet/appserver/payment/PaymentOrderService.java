package com.vedicmeet.appserver.payment;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import com.vedicmeet.appserver.crypto.CryptoService;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Ports Node TransactionService.orderInitiate/makePayment without enabling the money routes. */
@Service
public class PaymentOrderService {
    private final MongoTemplate mongo;
    private final RazorpayOrderService razorpay;
    private final CryptoService crypto;
    private final ObjectMapper mapper;

    public PaymentOrderService(MongoTemplate mongo, RazorpayOrderService razorpay,
                               CryptoService crypto, ObjectMapper mapper) {
        this.mongo = mongo; this.razorpay = razorpay; this.crypto = crypto; this.mapper = mapper;
    }

    @Transactional(transactionManager = "mongoTransactionManager")
    public String initiate(Map<String, Object> request, Document user) {
        Map<String, Object> input = new LinkedHashMap<>(request);
        double paid = requiredNumber(input.get("paidAmount"), "paidAmount");
        Document settings = mongo.getCollection(Collections.SEED_MASTERS)
                .find(new Document("for", "userMasterSettings"))
                .projection(new Document("data", 1)).first();
        Document data = nested(settings, "data");
        double minimum = number(data == null ? null : data.get("minimumPaymentAmount"), 50);
        double maximum = number(data == null ? null : data.get("maximumPaymentAmount"), 9999);
        if (paid < minimum) throw new IllegalArgumentException("Minimum payment amount is " + plain(minimum));
        if (paid > maximum) throw new IllegalArgumentException("Maximum payment amount is " + plain(maximum));

        Document master = mongo.getCollection(Collections.MASTERS).find()
                .projection(new Document("payment", 1).append("_id", 0)).first();
        Document payment = nested(master, "payment");
        double gstRate = number(payment == null ? null : payment.get("GST"), 18);
        ObjectId userId = id(user.get("_id"), "userId");
        mongo.getCollection(Collections.SAVE_INITIAL_PAYMENTS)
                .deleteMany(new Document("userId", userId));

        boolean gstInclusive = data == null || !Boolean.FALSE.equals(data.getBoolean("isGstInclusive"));
        double recharge = gstInclusive ? paid / (1 + gstRate / 100.0) : paid;
        double finalGst = gstInclusive ? paid - recharge : paid * gstRate / 100.0;
        Document row = new Document(input);
        row.put("_id", new ObjectId()); row.put("userId", userId);
        row.put("gst", payment == null ? null : payment.get("GST"));
        row.put("sgst", payment == null ? null : payment.get("SGST"));
        row.put("igst", payment == null ? null : payment.get("IGST"));
        row.put("rechargeAmount", money(recharge));
        row.put("coins", input.get("coins") == null ? input.get("paidAmount") : input.get("coins"));
        row.put("finalGst", money(finalGst));
        if (!gstInclusive) row.put("paidAmount", money(paid + finalGst));
        if (row.get("couponId") != null) row.put("couponId", id(row.get("couponId"), "couponId"));
        Date now = new Date(); row.put("createdAt", now); row.put("updatedAt", now);
        mongo.getCollection(Collections.SAVE_INITIAL_PAYMENTS).insertOne(row);
        return crypto.encrypt(row);
    }

    public String create(Map<String, Object> envelope, Document user) {
        Map<String, Object> input = decryptIfNeeded(envelope);
        ObjectId initiateId = id(input.get("orderInitiateId"), "orderInitiateId");
        ObjectId userId = id(user.get("_id"), "userId");
        Document initial = mongo.getCollection(Collections.SAVE_INITIAL_PAYMENTS)
                .find(new Document("_id", initiateId).append("userId", userId)).first();
        if (initial == null || !sameNumber(initial.get("paidAmount"), input.get("paidAmount"))
                || !sameNumber(initial.get("gst"), input.get("gst"))
                || !sameNumber(initial.get("sgst"), input.get("sgst"))
                || !sameNumber(initial.get("igst"), input.get("igst"))
                || number(initial.get("coins"), 0) > number(input.get("coins"), 0)) {
            throw new IllegalArgumentException("Current data is not matched with payment initiate data");
        }
        double paidAmount = requiredNumber(input.get("paidAmount"), "paidAmount");
        String currency = requiredText(input, "currency");
        // The create route calls payment-helper.js, which does parseInt(paidAmount) before ×100.
        Map<String, Object> gateway = razorpay.createOrder(Math.floor(paidAmount), currency, userId.toHexString());
        String orderId = requiredGatewayText(gateway, "id");
        Document tx = new Document("_id", new ObjectId())
                .append("paidAmount", money(number(gateway.get("amount"), 0) / 100.0))
                .append("baseAmount", input.get("baseAmount"))
                .append("currency", gateway.get("currency"))
                .append("paymentMethod", firstNonBlank(text(input, "paymentMethod"), "razorpay").toLowerCase(Locale.ENGLISH))
                .append("orderId", orderId).append("userId", userId)
                .append("orderCreatedDate", gateway.get("created_at"))
                .append("status", "INITIATED")
                .append("platform", firstNonBlank(text(input, "platform"), "android"))
                .append("coins", input.get("coins"));
        if (input.get("couponCode") != null) tx.put("couponCode", input.get("couponCode"));
        if (input.get("couponId") != null) tx.put("couponId", id(input.get("couponId"), "couponId"));
        List<Document> extra = extraCoins(input.get("extraCoins"));
        if (!extra.isEmpty()) tx.put("extraCoins", extra);
        Date now = new Date(); tx.put("createdAt", now); tx.put("updatedAt", now);
        mongo.getCollection(Collections.TRANSACTIONS).insertOne(tx);
        mongo.getCollection(Collections.SAVE_INITIAL_PAYMENTS).deleteOne(new Document("_id", initiateId));
        return crypto.encrypt(tx);
    }

    public void assertOrderOwner(String orderId, Object actorId) {
        Document tx = mongo.getCollection(Collections.TRANSACTIONS)
                .find(new Document("orderId", orderId).append("userId", actorId)).first();
        if (tx == null) throw new IllegalArgumentException("Order is not present or this order id and user");
    }

    public Map<String, Object> decryptIfNeeded(Map<String, Object> body) {
        if (body != null && body.get("reqData") instanceof String encrypted)
            return crypto.decryptToMap(encrypted);
        return body == null ? new LinkedHashMap<>() : new LinkedHashMap<>(body);
    }

    private List<Document> extraCoins(Object value) {
        Object decoded = value;
        if (value instanceof String text && !text.isBlank()) {
            try { decoded = mapper.readValue(text, new TypeReference<List<Map<String, Object>>>() {}); }
            catch (Exception ignored) { return List.of(); }
        }
        if (!(decoded instanceof Iterable<?> values)) return List.of();
        List<Document> result = new ArrayList<>();
        for (Object item : values) {
            if (!(item instanceof Map<?, ?> map)) continue;
            result.add(new Document("name", map.get("name") == null ? "" : String.valueOf(map.get("name")))
                    .append("value", number(map.get("value"), 0)));
        }
        return result;
    }

    private Document nested(Document parent, String key) {
        if (parent == null) return null;
        Object value = parent.get(key);
        if (value instanceof Document d) return d;
        if (value instanceof Map<?, ?> map) return new Document((Map<String, Object>) map);
        return null;
    }
    private boolean sameNumber(Object left, Object right) {
        try { return Math.abs(Double.parseDouble(String.valueOf(left))
                - Double.parseDouble(String.valueOf(right))) < 0.000001; }
        catch (Exception invalid) { return left == null && right == null; }
    }
    private double requiredNumber(Object value, String field) {
        try { return Double.parseDouble(String.valueOf(value)); }
        catch (Exception invalid) { throw new IllegalArgumentException(field + " is required"); }
    }
    private double number(Object value, double fallback) {
        try { return value == null ? fallback : Double.parseDouble(String.valueOf(value)); }
        catch (Exception ignored) { return fallback; }
    }
    private ObjectId id(Object value, String field) {
        if (value instanceof ObjectId objectId) return objectId;
        if (value != null && ObjectId.isValid(String.valueOf(value))) return new ObjectId(String.valueOf(value));
        throw new IllegalArgumentException(field + " is required");
    }
    private String requiredText(Map<String, Object> input, String field) {
        String value = text(input, field);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value;
    }
    private String requiredGatewayText(Map<String, Object> input, String field) {
        String value = text(input, field);
        if (value == null || value.isBlank()) throw new IllegalStateException("RAZORPAY_ORDER_CREATE_FAILED");
        return value;
    }
    private String text(Map<String, Object> input, String field) {
        Object value = input == null ? null : input.get(field); return value == null ? null : String.valueOf(value);
    }
    private String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value; return null;
    }
    private String money(double value) { return String.format(Locale.ROOT, "%.2f", value); }
    private String plain(double value) { return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value); }
}
