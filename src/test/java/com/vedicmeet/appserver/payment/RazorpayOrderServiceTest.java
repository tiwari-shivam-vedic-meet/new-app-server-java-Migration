package com.vedicmeet.appserver.payment;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Razorpay order-build contract for {@link RazorpayOrderService} (Node razorpay.js createOrder).
 * A fake {@link RazorpayGateway} captures the request so amount→paise, currency default, and receipt
 * are pinned without the SDK, credentials, or a network call. Shadow-only / money gate.
 */
class RazorpayOrderServiceTest {

    @Test
    void createOrder_convertsRupeesToPaise_defaultsCurrency_setsReceipt() {
        FakeGateway gw = new FakeGateway();
        RazorpayOrderService svc = new RazorpayOrderService(gw);

        Map<String, Object> order = svc.createOrder(199, null, "user-1");

        assertEquals(19900, gw.amountPaise, "amount ×100 → paise");
        assertEquals("INR", gw.currency, "default currency");
        assertEquals("user-1", gw.receipt, "receipt = userId");
        assertEquals("order_TEST123", order.get("id"));
    }

    @Test
    void createOrder_honoursExplicitCurrency() {
        FakeGateway gw = new FakeGateway();
        new RazorpayOrderService(gw).createOrder(50, "USD", "u2");
        assertEquals("USD", gw.currency);
        assertEquals(5000, gw.amountPaise);
    }

    @Test
    void toPaise_truncatesLikeParseInt() {
        assertEquals(19900, RazorpayOrderService.toPaise(199));
        assertEquals(19999, RazorpayOrderService.toPaise(199.99));
        assertEquals(0, RazorpayOrderService.toPaise(0));
    }

    static class FakeGateway implements RazorpayGateway {
        int amountPaise;
        String currency;
        String receipt;

        @Override public Map<String, Object> createOrder(int amountPaise, String currency, String receipt) {
            this.amountPaise = amountPaise;
            this.currency = currency;
            this.receipt = receipt;
            Map<String, Object> order = new LinkedHashMap<>();
            order.put("id", "order_TEST123");
            order.put("amount", amountPaise);
            order.put("currency", currency);
            order.put("receipt", receipt);
            order.put("status", "created");
            return order;
        }
    }
}
