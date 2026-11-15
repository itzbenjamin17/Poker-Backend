package com.pokergame.persistence.transaction;

import com.pokergame.config.RoomTimingProperties;
import com.pokergame.model.Room;
import com.pokergame.service.RoomCoordinator;
import com.pokergame.service.RoomService;
import com.pokergame.service.event.RoomDestroyed;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** Unit tests for {@link PostSaveActions} queueing, execution, and discard semantics. */
@Tag("unit")
class PostSaveActionsTest {

    @AfterEach
    void clearThreadLocal() {
        PostSaveActions.clear();
    }

    @Test
    void runsImmediatelyWhenNoSaveContextIsActive() {
        List<String> executed = new ArrayList<>();

        PostSaveActions.runAfterSave(() -> executed.add("immediate"));

        assertThat(PostSaveActions.currentRoomId()).isNull();
        assertThat(executed).containsExactly("immediate");
    }

    @Test
    void queuesUntilExecuteAllAndContinuesAfterActionFailure() {
        List<String> executed = new ArrayList<>();
        PostSaveActions.begin("room-1");
        assertThat(PostSaveActions.currentRoomId()).isEqualTo("room-1");

        PostSaveActions.runAfterSave(() -> executed.add("first"));
        PostSaveActions.runAfterSave(() -> {
            throw new IllegalStateException("broker down");
        });
        PostSaveActions.runAfterSave(() -> executed.add("third"));

        assertThat(executed).isEmpty();

        PostSaveActions.executeAll();

        assertThat(executed).containsExactly("first", "third");
        assertThat(PostSaveActions.currentRoomId()).isNull();
    }

    @Test
    void roomDestroyedEventPublishesOnlyAfterSaveExecutesAll() {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        RoomService rooms = roomServiceWithRoom("room-1", publisher);

        PostSaveActions.begin("room-1");
        rooms.destroyRoom("room-1");
        verifyNoInteractions(publisher);

        PostSaveActions.executeAll();

        verify(publisher).publishEvent(new RoomDestroyed("room-1"));
    }

    @Test
    void discardedSaveDropsQueuedRoomDestroyedEvent() {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        RoomService rooms = roomServiceWithRoom("room-1", publisher);

        PostSaveActions.begin("room-1");
        rooms.destroyRoom("room-1");
        PostSaveActions.clear();

        assertThat(PostSaveActions.currentRoomId()).isNull();
        verifyNoInteractions(publisher);
    }

    private static RoomService roomServiceWithRoom(String roomId, ApplicationEventPublisher publisher) {
        @SuppressWarnings("unchecked")
        ObjectProvider<RoomService> selfProvider = mock(ObjectProvider.class);
        RoomService rooms = new RoomService(
                mock(SimpMessagingTemplate.class),
                publisher,
                mock(PasswordEncoder.class),
                new RoomCoordinator(),
                Clock.systemUTC(),
                mock(TaskScheduler.class),
                new RoomTimingProperties(60_000L),
                selfProvider);
        Room room = new Room(roomId, "Lobby", "Host", 6, 5, 10, 1000, null);
        room.addPlayer("Host");
        rooms.restoreRoom(room, "Host");
        return rooms;
    }
}
