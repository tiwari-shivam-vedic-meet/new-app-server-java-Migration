package com.vedicmeet.appserver.realtime;

import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.*;

class LiveEventReplayTimerWorkerTest {

    @Test
    void idleTimerStartsTickWithoutEmittingImmediately() {
        LiveEventReplayTimerService timers = mock(LiveEventReplayTimerService.class);
        LiveEventChatService chat = mock(LiveEventChatService.class);
        SocketEventPublisher events = mock(SocketEventPublisher.class);
        var claimed = new LiveEventReplayTimerService.Claimed("E",
                LiveEventReplayTimerService.Phase.IDLE, 0, "G", 1);
        when(timers.claimNextDue()).thenReturn(claimed, (LiveEventReplayTimerService.Claimed) null);
        when(chat.getMessages("E")).thenReturn(List.of(new Document("message", "one")));

        new LiveEventReplayTimerWorker(timers, chat, events).poll();

        verify(timers).scheduleNext(claimed, LiveEventReplayTimerService.Phase.TICK, 0, 0);
        verifyNoInteractions(events);
    }

    @Test
    void tickEmitsOneMessageAndSchedulesNextIndex() {
        LiveEventReplayTimerService timers = mock(LiveEventReplayTimerService.class);
        LiveEventChatService chat = mock(LiveEventChatService.class);
        SocketEventPublisher events = mock(SocketEventPublisher.class);
        var claimed = new LiveEventReplayTimerService.Claimed("E",
                LiveEventReplayTimerService.Phase.TICK, 0, "G", 1);
        Document first = new Document("message", "one");
        when(timers.claimNextDue()).thenReturn(claimed, (LiveEventReplayTimerService.Claimed) null);
        when(chat.getMessages("E")).thenReturn(List.of(first, new Document("message", "two")));

        new LiveEventReplayTimerWorker(timers, chat, events).poll();

        verify(events).emitToRoom("live_event", "E", SocketEvents.RECEIVE_MESSAGE, first);
        verify(timers).scheduleNext(claimed, LiveEventReplayTimerService.Phase.TICK, 1, 5);
    }

    @Test
    void processingFailureIsRetriedAndNotLost() {
        LiveEventReplayTimerService timers = mock(LiveEventReplayTimerService.class);
        LiveEventChatService chat = mock(LiveEventChatService.class);
        SocketEventPublisher events = mock(SocketEventPublisher.class);
        var claimed = new LiveEventReplayTimerService.Claimed("E",
                LiveEventReplayTimerService.Phase.TICK, 0, "G", 1);
        when(timers.claimNextDue()).thenReturn(claimed, (LiveEventReplayTimerService.Claimed) null);
        when(chat.getMessages("E")).thenThrow(new RuntimeException("redis down"));

        new LiveEventReplayTimerWorker(timers, chat, events).poll();

        verify(timers).retryOrFail(eq(claimed), any(RuntimeException.class), eq(5));
        verify(timers, never()).markCompleted(any());
    }
}
