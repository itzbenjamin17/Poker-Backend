package com.pokergame.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.eq;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.pokergame.config.WebSocketEventListener;

import com.pokergame.dto.request.CreateRoomRequest;
import com.pokergame.dto.request.JoinRoomRequest;
import com.pokergame.dto.response.RoomDataResponse;
import com.pokergame.exception.BadRequestException;
import com.pokergame.exception.ResourceNotFoundException;
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

/** Tests room service behavior. */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
@DisplayName("Room service")
class RoomServiceTest {

    private static final Duration CONCURRENCY_TIMEOUT = Duration.ofSeconds(5);

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    private RoomService roomService;
    private CreateRoomRequest validCreateRequest;

    /**
     * Initializes the test fixtures before each scenario.
     */
    @BeforeEach
    void setUp() {
        roomService = new RoomService(messagingTemplate);
        validCreateRequest = new CreateRoomRequest("Test Room", "HostPlayer", 6, 5, 10, 100, null);
    }

    /** Groups test scenarios for create room. */
    @Nested
    @DisplayName("room creation")
    class CreateRoom {

        /**
         * Protects the contract that the system should create a room and add the host as the first player.
         */
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

        /**
         * Protects the contract that the system should create a password protected room when a password is supplied.
         */
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

        /**
         * Protects the contract that the system should reject duplicate room names regardless of case.
         */
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

        /**
         * Protects the contract that the system should trim room and player names during creation.
         */
        @Test
        @DisplayName("should trim room and player names during creation")
        void createRoom_TrimsNames() {
            String roomId = roomService.createRoom(new CreateRoomRequest(
                    "  Untrimmed Room  ",
                    "  Untrimmed Player  ",
                    6, 10, 20, 1000, null));

            Room room = roomService.getRoom(roomId);
            assertThat(room.getRoomName()).isEqualTo("Untrimmed Room");
            assertThat(room.getPlayers()).containsExactly("Untrimmed Player");
        }

        /**
         * Protects the contract that format characters are rejected in room name during creation.
         */
        @Test
        @DisplayName("should reject format characters in room name during creation")
        void createRoom_RejectsFormatCharactersInRoomName() {
            assertThatThrownBy(() -> roomService.createRoom(new CreateRoomRequest(
                    "Room\u200BName", "HostPlayer", 6, 5, 10, 100, null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("format characters");
        }

        /**
         * Protects the contract that format characters are rejected in player name during creation.
         */
        @Test
        @DisplayName("should reject format characters in player name during creation")
        void createRoom_RejectsFormatCharactersInPlayerName() {
            assertThatThrownBy(() -> roomService.createRoom(new CreateRoomRequest(
                    "ValidRoom", "Host\u200DPlayer", 6, 5, 10, 100, null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("format characters");
        }

        /**
         * Protects the contract that whitespace-only password is treated as absent on creation.
         */
        @Test
        @DisplayName("should treat whitespace-only password as absent on creation")
        void createRoom_WhitespaceOnlyPasswordTreatedAsAbsent() {
            String roomId = roomService.createRoom(new CreateRoomRequest(
                    "Public Room", "Host", 4, 10, 20, 200, "   "));
            Room room = roomService.getRoom(roomId);
            assertThat(room.hasPassword()).isFalse();
            assertThat(room.getPasswordForPersistence()).isNull();

            // Any player can join without password
            roomService.joinRoom(new JoinRoomRequest("Public Room", "NewPlayer", null));
            assertThat(room.getPlayers()).contains("NewPlayer");
        }

        /**
         * Protects the contract that leading and trailing spaces in password are preserved.
         */
        @Test
        @DisplayName("should preserve leading and trailing spaces in password")
        void createRoom_PreservesLeadingAndTrailingSpacesInPassword() {
            String roomId = roomService.createRoom(new CreateRoomRequest(
                    "Spaced Pass Room", "Host", 4, 10, 20, 200, "  secret123  "));
            Room room = roomService.getRoom(roomId);
            assertThat(room.hasPassword()).isTrue();
            assertThat(room.getPasswordForPersistence()).isEqualTo("  secret123  ");

            // Joining with exact password succeeds
            roomService.joinRoom(new JoinRoomRequest("Spaced Pass Room", "NewPlayer", "  secret123  "));
            assertThat(room.getPlayers()).contains("NewPlayer");

            // Joining without spaces fails
            assertThatThrownBy(() -> roomService.joinRoom(new JoinRoomRequest("Spaced Pass Room", "AnotherPlayer", "secret123")))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage("Invalid password");
        }
    }

    /** Groups test scenarios for join room. */
    @Nested
    @DisplayName("joining rooms")
    class JoinRoom {

        /**
         * Protects the contract that the system should allow joining a room with leading/trailing whitespace in the search name.
         */
        @Test
        @DisplayName("should allow joining a room with leading/trailing whitespace in the search name")
        void joinRoom_TrimsSearchName() {
            String roomId = roomService.createRoom(validCreateRequest);

            String joinedRoomId = roomService.joinRoom(new JoinRoomRequest("  Test Room  ", "Player2", null));

            assertThat(joinedRoomId).isEqualTo(roomId);
            assertThat(roomService.getRoom(roomId).getPlayers()).contains("Player2");
        }

        /**
         * Protects the contract that the system should trim player name when joining.
         */
        @Test
        @DisplayName("should trim player name when joining")
        void joinRoom_TrimsPlayerName() {
            roomService.createRoom(validCreateRequest);

            roomService.joinRoom(new JoinRoomRequest("Test Room", "  P2  ", null));

            assertThat(roomService.findRoomByName("Test Room").getPlayers()).contains("P2");
        }

        /**
         * Protects the contract that the system should add a second player to an existing public room.
         */
        @Test
        @DisplayName("should add a second player to an existing public room")
        void givenJoinRequestForPublicRoom_whenJoinRoom_thenPlayerIsAdded() {
            String roomId = roomService.createRoom(validCreateRequest);
            reset(messagingTemplate);

            roomService.joinRoom(new JoinRoomRequest("Test Room", "NewPlayer", null));

            assertThat(roomService.getRoom(roomId).getPlayers()).containsExactly("HostPlayer", "NewPlayer");
            verify(messagingTemplate).convertAndSend(anyString(), org.mockito.ArgumentMatchers.any(Object.class));
        }

        /**
         * Protects the contract that the system should allow joining a private room with the correct password.
         */
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

        /**
         * Protects the contract that the system should reject joins with the wrong password.
         */
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

        /**
         * Protects the contract that format characters are rejected in room name during join.
         */
        @Test
        @DisplayName("should reject format characters in room name during join")
        void joinRoom_RejectsFormatCharactersInRoomName() {
            roomService.createRoom(validCreateRequest);
            assertThatThrownBy(() -> roomService.joinRoom(new JoinRoomRequest(
                    "Test\u200BRoom", "Player2", null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("format characters");
        }

        /**
         * Protects the contract that format characters are rejected in player name during join.
         */
        @Test
        @DisplayName("should reject format characters in player name during join")
        void joinRoom_RejectsFormatCharactersInPlayerName() {
            roomService.createRoom(validCreateRequest);
            assertThatThrownBy(() -> roomService.joinRoom(new JoinRoomRequest(
                    "Test Room", "Player\u200E2", null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("format characters");
        }

        /**
         * Protects the contract that joining with a whitespace-only password fails when room has a password.
         */
        @Test
        @DisplayName("should fail join with whitespace-only password when room has password")
        void joinRoom_WhitespaceOnlyPasswordFailsWhenRoomHasPassword() {
            roomService.createRoom(new CreateRoomRequest(
                    "Protected Room", "Host", 4, 10, 20, 200, "swordfish"));

            assertThatThrownBy(() -> roomService.joinRoom(new JoinRoomRequest(
                    "Protected Room", "NewPlayer", "   ")))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage("Invalid password");
        }

        /**
         * Protects the contract that the system should reject joins for missing rooms, full rooms, and duplicate player names.
         */
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
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage("Room is full");

            assertThatThrownBy(() -> roomService.joinRoom(new JoinRoomRequest("Test Room", "HostPlayer", null)))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage("Player name already taken");
        }

        /**
         * Protects the contract that joining a full room via joinRoom results in BadRequestException ("Room is full").
         */
        @Test
        @DisplayName("should reject join with BadRequestException when room is full")
        void joinRoom_WhenRoomFull_ThrowsBadRequestException() {
            String roomId = roomService.createRoom(new CreateRoomRequest("Full Capacity Room", "Host", 2, 5, 10, 100, null));
            roomService.joinRoom(new JoinRoomRequest("Full Capacity Room", "Player2", null));

            // Test join by request
            assertThatThrownBy(() -> roomService.joinRoom(new JoinRoomRequest("Full Capacity Room", "Player3", null)))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage("Room is full");

            // Test join by roomId overload
            assertThatThrownBy(() -> roomService.joinRoom(roomId, "Player3", null))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage("Room is full");

            Room room = roomService.getRoom(roomId);
            assertThat(room.getPlayers()).containsExactly("Host", "Player2");
        }

        /**
         * Protects the contract that multi-threaded concurrent joins to joinRoom strictly admit exactly maxPlayers
         * and reject the rest with BadRequestException.
         */
        @Test
        @DisplayName("concurrent joins strictly admit exactly maxPlayers and reject excess with BadRequestException")
        void joinRoom_ConcurrentJoins_StrictlyEnforcesMaxPlayersCapacity() throws Exception {
            int capacity = 4;
            String roomId = roomService.createRoom(new CreateRoomRequest(
                    "Concurrent Join Room", "Host", capacity, 5, 10, 100, null));

            int totalJoiningThreads = 12;
            ExecutorService executor = Executors.newFixedThreadPool(totalJoiningThreads);
            CountDownLatch readyLatch = new CountDownLatch(totalJoiningThreads);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(totalJoiningThreads);

            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger fullExceptionCount = new AtomicInteger(0);
            Queue<Throwable> unexpectedErrors = new ConcurrentLinkedQueue<>();

            for (int i = 1; i <= totalJoiningThreads; i++) {
                final String playerName = "JoiningPlayer" + i;
                executor.submit(() -> {
                    readyLatch.countDown();
                    try {
                        startLatch.await();
                        if (playerName.hashCode() % 2 == 0) {
                            roomService.joinRoom(new JoinRoomRequest("Concurrent Join Room", playerName, null));
                        } else {
                            roomService.joinRoom(roomId, playerName, null);
                        }
                        successCount.incrementAndGet();
                    } catch (BadRequestException e) {
                        if ("Room is full".equals(e.getMessage())) {
                            fullExceptionCount.incrementAndGet();
                        } else {
                            unexpectedErrors.add(e);
                        }
                    } catch (Throwable t) {
                        unexpectedErrors.add(t);
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            assertThat(readyLatch.await(5, TimeUnit.SECONDS)).isTrue();
            startLatch.countDown();
            assertThat(doneLatch.await(5, TimeUnit.SECONDS)).isTrue();
            executor.shutdown();

            assertThat(unexpectedErrors).isEmpty();
            assertThat(successCount.get()).isEqualTo(capacity - 1);
            assertThat(fullExceptionCount.get()).isEqualTo(totalJoiningThreads - (capacity - 1));

            Room room = roomService.getRoom(roomId);
            assertThat(room.getPlayers().size()).isEqualTo(capacity);
            assertThat(room.isFull()).isTrue();
        }
    }

    /** Groups test scenarios for leave room. */
    @Nested
    @DisplayName("leaving rooms")
    class LeaveRoom {

        /**
         * Protects the contract that the system should remove a non-host player and keep the room open.
         */
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

        /**
         * Protects the contract that the system should destroy the room when the host leaves the lobby.
         */
        @Test
        @DisplayName("should destroy the room when the host leaves the lobby")
        void givenHostLeavesLobby_whenLeaveRoom_thenRoomIsDestroyed() {
            String roomId = roomService.createRoom(validCreateRequest);

            roomService.leaveRoom(roomId, "HostPlayer");

            assertThat(roomService.getRoom(roomId)).isNull();
            verify(messagingTemplate, times(2))
                    .convertAndSend(anyString(), org.mockito.ArgumentMatchers.any(Object.class));
        }

        /**
         * Protects the contract that the system should transfer the host when the host leaves an active room.
         */
        @Test
        @DisplayName("should transfer the host when the host leaves an active room")
        void givenHostLeavesActiveRoom_whenLeaveRoom_thenHostTransfersToNextPlayer() {
            String roomId = roomService.createRoom(validCreateRequest);
            roomService.joinRoom(new JoinRoomRequest("Test Room", "Player2", null));
            roomService.joinRoom(new JoinRoomRequest("Test Room", "Player3", null));
            reset(messagingTemplate);

            roomService.leaveRoom(roomId, "HostPlayer", "Player2");

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

        /**
         * Protects the contract that the system should reject leave requests for missing rooms.
         */
        @Test
        @DisplayName("should reject leave requests for missing rooms")
        void givenMissingRoom_whenLeaveRoom_thenThrowResourceNotFoundException() {
            assertThatThrownBy(() -> roomService.leaveRoom("missing-room", "Player"))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessage("Room not found");
        }

        @Test
        @DisplayName("concurrent active-host and successor departures never select an absent host or throw on empty list")
        void givenConcurrentHostAndSuccessorLeave_whenLeaveRoom_thenNeverSelectsAbsentHost() throws Exception {
            for (int i = 0; i < 50; i++) {
                String roomName = "Concurrent Room " + i;
                CreateRoomRequest request = new CreateRoomRequest(roomName, "Host", 6, 10, 20, 1000, null);
                String roomId = roomService.createRoom(request);
                roomService.joinRoom(new JoinRoomRequest(roomName, "Successor", null));
                roomService.joinRoom(new JoinRoomRequest(roomName, "Player3", null));

                java.util.concurrent.CountDownLatch startLatch = new java.util.concurrent.CountDownLatch(1);
                java.util.concurrent.CountDownLatch doneLatch = new java.util.concurrent.CountDownLatch(2);
                java.util.concurrent.atomic.AtomicReference<Throwable> error = new java.util.concurrent.atomic.AtomicReference<>();

                Thread t1 = new Thread(() -> {
                    try {
                        startLatch.await();
                        roomService.leaveRoom(roomId, "Host", "Successor");
                    } catch (Throwable t) {
                        error.compareAndSet(null, t);
                    } finally {
                        doneLatch.countDown();
                    }
                });

                Thread t2 = new Thread(() -> {
                    try {
                        startLatch.await();
                        roomService.leaveRoom(roomId, "Successor", "Player3");
                    } catch (Throwable t) {
                        error.compareAndSet(null, t);
                    } finally {
                        doneLatch.countDown();
                    }
                });

                t1.start();
                t2.start();
                startLatch.countDown();

                boolean completed = doneLatch.await(3, java.util.concurrent.TimeUnit.SECONDS);
                org.junit.jupiter.api.Assertions.assertTrue(completed, "Leave operations timed out at iteration " + i);
                org.junit.jupiter.api.Assertions.assertNull(error.get(), "Unexpected error during concurrent leave: " + error.get());

                Room room = roomService.getRoom(roomId);
                if (room != null) {
                    String currentHost = roomService.getCurrentHost(roomId);
                    org.junit.jupiter.api.Assertions.assertNotNull(currentHost, "Current host must not be null if room exists");
                    org.junit.jupiter.api.Assertions.assertTrue(room.getPlayers().contains(currentHost), "Current host must be a member of the room, was: " + currentHost);
                    org.junit.jupiter.api.Assertions.assertEquals("Player3", currentHost);
                }
            }
        }
    }

    /** Groups test scenarios for room queries. */
    @Nested
    @DisplayName("room queries")
    class RoomQueries {

        /**
         * Protects the contract that the system should expose room data including host flags and start eligibility.
         */
        @Test
        @DisplayName("getRoomData synchronizes on Room monitor for coherent projection")
        void givenRoomDataRequest_whenGetRoomData_thenSynchronizesOnRoomMonitor() throws Exception {
            String roomId = roomService.createRoom(validCreateRequest);
            roomService.joinRoom(new JoinRoomRequest("Test Room", "Player2", null));
            Room room = roomService.getRoom(roomId);

            java.util.concurrent.CountDownLatch lockAcquired = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch releaseLock = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.atomic.AtomicBoolean getRoomDataStarted = new java.util.concurrent.atomic.AtomicBoolean(false);
            java.util.concurrent.atomic.AtomicBoolean getRoomDataFinished = new java.util.concurrent.atomic.AtomicBoolean(false);

            Thread holder = new Thread(() -> {
                synchronized (room) {
                    lockAcquired.countDown();
                    try {
                        releaseLock.await();
                    } catch (InterruptedException ignored) {}
                }
            });
            holder.start();
            lockAcquired.await();

            Thread reader = new Thread(() -> {
                getRoomDataStarted.set(true);
                roomService.getRoomData(roomId);
                getRoomDataFinished.set(true);
            });
            reader.start();

            org.awaitility.Awaitility.await().atMost(2, java.util.concurrent.TimeUnit.SECONDS).untilTrue(getRoomDataStarted);
            org.junit.jupiter.api.Assertions.assertFalse(getRoomDataFinished.get(), "getRoomData must block while Room monitor is held by another thread");

            releaseLock.countDown();
            reader.join(2000);
            holder.join(2000);
            org.junit.jupiter.api.Assertions.assertTrue(getRoomDataFinished.get());
        }

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
        @DisplayName("should set canStartGame to false once the game has started even if player count >= 2")
        void givenGameStarted_whenGetRoomData_thenCanStartGameIsFalse() {
            String roomId = roomService.createRoom(validCreateRequest);
            roomService.joinRoom(new JoinRoomRequest("Test Room", "Player2", null));
            Room room = roomService.getRoom(roomId);
            room.setGameStarted(true);

            RoomDataResponse roomData = roomService.getRoomData(roomId);

            assertThat(roomData.gameStarted()).isTrue();
            assertThat(roomData.canStartGame()).isFalse();
        }

        /**
         * Protects the contract that the system should validate room identifiers when retrieving room data.
         */
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

        /**
         * Protects the contract that the system should return rooms, hosts, and lookups consistently.
         */
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

        /**
         * Protects the contract that the system should destroy rooms safely even when the room does not exist.
         */
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

    /** Groups test scenarios for concurrency. */
    @Nested
    @DisplayName("concurrency")
    class Concurrency {

        /**
         * Protects the contract that the system should allow only one successful room creation when requests race on the same room name.
         */
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

    @Test
    @DisplayName("createRoom establishes first connection allowance with original acceptance deadline")
    void createRoom_EstablishesFirstConnectionRegistrationWithAcceptanceDeadline() {
        WebSocketEventListener listener = mock(WebSocketEventListener.class);
        RoomService serviceWithListener = new RoomService(messagingTemplate, listener, 60_000L);

        String roomId = serviceWithListener.createRoom(validCreateRequest);
        Room createdRoom = serviceWithListener.getRoom(roomId);

        assertNotNull(createdRoom.getAbsenceDeadline("HostPlayer"));
        String membershipId = createdRoom.getMembershipId("HostPlayer");
        assertNotNull(membershipId);

        verify(listener).registerInitialConnection(eq(roomId), eq("HostPlayer"), eq(membershipId), eq(createdRoom.getAbsenceDeadline("HostPlayer")));
    }

    @Test
    @DisplayName("joinRoom establishes first connection allowance with original acceptance deadline")
    void joinRoom_EstablishesFirstConnectionRegistrationWithAcceptanceDeadline() {
        WebSocketEventListener listener = mock(WebSocketEventListener.class);
        RoomService serviceWithListener = new RoomService(messagingTemplate, listener, 60_000L);

        String roomId = serviceWithListener.createRoom(validCreateRequest);
        serviceWithListener.joinRoom(new JoinRoomRequest("Test Room", "Player2", null));

        Room room = serviceWithListener.getRoom(roomId);
        assertNotNull(room.getAbsenceDeadline("Player2"));
        String p2MembershipId = room.getMembershipId("Player2");
        assertNotNull(p2MembershipId);

        verify(listener).registerInitialConnection(eq(roomId), eq("Player2"), eq(p2MembershipId), eq(room.getAbsenceDeadline("Player2")));
    }

}