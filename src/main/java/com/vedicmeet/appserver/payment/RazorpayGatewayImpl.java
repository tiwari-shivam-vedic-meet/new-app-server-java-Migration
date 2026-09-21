package com.vedicmeet.appserver.payment;

import com.razorpay.Order;
import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SDK-backed {@link RazorpayGateway} using the official {@code com.razorpay:razorpay-java} client
 * (Node uses the {@code razorpay} npm client the same way). The client is constructed lazily per call
 * so the app boots without credentials; real order creation needs RAZORPAY_LIVE_KEY / _SECRET at
 * runtime (env config — never hardcoded). SHADOW-ONLY (only reached via {@link RazorpayOrderService}).
 */
@Repository
public class RazorpayGatewayImpl implements RazorpayGateway {

    private final String keyId;
    private final String keySecret;

    public RazorpayGatewayImpl(@Value("${RAZORPAY_LIVE_KEY:}") String keyId,
                               @Value("${RAZORPAY_LIVE_SECRET:}") String keySecret) {
        this.keyId = keyId;
        this.keySecret = keySecret;
    }

    @Override
    public Map<String, Object> createOrder(int amountPaise, String currency, String receipt) {
        try {
            RazorpayClient client = new RazorpayClient(keyId, keySecret);
            JSONObject request = new JSONObject();
            request.put("amount", amountPaise);
            request.put("currency", currency);
            if (receipt != null) request.put("receipt", receipt);

            Order order = client.orders.create(request);
            JSONObject json = order.toJson();
            Map<String, Object> result = new LinkedHashMap<>();
            for (String key : json.keySet()) {
                result.put(key, json.get(key));
            }
            return result;
        } catch (RazorpayException e) {
            throw new RuntimeException("RAZORPAY_ORDER_CREATE_FAILED", e);
        }
    }
}
