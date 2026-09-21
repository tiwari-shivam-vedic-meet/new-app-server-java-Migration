package com.vedicmeet.appserver.payment;

import org.bson.Document;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Date;

/**
 * ⚠ SHADOW-ONLY / MONEY. Faithful port of Node {@code TransactionService.purchaseMembership}
 * (utils/classes/transaction.js L736): validate the plan, check the user has enough `coins`, then
 * (for SILVER) credit the consultant and debit the user via {@link WalletService}, and bump the
 * saved-amount. NOT wired to any route.
 *
 * FAITHFUL — per the team decision, Node's logic is reproduced EXACTLY, including the quirk that the
 * coins balance + savedAmount are read via {@code waitlistModel.findOne({ userId })} (the waitlist
 * collection, keyed on `user_id`, so it typically returns null → coins 0). No fix-forward: the
 * behaviour is preserved as built. The coins cipher (when present) is decrypted the same way as the
 * rest of the app ({@link AesWalletService#readCoins}).
 */
@Service
public class MembershipPurchaseService {

    private final MembershipStore membershipStore;
    private final AesWalletService aesWallet;
    private final WalletService wallet;

    public MembershipPurchaseService(MembershipStore membershipStore, AesWalletService aesWallet,
                                     WalletService wallet) {
        this.membershipStore = membershipStore;
        this.aesWallet = aesWallet;
        this.wallet = wallet;
    }

    @Transactional(transactionManager = "mongoTransactionManager")
    public void purchaseMembership(Document input, Document user) {
        String membershipId = input.getString("memberhsipId"); // Node spelling
        double coins = num(input.get("coins"));
        Object consultantId = input.get("consultantId");

        Document membership = membershipStore.findActiveMembership(membershipId);
        if (membership == null) throw new RuntimeException("Membership is not exist, please contact to admin");

        if (num(membership.get("membershipDiscountPrice")) != coins) {
            throw new RuntimeException("Plan amount is not matched");
        }

        String planType = membership.getString("planType");
        if ("SILVER".equals(planType) && consultantId == null) {
            throw new RuntimeException("For silver membership, choose consultant first");
        }

        boolean isMembership = Boolean.TRUE.equals(user.getBoolean("isMembership"));
        String userPlan = user.getString("planType");
        if (isMembership && planType != null && planType.equals(userPlan)) {
            throw new RuntimeException("You have already purchased this membership");
        }
        if (isMembership && "GOLD".equals(userPlan)) {
            throw new RuntimeException("You have already membership");
        }

        // Balance check — Node reads coins via waitlistModel.findOne({userId}).coins (L765-766),
        // decrypting when present. Reproduced exactly (no fix-forward).
        double remainingCoins = aesWallet.readCoins(membershipStore.findWalletCoinsCipher(user.get("_id")));
        if (remainingCoins < coins) throw new RuntimeException("Recharge your wallet please");

        Date start = utcMidnightToday();
        int duration = membership.get("membershipDuration") == null ? 1 : (int) num(membership.get("membershipDuration"));
        Date end = utcMidnightPlusMonths(duration);

        input.put("transactionFor", "membership");
        input.put("userId", user.get("_id"));
        input.put("planType", planType);
        input.put("startDate", start);
        input.put("endDate", end);
        input.put("planDuration", duration);
        input.put("discountPercentage", membership.get("discountPercentage") == null ? 0 : membership.get("discountPercentage"));

        String membershipPlanType = "";
        if ("SILVER".equals(planType)) {
            wallet.creditAndDebitOnWallet("cons", "credit", input);
            membershipPlanType = "SILVER";
        }
        wallet.creditAndDebitOnWallet("user", "debit", input, membershipPlanType);

        double totalSave = num(membership.get("membershipPrice")) - num(membership.get("membershipDiscountPrice"));
        if (totalSave > 0) {
            double saved = membershipStore.getSavedAmount(user.get("_id"));
            membershipStore.setSavedAmount(user.get("_id"), saved + totalSave);
        }

        membershipStore.sendMembershipNotification(user, membershipPlanType);
    }

    private Date utcMidnightToday() {
        return Date.from(LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant());
    }

    private Date utcMidnightPlusMonths(int months) {
        return Date.from(LocalDate.now(ZoneOffset.UTC).plusMonths(months).atStartOfDay(ZoneOffset.UTC).toInstant());
    }

    private double num(Object v) {
        if (v instanceof Number) return ((Number) v).doubleValue();
        try { return v == null ? 0 : Double.parseDouble(String.valueOf(v)); } catch (Exception e) { return 0; }
    }
}
