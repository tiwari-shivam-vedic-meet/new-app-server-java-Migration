package com.vedicmeet.appserver.payment;

import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.vedicmeet.appserver.call.TimerWriteService;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OngoingSessionExtensionServiceTest {

    @Test
    void convertsRechargeCoinsToSeconds() {
        assertEquals(300, OngoingSessionExtensionService.calculateAddedSeconds(100, 20));
        assertEquals(0, OngoingSessionExtensionService.calculateAddedSeconds(100, 0));
    }

    @Test
    @SuppressWarnings("unchecked")
    void noProgressingWaitlistIsANoop() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        TimerWriteService timers = mock(TimerWriteService.class);
        MongoCollection<Document> waitlists = mock(MongoCollection.class);
        FindIterable<Document> find = mock(FindIterable.class);
        when(mongo.getCollection(Collections.WAITLISTS)).thenReturn(waitlists);
        when(waitlists.find(any(Document.class))).thenReturn(find);
        when(find.projection(any(Document.class))).thenReturn(find);
        when(find.first()).thenReturn(null);

        long result = new OngoingSessionExtensionService(mongo, timers).extend(new ObjectId(), 100);

        assertEquals(0, result);
        verify(timers, never()).extendTimer(any(), any(Long.class));
    }
}
