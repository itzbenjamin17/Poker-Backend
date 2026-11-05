package com.pokergame.service;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.pokergame.service.event.RoomDestroyed;
import com.pokergame.service.event.RoomMemberRegistered;
import com.pokergame.service.event.RoomMembershipRetired;
import org.springframework.context.ApplicationEventPublisher;

import com.pokergame.dto.request.CreateRoomRequest;
import com.pokergame.dto.request.JoinRoomRequest;
import com.pokergame.dto.response.ApiResponse;
import com.pokergame.dto.response.RoomDataResponse;
import com.pokergame.enums.ResponseMessage;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Assertions;
import org.mockito.ArgumentMatchers;

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

        /** Rejects unsupported BCrypt inputs before creating an aggregate. */
        @Test
        void rejectsPasswordAboveUtf8ByteLimitWithoutCreatingRoom() {
            assertThatThrownBy(() -> roomService.createRoom(new CreateRoomRequest(
                    "Unicode Room", "Host", 4, 5, 10, 100, "漢".repeat(25))))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage("Password must be at most 72 UTF-8 bytes.");
            assertThat(roomService.getRooms()).isEmpty();
        }

        /** Preserves exact nonblank passwords at BCrypt's byte boundary. */
        @Test
        void supportsUtf8BoundaryAndRejectsOverlongJoinWithoutAddingPlayer() {
            String password = " " + "漢".repeat(23) + "ab";
            String roomId = roomService.createRoom(new CreateRoomRequest(
                    "Boundary Room", "Host", 4, 5, 10, 100, password));
            roomService.joinRoom(new JoinRoomRequest("Boundary Room", "Guest", password));
            assertThatThrownBy(() -> roomService.joinRoom(new JoinRoomRequest(
                    "Boundary Room", "Rejected", password + " ")))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage("Password must be at most 72 UTF-8 bytes.");
            assertThat(roomService.getRoom(roomId).getPlayers()).containsExactly("Host", "Guest");
        }

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
            verify(messagingTemplate).convertAndSend(anyString(), ArgumentMatchers.any(Object.class));
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
            assertThat(room.getPassword()).isNull();
            assertThat(room.getPasswordHash()).startsWith("$2");
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
            assertThat(room.getPassword()).isNull();
            assertThat(room.getPasswordHash()).startsWith("$2");
            assertThat(room.checkPassword("  secret123  ")).isTrue();
            assertThat(room.checkPassword("secret123")).isFalse();

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
            verify(messagingTemplate).convertAndSend(anyString(), ArgumentMatchers.any(Object.class));
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
            verify(messagingTemplate).convertAndSend(anyString(), ArgumentMatchers.any(Object.class));
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
                    .convertAndSend(anyString(), ArgumentMatchers.any(Object.class));
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

                CountDownLatch startLatch = new CountDownLatch(1);
                CountDownLatch doneLatch = new CountDownLatch(2);
                AtomicReference<Throwable> error = new AtomicReference<>();

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

                boolean completed = doneLatch.await(3, TimeUnit.SECONDS);
                Assertions.assertTrue(completed, "Leave operations timed out at iteration " + i);
                Assertions.assertNull(error.get(), "Unexpected error during concurrent leave: " + error.get());

                Room room = roomService.getRoom(roomId);
                if (room != null) {
                    String currentHost = roomService.getCurrentHost(roomId);
                    Assertions.assertNotNull(currentHost, "Current host must not be null if room exists");
                    Assertions.assertTrue(room.getPlayers().contains(currentHost), "Current host must be a member of the room, was: " + currentHost);
                    Assertions.assertEquals("Player3", currentHost);
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

            CountDownLatch lockAcquired = new CountDownLatch(1);
            CountDownLatch releaseLock = new CountDownLatch(1);
            AtomicBoolean getRoomDataStarted = new AtomicBoolean(false);
            AtomicBoolean getRoomDataFinished = new AtomicBoolean(false);

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

            Awaitility.await().atMost(2, TimeUnit.SECONDS).untilTrue(getRoomDataStarted);
            Assertions.assertFalse(getRoomDataFinished.get(), "getRoomData must block while Room monitor is held by another thread");

            releaseLock.countDown();
            reader.join(2000);
            holder.join(2000);
            Assertions.assertTrue(getRoomDataFinished.get());
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
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        RoomService serviceWithPublisher = new RoomService(messagingTemplate, publisher, 60_000L);

        String roomId = serviceWithPublisher.createRoom(validCreateRequest);
        Room createdRoom = serviceWithPublisher.getRoom(roomId);

        assertNotNull(createdRoom.getAbsenceDeadline("HostPlayer"));
        String membershipId = createdRoom.getMembershipId("HostPlayer");
        assertNotNull(membershipId);

        verify(publisher).publishEvent(new RoomMemberRegistered(
                roomId, "HostPlayer", membershipId, createdRoom.getAbsenceDeadline("HostPlayer")));
    }

    @Test
    @DisplayName("joinRoom establishes first connection allowance with original acceptance deadline")
    void joinRoom_EstablishesFirstConnectionRegistrationWithAcceptanceDeadline() {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        RoomService serviceWithPublisher = new RoomService(messagingTemplate, publisher, 60_000L);

        String roomId = serviceWithPublisher.createRoom(validCreateRequest);
        serviceWithPublisher.joinRoom(new JoinRoomRequest("Test Room", "Player2", null));

        Room room = serviceWithPublisher.getRoom(roomId);
        assertNotNull(room.getAbsenceDeadline("Player2"));
        String p2MembershipId = room.getMembershipId("Player2");
        assertNotNull(p2MembershipId);

        verify(publisher).publishEvent(new RoomMemberRegistered(
                roomId, "Player2", p2MembershipId, room.getAbsenceDeadline("Player2")));
    }

    @Test
    @DisplayName("leaveRoom publishes retirement of exactly the leaving member's membership")
    void leaveRoom_PublishesMembershipRetired() {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        RoomService serviceWithPublisher = new RoomService(messagingTemplate, publisher, 60_000L);

        String roomId = serviceWithPublisher.createRoom(validCreateRequest);
        serviceWithPublisher.joinRoom(new JoinRoomRequest("Test Room", "Player2", null));
        String p2MembershipId = serviceWithPublisher.getRoom(roomId).getMembershipId("Player2");

        serviceWithPublisher.leaveRoom(roomId, "Player2");

        verify(publisher).publishEvent(new RoomMembershipRetired(roomId, "Player2", p2MembershipId));
    }

    @Test
    @DisplayName("destroyRoom publishes room destruction")
    void destroyRoom_PublishesRoomDestroyed() {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        RoomService serviceWithPublisher = new RoomService(messagingTemplate, publisher, 60_000L);

        String roomId = serviceWithPublisher.createRoom(validCreateRequest);
        serviceWithPublisher.destroyRoom(roomId);

        verify(publisher).publishEvent(new RoomDestroyed(roomId));
    }

    @Test
    @DisplayName("createRoom delegates raw password hashing to injected PasswordEncoder and joinRoom verifies using matches")
    void passwordSecurity_UsesInjectedPasswordEncoderForHashingAndValidation() {
        PasswordEncoder mockEncoder = mock(PasswordEncoder.class);
        when(mockEncoder.encode("secretPass42")).thenReturn("$2a$10$customMockHashForSecretPass42");
        when(mockEncoder.matches("secretPass42", "$2a$10$customMockHashForSecretPass42")).thenReturn(true);
        when(mockEncoder.matches("wrongPass", "$2a$10$customMockHashForSecretPass42")).thenReturn(false);

        RoomService customService = new RoomService(messagingTemplate, mockEncoder);

        String roomId = customService.createRoom(new CreateRoomRequest(
                "Secure Room", "Host", 4, 10, 20, 200, "secretPass42"));

        verify(mockEncoder).encode("secretPass42");

        Room room = customService.getRoom(roomId);
        assertThat(room).isNotNull();
        assertThat(room.hasPassword()).isTrue();
        assertThat(room.getPassword()).isNull();
        assertThat(room.getPasswordHash()).isEqualTo("$2a$10$customMockHashForSecretPass42");

        // Join with correct password
        String joinedId = customService.joinRoom(new JoinRoomRequest("Secure Room", "Guest", "secretPass42"));
        assertThat(joinedId).isEqualTo(roomId);
        assertThat(room.getPlayers()).contains("Guest");
        verify(mockEncoder).matches("secretPass42", "$2a$10$customMockHashForSecretPass42");

        // Join with wrong password fails
        assertThatThrownBy(() -> customService.joinRoom(new JoinRoomRequest("Secure Room", "Intruder", "wrongPass")))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Invalid password");
    }

    @Test
    @DisplayName("createRoom does not invoke PasswordEncoder for public rooms with null or empty passwords")
    void passwordSecurity_PublicRoomDoesNotInvokePasswordEncoder() {
        PasswordEncoder mockEncoder = mock(PasswordEncoder.class);
        RoomService customService = new RoomService(messagingTemplate, mockEncoder);

        String roomId1 = customService.createRoom(new CreateRoomRequest(
                "Public Room 1", "Host", 4, 10, 20, 200, null));
        String roomId2 = customService.createRoom(new CreateRoomRequest(
                "Public Room 2", "Host", 4, 10, 20, 200, "   "));

        verify(mockEncoder, never()).encode(any());

        Room r1 = customService.getRoom(roomId1);
        Room r2 = customService.getRoom(roomId2);
        assertThat(r1.hasPassword()).isFalse();
        assertThat(r1.getPasswordHash()).isNull();
        assertThat(r1.getPassword()).isNull();
        assertThat(r2.hasPassword()).isFalse();
        assertThat(r2.getPasswordHash()).isNull();
        assertThat(r2.getPassword()).isNull();

        // Any password or null password allows joining public room
        customService.joinRoom(new JoinRoomRequest("Public Room 1", "P2", null));
        customService.joinRoom(new JoinRoomRequest("Public Room 2", "P3", "anything"));
        assertThat(r1.getPlayers()).contains("P2");
        assertThat(r2.getPlayers()).contains("P3");
        verify(mockEncoder, never()).matches(any(), any());
    }

    /**
     * Branch coverage for RoomService's post-addPlayer catch block. The reachable "Room is full" contract is
     * already proven by the full-room and concurrency tests above. Production cannot actually reach this catch:
     * Room synchronizes isFull() and addPlayer() on the same monitor, so the room cannot fill between the
     * service's own capacity check and the add. These cases therefore force the failure through a spy rather
     * than a fake injectable seam, exercising only the exception-mapping branch.
     */
    @Nested
    @DisplayName("join failures raised by the room aggregate")
    class JoinAddPlayerFailures {

        /** Registers a spy of the created room so addPlayer can fail after the service's own checks pass. */
        private Room registerSpiedRoom() {
            String roomId = roomService.createRoom(validCreateRequest);
            Room spied = spy(roomService.getRoom(roomId));
            roomService.restoreRoom(spied, "HostPlayer");
            return spied;
        }

        @Test
        @DisplayName("maps the exact 'Room is full' IllegalStateException to BadRequestException")
        void joinRoom_AddPlayerReportsRoomIsFull_ThrowsBadRequestException() {
            Room spied = registerSpiedRoom();
            doThrow(new IllegalStateException("Room is full")).when(spied).addPlayer("Guest");

            assertThatThrownBy(() -> roomService.joinRoom(new JoinRoomRequest("Test Room", "Guest", null)))
                    .isExactlyInstanceOf(BadRequestException.class)
                    .hasMessage("Room is full");
            assertThat(spied.getPlayers()).containsExactly("HostPlayer");
        }

        @Test
        @DisplayName("maps any IllegalStateException message containing 'full' to BadRequestException")
        void joinRoom_AddPlayerMessageContainsFull_ThrowsBadRequestException() {
            Room spied = registerSpiedRoom();
            doThrow(new IllegalStateException("Table is full right now")).when(spied).addPlayer("Guest");

            assertThatThrownBy(() -> roomService.joinRoom(new JoinRoomRequest("Test Room", "Guest", null)))
                    .isExactlyInstanceOf(BadRequestException.class)
                    .hasMessage("Room is full");
            assertThat(spied.getPlayers()).containsExactly("HostPlayer");
        }

        @Test
        @DisplayName("re-throws an unrelated IllegalStateException unchanged")
        void joinRoom_AddPlayerUnrelatedFailure_RethrowsOriginal() {
            Room spied = registerSpiedRoom();
            IllegalStateException failure = new IllegalStateException("Corrupt membership table");
            doThrow(failure).when(spied).addPlayer("Guest");

            assertThatThrownBy(() -> roomService.joinRoom(new JoinRoomRequest("Test Room", "Guest", null)))
                    .isSameAs(failure);
        }

        @Test
        @DisplayName("re-throws an IllegalStateException without a message unchanged")
        void joinRoom_AddPlayerFailureWithoutMessage_RethrowsOriginal() {
            Room spied = registerSpiedRoom();
            IllegalStateException failure = new IllegalStateException();
            doThrow(failure).when(spied).addPlayer("Guest");

            assertThatThrownBy(() -> roomService.joinRoom(new JoinRoomRequest("Test Room", "Guest", null)))
                    .isSameAs(failure);
        }
    }

    /** Groups leave scenarios where the last remaining player is not the host. */
    @Nested
    @DisplayName("last non-host player leaving")
    class LastPlayerLeaving {

        /** Builds a room whose recorded host (G1) is no longer a member, leaving only G2. */
        private Room roomWithOnlyNonHostPlayerLeft(boolean terminal) {
            String roomId = roomService.createRoom(validCreateRequest);
            roomService.joinRoom(new JoinRoomRequest("Test Room", "G1", null));
            roomService.joinRoom(new JoinRoomRequest("Test Room", "G2", null));
            roomService.leaveRoom(roomId, "HostPlayer", "G1");
            Room room = roomService.getRoom(roomId);
            room.removePlayer("G1");
            room.setTerminal(terminal);
            assertThat(room.getPlayers()).containsExactly("G2");
            assertThat(roomService.getCurrentHost(roomId)).isEqualTo("G1");
            reset(messagingTemplate);
            return room;
        }

        @Test
        @DisplayName("keeps a terminal room registered, clears its host mapping and broadcasts ROOM_CLOSED")
        void leaveRoom_LastPlayerLeavesTerminalRoom_PreservesRoomContainer() {
            Room room = roomWithOnlyNonHostPlayerLeft(true);
            String roomId = room.getRoomId();

            roomService.leaveRoom(roomId, "G2");

            assertThat(roomService.getRoom(roomId)).isSameAs(room);
            assertThat(room.getPlayers()).isEmpty();
            assertThat(roomService.getCurrentHost(roomId)).isEqualTo("HostPlayer");
            verify(messagingTemplate).convertAndSend(eq("/room/" + roomId),
                    eq(new ApiResponse<>(ResponseMessage.ROOM_CLOSED.getMessage(), null)));
        }

        @Test
        @DisplayName("destroys a non-terminal room and broadcasts ROOM_CLOSED when the last player leaves")
        void leaveRoom_LastPlayerLeavesNonTerminalRoom_DestroysRoom() {
            Room room = roomWithOnlyNonHostPlayerLeft(false);
            String roomId = room.getRoomId();

            roomService.leaveRoom(roomId, "G2");

            assertThat(roomService.getRoom(roomId)).isNull();
            verify(messagingTemplate).convertAndSend(eq("/room/" + roomId),
                    eq(new ApiResponse<>(ResponseMessage.ROOM_CLOSED.getMessage(), null)));
        }
    }

    /** Groups membership lookups and in-memory eviction. */
    @Nested
    @DisplayName("isPlayerInRoom and evictRoom")
    class MembershipAndEviction {

        @Test
        @DisplayName("isPlayerInRoom is true for a known room and member")
        void isPlayerInRoom_KnownRoomAndMember_ReturnsTrue() {
            String roomId = roomService.createRoom(validCreateRequest);

            assertThat(roomService.isPlayerInRoom(roomId, "HostPlayer")).isTrue();
        }

        @Test
        @DisplayName("isPlayerInRoom is false for a known room with an unknown or null player")
        void isPlayerInRoom_KnownRoomNonMember_ReturnsFalse() {
            String roomId = roomService.createRoom(validCreateRequest);

            assertThat(roomService.isPlayerInRoom(roomId, "Stranger")).isFalse();
            assertThat(roomService.isPlayerInRoom(roomId, null)).isFalse();
        }

        @Test
        @DisplayName("isPlayerInRoom is false for an unknown room")
        void isPlayerInRoom_UnknownRoom_ReturnsFalse() {
            roomService.createRoom(validCreateRequest);

            assertThat(roomService.isPlayerInRoom("missing-room", "HostPlayer")).isFalse();
        }

        @Test
        @DisplayName("isPlayerInRoom is false for a null room id")
        void isPlayerInRoom_NullRoomId_ReturnsFalse() {
            assertThat(roomService.isPlayerInRoom(null, "HostPlayer")).isFalse();
        }

        @Test
        @DisplayName("evictRoom ignores a null room id")
        void evictRoom_NullRoomId_IsNoOp() {
            String roomId = roomService.createRoom(validCreateRequest);

            assertThatCode(() -> roomService.evictRoom(null)).doesNotThrowAnyException();

            assertThat(roomService.getRoom(roomId)).isNotNull();
        }

        @Test
        @DisplayName("evictRoom ignores an unknown room id and leaves other rooms registered")
        void evictRoom_UnknownRoomId_LeavesOtherRoomsUntouched() {
            String roomId = roomService.createRoom(validCreateRequest);

            assertThatCode(() -> roomService.evictRoom("missing-room")).doesNotThrowAnyException();

            assertThat(roomService.getRoom(roomId)).isNotNull();
            assertThat(roomService.isRoomHost(roomId, "HostPlayer")).isTrue();
        }

        @Test
        @DisplayName("evictRoom removes a known room and its host mapping without events or broadcasts")
        void evictRoom_KnownRoomId_RemovesRoomSilently() {
            ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
            RoomService serviceWithPublisher = new RoomService(messagingTemplate, publisher, 60_000L);
            String roomId = serviceWithPublisher.createRoom(validCreateRequest);
            reset(messagingTemplate, publisher);

            serviceWithPublisher.evictRoom(roomId);

            assertThat(serviceWithPublisher.getRoom(roomId)).isNull();
            assertThat(serviceWithPublisher.getCurrentHost(roomId)).isNull();
            assertThat(serviceWithPublisher.isPlayerInRoom(roomId, "HostPlayer")).isFalse();
            verify(publisher, never()).publishEvent(any(RoomDestroyed.class));
            verify(messagingTemplate, never()).convertAndSend(anyString(), ArgumentMatchers.any(Object.class));
        }
    }
}
