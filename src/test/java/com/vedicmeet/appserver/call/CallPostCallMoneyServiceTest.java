package com.vedicmeet.appserver.call;

import org.bson.Document;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CallPostCallMoneyServiceTest {

    @Test
    void limitlessSession_returnsFiveCoins() {
        assertEquals(5, CallPostCallMoneyService.reward(
                payload("CHAT_AUDIO_LIMITLESS", 1, null)));
    }

    @Test
    void ninetyNineInfiniteSession_returnsTenCoins() {
        assertEquals(10, CallPostCallMoneyService.reward(
                payload("5COINSPERMIN_99AUDIOENDLESS", 30, "99FORINFINITE")));
    }

    @Test
    void perMinuteSpecialAndNormal_floorCompletedMinutes() {
        assertEquals(2, CallPostCallMoneyService.reward(
                payload("5COINSPERMIN_99AUDIOENDLESS", 179, "OTHER")));
        assertEquals(2, CallPostCallMoneyService.reward(payload("NORMAL", 179, null)));
    }

    @Test
    void unknownSessionType_hasNoReward() {
        assertEquals(0, CallPostCallMoneyService.reward(payload("FIXED", 600, null)));
    }

    private Document payload(String type, double seconds, String bookType) {
        return new Document("sessionType", type).append("callDurationSeconds", seconds)
                .append("bookType", bookType);
    }
}
