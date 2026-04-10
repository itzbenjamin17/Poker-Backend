package com.pokergame.service;

import com.pokergame.dto.request.CreateRoomRequest;
import com.pokergame.dto.request.JoinRoomRequest;
import com.pokergame.dto.response.RoomDataResponse;
import com.pokergame.exception.BadRequestException;
import com.pokergame.exception.ResourceNotFoundException;
import com.pokergame.exception.UnauthorisedActionException;
import com.pokergame.model.Room;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Duration;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
@DisplayName("Room service")
class RoomServiceTest {

    private static final Duration CONCURRENCY_TIMEOUT = Duration.ofSeconds(5);

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    private RoomService roomService;
    private CreateRoomRequest validCreateRequest;

    @BeforeEach
    void setUp() {
        roomService = new RoomService(messagingTemplate);
        validCreateRequest = new CreateRoomRequest("Test Room", "HostPlayer", 6, 5, 10, 100, null);
    }

    @Nested
    @DisplayName("room creation")
    class CreateRoom {

        @Test
        @DisplayName("should create a room and add the host as the first player")
        void givenValidRequest_whenCreateRoom_thenReturnPersistedRoomId() {
            String roomId = roomService.createRoom(validCreateRequest);

            Room room = roomService.getRoom(roomId);

            assertThat(roomId).isNotBlank();
            assertThat(room).isNotNull();
            assertThat(room.getRoomName()).isEqualTo("Test Room");
            assertThat(room.getHostName()).isEqualTo("HostPlayer");
            assertThat(room.getPlayers()).containsExactly("HostPlayer");
            verify(messagingTemplate).convertAndSend(anyString(), org.mockito.ArgumentMatchers.any(Object.class));
        }

        @Test
        @DisplayName("should create a password protected room when a password is supplied")
        void givenPasswordProtectedRoom_whenCreateRoom_thenPasswordIsStoredAndValidated() {
            String roomId = roomService.createRoom(new CreateRoomRequest(
                    "Private Room",
                    "Host",
                    4,
                    10,
                    20,
                    200,
                    "secret123"));

            Room room = roomService.getRoom(roomId);

            assertThat(room).isNotNull();
            assertThat(room.hasPassword()).isTrue();
            assertThat(room.checkPassword("secret123")).isTrue();
            assertThat(room.checkPassword("wrongpassword")).isFalse();
        }

        @Test
        @DisplayName("should reject duplicate room names regardless of case")
        void givenDuplicateRoomName_whenCreateRoom_thenThrowBadRequestException() {
            roomService.createRoom(validCreateRequest);

            assertThatThrownBy(() -> roomService.createRoom(new CreateRoomRequest(
                    "TEST ROOM",
                    "AnotherHost",
                    4,
                    5,
                    10,
                    100,
                    null)))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("already taken");
        }
    }

    @Nested
    @DisplayName("joining rooms")
    class JoinRoom {

        @Test
        @DisplayName("should add a second player to an existing public room")
        void givenJoinRequestForPublicRoom_whenJoinRoom_thenPlayerIsAdded() {
            String roomId = roomService.createRoom(validCreateRequest);
            reset(messagingTemplate);

            roomService.joinRoom(new JoinRoomRequest("Test Room", "NewPlayer", null));

            assertThat(roomService.getRoom(roomId).getPlayers()).containsExactly("HostPlayer", "NewPlayer");
            verify(messagingTemplate).convertAndSend(anyString(), org.mockito.ArgumentMatchers.any(Object.class));
        }

        @Test
        @DisplayName("should allow joining a private room with the correct password")
        void givenCorrectPassword_whenJoinRoom_thenJoinSucceeds() {
            String roomId = roomService.createRoom(new CreateRoomRequest(
                    "Private Room",
                    "Host",
                    4,
                    5,
                    10,
                    100,
                    "password123"));

            roomService.joinRoom(new JoinRoomRequest("Private Room", "NewPlayer", "password123"));

            assertThat(roomService.getRoom(roomId).getPlayers()).containsExactly("Host", "NewPlayer");
        }

        @Test
        @DisplayName("should reject joins with the wrong password")
        void givenWrongPassword_whenJoinRoom_thenThrowBadRequestException() {
            roomService.createRoom(new CreateRoomRequest(
                    "Private Room",
                    "Host",
                    4,
                    5,
                    10,
                    100,
                    "password123"));

            assertThatThrownBy(() -> roomService.joinRoom(new JoinRoomRequest(
                    "Private Room",
                    "NewPlayer",
                    "wrongpassword")))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage("Invalid password");
        }

        @Test
        @DisplayName("should reject joins for missing rooms, full rooms, and duplicate player names")
        void givenInvalidJoinScenarios_whenJoinRoom_thenThrowMeaningfulExceptions() {
            roomService.createRoom(validCreateRequest);
            roomService.createRoom(new CreateRoomRequest("Small Room", "Host", 2, 5, 10, 100, null));
            roomService.joinRoom(new JoinRoomRequest("Small Room", "Player2", null));

            assertThatThrownBy(() -> roomService.joinRoom(new JoinRoomRequest("Missing Room", "Player", null)))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessage("Room not found");

            assertThatThrownBy(() -> roomService.joinRoom(new JoinRoomRequest("Small Room", "Player3", null)))
                    .isInstanceOf(UnauthorisedActionException.class)
                    .hasMessage("Room is full");

            assertThatThrownBy(() -> roomService.joinRoom(new JoinRoomRequest("Test Room", "HostPlayer", null)))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage("Player name already taken");
        }
    }

    @Nested
    @DisplayName("leaving rooms")
    class LeaveRoom {

        @Test
        @DisplayName("should remove a non-host player and keep the room open")
        void givenRegularPlayer_whenLeaveRoom_thenOnlyThatPlayerIsRemoved() {
            String roomId = roomService.createRoom(validCreateRequest);
            roomService.joinRoom(new JoinRoomRequest("Test Room", "Player2", null));
            reset(messagingTemplate);

            roomService.leaveRoom(roomId, "Player2");

            assertThat(roomService.getRoom(roomId)).isNotNull();
            assertThat(roomService.getRoom(roomId).getPlayers()).containsExactly("HostPlayer");
            verify(messagingTemplate).convertAndSend(anyString(), org.mockito.ArgumentMatchers.any(Object.class));
        }

        @Test
        @DisplayName("should destroy the room when the host leaves the lobby")
        void givenHostLeavesLobby_whenLeaveRoom_thenRoomIsDestroyed() {
            String roomId = roomService.createRoom(validCreateRequest);

            roomService.leaveRoom(roomId, "HostPlayer", true);

            assertThat(roomService.getRoom(roomId)).isNull();
            verify(messagingTemplate, times(2))
                    .convertAndSend(anyString(), org.mockito.ArgumentMatchers.any(Object.class));
        }

        @Test
        @DisplayName("should transfer the host when the host leaves an active room")
        void givenHostLeavesActiveRoom_whenLeaveRoom_thenHostTransfersToNextPlayer() {
            String roomId = roomService.createRoom(validCreateRequest);
            roomService.joinRoom(new JoinRoomRequest("Test Room", "Player2", null));
            roomService.joinRoom(new JoinRoomRequest("Test Room", "Player3", null));
            reset(messagingTemplate);

            roomService.leaveRoom(roomId, "HostPlayer", false);

            Room room = roomService.getRoom(roomId);
            RoomDataResponse roomData = roomService.getRoomData(roomId);

            assertThat(room).isNotNull();
            assertThat(room.getPlayers()).containsExactly("Player2", "Player3");
            assertThat(roomService.isRoomHost(roomId, "Player2")).isTrue();
            assertThat(roomService.isRoomHost(roomId, "Player3")).isFalse();
            assertThat(roomData.hostName()).isEqualTo("Player2");
            assertThat(roomData.players())
                    .filteredOn(player -> player.name().equals("Player2"))
                    .singleElement()
                    .extracting(player -> player.isHost())
                    .isEqualTo(true);
        }

        @Test
        @DisplayName("should reject leave requests for missing rooms")
        void givenMissingRoom_whenLeaveRoom_thenThrowResourceNotFoundException() {
            assertThatThrownBy(() -> roomService.leaveRoom("missing-room", "Player"))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessage("Room not found");
        }
    }

    @Nested
    @DisplayName("room queries")
    class RoomQueries {

        @Test
        @DisplayName("should expose room data including host flags and start eligibility")
        void givenRoomDataRequest_whenGetRoomData_thenReturnProjectedLobbyState() {
            String roomId = roomService.createRoom(validCreateRequest);
            roomService.joinRoom(new JoinRoomRequest("Test Room", "Player2", null));

            RoomDataResponse roomData = roomService.getRoomData(roomId);

            assertThat(roomData.roomId()).isEqualTo(roomId);
            assertThat(roomData.roomName()).isEqualTo("Test Room");
            assertThat(roomData.currentPlayers()).isEqualTo(2);
            assertThat(roomData.canStartGame()).isTrue();
            assertThat(roomData.players())
                    .extracting(player -> player.name() + ":" + player.isHost())
                    .containsExactly("HostPlayer:true", "Player2:false");
        }

        @Test
        @DisplayName("should validate room identifiers when retrieving room data")
        void givenInvalidRoomIdentifiers_whenGetRoomData_thenThrowMeaningfulExceptions() {
            assertThatThrownBy(() -> roomService.getRoomData(null))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage("Room ID cannot be null");

            assertThatThrownBy(() -> roomService.getRoomData("missing-room"))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessage("Room not found");
        }

        @Test
        @DisplayName("should return rooms, hosts, and lookups consistently")
        void givenRoomsExist_whenQuerying_thenReturnExpectedResults() {
            String roomId = roomService.createRoom(validCreateRequest);
            roomService.createRoom(new CreateRoomRequest("Room 2", "Host2", 4, 10, 20, 200, null));

            assertThat(roomService.getRooms()).hasSize(2);
            assertThat(roomService.getRoom(roomId)).isNotNull();
            assertThat(roomService.getRoom("missing-room")).isNull();
            assertThat(roomService.findRoomByName("test room")).isNotNull();
            assertThat(roomService.findRoomByName("missing-room")).isNull();
            assertThat(roomService.isRoomHost(roomId, "HostPlayer")).isTrue();
            assertThat(roomService.isRoomHost(roomId, "Player2")).isFalse();
        }

        @Test
        @DisplayName("should destroy rooms safely even when the room does not exist")
        void givenDestroyRoomRequest_whenDestroyRoom_thenRemoveRoomWithoutThrowing() {
            String roomId = roomService.createRoom(validCreateRequest);

            roomService.destroyRoom(roomId);

            assertThat(roomService.getRoom(roomId)).isNull();
            assertThat(roomService.isRoomHost(roomId, "HostPlayer")).isFalse();
            assertThatCode(() -> roomService.destroyRoom("missing-room")).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("concurrency")
    class Concurrency {

        @Test
        @DisplayName("should allow only one successful room creation when requests race on the same room name")
        void givenConcurrentDuplicateCreates_whenCreateRoom_thenOnlyOneSucceeds() throws InterruptedException {
            int attempts = 10;
            ExecutorService executor = Executors.newFixedThreadPool(attempts);
            CountDownLatch ready = new CountDownLatch(attempts);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(attempts);
            AtomicInteger successCount = new AtomicInteger();
            Queue<Throwable> unexpectedFailures = new ConcurrentLinkedQueue<>();

            for (int index = 0; index < attempts; index++) {
                int currentIndex = index;
                executor.submit(() -> {
                    try {
                        ready.countDown();
                        assertThat(start.await(CONCURRENCY_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();

                        roomService.createRoom(new CreateRoomRequest(
                                "Concurrent Name",
                                "Host" + currentIndex,
                                6,
                                5,
                                10,
                                100,
                                null));
                        successCount.incrementAndGet();
                    } catch (BadRequestException expected) {
                        assertThat(expected).hasMessageContaining("already taken");
                    } catch (Throwable throwable) {
                        unexpectedFailures.add(throwable);
                    } finally {
                        done.countDown();
                    }
                });
            }

            assertThat(ready.await(CONCURRENCY_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
            start.countDown();
            assertThat(done.await(CONCURRENCY_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
            executor.shutdownNow();

            assertThat(unexpectedFailures).isEmpty();
            assertThat(successCount).hasValue(1);
        }
    }
}
