package com.pokergame.wal;

import com.pokergame.dto.request.CreateRoomRequest;
import com.pokergame.dto.request.JoinRoomRequest;
import com.pokergame.dto.request.PlayerActionRequest;
import com.pokergame.enums.PlayerAction;
import com.pokergame.model.Game;
import com.pokergame.model.Room;
import com.pokergame.service.GameLifecycleService;
import com.pokergame.service.PlayerActionService;
import com.pokergame.service.RoomService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
public class WalLifecycleIntegrationTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void walProperties(DynamicPropertyRegistry registry) {
        registry.add("poker.wal.dir", () -> tempDir.toString());
    }

    @Autowired
    private RoomService roomService;

    @Autowired
    private GameLifecycleService gameLifecycleService;

    @Autowired
    private PlayerActionService playerActionService;

    @Autowired
    private WalReplayService walReplayService;

    @BeforeEach
    void setUp() {
        clearState();
    }

    private void clearState() {
        ((Map<?, ?>) ReflectionTestUtils.getField(roomService, "rooms")).clear();
        ((Map<?, ?>) ReflectionTestUtils.getField(roomService, "roomHosts")).clear();
        ((Map<?, ?>) ReflectionTestUtils.getField(gameLifecycleService, "activeGames")).clear();
        Map<?, ?> timeouts = (Map<?, ?>) ReflectionTestUtils.getField(gameLifecycleService, "readyCountdownTimeouts");
        if (timeouts != null) {
            timeouts.clear();
        }
    }

    @Test
    void testFullLifecycleReplay() {
        // 1. Create a room
        CreateRoomRequest createReq = new CreateRoomRequest("IntegrationRoom", "HostPlayer", 4, 10, 20, 1000, null);
        String roomId = roomService.createRoom(createReq);

        // 2. Join a player
        JoinRoomRequest joinReq = new JoinRoomRequest("IntegrationRoom", "Player2", null);
        roomService.joinRoom(joinReq);

        // 3. Start a game
        gameLifecycleService.createGameFromRoom(roomId);

        // Get initial state to verify later
        Game gameBeforeReplay = gameLifecycleService.getGame(roomId);
        assertThat(gameBeforeReplay).isNotNull();
        String currentTurnBeforeReplay = gameBeforeReplay.getCurrentPlayer().getName();
        int initialPot = gameBeforeReplay.getPot();

        // 4. Perform an action
        // We will just fold
        PlayerActionRequest foldReq = new PlayerActionRequest(PlayerAction.FOLD, null);
        playerActionService.processPlayerAction(roomId, foldReq, currentTurnBeforeReplay);

        // Get game state after fold
        Game gameAfterFold = gameLifecycleService.getGame(roomId);
        assertThat(gameAfterFold).isNotNull();
        int activePlayersCount = gameAfterFold.getActivePlayers().size();
        String nextCurrentTurn = gameAfterFold.getCurrentPlayer().getName();

        // 5. Clear state
        clearState();
        assertThat(roomService.getRoom(roomId)).isNull();
        assertThat(gameLifecycleService.getGame(roomId)).isNull();

        // 6. Replay
        walReplayService.replayAll();

        // 7. Assertions
        Room replayedRoom = roomService.getRoom(roomId);
        assertThat(replayedRoom).isNotNull();
        assertThat(replayedRoom.getRoomName()).isEqualTo("IntegrationRoom");
        assertThat(roomService.isRoomHost(roomId, "HostPlayer")).isTrue();

        Game replayedGame = gameLifecycleService.getGame(roomId);
        assertThat(replayedGame).isNotNull();
        assertThat(replayedGame.getActivePlayers()).hasSize(activePlayersCount);
        assertThat(replayedGame.getCurrentPlayer().getName()).isEqualTo(nextCurrentTurn);
        assertThat(replayedGame.getPot()).isEqualTo(gameAfterFold.getPot());
        assertThat(replayedGame.getCurrentPhase()).isEqualTo(gameAfterFold.getCurrentPhase());
        
        // Ensure action was applied (the player who folded should have hasFolded = true)
        boolean hasFolded = replayedGame.getPlayers().stream()
                .filter(p -> p.getName().equals(currentTurnBeforeReplay))
                .findFirst().get().getHasFolded();
        assertThat(hasFolded).isTrue();
    }
}
