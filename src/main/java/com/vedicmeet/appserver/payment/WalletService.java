package com.vedicmeet.appserver.payment;

import org.bson.Document;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;

/**
 * ⚠⚠ HIGH-RISK / SHADOW-ONLY — HUMAN REVIEW REQUIRED BEFORE ENABLING ⚠⚠
 *
 * Faithful port of Node {@code TransactionService.creditAndDebitOnWallet} + the pure
 * {@code getAdminCommissionAmount} (utils/classes/transaction.js L160 / L118). Operates on the PLAIN
 * numeric {@code wallet} field (user or consultant), writes a ledger row, and (for user credits)
 * fires the {@code extendOngoingSessionTime} seam. NOT wired to any route.
 *
 * Node quirks preserved deliberately:
 *   - the consult low-balance guard reproduces JS operator-precedence/coercion:
 *     {@code if ((remaining == 0 || (remaining * 5)) < coins)} → when remaining==0 the left side is
 *     boolean true → coerced to 1, so the test becomes {@code 1 < coins}; otherwise {@code remaining*5 < coins}.
 *   - adminCommision defaults to 50 (the method-signature default), NOT the master-config 40 that
 *     {@code paymentFromWallet} passes in.
 *   - gift credit re-assigns {@code coins} to the platform-share before building the ledger row.
 */
@Service
public class WalletService {

    private final WalletStore store;

    public WalletService(WalletStore store) {
        this.store = store;
    }

    @Transactional(transactionManager = "mongoTransactionManager")
    public void creditAndDebitOnWallet(String userType, String requestType, Document input) {
        creditAndDebitOnWallet(userType, requestType, input, "DEFAULT");
    }

    @Transactional(transactionManager = "mongoTransactionManager")
    public void creditAndDebitOnWallet(String userType, String requestType, Document input, String membershipType) {
        boolean isCons = "cons".equals(userType);
        Object userId = input.get("userId");
        Object consultantId = input.get("consultantId");
        double coins = num(input.get("coins"));
        double extraCoins = input.get("extraCoins") == null ? 0 : num(input.get("extraCoins"));
        String transactionFor = input.getString("transactionFor");
        double adminCommision = input.get("adminCommision") == null ? 50 : num(input.get("adminCommision"));
        Object invoiceURI = input.get("invoiceURI");

        Document walletDoc = isCons ? store.findConsultantWallet(consultantId) : store.findUserWallet(userId);
        double remainingCoins = walletDoc == null ? 0 : num(walletDoc.get("wallet"));

        // Low-balance guard — only for non-consultant flows that carry a transactionFor.
        if (transactionFor != null && !isCons) {
            if ("consult".equals(transactionFor) && "user".equals(userType)) {
                // Node: (remaining == 0 || (remaining * 5)) < coins  (see class note).
                double lhs = (remainingCoins == 0) ? 1 : (remainingCoins * 5);
                if (lhs < coins) throw new RuntimeException("Recharge your wallet please");
            } else {
                if (remainingCoins == 0 || remainingCoins < coins) {
                    throw new RuntimeException("Recharge your wallet please");
                }
            }
        }

        boolean isGift = "gift".equals(input.getString("transactionFor"));

        if ("credit".equals(requestType)) {
            double walletDelta = isCons ? ((coins * adminCommision) / 100) : (coins + extraCoins);

            if (isGift) {
                Double platformShare = platformShare(walletDoc);
                if (platformShare != null) {
                    coins = (coins * platformShare) / 100;
                    walletDelta = coins;
                } else {
                    walletDelta = coins;
                }
            }

            double consultantCharge = isCons ? ((coins * adminCommision) / 100) : 0;

            if (isCons) {
                if (!store.incrementConsultantWallet(consultantId, walletDelta)) {
                    throw new IllegalStateException("CONSULTANT_WALLET_NOT_FOUND");
                }
            } else {
                if (!store.incrementUserWallet(userId, walletDelta)) {
                    throw new IllegalStateException("PAYMENT_USER_NOT_FOUND");
                }
                store.extendOngoingSessionTime(userId, coins);
            }

            double ledgerCoins = isCons ? (isGift ? coins : consultantCharge) : (coins + extraCoins);
            store.insertLedger(baseLedger(input, userId, consultantId, userType, invoiceURI)
                    .append("coins", ledgerCoins)
                    .append("totalAmountPayToPlateform", 0)
                    .append("transactionType", 0));
        } else if ("debit".equals(requestType)) {
            if (isCons) {
                if (!store.incrementConsultantWallet(consultantId, -coins)) {
                    throw new IllegalStateException("CONSULTANT_WALLET_NOT_FOUND");
                }
            } else {
                if (!store.debitUserWallet(userId, coins, transactionFor)) {
                    throw new RuntimeException("Recharge your wallet please");
                }
            }

            double totalAmountPayToPlateform = getAdminCommissionAmount(transactionFor, coins, adminCommision, membershipType);
            double consultantCharge = isCons ? ((coins * adminCommision) / 100) : 0;

            store.insertLedger(baseLedger(input, userId, consultantId, userType, null)
                    .append("coins", isCons ? consultantCharge : coins)
                    .append("totalAmountPayToPlateform", isGift ? 0 : totalAmountPayToPlateform)
                    .append("transactionType", 1));
        }
    }

    /** Node getAdminCommissionAmount (transaction.js L118) — pure. */
    public double getAdminCommissionAmount(String requestType, double coins, double adminCommision, String membershipType) {
        if (requestType == null) return 0;
        switch (requestType) {
            case "gift":
            case "consult":
                return coins - (coins * adminCommision / 100);
            case "membership":
                return "SILVER".equals(membershipType) ? coins - (coins * adminCommision / 100) : coins;
            case "meditation":
            case "vastu":
                return coins;
            default:
                return 0;
        }
    }

    private Document baseLedger(Document input, Object userId, Object consultantId, String userType, Object invoiceURI) {
        Document d = new Document("userId", userId)
                .append("consultantId", consultantId)
                .append("transactionId", input.get("transactionId"))
                .append("walletDeductReason", input.get("walletDeductReason") == null ? "" : input.get("walletDeductReason"))
                .append("transactionFor", input.get("transactionFor") == null ? "topup" : input.get("transactionFor"))
                .append("userType", userType)
                .append("startDate", input.get("startDate") == null ? new Date() : input.get("startDate"))
                .append("endDate", input.get("endDate") == null ? new Date() : input.get("endDate"))
                .append("planDuration", input.get("planDuration") == null ? 1 : input.get("planDuration"))
                .append("discountPercentage", input.get("discountPercentage") == null ? 0 : input.get("discountPercentage"))
                .append("isConsTransfer", input.get("isConsTransfer") == null ? false : input.get("isConsTransfer"))
                .append("meta", input.get("meta"));
        if (invoiceURI != null || input.containsKey("invoiceURI")) d.append("invoiceURI", invoiceURI);
        if ("membership".equals(input.getString("transactionFor"))) {
            d.append("planType", input.get("planType") == null ? "OTHERS" : input.get("planType"));
        }
        return d;
    }

    private Double platformShare(Document consultantWalletDoc) {
        if (consultantWalletDoc != null && consultantWalletDoc.get("price") instanceof Document) {
            Object ps = ((Document) consultantWalletDoc.get("price")).get("platformShare");
            if (ps instanceof Number) return ((Number) ps).doubleValue();
        }
        return null;
    }

    private double num(Object v) {
        if (v instanceof Number) return ((Number) v).doubleValue();
        try { return v == null ? 0 : Double.parseDouble(String.valueOf(v)); } catch (Exception e) { return 0; }
    }
}
