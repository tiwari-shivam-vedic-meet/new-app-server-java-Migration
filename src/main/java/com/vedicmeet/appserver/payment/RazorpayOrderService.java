package com.vedicmeet.appserver.payment;

import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * ⚠ SHADOW-ONLY / MONEY. Faithful port of Node {@code RazorpayService.createOrder}
 * (utils/classes/razorpay.js L16): amount → paise (×100, integer-truncated), currency default INR,
 * receipt = userId. Delegates the actual order creation to {@link RazorpayGateway}. NOT wired to a route.
 */
@Service
public class RazorpayOrderService {

    private final RazorpayGateway gateway;

    public RazorpayOrderService(RazorpayGateway gateway) {
        this.gateway = gateway;
    }

    /** Node: amount = parseInt(input.amount * 100); currency || 'INR'; receipt = userId. */
    public Map<String, Object> createOrder(double amount, String currency, String userId) {
        int amountPaise = toPaise(amount);
        String cur = (currency == null || currency.isEmpty()) ? "INR" : currency;
        return gateway.createOrder(amountPaise, cur, userId);
    }

    /** parseInt(amount * 100) — integer paise (truncated), matching Node. */
    public static int toPaise(double amount) {
        return (int) (amount * 100);
    }
}
