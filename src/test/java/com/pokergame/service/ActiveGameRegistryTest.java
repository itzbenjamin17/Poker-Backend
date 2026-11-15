package com.pokergame.service;

import com.pokergame.util.RoomCoordinator;

import com.pokergame.model.Game;
import com.pokergame.model.Player;
import com.pokergame.util.LockLease;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
@DisplayName("Active game registry")
@ExtendWith(MockitoExtension.class)
class ActiveGameRegistryTest {

    @Mock
    private HandEvaluatorService handEvaluator;

    private RoomCoordinator roomCoordinator;
    private ActiveGameRegistry registry;

    @BeforeEach
    void setUp() {
        roomCoordinator = new RoomCoordinator();
        registry = new ActiveGameRegistry(roomCoordinator);
    }

    private Game createGame(String gameId) {
        return new Game(gameId, List.of(
                new Player("Alice", "p1", 1000),
                new Player("Bob", "p2", 1000)), 6, 5, 10, handEvaluator);
    }

    @Test
    @DisplayName("uncoordinated access throws IllegalStateException across all operations")
    void uncoordinatedAccessThrowsIllegalStateException() {
        Game game = createGame("room-1");

        assertThatThrownBy(() -> registry.get("room-1")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> registry.contains("room-1")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> registry.register(game)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> registry.replace(game)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> registry.remove("room-1")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("register keeps existing game while replace overwrites and remove clears it")
    void registerReplaceAndRemoveRespectPresenceUnderLock() {
        Game first = createGame("room-1");
        Game second = createGame("room-1");

        try (LockLease _ = roomCoordinator.acquire("room-1")) {
            assertThat(registry.contains("room-1")).isFalse();
            assertThat(registry.get("room-1")).isNull();

            registry.register(first);
            assertThat(registry.contains("room-1")).isTrue();
            assertThat(registry.get("room-1")).isSameAs(first);

            registry.register(second);
            assertThat(registry.get("room-1")).isSameAs(first);

            registry.replace(second);
            assertThat(registry.get("room-1")).isSameAs(second);

            registry.remove("room-1");
            assertThat(registry.contains("room-1")).isFalse();
            assertThat(registry.get("room-1")).isNull();
        }
    }

    @Test
    @DisplayName("null games and cross-room lock access fail immediately")
    void rejectsNullGamesAndCrossRoomLockAccess() {
        Game gameB = createGame("room-B");

        try (LockLease _ = roomCoordinator.acquire("room-A")) {
            assertThatThrownBy(() -> registry.register(null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> registry.replace(null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> registry.get("room-B")).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> registry.register(gameB)).isInstanceOf(IllegalStateException.class);
        }
    }
}
