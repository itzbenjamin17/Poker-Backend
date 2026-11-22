package com.pokergame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pokergame.dto.request.CreateRoomRequest;
import com.pokergame.dto.request.JoinRoomRequest;
import com.pokergame.dto.response.RoomDataResponse;
import com.pokergame.exception.BadRequestException;
import com.pokergame.exception.InvalidRoomPasswordException;
import com.pokergame.exception.ResourceNotFoundException;
import com.pokergame.model.Room;
import com.pokergame.service.event.RoomDestroyed;
import com.pokergame.service.event.RoomMemberRegistered;
import com.pokergame.service.event.RoomMembershipEnded;
import com.pokergame.testutil.MutableClock;
import com.pokergame.testutil.TestTimings;
import com.pokergame.util.LockLease;
import com.pokergame.util.RoomCoordinator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Unit tests for {@link RoomService}. */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
@DisplayName("Room service")
class RoomServiceTest {

    private static final long DISCONNECT_GRACE_MS = 60_000L;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private TaskScheduler taskScheduler;

    private MutableClock clock;
    private PasswordEncoder passwordEncoder;
    private RoomCoordinator roomCoordinator;
    private RoomService roomService;
    /** Stands in for the Spring proxy that the lobby timer uses for the durable close. */
    private RoomService proxiedRoomService;
    private RoomRegistry roomRegistry;
    private CreateRoomRequest validCreateRequest;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(1_900_000_000_000L);
        passwordEncoder = new BCryptPasswordEncoder(4);
        roomCoordinator = new RoomCoordinator();
        roomService = newService(eventPublisher, passwordEncoder, roomCoordinator);
        validCreateRequest = new CreateRoomRequest("Test Room", "HostPlayer", 6, 5, 10, 100, null);
    }

    private RoomService newService(
            ApplicationEventPublisher publisher,
            PasswordEncoder passwordEncoder,
            RoomCoordinator coordinator) {
        roomRegistry = new RoomRegistry(coordinator);
        // A real scheduler returns a future; the timer registry drops registrations that get none.
        lenient().doAnswer(invocation -> mock(ScheduledFuture.class))
                .when(taskScheduler).schedule(any(Runnable.class), any(Instant.class));
        @SuppressWarnings("unchecked")
        ObjectProvider<RoomService> roomServiceProvider = mock(ObjectProvider.class);
        LobbyInactivityScheduler lobbyInactivity = new LobbyInactivityScheduler(
                new GameTimerRegistry(taskScheduler, clock),
                roomRegistry,
                coordinator,
                clock,
                roomServiceProvider);
        RoomService service = new RoomService(
                messagingTemplate,
                publisher,
                passwordEncoder,
                roomRegistry,
                coordinator,
                clock,
                TestTimings.room(DISCONNECT_GRACE_MS),
                lobbyInactivity);
        proxiedRoomService = spy(service);
        lenient().when(roomServiceProvider.getObject()).thenReturn(proxiedRoomService);
        return service;
    }

    private RoomService.EntryResult createRoom(RoomService service, CreateRoomRequest request) {
        String roomId = UUID.randomUUID().toString();
        return withRoomLock(roomId, () -> service.createRoom(roomId, request));
    }

    private RoomService.EntryResult joinByName(RoomService service, JoinRoomRequest request) {
        String roomId = service.resolveRoomIdByName(request.roomName());
        return withRoomLock(roomId, () -> service.joinRoom(roomId, request));
    }

    private <T> T withRoomLock(String roomId, Supplier<T> action) {
        try (LockLease _ = roomCoordinator.acquire(roomId)) {
            return action.get();
        }
    }

    private void withRoomLock(String roomId, Runnable action) {
        try (LockLease _ = roomCoordinator.acquire(roomId)) {
            action.run();
        }
    }

    private static Room coordinatedGetRoom(RoomService service, RoomCoordinator coordinator, String roomId) {
        try (LockLease _ = coordinator.acquire(roomId)) {
            return service.getRoom(roomId);
        }
    }

    private Room roomNamed(String roomName) {
        return roomRegistry.all().stream()
                .filter(room -> room.getRoomName().equalsIgnoreCase(roomName))
                .findFirst()
                .orElse(null);
    }

    private String hostOf(String roomId) {
        Room room = coordinatedGetRoom(roomId);
        return room == null ? null : room.getHostName();
    }

    private Room coordinatedGetRoom(String roomId) {
        return coordinatedGetRoom(roomService, roomCoordinator, roomId);
    }

    /** Groups test scenarios for create room. */
    @Nested
    @DisplayName("room creation")
    class CreateRoom {

        @Test
        void rejectsPasswordAboveUtf8ByteLimitWithoutCreatingRoom() {
            assertThatThrownBy(() -> createRoom(roomService, new CreateRoomRequest(
                    "Unicode Room", "Host", 4, 5, 10, 100, "漢".repeat(25))))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage("Password must be at most 72 UTF-8 bytes.");
            assertThat(roomNamed("Unicode Room")).isNull();
        }

        @Test
        void supportsUtf8BoundaryAndRejectsOverlongJoinWithoutAddingPlayer() {
            String password = " " + "漢".repeat(23) + "ab";
            String roomId = createRoom(roomService, new CreateRoomRequest(
                    "Boundary Room", "Host", 4, 5, 10, 100, password)).roomId();
            joinByName(roomService, new JoinRoomRequest("Boundary Room", "Guest", password));
            assertThatThrownBy(() -> joinByName(roomService, new JoinRoomRequest(
                    "Boundary Room", "Rejected", password + " ")))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage("Password must be at most 72 UTF-8 bytes.");
            assertThat(coordinatedGetRoom(roomId).getPlayers()).containsExactly("Host", "Guest");
        }

        @Test
        @DisplayName("should create a room and add the host as the first player")
        void givenValidRequest_whenCreateRoom_thenReturnPersistedRoomId() {
            RoomService.EntryResult result = createRoom(roomService, validCreateRequest);
            String roomId = result.roomId();

            Room room = coordinatedGetRoom(roomId);

            assertThat(roomId).isNotBlank();
            assertThat(result.canonicalPlayerName()).isEqualTo("HostPlayer");
            assertThat(result.membershipId()).isNotBlank();
            assertThat(room).isNotNull();
            assertThat(room.getRoomName()).isEqualTo("Test Room");
            assertThat(room.getHostName()).isEqualTo("HostPlayer");
            assertThat(room.getPlayers()).containsExactly("HostPlayer");
            verify(messagingTemplate).convertAndSend(eq("/room/" + roomId), ArgumentMatchers.any(Object.class));
        }

        @Test
        @DisplayName("should create a password protected room when a password is supplied")
        void givenPasswordProtectedRoom_whenCreateRoom_thenPasswordIsStoredAndValidated() {
            String roomId = createRoom(roomService, new CreateRoomRequest(
                    "Private Room", "Host", 4, 10, 20, 200, "secret123")).roomId();

            Room room = coordinatedGetRoom(roomId);

            assertThat(room).isNotNull();
            assertThat(room.hasPassword()).isTrue();
            assertThat(room.getPasswordHash()).startsWith("$2");
            assertThat(room.checkPassword("secret123", passwordEncoder)).isTrue();
            assertThat(room.checkPassword("wrongpassword", passwordEncoder)).isFalse();
        }

        @Test
        @DisplayName("should reject duplicate room names regardless of case")
        void givenDuplicateRoomName_whenCreateRoom_thenThrowBadRequestException() {
            createRoom(roomService, validCreateRequest);

            assertThatThrownBy(() -> createRoom(roomService, new CreateRoomRequest(
                    "TEST ROOM", "AnotherHost", 4, 5, 10, 100, null)))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("already taken");
        }

        @Test
        @DisplayName("frees a finished room's name for a new room while the finished room stays readable for its review")
        void givenFinishedRoom_whenCreateRoomWithSameName_thenNewRoomTakesTheName() {
            String finishedRoomId = createRoom(roomService, validCreateRequest).roomId();
            withRoomLock(finishedRoomId, () -> coordinatedGetRoom(finishedRoomId).setFinished(true));

            String newRoomId = createRoom(roomService, new CreateRoomRequest(
                    "TEST ROOM", "AnotherHost", 4, 5, 10, 100, null)).roomId();

            assertThat(newRoomId).isNotEqualTo(finishedRoomId);
            assertThat(coordinatedGetRoom(finishedRoomId)).isNotNull();
            assertThat(roomService.resolveRoomIdByName("Test Room")).isEqualTo(newRoomId);
        }

        @Test
        @DisplayName("should trim room and player names during creation")
        void createRoom_TrimsNames() {
            String roomId = createRoom(roomService, new CreateRoomRequest(
                    "  Untrimmed Room  ", "  Untrimmed Player  ", 6, 10, 20, 1000, null)).roomId();

            Room room = coordinatedGetRoom(roomId);
            assertThat(room.getRoomName()).isEqualTo("Untrimmed Room");
            assertThat(room.getPlayers()).containsExactly("Untrimmed Player");
        }

        @Test
        @DisplayName("should reject format characters in room name during creation")
        void createRoom_RejectsFormatCharactersInRoomName() {
            assertThatThrownBy(() -> createRoom(roomService, new CreateRoomRequest(
                    "Room\u200BName", "HostPlayer", 6, 5, 10, 100, null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("format characters");
        }

        @Test
        @DisplayName("should reject format characters in player name during creation")
        void createRoom_RejectsFormatCharactersInPlayerName() {
            assertThatThrownBy(() -> createRoom(roomService, new CreateRoomRequest(
                    "ValidRoom", "Host\u200DPlayer", 6, 5, 10, 100, null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("format characters");
        }

        @Test
        @DisplayName("should treat whitespace-only password as absent on creation")
        void createRoom_WhitespaceOnlyPasswordTreatedAsAbsent() {
            String roomId = createRoom(roomService, new CreateRoomRequest(
                    "Public Room", "Host", 4, 10, 20, 200, "   ")).roomId();
            Room room = coordinatedGetRoom(roomId);
            assertThat(room.hasPassword()).isFalse();
            assertThat(room.getPasswordHash()).isNull();

            joinByName(roomService, new JoinRoomRequest("Public Room", "NewPlayer", null));
            assertThat(room.getPlayers()).contains("NewPlayer");
        }

        @Test
        @DisplayName("should preserve leading and trailing spaces in password")
        void createRoom_PreservesLeadingAndTrailingSpacesInPassword() {
            String roomId = createRoom(roomService, new CreateRoomRequest(
                    "Spaced Pass Room", "Host", 4, 10, 20, 200, "  secret123  ")).roomId();
            Room room = coordinatedGetRoom(roomId);
            assertThat(room.hasPassword()).isTrue();
            assertThat(room.getPasswordHash()).startsWith("$2");
            assertThat(room.checkPassword("  secret123  ", passwordEncoder)).isTrue();
            assertThat(room.checkPassword("secret123", passwordEncoder)).isFalse();

            joinByName(roomService, new JoinRoomRequest("Spaced Pass Room", "NewPlayer", "  secret123  "));
            assertThat(room.getPlayers()).contains("NewPlayer");

            assertThatThrownBy(() -> joinByName(roomService,
                    new JoinRoomRequest("Spaced Pass Room", "AnotherPlayer", "secret123")))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage("Invalid password");
        }

        @Test
        @DisplayName("should reject big blind smaller than twice small blind with BadRequestException")
        void createRoom_RejectsBigBlindBelowTwiceSmallBlind() {
            CreateRoomRequest request = new CreateRoomRequest("Test Room", "Alice", 6, 10, 15, 1000, null);

            assertThatThrownBy(() -> createRoom(roomService, request))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage("Big blind must be at least twice the small blind.");
        }

        @Test
        @DisplayName("should reject buy-in smaller than big blind with BadRequestException")
        void createRoom_RejectsBuyInBelowBigBlind() {
            CreateRoomRequest request = new CreateRoomRequest("Test Room", "Alice", 6, 10, 50, 30, null);

            assertThatThrownBy(() -> createRoom(roomService, request))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage("Buy-in must be at least the big blind amount.");
        }
    }

    /** Groups test scenarios for join room. */
    @Nested
    @DisplayName("joining rooms")
    class JoinRoom {

        @Test
        @DisplayName("should allow resolving and joining a room with leading/trailing whitespace in the search name")
        void joinRoom_TrimsSearchName() {
            String roomId = createRoom(roomService, validCreateRequest).roomId();

            String joinedRoomId = joinByName(roomService,
                    new JoinRoomRequest("  Test Room  ", "Player2", null)).roomId();

            assertThat(joinedRoomId).isEqualTo(roomId);
            assertThat(coordinatedGetRoom(roomId).getPlayers()).contains("Player2");
        }

        @Test
        @DisplayName("should trim player name when joining")
        void joinRoom_TrimsPlayerName() {
            createRoom(roomService, validCreateRequest);

            joinByName(roomService, new JoinRoomRequest("Test Room", "  P2  ", null));

            assertThat(roomNamed("Test Room").getPlayers()).contains("P2");
        }

        @Test
        @DisplayName("should add a second player to an existing public room")
        void givenJoinRequestForPublicRoom_whenJoinRoom_thenPlayerIsAdded() {
            String roomId = createRoom(roomService, validCreateRequest).roomId();
            reset(messagingTemplate);

            joinByName(roomService, new JoinRoomRequest("Test Room", "NewPlayer", null));

            assertThat(coordinatedGetRoom(roomId).getPlayers()).containsExactly("HostPlayer", "NewPlayer");
            verify(messagingTemplate).convertAndSend(eq("/room/" + roomId), ArgumentMatchers.any(Object.class));
        }

        @Test
        @DisplayName("should allow joining a private room with the correct password")
        void givenCorrectPassword_whenJoinRoom_thenJoinSucceeds() {
            String roomId = createRoom(roomService, new CreateRoomRequest(
                    "Private Room", "Host", 4, 5, 10, 100, "password123")).roomId();

            joinByName(roomService, new JoinRoomRequest("Private Room", "NewPlayer", "password123"));

            assertThat(coordinatedGetRoom(roomId).getPlayers()).containsExactly("Host", "NewPlayer");
        }

        @Test
        @DisplayName("should reject joins with the wrong password")
        void givenWrongPassword_whenJoinRoom_thenThrowBadRequestException() {
            createRoom(roomService, new CreateRoomRequest(
                    "Private Room", "Host", 4, 5, 10, 100, "password123"));

            assertThatThrownBy(() -> joinByName(roomService,
                    new JoinRoomRequest("Private Room", "NewPlayer", "wrongpassword")))
                    .isInstanceOf(InvalidRoomPasswordException.class)
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage("Invalid password");
        }

        @Test
        @DisplayName("should reject format characters in room name during lookup")
        void resolveRoomIdByName_RejectsFormatCharactersInRoomName() {
            createRoom(roomService, validCreateRequest);
            assertThatThrownBy(() -> roomService.resolveRoomIdByName("Test\u200BRoom"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("format characters");
        }

        @Test
        @DisplayName("should reject format characters in player name during join")
        void joinRoom_RejectsFormatCharactersInPlayerName() {
            String roomId = createRoom(roomService, validCreateRequest).roomId();
            assertThatThrownBy(() -> withRoomLock(roomId, () -> roomService.joinRoom(roomId,
                    new JoinRoomRequest("Test Room", "Player\u200E2", null))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("format characters");
        }

        @Test
        @DisplayName("should fail join with whitespace-only password when room has password")
        void joinRoom_WhitespaceOnlyPasswordFailsWhenRoomHasPassword() {
            createRoom(roomService, new CreateRoomRequest(
                    "Protected Room", "Host", 4, 10, 20, 200, "swordfish"));

            assertThatThrownBy(() -> joinByName(roomService,
                    new JoinRoomRequest("Protected Room", "NewPlayer", "   ")))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage("Invalid password");
        }

        @Test
        @DisplayName("should reject joins for missing rooms, full rooms, and duplicate player names")
        void givenInvalidJoinScenarios_whenJoinRoom_thenThrowMeaningfulExceptions() {
            String testRoomId = createRoom(roomService, validCreateRequest).roomId();
            String smallRoomId = createRoom(roomService,
                    new CreateRoomRequest("Small Room", "Host", 2, 5, 10, 100, null)).roomId();
            withRoomLock(smallRoomId, () ->
                    roomService.joinRoom(smallRoomId, new JoinRoomRequest("Small Room", "Player2", null)));

            assertThatThrownBy(() -> roomService.resolveRoomIdByName("Missing Room"))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessage("Room not found");

            assertThatThrownBy(() -> withRoomLock("missing-id", () -> roomService.joinRoom("missing-id",
                    new JoinRoomRequest("Missing Room", "Player", null))))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessage("Room not found");

            assertThatThrownBy(() -> withRoomLock(smallRoomId, () -> roomService.joinRoom(smallRoomId,
                    new JoinRoomRequest("Small Room", "Player3", null))))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage("Room is full");

            assertThatThrownBy(() -> withRoomLock(testRoomId, () -> roomService.joinRoom(testRoomId,
                    new JoinRoomRequest("Test Room", "HostPlayer", null))))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage("Player name already taken");
        }

        @Test
        @DisplayName("should reject a player name that differs from a seated player only by case")
        void givenNameDifferingOnlyByCase_whenJoinRoom_thenThrowNameTaken() {
            String testRoomId = createRoom(roomService, validCreateRequest).roomId();

            assertThatThrownBy(() -> withRoomLock(testRoomId, () -> roomService.joinRoom(testRoomId,
                    new JoinRoomRequest("Test Room", "hostplayer", null))))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage("Player name already taken");
        }

        @Test
        @DisplayName("should reject join with BadRequestException when room is full")
        void joinRoom_WhenRoomFull_ThrowsBadRequestException() {
            String roomId = createRoom(roomService,
                    new CreateRoomRequest("Full Capacity Room", "Host", 2, 5, 10, 100, null)).roomId();
            withRoomLock(roomId, () ->
                    roomService.joinRoom(roomId, new JoinRoomRequest("Full Capacity Room", "Player2", null)));

            assertThatThrownBy(() -> joinByName(roomService,
                    new JoinRoomRequest("Full Capacity Room", "Player3", null)))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage("Room is full");

            assertThatThrownBy(() -> withRoomLock(roomId, () -> roomService.joinRoom(roomId,
                    new JoinRoomRequest("Full Capacity Room", "Player3", null))))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage("Room is full");

            assertThat(coordinatedGetRoom(roomId).getPlayers()).containsExactly("Host", "Player2");
        }
    }

    /** Groups test scenarios for leave room and batch departure. */
    @Nested
    @DisplayName("leaving rooms")
    class LeaveRoom {

        @Test
        @DisplayName("should destroy the room when the host leaves the lobby")
        void givenHostLeavesLobby_whenLeaveRoom_thenRoomIsDestroyed() {
            String roomId = createRoom(roomService, validCreateRequest).roomId();

            withRoomLock(roomId, () -> roomService.leaveRoom(roomId, "HostPlayer"));

            assertThat(coordinatedGetRoom(roomId)).isNull();
            verify(messagingTemplate, times(2))
                    .convertAndSend(eq("/room/" + roomId), ArgumentMatchers.any(Object.class));
        }

        @Test
        @DisplayName("should transfer the host when the host leaves in removeMembers")
        void givenHostLeavesActiveRoom_whenRemoveMembers_thenHostTransfersToSuccessor() {
            String roomId = createRoom(roomService, validCreateRequest).roomId();
            withRoomLock(roomId, () -> {
                roomService.joinRoom(roomId, new JoinRoomRequest("Test Room", "Player2", null));
                roomService.joinRoom(roomId, new JoinRoomRequest("Test Room", "Player3", null));
                reset(messagingTemplate);
                roomService.removeMembers(roomId, List.of("HostPlayer"), "Player2");
            });

            Room room = coordinatedGetRoom(roomId);
            RoomDataResponse roomData = withRoomLock(roomId, () -> roomService.getRoomData(roomId));

            assertThat(room).isNotNull();
            assertThat(room.getPlayers()).containsExactly("Player2", "Player3");
            withRoomLock(roomId, () -> {
                assertThat(roomService.isRoomHost(roomId, "Player2")).isTrue();
                assertThat(roomService.isRoomHost(roomId, "Player3")).isFalse();
            });
            assertThat(roomData.players())
                    .filteredOn(player -> player.name().equals("Player2"))
                    .singleElement()
                    .extracting(player -> player.isHost())
                    .isEqualTo(true);
        }

        @Test
        @DisplayName("keeps a finished room registered and broadcasts closed RoomDataResponse when the last members leave")
        void givenFinishedRoom_whenLastMembersRemoved_thenRoomContainerIsPreserved() {
            String roomId = createRoom(roomService, validCreateRequest).roomId();
            withRoomLock(roomId, () -> {
                roomService.joinRoom(roomId, new JoinRoomRequest("Test Room", "G1", null));
                coordinatedGetRoom(roomId).setFinished(true);
                reset(messagingTemplate);
                roomService.removeMembers(roomId, List.of("HostPlayer", "G1"), null);
            });

            Room room = coordinatedGetRoom(roomId);
            assertThat(room).isNotNull();
            assertThat(room.getPlayers()).isEmpty();
            ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
            verify(messagingTemplate).convertAndSend(eq("/room/" + roomId), captor.capture());
            assertThat(captor.getValue()).isInstanceOfSatisfying(RoomDataResponse.class, data -> {
                assertThat(data.closedReason()).isEqualTo("HOST_LEFT");
                assertThat(data.players()).isEmpty();
            });
        }

        @Test
        @DisplayName("destroys an unfinished room and broadcasts closed RoomDataResponse when the last members leave")
        void givenUnfinishedRoom_whenLastMembersRemoved_thenRoomIsDestroyed() {
            String roomId = createRoom(roomService, validCreateRequest).roomId();
            withRoomLock(roomId, () -> {
                roomService.joinRoom(roomId, new JoinRoomRequest("Test Room", "G1", null));
                reset(messagingTemplate);
                roomService.removeMembers(roomId, List.of("HostPlayer", "G1"), null);
            });

            assertThat(coordinatedGetRoom(roomId)).isNull();
            ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
            verify(messagingTemplate).convertAndSend(eq("/room/" + roomId), captor.capture());
            assertThat(captor.getValue()).isInstanceOfSatisfying(RoomDataResponse.class, data -> {
                assertThat(data.closedReason()).isEqualTo("HOST_LEFT");
                assertThat(data.players()).isEmpty();
            });
        }

        @Test
        @DisplayName("should reject leave requests for missing rooms")
        void givenMissingRoom_whenLeaveRoom_thenThrowResourceNotFoundException() {
            assertThatThrownBy(() -> withRoomLock("missing-room", () -> roomService.leaveRoom("missing-room", "Player")))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessage("Room not found");
        }
    }

    /** Groups test scenarios for room queries and locking. */
    @Nested
    @DisplayName("room queries")
    class RoomQueries {

        @Test
        @DisplayName("non-durable RoomService methods enforce that callers already hold the room lock")
        void givenUncoordinatedCalls_whenCallingNonDurableMethods_thenRequireHeldRoomLock() {
            String roomId = createRoom(roomService, validCreateRequest).roomId();
            Room room = coordinatedGetRoom(roomId);

            assertThatThrownBy(() -> roomService.getRoomData(roomId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Current thread does not hold room lock");
            assertThatThrownBy(() -> roomService.isRoomHost(roomId, "HostPlayer"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Current thread does not hold room lock");
            assertThatThrownBy(() -> roomService.getRoom(roomId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Current thread does not hold room lock");
            assertThatThrownBy(() -> roomService.closeLobbyIfExpired(roomId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Current thread does not hold room lock");
            assertThatThrownBy(() -> roomService.isLobbyExpired(room))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Current thread does not hold room lock");
        }

        @Test
        @DisplayName("getRoomData waits for the room lock when another caller holds it")
        void givenRoomDataRequest_whenAnotherCallerHoldsTheRoomLock_thenWaitsForRelease() throws Exception {
            String roomId = createRoom(roomService, validCreateRequest).roomId();
            withRoomLock(roomId, () ->
                    roomService.joinRoom(roomId, new JoinRoomRequest("Test Room", "Player2", null)));

            CountDownLatch lockHeld = new CountDownLatch(1);
            CountDownLatch releaseLock = new CountDownLatch(1);
            AtomicBoolean getRoomDataFinished = new AtomicBoolean(false);

            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                Future<?> holder = executor.submit(() -> {
                    try (LockLease _ = roomCoordinator.acquire(roomId)) {
                        lockHeld.countDown();
                        return releaseLock.await(5, TimeUnit.SECONDS);
                    }
                });
                assertTrue(lockHeld.await(2, TimeUnit.SECONDS));

                Future<RoomDataResponse> reader = executor.submit(() -> withRoomLock(roomId, () -> {
                    RoomDataResponse response = roomService.getRoomData(roomId);
                    getRoomDataFinished.set(true);
                    return response;
                }));

                try {
                    Awaitility.await().atMost(2, TimeUnit.SECONDS)
                            .until(() -> roomCoordinator.lockReservationCount(roomId) >= 2);
                    assertFalse(getRoomDataFinished.get(),
                            "coordinated reader must wait while the room lock is held");

                    releaseLock.countDown();
                    RoomDataResponse response = reader.get(2, TimeUnit.SECONDS);
                    holder.get(2, TimeUnit.SECONDS);
                    assertTrue(getRoomDataFinished.get());
                    assertThat(response.players()).hasSize(2);
                } finally {
                    releaseLock.countDown();
                }
            } finally {
                executor.shutdownNow();
            }
        }

        @Test
        @DisplayName("should expose room data including host flags and start eligibility")
        void givenRoomDataRequest_whenGetRoomData_thenReturnProjectedLobbyState() {
            String roomId = createRoom(roomService, validCreateRequest).roomId();
            withRoomLock(roomId, () ->
                    roomService.joinRoom(roomId, new JoinRoomRequest("Test Room", "Player2", null)));

            RoomDataResponse roomData = withRoomLock(roomId, () -> roomService.getRoomData(roomId));

            assertThat(roomData.roomId()).isEqualTo(roomId);
            assertThat(roomData.roomName()).isEqualTo("Test Room");
            assertThat(roomData.canStartGame()).isTrue();
            assertThat(roomData.gameStarted()).isFalse();
            assertThat(roomData.closedReason()).isNull();
            assertThat(roomData.players())
                    .extracting(player -> player.name() + ":" + player.isHost())
                    .containsExactly("HostPlayer:true", "Player2:false");
        }

        @Test
        @DisplayName("should set canStartGame to false once the game has started even if player count >= 2")
        void givenGameStarted_whenGetRoomData_thenCanStartGameIsFalse() {
            String roomId = createRoom(roomService, validCreateRequest).roomId();
            withRoomLock(roomId, () -> {
                roomService.joinRoom(roomId, new JoinRoomRequest("Test Room", "Player2", null));
                roomService.getRoom(roomId).setGameStarted(true);
            });

            RoomDataResponse roomData = withRoomLock(roomId, () -> roomService.getRoomData(roomId));

            assertThat(roomData.gameStarted()).isTrue();
            assertThat(roomData.canStartGame()).isFalse();
        }

        @Test
        @DisplayName("should validate room identifiers when retrieving room data or rooms")
        void givenInvalidRoomIdentifiers_whenGetRoomData_thenThrowMeaningfulExceptions() {
            assertThatThrownBy(() -> roomService.getRoomData(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("roomId must not be null");
            assertThatThrownBy(() -> roomService.getRoom(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("roomId must not be null");

            assertThatThrownBy(() -> withRoomLock("missing-room", () -> roomService.getRoomData("missing-room")))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessage("Room not found");
        }

        @Test
        @DisplayName("getRoomData throws ResourceNotFoundException for expired lobby without closing or evicting it")
        void givenExpiredLobby_whenGetRoomData_thenThrowNotFoundWithoutSideEffects() {
            String roomName = "ExpiredReadRoom";
            String roomId = createRoom(roomService, new CreateRoomRequest(
                    roomName, "HostPlayer", 6, 5, 10, 100, null)).roomId();
            Long deadline = coordinatedGetRoom(roomId).getInactivityDeadlineEpochMs();
            clock.setMillis(deadline + 5_000L);

            assertThatThrownBy(() -> withRoomLock(roomId, () -> roomService.getRoomData(roomId)))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessage("Room not found");

            Room roomAfterRead = coordinatedGetRoom(roomId);
            assertThat(roomAfterRead).isNotNull();
            assertThat(roomAfterRead.isFinished()).isFalse();
            assertThat(roomNamed(roomName)).isNotNull();
        }

        @Test
        @DisplayName("should return rooms, hosts, and lookups consistently")
        void givenRoomsExist_whenQuerying_thenReturnExpectedResults() {
            String roomId = createRoom(roomService, validCreateRequest).roomId();
            String room2Id = createRoom(roomService, new CreateRoomRequest("Room 2", "Host2", 4, 10, 20, 200, null)).roomId();

            assertThat(roomService.resolveRoomIdByName("Test Room")).isEqualTo(roomId);
            assertThat(roomService.resolveRoomIdByName("Room 2")).isEqualTo(room2Id);
            assertThat(coordinatedGetRoom(roomId)).isNotNull();
            assertThat(coordinatedGetRoom("missing-room")).isNull();
            assertThat(roomService.resolveRoomIdByName("test room")).isEqualTo(roomId);
            assertThatThrownBy(() -> roomService.resolveRoomIdByName("missing-room"))
                    .isInstanceOf(ResourceNotFoundException.class);
            withRoomLock(roomId, () -> {
                assertThat(roomService.isRoomHost(roomId, "HostPlayer")).isTrue();
                assertThat(roomService.isRoomHost(roomId, "Player2")).isFalse();
            });
        }

        @Test
        @DisplayName("should destroy rooms safely even when the room does not exist")
        void givenDestroyRoomRequest_whenDestroyRoom_thenRemoveRoomWithoutThrowing() {
            String roomId = createRoom(roomService, validCreateRequest).roomId();

            withRoomLock(roomId, () -> roomService.destroyRoom(roomId));

            assertThat(coordinatedGetRoom(roomId)).isNull();
            assertThat(withRoomLock(roomId, () -> roomService.isRoomHost(roomId, "HostPlayer"))).isFalse();
            assertThatCode(() -> withRoomLock("missing-room", () -> roomService.destroyRoom("missing-room")))
                    .doesNotThrowAnyException();
        }
    }

    /** Groups high-contention concurrency, capacity, and room-isolation scenarios. */
    @Nested
    @DisplayName("concurrency and coordination")
    class ConcurrencyAndCoordination {

        @Test
        @DisplayName("concurrent duplicate creates admit one room and reject the rest with already taken")
        void concurrentSameNameRoomCreation_oneSucceedsOtherRejected() throws Exception {
            int threads = 8;
            String roomName = "ContendedSameNameRoom";
            CyclicBarrier barrier = new CyclicBarrier(threads);
            AtomicInteger successCount = new AtomicInteger();
            AtomicInteger rejectedCount = new AtomicInteger();
            AtomicReference<String> createdRoomId = new AtomicReference<>();

            ExecutorService executor = Executors.newFixedThreadPool(threads);
            try {
                List<Future<?>> futures = new ArrayList<>();
                for (int i = 0; i < threads; i++) {
                    final String hostName = "Host_" + i;
                    futures.add(executor.submit(() -> {
                        barrier.await(5, TimeUnit.SECONDS);
                        try {
                            String roomId = createRoom(roomService, new CreateRoomRequest(
                                    roomName, hostName, 6, 5, 10, 100, null)).roomId();
                            successCount.incrementAndGet();
                            createdRoomId.set(roomId);
                        } catch (BadRequestException expected) {
                            assertThat(expected.getMessage()).contains("already taken");
                            rejectedCount.incrementAndGet();
                        }
                        return null;
                    }));
                }
                for (Future<?> future : futures) {
                    future.get(10, TimeUnit.SECONDS);
                }
            } finally {
                executor.shutdownNow();
            }

            assertThat(successCount.get()).isEqualTo(1);
            assertThat(rejectedCount.get()).isEqualTo(threads - 1);
            String winnerRoomId = createdRoomId.get();
            assertThat(winnerRoomId).isNotNull();
            Room room = coordinatedGetRoom(winnerRoomId);
            assertThat(room).isNotNull();
            assertThat(room.getRoomName()).isEqualTo(roomName);
            assertThat(roomNamed(roomName)).isSameAs(room);
            assertThat(room.getPlayers()).hasSize(1);
        }

        @Test
        @DisplayName("high-contention join storm (8 threads, capacity 3) admits exactly 2 joiners")
        void joinRoom_highContentionJoinStorm_strictlyAdmitsMaxPlayers() throws Exception {
            int maxCapacity = 3;
            String roomName = "Contention Storm Room";
            String roomId = createRoom(roomService, new CreateRoomRequest(
                    roomName, "HostUser", maxCapacity, 5, 10, 100, null)).roomId();

            int totalThreads = 8;
            CyclicBarrier startBarrier = new CyclicBarrier(totalThreads);
            AtomicInteger successCount = new AtomicInteger();
            AtomicInteger roomFullCount = new AtomicInteger();
            Queue<Throwable> unexpectedErrors = new ConcurrentLinkedQueue<>();

            ExecutorService executor = Executors.newFixedThreadPool(totalThreads);
            try {
                List<Future<?>> futures = new ArrayList<>();
                for (int i = 1; i <= totalThreads; i++) {
                    final int index = i;
                    final String playerName = "Contender_" + index;
                    futures.add(executor.submit(() -> {
                        try {
                            startBarrier.await(5, TimeUnit.SECONDS);
                            if (index % 2 == 0) {
                                joinByName(roomService, new JoinRoomRequest(roomName, playerName, null));
                            } else {
                                withRoomLock(roomId, () ->
                                        roomService.joinRoom(roomId, new JoinRoomRequest(roomName, playerName, null)));
                            }
                            successCount.incrementAndGet();
                        } catch (BadRequestException e) {
                            if ("Room is full".equals(e.getMessage())) {
                                roomFullCount.incrementAndGet();
                            } else {
                                unexpectedErrors.add(e);
                            }
                        } catch (Throwable t) {
                            unexpectedErrors.add(t);
                        }
                    }));
                }
                for (Future<?> future : futures) {
                    future.get(10, TimeUnit.SECONDS);
                }
            } finally {
                executor.shutdownNow();
            }

            assertThat(unexpectedErrors).isEmpty();
            assertEquals(maxCapacity - 1, successCount.get());
            assertEquals(totalThreads - (maxCapacity - 1), roomFullCount.get());

            Room room = coordinatedGetRoom(roomId);
            assertNotNull(room);
            assertEquals(maxCapacity, room.getPlayers().size());
            assertTrue(room.isFull());
        }

        @Test
        @DisplayName("concurrent join storm with duplicate and unique names preserves capacity invariant")
        void joinRoom_mixedCollisionsAndJoins_enforcesExactCapacityAndErrorTypes() throws Exception {
            int maxCapacity = 4;
            String roomName = "Collision Storm Room";
            String roomId = createRoom(roomService, new CreateRoomRequest(
                    roomName, "HostUser", maxCapacity, 5, 10, 100, null)).roomId();

            int totalThreads = 8;
            CyclicBarrier startBarrier = new CyclicBarrier(totalThreads);
            AtomicInteger successCount = new AtomicInteger();
            AtomicInteger duplicateSuccessCount = new AtomicInteger();
            AtomicInteger roomFullCount = new AtomicInteger();
            AtomicInteger nameTakenCount = new AtomicInteger();
            Queue<Throwable> unexpectedErrors = new ConcurrentLinkedQueue<>();

            ExecutorService executor = Executors.newFixedThreadPool(totalThreads);
            try {
                List<Future<?>> futures = new ArrayList<>();
                for (int i = 1; i <= totalThreads; i++) {
                    final boolean isDuplicateCandidate = (i % 2 == 0);
                    final String playerName = isDuplicateCandidate ? "ClashPlayer" : "UniquePlayer_" + i;
                    futures.add(executor.submit(() -> {
                        try {
                            startBarrier.await(5, TimeUnit.SECONDS);
                            withRoomLock(roomId, () ->
                                    roomService.joinRoom(roomId, new JoinRoomRequest(roomName, playerName, null)));
                            successCount.incrementAndGet();
                            if (isDuplicateCandidate) {
                                duplicateSuccessCount.incrementAndGet();
                            }
                        } catch (BadRequestException e) {
                            if ("Room is full".equals(e.getMessage())) {
                                roomFullCount.incrementAndGet();
                            } else if ("Player name already taken".equals(e.getMessage())) {
                                nameTakenCount.incrementAndGet();
                            } else {
                                unexpectedErrors.add(e);
                            }
                        } catch (Throwable t) {
                            unexpectedErrors.add(t);
                        }
                    }));
                }
                for (Future<?> future : futures) {
                    future.get(10, TimeUnit.SECONDS);
                }
            } finally {
                executor.shutdownNow();
            }

            assertThat(unexpectedErrors).isEmpty();
            assertEquals(maxCapacity - 1, successCount.get());
            assertTrue(duplicateSuccessCount.get() <= 1);
            assertEquals(totalThreads - (maxCapacity - 1), roomFullCount.get() + nameTakenCount.get());

            Room room = coordinatedGetRoom(roomId);
            assertNotNull(room);
            assertEquals(maxCapacity, room.getPlayers().size());
            assertTrue(room.isFull());
        }

        @Test
        @DisplayName("room independence: a held room lock on room A does not delay joins or reads on room B")
        void roomIndependenceBlockedRoomADoesNotDelayRoomB() throws Exception {
            String idA = createRoom(roomService, new CreateRoomRequest(
                    "IndependenceRoomA", "HostA", 4, 5, 10, 100, null)).roomId();
            String idB = createRoom(roomService, new CreateRoomRequest(
                    "IndependenceRoomB", "HostB", 4, 5, 10, 100, null)).roomId();

            CountDownLatch roomAHeld = new CountDownLatch(1);
            CountDownLatch releaseRoomA = new CountDownLatch(1);
            AtomicBoolean roomBCompleted = new AtomicBoolean(false);

            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                Future<Void> taskA = executor.submit(() -> {
                    try (LockLease _ = roomCoordinator.acquire(idA)) {
                        roomAHeld.countDown();
                        releaseRoomA.await(5, TimeUnit.SECONDS);
                    }
                    return null;
                });

                assertTrue(roomAHeld.await(2, TimeUnit.SECONDS));

                Future<RoomDataResponse> taskB = executor.submit(() -> withRoomLock(idB, () -> {
                    roomService.joinRoom(idB, new JoinRoomRequest("IndependenceRoomB", "GuestB", null));
                    RoomDataResponse response = roomService.getRoomData(idB);
                    roomBCompleted.set(true);
                    return response;
                }));

                try {
                    RoomDataResponse dataB = taskB.get(2, TimeUnit.SECONDS);
                    assertThat(dataB).isNotNull();
                    assertThat(dataB.players()).hasSize(2);
                    assertThat(roomBCompleted.get()).isTrue();
                    assertThat(taskA.isDone()).isFalse();
                } finally {
                    releaseRoomA.countDown();
                }
                taskA.get(2, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
            }
        }
    }

    /** Groups lobby expiration and cleanup lifecycle checks. */
    @Nested
    @DisplayName("expiration and cleanup")
    class ExpirationAndCleanup {

        @Test
        @DisplayName("pure lookup and joinRoom expiry check reject without closing room")
        void pureLookupDoesNotCloseExpiredRoomWithoutExplicitExpirationCoordination() {
            String roomName = "ExpiryLookupRoom";
            String roomId = createRoom(roomService, new CreateRoomRequest(
                    roomName, "HostPlayer", 6, 5, 10, 100, null)).roomId();

            Room roomBefore = coordinatedGetRoom(roomId);
            assertThat(roomBefore).isNotNull();
            Long deadline = roomBefore.getInactivityDeadlineEpochMs();
            assertThat(deadline).isNotNull();

            clock.setMillis(deadline + 10_000L);
            assertThat(withRoomLock(roomId, () -> roomService.isLobbyExpired(roomBefore))).isTrue();

            Room roomLookedUp = coordinatedGetRoom(roomId);
            assertThat(roomLookedUp).isNotNull();
            assertThat(roomLookedUp.isFinished()).isFalse();
            assertThat(roomNamed(roomName)).isNotNull();

            assertThatThrownBy(() -> withRoomLock(roomId, () -> roomService.joinRoom(roomId,
                    new JoinRoomRequest(roomName, "LateJoiner", null))))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("expired due to inactivity");

            assertThat(coordinatedGetRoom(roomId)).isNotNull();
            assertThat(roomNamed(roomName)).isNotNull();
        }

        @Test
        @DisplayName("explicit closeLobbyIfExpired closes expired lobby under RoomCoordinator")
        void explicitExpirationCoordinatesUnderRoomCoordinatorAndClosesExpiredLobby() {
            String roomId = createRoom(roomService, new CreateRoomRequest(
                    "ExplicitCloseRoom", "HostPlayer", 6, 5, 10, 100, null)).roomId();

            Long deadline = coordinatedGetRoom(roomId).getInactivityDeadlineEpochMs();
            assertThat(deadline).isNotNull();

            assertThat(withRoomLock(roomId, () -> roomService.closeLobbyIfExpired(roomId))).isFalse();
            assertThat(coordinatedGetRoom(roomId)).isNotNull();

            clock.setMillis(deadline + 5_000L);

            assertThat(withRoomLock(roomId, () -> roomService.closeLobbyIfExpired(roomId))).isTrue();
            assertThat(coordinatedGetRoom(roomId)).isNull();
            assertThat(hostOf(roomId)).isNull();
            assertThat(roomNamed("ExplicitCloseRoom")).isNull();
        }

        @Test
        @DisplayName("cleanup releases registrations and locks and allows room name reuse")
        void cleanupWithoutStaleIndexEntriesAllowsNameRecreationAndReleasesLocks() {
            String roomName = "RecyclableRoom";
            String roomId1 = createRoom(roomService, new CreateRoomRequest(
                    roomName, "Host1", 4, 5, 10, 100, null)).roomId();
            withRoomLock(roomId1, () -> {
                roomService.joinRoom(roomId1, new JoinRoomRequest(roomName, "Guest1", null));
                assertThat(hostOf(roomId1)).isEqualTo("Host1");
                roomService.destroyRoom(roomId1);
                assertThat(hostOf(roomId1)).isNull();
            });

            assertThat(coordinatedGetRoom(roomId1)).isNull();
            assertThat(roomNamed(roomName)).isNull();
            assertThat(roomCoordinator.lockReservationCount(roomId1)).isZero();

            String roomId2 = createRoom(roomService, new CreateRoomRequest(
                    roomName, "Host2", 4, 5, 10, 100, null)).roomId();
            assertThat(roomId2).isNotEqualTo(roomId1);
            assertThat(withRoomLock(roomId2, () -> hostOf(roomId2))).isEqualTo("Host2");

            withRoomLock(roomId2, () -> roomService.destroyRoom(roomId2));
            assertThat(roomCoordinator.lockReservationCount(roomId2)).isZero();
            assertThat(roomCoordinator.activeLockCount()).isZero();
        }
    }

    @Test
    @DisplayName("createRoom establishes first connection allowance with first-connect deadline")
    void createRoom_EstablishesFirstConnectionRegistrationWithFirstConnectDeadline() {
        String roomId = createRoom(roomService, validCreateRequest).roomId();
        Room createdRoom = coordinatedGetRoom(roomId);

        assertNotNull(createdRoom.getAbsenceDeadline("HostPlayer"));
        String membershipId = createdRoom.getMembershipId("HostPlayer");
        assertNotNull(membershipId);

        verify(eventPublisher).publishEvent(new RoomMemberRegistered(
                roomId, "HostPlayer", membershipId, createdRoom.getAbsenceDeadline("HostPlayer")));
    }

    @Test
    @DisplayName("joinRoom establishes first connection allowance with first-connect deadline")
    void joinRoom_EstablishesFirstConnectionRegistrationWithFirstConnectDeadline() {
        String roomId = createRoom(roomService, validCreateRequest).roomId();
        withRoomLock(roomId, () ->
                roomService.joinRoom(roomId, new JoinRoomRequest("Test Room", "Player2", null)));

        Room room = coordinatedGetRoom(roomId);
        assertNotNull(room.getAbsenceDeadline("Player2"));
        String p2MembershipId = room.getMembershipId("Player2");
        assertNotNull(p2MembershipId);

        verify(eventPublisher).publishEvent(new RoomMemberRegistered(
                roomId, "Player2", p2MembershipId, room.getAbsenceDeadline("Player2")));
    }

    @Test
    @DisplayName("removeMembers publishes membership-ended event for exactly the leaving member's membership")
    void removeMembers_PublishesMembershipEnded() {
        String roomId = createRoom(roomService, validCreateRequest).roomId();
        withRoomLock(roomId, () ->
                roomService.joinRoom(roomId, new JoinRoomRequest("Test Room", "Player2", null)));
        String p2MembershipId = coordinatedGetRoom(roomId).getMembershipId("Player2");

        withRoomLock(roomId, () -> roomService.removeMembers(roomId, List.of("Player2"), "HostPlayer"));

        verify(eventPublisher).publishEvent(new RoomMembershipEnded(roomId, "Player2", p2MembershipId));
    }

    @Test
    @DisplayName("destroyRoom publishes room destruction")
    void destroyRoom_PublishesRoomDestroyed() {
        String roomId = createRoom(roomService, validCreateRequest).roomId();
        withRoomLock(roomId, () -> roomService.destroyRoom(roomId));

        verify(eventPublisher).publishEvent(new RoomDestroyed(roomId));
    }

    @Test
    @DisplayName("destroyRoom on a missing room does not publish room destruction")
    void destroyRoom_MissingRoom_DoesNotPublishRoomDestroyed() {
        withRoomLock("missing-room", () -> roomService.destroyRoom("missing-room"));

        verify(eventPublisher, never()).publishEvent(any(RoomDestroyed.class));
    }

    @Test
    @DisplayName("createRoom delegates password hashing to PasswordEncoder and joinRoom verifies via matches")
    void passwordSecurity_UsesInjectedPasswordEncoderForHashingAndValidation() {
        PasswordEncoder mockEncoder = mock(PasswordEncoder.class);
        when(mockEncoder.encode("secretPass42")).thenReturn("$2a$10$customMockHashForSecretPass42");
        when(mockEncoder.matches("secretPass42", "$2a$10$customMockHashForSecretPass42")).thenReturn(true);
        when(mockEncoder.matches("wrongPass", "$2a$10$customMockHashForSecretPass42")).thenReturn(false);

        RoomService customService = newService(eventPublisher, mockEncoder, roomCoordinator);

        String roomId = createRoom(customService, new CreateRoomRequest(
                "Secure Room", "Host", 4, 10, 20, 200, "secretPass42")).roomId();

        verify(mockEncoder).encode("secretPass42");

        Room room = coordinatedGetRoom(customService, roomCoordinator, roomId);
        assertThat(room).isNotNull();
        assertThat(room.hasPassword()).isTrue();
        assertThat(room.getPasswordHash()).isEqualTo("$2a$10$customMockHashForSecretPass42");

        String joinedId = joinByName(customService,
                new JoinRoomRequest("Secure Room", "Guest", "secretPass42")).roomId();
        assertThat(joinedId).isEqualTo(roomId);
        assertThat(room.getPlayers()).contains("Guest");
        verify(mockEncoder).matches("secretPass42", "$2a$10$customMockHashForSecretPass42");

        assertThatThrownBy(() -> withRoomLock(roomId, () -> customService.joinRoom(roomId,
                new JoinRoomRequest("Secure Room", "Intruder", "wrongPass"))))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Invalid password");
    }

    @Test
    @DisplayName("createRoom does not invoke PasswordEncoder for public rooms with null or empty passwords")
    void passwordSecurity_PublicRoomDoesNotInvokePasswordEncoder() {
        PasswordEncoder mockEncoder = mock(PasswordEncoder.class);
        RoomService customService = newService(eventPublisher, mockEncoder, roomCoordinator);

        String roomId1 = createRoom(customService, new CreateRoomRequest(
                "Public Room 1", "Host", 4, 10, 20, 200, null)).roomId();
        String roomId2 = createRoom(customService, new CreateRoomRequest(
                "Public Room 2", "Host", 4, 10, 20, 200, "   ")).roomId();

        verify(mockEncoder, never()).encode(any());

        Room r1 = coordinatedGetRoom(customService, roomCoordinator, roomId1);
        Room r2 = coordinatedGetRoom(customService, roomCoordinator, roomId2);
        assertThat(r1.hasPassword()).isFalse();
        assertThat(r1.getPasswordHash()).isNull();
        assertThat(r2.hasPassword()).isFalse();
        assertThat(r2.getPasswordHash()).isNull();

        withRoomLock(roomId1, () -> customService.joinRoom(roomId1, new JoinRoomRequest("Public Room 1", "P2", null)));
        withRoomLock(roomId2, () -> customService.joinRoom(roomId2, new JoinRoomRequest("Public Room 2", "P3", "anything")));
        assertThat(r1.getPlayers()).contains("P2");
        assertThat(r2.getPlayers()).contains("P3");
        verify(mockEncoder, never()).matches(any(), any());
    }

    /**
     * Characterizes what the room service does when the lobby inactivity timer fires. The callback
     * itself is covered by {@link LobbyInactivitySchedulerTest}.
     */
    @Nested
    @DisplayName("lobby inactivity timer")
    class LobbyInactivityTimer {

        private List<Runnable> scheduledCallbacks() {
            ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
            verify(taskScheduler, atLeastOnce()).schedule(captor.capture(), any(Instant.class));
            return captor.getAllValues();
        }

        private Runnable latestCallback() {
            List<Runnable> callbacks = scheduledCallbacks();
            return callbacks.get(callbacks.size() - 1);
        }

        // The production timer callback takes the room lock itself (reentrant); holding it here keeps
        // this characterization valid both before and after that change.
        private void fire(String roomId, Runnable callback) {
            withRoomLock(roomId, callback);
        }

        private long deadlineOf(String roomId) {
            return coordinatedGetRoom(roomId).getInactivityDeadlineEpochMs();
        }

        @Test
        @DisplayName("firing after the deadline closes the lobby and announces it")
        void givenExpiredLobby_whenTimerFires_thenRoomIsClosedAndAnnounced() {
            String roomId = createRoom(roomService, validCreateRequest).roomId();
            clock.setMillis(deadlineOf(roomId) + 1L);

            fire(roomId, latestCallback());

            assertThat(coordinatedGetRoom(roomId)).isNull();
            assertThat(roomNamed("Test Room")).isNull();
            ArgumentCaptor<Object> payloads = ArgumentCaptor.forClass(Object.class);
            verify(messagingTemplate, atLeastOnce()).convertAndSend(eq("/room/" + roomId), payloads.capture());
            assertThat(payloads.getAllValues()).anySatisfy(payload ->
                    assertThat(payload).isInstanceOfSatisfying(RoomDataResponse.class, response ->
                            assertThat(response.closedReason()).isEqualTo("INACTIVITY")));
            verify(eventPublisher).publishEvent(new RoomDestroyed(roomId));
        }

        @Test
        @DisplayName("activity extends the deadline so the original timer no longer closes the lobby")
        void givenJoinAfterScheduling_whenOriginalTimerFiresAtOldDeadline_thenLobbyStaysOpen() {
            String roomId = createRoom(roomService, validCreateRequest).roomId();
            long originalDeadline = deadlineOf(roomId);
            Runnable originalCallback = latestCallback();

            clock.setMillis(clock.millis() + 10 * 60_000L);
            withRoomLock(roomId, () -> roomService.joinRoom(roomId,
                    new JoinRoomRequest("Test Room", "LateFriend", null)));
            long extendedDeadline = deadlineOf(roomId);
            assertThat(extendedDeadline).isGreaterThan(originalDeadline);

            clock.setMillis(originalDeadline + 1L);
            fire(roomId, originalCallback);
            assertThat(coordinatedGetRoom(roomId)).isNotNull();

            clock.setMillis(extendedDeadline + 1L);
            fire(roomId, latestCallback());
            assertThat(coordinatedGetRoom(roomId)).isNull();
        }
    }
}
