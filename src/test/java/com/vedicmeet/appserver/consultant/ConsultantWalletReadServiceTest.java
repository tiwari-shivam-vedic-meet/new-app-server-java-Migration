package com.vedicmeet.appserver.consultant;

import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConsultantWalletReadServiceTest {

    @Test
    void classificationKeepsNodeEarningBucketsAndExcludesPayoutDebit() {
        Date day = Date.from(LocalDate.of(2026, 9, 8).atStartOfDay().toInstant(ZoneOffset.UTC));
        List<Document> input = List.of(
                tx(day, 0, "100.50", "consult", "chat", "A"),
                tx(day, 0, 40, "consult", "audio", "B"),
                tx(day, 0, 20, "session_book", "", "C"),
                tx(day, 0, 5, "wallet_refund", "", null),
                tx(day, 1, 10, "manual_adjustment", "", null),
                tx(day, 1, 999, "payout", "", null));

        ConsultantWalletReadService.LedgerBuckets result = ConsultantWalletReadService.classify(
                input, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1));

        assertEquals(100.5, result.chatEarning);
        assertEquals(40, result.callEarning);
        assertEquals(20, result.fixedEarning);
        assertEquals(5, result.walletRefund);
        assertEquals(10, result.walletDeduct);
        assertEquals(3, result.orderIds.size());
    }

    @Test
    void periodAliasesWeeklyToNodeWeekAndUnknownToToday() {
        ConsultantWalletReadService.Period week = ConsultantWalletReadService.period("weekly");
        ConsultantWalletReadService.Period unknown = ConsultantWalletReadService.period("anything");

        assertEquals("week", week.name());
        assertEquals(7L * 86_400_000L, week.end().getTime() - week.start().getTime());
        assertEquals("today", unknown.name());
        assertEquals(86_400_000L, unknown.end().getTime() - unknown.start().getTime());
    }

    private Document tx(Date date, int type, Object coins, String forWhat, String mode, String orderId) {
        Document meta = new Document("mode", mode);
        if (orderId != null) meta.put("orderId", orderId);
        return new Document("createdAt", date).append("transactionType", type).append("coins", coins)
                .append("transactionFor", forWhat).append("meta", meta);
    }
}
