package com.pokergame.service;

import com.pokergame.dto.response.RoomDataResponse;
import com.pokergame.exception.UnauthorisedActionException;
import com.pokergame.dto.request.CreateRoomRequest;
import com.pokergame.dto.request.JoinRoomRequest;
import com.pokergame.model.Room;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the RoomService class.
 */
@ExtendWith(MockitoExtension.class)
class RoomServiceTest {

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    private RoomService roomService;

    private CreateRoomRequest validCreateRequest;

    @BeforeEach
    void setUp() {
        roomService = new RoomService(messagingTemplate);
        validCreateRequest = new CreateRoomRequest(
                "Test Room",
                "HostPlayer",
                6,
                5,
                10,
                100,
                null);
    }

    // ==================== createRoom Tests ====================

    @Test
    void createRoom_WithValidRequest_ShouldCreateRoomSuccessfully() {
        String roomId = roomService.createRoom(validCreateRequest);

        assertNotNull(roomId);
        assertFalse(roomId.isEmpty());

        Room room = roomService.getRoom(roomId);
        assertNotNull(room);
        assertEquals("Test Room", room.getRoomName());
        assertEquals("HostPlayer", room.getHostName());
        assertEquals(6, room.getMaxPlayers());
        assertEquals(5, room.getSmallBlind());
        assertEquals(10, room.getBigBlind());
        assertEquals(100, room.getBuyIn());
        assertTrue(room.getPlayers().contains("HostPlayer"));

        verify(messagingTemplate).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    void createRoom_WithPassword_ShouldCreatePrivateRoom() {
        CreateRoomRequest requestWithPassword = new CreateRoomRequest(
                "Private Room",
                "Host",
                4,
                10,
                20,
                200,
                "secret123");

        String roomId = roomService.createRoom(requestWithPassword);
        Room room = roomService.getRoom(roomId);

        assertNotNull(room);
        assertTrue(room.hasPassword());
        assertTrue(room.checkPassword("secret123"));
        assertFalse(room.checkPassword("wrongpassword"));
    }

    @Test
    void createRoom_WithDuplicateName_ShouldThrowException() {
        roomService.createRoom(validCreateRequest);

        CreateRoomRequest duplicateRequest = new CreateRoomRequest(
                "Test Room",
                "AnotherHost",
                4,
                5,
                10,
                100,
                null);

        com.pokergame.exception.BadRequestException exception = assertThrows(
                com.pokergame.exception.BadRequestException.class,
                () -> roomService.createRoom(duplicateRequest));

        assertTrue(exception.getMessage().contains("already taken"));
    }

    @Test
    void createRoom_WithDuplicateNameCaseInsensitive_ShouldThrowException() {
        roomService.createRoom(validCreateRequest);

        CreateRoomRequest duplicateRequest = new CreateRoomRequest(
                "TEST ROOM",
                "AnotherHost",
                4,
                5,
                10,
                100,
                null);

        assertThrows(com.pokergame.exception.BadRequestException.class, () -> roomService.createRoom(duplicateRequest));
    }

    // ==================== joinRoom Tests ====================

    @Test
    void joinRoom_WithValidRequest_ShouldAddPlayerToRoom() {
        String roomId = roomService.createRoom(validCreateRequest);
        reset(messagingTemplate);

        JoinRoomRequest joinRequest = new JoinRoomRequest("Test Room", "NewPlayer", null);
        roomService.joinRoom(joinRequest);

        Room room = roomService.getRoom(roomId);
        assertTrue(room.getPlayers().contains("NewPlayer"));
        assertEquals(2, room.getPlayers().size());

        verify(messagingTemplate).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    void joinRoom_WithCorrectPassword_ShouldSucceed() {
        CreateRoomRequest privateRoomRequest = new CreateRoomRequest(
                "Private Room",
                "Host",
                4,
                5,
                10,
                100,
                "password123");
        String roomId = roomService.createRoom(privateRoomRequest);
        reset(messagingTemplate);

        JoinRoomRequest joinRequest = new JoinRoomRequest("Private Room", "NewPlayer", "password123");
        roomService.joinRoom(joinRequest);

        Room room = roomService.getRoom(roomId);
        assertTrue(room.getPlayers().contains("NewPlayer"));
    }

    @Test
    void joinRoom_WithWrongPassword_ShouldThrowException() {
        CreateRoomRequest privateRoomRequest = new CreateRoomRequest(
                "Private Room",
                "Host",
                4,
                5,
                10,
                100,
                "password123");
        roomService.createRoom(privateRoomRequest);

        JoinRoomRequest joinRequest = new JoinRoomRequest("Private Room", "NewPlayer", "wrongpassword");

        com.pokergame.exception.BadRequestException exception = assertThrows(
                com.pokergame.exception.BadRequestException.class,
                () -> roomService.joinRoom(joinRequest));

        assertEquals("Invalid password", exception.getMessage());
    }

    @Test
    void joinRoom_WhenRoomNotFound_ShouldThrowException() {
        JoinRoomRequest joinRequest = new JoinRoomRequest("Nonexistent Room", "Player", null);

        com.pokergame.exception.ResourceNotFoundException exception = assertThrows(
                com.pokergame.exception.ResourceNotFoundException.class,
                () -> roomService.joinRoom(joinRequest));

        assertEquals("Room not found", exception.getMessage());
    }

    @Test
    void joinRoom_WhenRoomIsFull_ShouldThrowException() {
        CreateRoomRequest smallRoomRequest = new CreateRoomRequest(
                "Small Room",
                "Host",
                2,
                5,
                10,
                100,
                null);
        roomService.createRoom(smallRoomRequest);
        roomService.joinRoom(new JoinRoomRequest("Small Room", "Player2", null));

        JoinRoomRequest thirdPlayer = new JoinRoomRequest("Small Room", "Player3", null);

        UnauthorisedActionException exception = assertThrows(
                UnauthorisedActionException.class,
                () -> roomService.joinRoom(thirdPlayer));

        assertEquals("Room is full", exception.getMessage());
    }

    @Test
    void joinRoom_WithDuplicatePlayerName_ShouldThrowException() {
        roomService.createRoom(validCreateRequest);

        JoinRoomRequest duplicateNameRequest = new JoinRoomRequest("Test Room", "HostPlayer", null);

        com.pokergame.exception.BadRequestException exception = assertThrows(
                com.pokergame.exception.BadRequestException.class,
                () -> roomService.joinRoom(duplicateNameRequest));

        assertEquals("Player name already taken", exception.getMessage());
    }

    // ==================== leaveRoom Tests ====================

    @Test
    void leaveRoom_RegularPlayer_ShouldRemovePlayer() {
        String roomId = roomService.createRoom(validCreateRequest);
        roomService.joinRoom(new JoinRoomRequest("Test Room", "Player2", null));
        reset(messagingTemplate);

        roomService.leaveRoom(roomId, "Player2");

        Room room = roomService.getRoom(roomId);
        assertNotNull(room);
        assertFalse(room.getPlayers().contains("Player2"));
        assertTrue(room.getPlayers().contains("HostPlayer"));

        verify(messagingTemplate).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    void leaveRoom_Host_ShouldDestroyRoom() {
        String roomId = roomService.createRoom(validCreateRequest);
        reset(messagingTemplate);

        roomService.leaveRoom(roomId, "HostPlayer");

        assertNull(roomService.getRoom(roomId));
        verify(messagingTemplate).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    void leaveRoom_LastPlayer_ShouldDestroyRoom() {
        String roomId = roomService.createRoom(validCreateRequest);
        roomService.joinRoom(new JoinRoomRequest("Test Room", "Player2", null));
        reset(messagingTemplate);

        // Host leaves first - room should be destroyed
        roomService.leaveRoom(roomId, "HostPlayer");

        assertNull(roomService.getRoom(roomId));
    }

    @Test
    void leaveRoom_WhenRoomNotFound_ShouldThrowException() {
        com.pokergame.exception.ResourceNotFoundException exception = assertThrows(
                com.pokergame.exception.ResourceNotFoundException.class,
                () -> roomService.leaveRoom("nonexistent-id", "Player"));

        assertEquals("Room not found", exception.getMessage());
    }

    // ==================== getRooms Tests ====================

    @Test
    void getRooms_WhenEmpty_ShouldReturnEmptyList() {
        List<Room> rooms = roomService.getRooms();
        assertTrue(rooms.isEmpty());
    }

    @Test
    void getRooms_WithRooms_ShouldReturnAllRooms() {
        roomService.createRoom(validCreateRequest);
        roomService.createRoom(new CreateRoomRequest(
                "Room 2",
                "Host2",
                4,
                10,
                20,
                200,
                null));

        List<Room> rooms = roomService.getRooms();

        assertEquals(2, rooms.size());
    }

    // ==================== getRoomData Tests ====================

    @Test
    void getRoomData_WithValidRoomId_ShouldReturnRoomData() {
        String roomId = roomService.createRoom(validCreateRequest);
        roomService.joinRoom(new JoinRoomRequest("Test Room", "Player2", null));

        RoomDataResponse roomData = roomService.getRoomData(roomId);

        assertNotNull(roomData);
        assertEquals(roomId, roomData.roomId());
        assertEquals("Test Room", roomData.roomName());
        assertEquals(6, roomData.maxPlayers());
        assertEquals(100, roomData.buyIn());
        assertEquals(5, roomData.smallBlind());
        assertEquals(10, roomData.bigBlind());
        assertEquals("HostPlayer", roomData.hostName());
        assertEquals(2, roomData.currentPlayers());
        assertTrue(roomData.canStartGame());
    }

    @Test
    void getRoomData_WithNullRoomId_ShouldThrowException() {
        com.pokergame.exception.BadRequestException exception = assertThrows(
                com.pokergame.exception.BadRequestException.class,
                () -> roomService.getRoomData(null));

        assertEquals("Room ID cannot be null", exception.getMessage());
    }

    @Test
    void getRoomData_WithNonexistentRoomId_ShouldThrowException() {
        com.pokergame.exception.ResourceNotFoundException exception = assertThrows(
                com.pokergame.exception.ResourceNotFoundException.class,
                () -> roomService.getRoomData("nonexistent-id"));

        assertEquals("Room not found", exception.getMessage());
    }

    @Test
    void getRoomData_ShouldMarkHostPlayerCorrectly() {
        String roomId = roomService.createRoom(validCreateRequest);
        roomService.joinRoom(new JoinRoomRequest("Test Room", "Player2", null));

        RoomDataResponse roomData = roomService.getRoomData(roomId);

        var hostPlayer = roomData.players().stream()
                .filter(p -> p.name().equals("HostPlayer"))
                .findFirst()
                .orElseThrow();
        var regularPlayer = roomData.players().stream()
                .filter(p -> p.name().equals("Player2"))
                .findFirst()
                .orElseThrow();

        assertTrue(hostPlayer.isHost());
        assertFalse(regularPlayer.isHost());
    }

    @Test
    void createRoom_ConcurrentDuplicateName_ShouldAllowSingleSuccess() throws InterruptedException {
        int attempts = 10;
        ExecutorService executor = Executors.newFixedThreadPool(attempts);
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(attempts);
        AtomicInteger successCount = new AtomicInteger(0);
        ConcurrentLinkedQueue<Exception> failures = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < attempts; i++) {
            int idx = i;
            executor.submit(() -> {
                try {
                    CreateRoomRequest request = new CreateRoomRequest(
                            "Concurrent Name",
                            "Host" + idx,
                            6,
                            5,
                            10,
                            100,
                            null);

                    ready.countDown();
                    assertTrue(start.await(2, TimeUnit.SECONDS));

                    roomService.createRoom(request);
                    successCount.incrementAndGet();
                } catch (com.pokergame.exception.BadRequestException expected) {
                    // expected for duplicates
                } catch (Exception ex) {
                    failures.add(ex);
                } finally {
                    done.countDown();
                }
            });
        }

        assertTrue(ready.await(2, TimeUnit.SECONDS));
        start.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        executor.shutdown();

        assertTrue(failures.isEmpty(), "Unexpected failures: " + failures);
        assertEquals(1, successCount.get(), "Only one concurrent create should succeed for duplicate room name");
    }

    // ==================== getRoom Tests ====================

    @Test
    void getRoom_WithValidId_ShouldReturnRoom() {
        String roomId = roomService.createRoom(validCreateRequest);

        Room room = roomService.getRoom(roomId);

        assertNotNull(room);
        assertEquals("Test Room", room.getRoomName());
    }

    @Test
    void getRoom_WithInvalidId_ShouldReturnNull() {
        Room room = roomService.getRoom("nonexistent-id");
        assertNull(room);
    }

    // ==================== findRoomByName Tests ====================

    @Test
    void findRoomByName_WithExactMatch_ShouldReturnRoom() {
        roomService.createRoom(validCreateRequest);

        Room room = roomService.findRoomByName("Test Room");

        assertNotNull(room);
        assertEquals("Test Room", room.getRoomName());
    }

    @Test
    void findRoomByName_CaseInsensitive_ShouldReturnRoom() {
        roomService.createRoom(validCreateRequest);

        Room room = roomService.findRoomByName("test room");

        assertNotNull(room);
        assertEquals("Test Room", room.getRoomName());
    }

    @Test
    void findRoomByName_WhenNotFound_ShouldReturnNull() {
        Room room = roomService.findRoomByName("Nonexistent Room");
        assertNull(room);
    }

    // ==================== isRoomHost Tests ====================

    @Test
    void isRoomHost_WithHost_ShouldReturnTrue() {
        String roomId = roomService.createRoom(validCreateRequest);

        assertTrue(roomService.isRoomHost(roomId, "HostPlayer"));
    }

    @Test
    void isRoomHost_WithNonHost_ShouldReturnFalse() {
        String roomId = roomService.createRoom(validCreateRequest);
        roomService.joinRoom(new JoinRoomRequest("Test Room", "Player2", null));

        assertFalse(roomService.isRoomHost(roomId, "Player2"));
    }

    @Test
    void isRoomHost_WithNonexistentRoom_ShouldReturnFalse() {
        assertFalse(roomService.isRoomHost("nonexistent-id", "Player"));
    }

    // ==================== destroyRoom Tests ====================

    @Test
    void destroyRoom_ShouldRemoveRoom() {
        String roomId = roomService.createRoom(validCreateRequest);
        assertNotNull(roomService.getRoom(roomId));

        roomService.destroyRoom(roomId);

        assertNull(roomService.getRoom(roomId));
        assertFalse(roomService.isRoomHost(roomId, "HostPlayer"));
    }

    @Test
    void destroyRoom_WithNonexistentRoom_ShouldNotThrowException() {
        assertDoesNotThrow(() -> roomService.destroyRoom("nonexistent-id"));
    }

    // ==================== canStartGame Tests ====================

    @Test
    void getRoomData_WithOnePlayer_ShouldNotBeAbleToStart() {
        String roomId = roomService.createRoom(validCreateRequest);

        RoomDataResponse roomData = roomService.getRoomData(roomId);

        assertEquals(1, roomData.currentPlayers());
        assertFalse(roomData.canStartGame());
    }

    @Test
    void getRoomData_WithTwoPlayers_ShouldBeAbleToStart() {
        String roomId = roomService.createRoom(validCreateRequest);
        roomService.joinRoom(new JoinRoomRequest("Test Room", "Player2", null));

        RoomDataResponse roomData = roomService.getRoomData(roomId);

        assertEquals(2, roomData.currentPlayers());
        assertTrue(roomData.canStartGame());
    }
}
