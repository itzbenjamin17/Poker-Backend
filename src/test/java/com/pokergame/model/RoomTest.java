package com.pokergame.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import com.pokergame.exception.BadRequestException;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the Room class.
 */
@Tag("unit")
@DisplayName("Room model")
class RoomTest {

    private Room room;

    /**
     * Initializes the test fixtures before each scenario.
     */
    @BeforeEach
    void setUp() {
        room = new Room("room123", "Test Room", "HostPlayer", 6, 10, 20, 1000, null);
    }

    /**
     * Protects the expected behavior for room creation.
     */
    @Test
    void testRoomCreation() {
        assertNotNull(room);
        assertEquals("room123", room.getRoomId());
        assertEquals("Test Room", room.getRoomName());
        assertEquals("HostPlayer", room.getHostName());
        assertEquals(6, room.getMaxPlayers());
        assertEquals(10, room.getSmallBlind());
        assertEquals(20, room.getBigBlind());
        assertEquals(1000, room.getBuyIn());
        assertNotNull(room.getCreatedAt());
        assertEquals(0, room.getPlayers().size());
    }

    /**
     * Protects the expected behavior for room creation with password.
     */
    @Test
    void testRoomCreationWithPassword() {
        Room secureRoom = new Room("room456", "Private Room", "Host", 4, 5, 10, 500, "secret123");

        assertTrue(secureRoom.hasPassword());
        assertTrue(secureRoom.checkPassword("secret123"));
        assertFalse(secureRoom.checkPassword("wrong"));
    }

    /**
     * Protects the expected behavior for room creation with null room ID.
     */
    @Test
    void testRoomCreationWithNullRoomId() {
        BadRequestException exception = assertThrows(
                BadRequestException.class,
                () -> new Room(null, "Room", "Host", 6, 10, 20, 1000, null));
        assertEquals("Room ID cannot be null or empty.", exception.getMessage());
    }

    /**
     * Protects the expected behavior for room creation with empty room ID.
     */
    @Test
    void testRoomCreationWithEmptyRoomId() {
        BadRequestException exception = assertThrows(
                BadRequestException.class,
                () -> new Room("   ", "Room", "Host", 6, 10, 20, 1000, null));
        assertEquals("Room ID cannot be null or empty.", exception.getMessage());
    }

    /**
     * Protects the expected behavior for room creation with null room name.
     */
    @Test
    void testRoomCreationWithNullRoomName() {
        BadRequestException exception = assertThrows(
                BadRequestException.class,
                () -> new Room("room123", null, "Host", 6, 10, 20, 1000, null));
        assertEquals("Room name cannot be null or empty.", exception.getMessage());
    }

    /**
     * Protects the expected behavior for room creation with empty room name.
     */
    @Test
    void testRoomCreationWithEmptyRoomName() {
        BadRequestException exception = assertThrows(
                BadRequestException.class,
                () -> new Room("room123", "   ", "Host", 6, 10, 20, 1000, null));
        assertEquals("Room name cannot be null or empty.", exception.getMessage());
    }

    /**
     * Protects the expected behavior for room creation with null host name.
     */
    @Test
    void testRoomCreationWithNullHostName() {
        BadRequestException exception = assertThrows(
                BadRequestException.class,
                () -> new Room("room123", "Room", null, 6, 10, 20, 1000, null));
        assertEquals("Host name cannot be null or empty.", exception.getMessage());
    }

    /**
     * Protects the expected behavior for room creation with empty host name.
     */
    @Test
    void testRoomCreationWithEmptyHostName() {
        BadRequestException exception = assertThrows(
                BadRequestException.class,
                () -> new Room("room123", "Room", "   ", 6, 10, 20, 1000, null));
        assertEquals("Host name cannot be null or empty.", exception.getMessage());
    }

    /**
     * Protects the expected behavior for room creation with too few players.
     */
    @Test
    void testRoomCreationWithTooFewPlayers() {
        BadRequestException exception = assertThrows(
                BadRequestException.class,
                () -> new Room("room123", "Room", "Host", 1, 10, 20, 1000, null));
        assertEquals("Max players must be between 2 and 10.", exception.getMessage());
    }

    /**
     * Protects the expected behavior for room creation with too many players.
     */
    @Test
    void testRoomCreationWithTooManyPlayers() {
        BadRequestException exception = assertThrows(
                BadRequestException.class,
                () -> new Room("room123", "Room", "Host", 11, 10, 20, 1000, null));
        assertEquals("Max players must be between 2 and 10.", exception.getMessage());
    }

    /**
     * Protects the expected behavior for room creation with minimum players.
     */
    @Test
    void testRoomCreationWithMinimumPlayers() {
        Room minRoom = new Room("room123", "Room", "Host", 2, 10, 20, 1000, null);
        assertEquals(2, minRoom.getMaxPlayers());
    }

    /**
     * Protects the expected behavior for room creation with maximum players.
     */
    @Test
    void testRoomCreationWithMaximumPlayers() {
        Room maxRoom = new Room("room123", "Room", "Host", 10, 10, 20, 1000, null);
        assertEquals(10, maxRoom.getMaxPlayers());
    }

    /**
     * Protects the expected behavior for room creation with invalid small blind.
     */
    @Test
    void testRoomCreationWithInvalidSmallBlind() {
        BadRequestException exception = assertThrows(
                BadRequestException.class,
                () -> new Room("room123", "Room", "Host", 6, 0, 20, 1000, null));
        assertEquals("Small blind must be at least 1.", exception.getMessage());
    }

    /**
     * Protects the expected behavior for room creation with negative small blind.
     */
    @Test
    void testRoomCreationWithNegativeSmallBlind() {
        BadRequestException exception = assertThrows(
                BadRequestException.class,
                () -> new Room("room123", "Room", "Host", 6, -5, 20, 1000, null));
        assertEquals("Small blind must be at least 1.", exception.getMessage());
    }

    /**
     * Protects the expected behavior for room creation with big blind less than small blind.
     */
    @Test
    void testRoomCreationWithBigBlindLessThanSmallBlind() {
        BadRequestException exception = assertThrows(
                BadRequestException.class,
                () -> new Room("room123", "Room", "Host", 6, 20, 10, 1000, null));
        assertEquals("Big blind must be at least twice the small blind.", exception.getMessage());
    }

    /**
     * Protects the expected behavior for room creation with big blind equal to small blind.
     */
    @Test
    void testRoomCreationWithBigBlindEqualToSmallBlind() {
        assertThrows(BadRequestException.class, () -> new Room("room123", "Room", "Host", 6, 10, 10, 1000, null));
    }

    /**
     * Protects the expected behavior for room creation with big blind greater than small blind.
     */
    @Test
    void testRoomCreationWithBigBlindGreaterThanSmallBlind() {
        Room validRoom = new Room("room123", "Room", "Host", 6, 10, 20, 1000, null);
        assertEquals(10, validRoom.getSmallBlind());
        assertEquals(20, validRoom.getBigBlind());
    }

    /**
     * Protects the expected behavior for add player.
     */
    @Test
    void testAddPlayer() {
        room.addPlayer("Player1");

        assertEquals(1, room.getPlayers().size());
        assertTrue(room.hasPlayer("Player1"));
    }

    /**
     * Protects the expected behavior for add multiple players.
     */
    @Test
    void testAddMultiplePlayers() {
        room.addPlayer("Player1");
        room.addPlayer("Player2");
        room.addPlayer("Player3");

        assertEquals(3, room.getPlayers().size());
        assertTrue(room.hasPlayer("Player1"));
        assertTrue(room.hasPlayer("Player2"));
        assertTrue(room.hasPlayer("Player3"));
    }

    /**
     * Protects the expected behavior for add duplicate player.
     */
    @Test
    void testAddDuplicatePlayer() {
        room.addPlayer("Player1");
        assertThrows(BadRequestException.class, () -> room.addPlayer("Player1"));
        assertEquals(1, room.getPlayers().size());
        assertTrue(room.hasPlayer("Player1"));
    }

    /**
     * Protects the expected behavior for remove player.
     */
    @Test
    void testRemovePlayer() {
        room.addPlayer("Player1");
        room.addPlayer("Player2");

        assertEquals(2, room.getPlayers().size());

        room.removePlayer("Player1");

        assertEquals(1, room.getPlayers().size());
        assertFalse(room.hasPlayer("Player1"));
        assertTrue(room.hasPlayer("Player2"));
    }

    /**
     * Protects the expected behavior for remove non existent player.
     */
    @Test
    void testRemoveNonExistentPlayer() {
        room.addPlayer("Player1");

        assertThrows(BadRequestException.class, () -> room.removePlayer("NonExistent"));

        assertEquals(1, room.getPlayers().size());
        assertTrue(room.hasPlayer("Player1"));
    }

    /**
     * Protects the expected behavior for has player.
     */
    @Test
    void testHasPlayer() {
        assertFalse(room.hasPlayer("Player1"));

        room.addPlayer("Player1");

        assertTrue(room.hasPlayer("Player1"));
        assertFalse(room.hasPlayer("Player2"));
    }

    /**
     * Protects the expected behavior for has password for public room.
     */
    @Test
    void testHasPasswordForPublicRoom() {
        assertFalse(room.hasPassword());
    }

    /**
     * Protects the expected behavior for has password for private room.
     */
    @Test
    void testHasPasswordForPrivateRoom() {
        Room privateRoom = new Room("room456", "Private", "Host", 6, 10, 20, 1000, "password");
        assertTrue(privateRoom.hasPassword());
    }

    /**
     * Protects the expected behavior for has password with empty password.
     */
    @Test
    void testHasPasswordWithEmptyPassword() {
        Room emptyPasswordRoom = new Room("room456", "Room", "Host", 6, 10, 20, 1000, "");
        assertFalse(emptyPasswordRoom.hasPassword());
    }

    /**
     * Protects the expected behavior for has password with whitespace password.
     */
    @Test
    void testHasPasswordWithWhitespacePassword() {
        Room whitespaceRoom = new Room("room456", "Room", "Host", 6, 10, 20, 1000, "   ");
        assertFalse(whitespaceRoom.hasPassword());
    }

    /**
     * Protects the expected behavior for check password for public room.
     */
    @Test
    void testCheckPasswordForPublicRoom() {
        assertTrue(room.checkPassword(null));
        assertTrue(room.checkPassword(""));
        assertTrue(room.checkPassword("anything"));
    }

    /**
     * Protects the expected behavior for check password for private room.
     */
    @Test
    void testCheckPasswordForPrivateRoom() {
        Room privateRoom = new Room("room456", "Private", "Host", 6, 10, 20, 1000, "secret123");

        assertTrue(privateRoom.checkPassword("secret123"));
        assertFalse(privateRoom.checkPassword("wrong"));
        assertFalse(privateRoom.checkPassword(""));
        assertFalse(privateRoom.checkPassword(null));
    }

    /**
     * Protects the expected behavior for check password case sensitive.
     */
    @Test
    void testCheckPasswordCaseSensitive() {
        Room privateRoom = new Room("room456", "Private", "Host", 6, 10, 20, 1000, "Secret123");

        assertTrue(privateRoom.checkPassword("Secret123"));
        assertFalse(privateRoom.checkPassword("secret123"));
        assertFalse(privateRoom.checkPassword("SECRET123"));
    }

    /**
     * Protects the expected behavior for to string.
     */
    @Test
    void testToString() {
        String result = room.toString();

        assertTrue(result.contains("Test Room"));
        assertTrue(result.contains("room123"));
        assertTrue(result.contains("HostPlayer"));
        assertTrue(result.contains("0 players"));
    }

    /**
     * Protects the expected behavior for to string with players.
     */
    @Test
    void testToStringWithPlayers() {
        room.addPlayer("Player1");
        room.addPlayer("Player2");

        String result = room.toString();

        assertTrue(result.contains("2 players"));
    }

    /**
     * Protects the expected behavior for get room ID.
     */
    @Test
    void testGetRoomId() {
        assertEquals("room123", room.getRoomId());
    }

    /**
     * Protects the expected behavior for get room name.
     */
    @Test
    void testGetRoomName() {
        assertEquals("Test Room", room.getRoomName());
    }

    /**
     * Protects the expected behavior for get host name.
     */
    @Test
    void testGetHostName() {
        assertEquals("HostPlayer", room.getHostName());
    }

    /**
     * Protects the expected behavior for get players.
     */
    @Test
    void testGetPlayers() {
        assertNotNull(room.getPlayers());
        assertEquals(0, room.getPlayers().size());

        room.addPlayer("Player1");
        assertEquals(1, room.getPlayers().size());
        assertEquals("Player1", room.getPlayers().getFirst());
    }

    /**
     * Protects the expected behavior for get max players.
     */
    @Test
    void testGetMaxPlayers() {
        assertEquals(6, room.getMaxPlayers());
    }

    /**
     * Protects the expected behavior for get small blind.
     */
    @Test
    void testGetSmallBlind() {
        assertEquals(10, room.getSmallBlind());
    }

    /**
     * Protects the expected behavior for get big blind.
     */
    @Test
    void testGetBigBlind() {
        assertEquals(20, room.getBigBlind());
    }

    /**
     * Protects the expected behavior for get buy in.
     */
    @Test
    void testGetBuyIn() {
        assertEquals(1000, room.getBuyIn());
    }

    /**
     * Protects the expected behavior for get created at.
     */
    @Test
    void testGetCreatedAt() {
        LocalDateTime createdAt = room.getCreatedAt();
        assertNotNull(createdAt);
        assertTrue(createdAt.isBefore(LocalDateTime.now().plusSeconds(1)));
        assertTrue(createdAt.isAfter(LocalDateTime.now().minusSeconds(10)));
    }

    /**
     * Protects the expected behavior for room immutability.
     */
    @Test
    void testRoomImmutability() {
        // Room properties should be immutable
        assertEquals("room123", room.getRoomId());
        assertEquals("Test Room", room.getRoomName());
        assertEquals("HostPlayer", room.getHostName());
        assertEquals(6, room.getMaxPlayers());
        assertEquals(10, room.getSmallBlind());
        assertEquals(20, room.getBigBlind());
        assertEquals(1000, room.getBuyIn());

        // Adding players shouldn't affect these properties
        room.addPlayer("Player1");

        assertEquals("room123", room.getRoomId());
        assertEquals(6, room.getMaxPlayers());
    }

    /**
     * Protects the expected behavior for multiple player operations.
     */
    @Test
    void testMultiplePlayerOperations() {
        // Add players
        room.addPlayer("Alice");
        room.addPlayer("Bob");
        room.addPlayer("Charlie");

        assertEquals(3, room.getPlayers().size());

        // Try to add duplicate
        assertThrows(BadRequestException.class, () -> room.addPlayer("Bob"));
        assertEquals(3, room.getPlayers().size());

        // Remove one
        room.removePlayer("Bob");
        assertEquals(2, room.getPlayers().size());
        assertFalse(room.hasPlayer("Bob"));

        // Add Bob back
        room.addPlayer("Bob");
        assertEquals(3, room.getPlayers().size());
        assertTrue(room.hasPlayer("Bob"));
    }

    /**
     * Protects the expected behavior for room with various blinds.
     */
    @Test
    void testRoomWithVariousBlinds() {
        Room lowStakes = new Room("low", "Low Stakes", "Host", 6, 1, 2, 100, null);
        assertEquals(1, lowStakes.getSmallBlind());
        assertEquals(2, lowStakes.getBigBlind());

        Room highStakes = new Room("high", "High Stakes", "Host", 6, 100, 200, 10000, null);
        assertEquals(100, highStakes.getSmallBlind());
        assertEquals(200, highStakes.getBigBlind());
    }

    /**
     * Protects the expected behavior for room with zero buy in.
     */
    @Test
    void testRoomWithZeroBuyIn() {
        Room freeBuyIn = new Room("free", "Free Game", "Host", 6, 10, 20, 0, null);
        assertEquals(0, freeBuyIn.getBuyIn());
    }

    /**
     * Protects the expected behavior for room with negative buy in.
     */
    @Test
    void testRoomWithNegativeBuyIn() {
        Room negativeBuyIn = new Room("negative", "Negative Buy-in", "Host", 6, 10, 20, -100, null);
        assertEquals(-100, negativeBuyIn.getBuyIn());
    }
}
