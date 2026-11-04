package com.pokergame.persistence.transaction;

import com.pokergame.service.RoomService;
import com.pokergame.service.event.RoomDestroyed;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** Room lifecycle events follow the durable transaction's accept-or-discard outcome. */
@Tag("unit")
class RoomEventsAfterCommitTest {
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final RoomService rooms = new RoomService(mock(SimpMessagingTemplate.class), publisher, 60_000L);

    @Test
    void eventIsPublishedOnlyAfterAcceptedMutationCompletes() {
        DurableTransactionContext.begin("room-1");
        try {
            rooms.destroyRoom("room-1");
            verifyNoInteractions(publisher);
        } finally {
            DurableTransactionContext.complete();
        }

        verify(publisher).publishEvent(new RoomDestroyed("room-1"));
    }

    @Test
    void discardedMutationPublishesNothing() {
        DurableTransactionContext.begin("room-1");
        rooms.destroyRoom("room-1");
        DurableTransactionContext.discard();

        verifyNoInteractions(publisher);
    }
}
