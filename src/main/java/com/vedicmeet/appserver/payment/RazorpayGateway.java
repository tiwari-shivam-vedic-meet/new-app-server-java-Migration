package com.vedicmeet.appserver.payment;

import java.util.Map;

/**
 * Port for creating a Razorpay order (Node utils/classes/razorpay.js {@code createOrder}). Behind an
 * interface so {@link RazorpayOrderService}'s amount/currency/receipt logic is unit-testable without
 * the SDK, real credentials, or a network call; {@link RazorpayGatewayImpl} is the SDK-backed impl.
 */
public interface RazorpayGateway {

    /**
     * Creates an order for {@code amountPaise} (already ×100), currency, receipt.
     * @return the order fields (at least {@code id}, {@code amount}, {@code currency}, {@code receipt}, {@code status}).
     */
    Map<String, Object> createOrder(int amountPaise, String currency, String receipt);
}
