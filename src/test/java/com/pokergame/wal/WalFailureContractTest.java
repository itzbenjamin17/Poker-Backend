package com.pokergame.wal;

import com.pokergame.dto.request.CreateRoomRequest;
import com.pokergame.dto.request.PlayerActionRequest;
import com.pokergame.enums.PlayerAction;
import com.pokergame.exception.BadRequestException;
import com.pokergame.exception.ResourceNotFoundException;
import com.pokergame.service.GameLifecycleService;
import com.pokergame.service.PlayerActionService;
import com.pokergame.service.RoomService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@DisplayName("WAL Failure Contract")
class WalFailureContractTest {

    @MockitoSpyBean
    private WalFileService walFileService;

    @Autowired
    private RoomService roomService;

    @Autowired
    private GameLifecycleService gameLifecycleService;

    @Autowired
    private PlayerActionService playerActionService;

    @Test
    @DisplayName("should not write to WAL when RoomService.createRoom fails")
    void createRoom_whenFails_doesNotWriteToWal() {
        String uniqueName = "DuplicateRoom_" + java.util.UUID.randomUUID().toString();
        CreateRoomRequest request = new CreateRoomRequest(uniqueName, "player1", 2, 10, 20, 1000, null);

        // Success call
        roomService.createRoom(request);

        Mockito.clearInvocations(walFileService);

        // Failure call
        assertThatThrownBy(() -> roomService.createRoom(request))
                .isInstanceOf(Exception.class);

        // Verify no WAL event was appended for the failed attempt
        verify(walFileService, never()).appendEvent(anyString(), any());
    }

    @Test
    @DisplayName("should not write to WAL when GameLifecycleService.createGameFromRoom fails")
    void createGameFromRoom_whenFails_doesNotWriteToWal() {
        Mockito.clearInvocations(walFileService);

        // Use a non-existent room ID
        assertThatThrownBy(() -> gameLifecycleService.createGameFromRoom("non-existent-room-id"))
                .isInstanceOf(Exception.class);

        verify(walFileService, never()).appendEvent(anyString(), any());
    }

    @Test
    @DisplayName("should not write to WAL when PlayerActionService.processPlayerAction fails")
    void processPlayerAction_whenFails_doesNotWriteToWal() {
        Mockito.clearInvocations(walFileService);

        PlayerActionRequest request = new PlayerActionRequest(PlayerAction.FOLD, null);

        // Use a non-existent game ID, will throw ResourceNotFoundException
        assertThatThrownBy(() -> playerActionService.processPlayerAction("non-existent-game-id", request, "Player1"))
                .isInstanceOf(Exception.class);

        verify(walFileService, never()).appendEvent(anyString(), any());
    }
}
