package com.pokergame.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import com.pokergame.exception.BadRequestException;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the Room class.
 */
class RoomTest {

    private Room room;

    @BeforeEach
    void setUp() {
        room = new Room("room123", "Test Room", "HostPlayer", 6, 10, 20, 1000, null);
    }

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

    @Test
    void testRoomCreationWithPassword() {
        Room secureRoom = new Room("room456", "Private Room", "Host", 4, 5, 10, 500, "secret123");

        assertTrue(secureRoom.hasPassword());
        assertTrue(secureRoom.checkPassword("secret123"));
        assertFalse(secureRoom.checkPassword("wrong"));
    }

    @Test
    void testRoomCreationWithNullRoomId() {
        BadRequestException exception = assertThrows(
                BadRequestException.class,
                () -> new Room(null, "Room", "Host", 6, 10, 20, 1000, null));
        assertEquals("Room ID cannot be null or empty.", exception.getMessage());
    }

    @Test
    void testRoomCreationWithEmptyRoomId() {
        BadRequestException exception = assertThrows(
                BadRequestException.class,
                () -> new Room("   ", "Room", "Host", 6, 10, 20, 1000, null));
        assertEquals("Room ID cannot be null or empty.", exception.getMessage());
    }

    @Test
    void testRoomCreationWithNullRoomName() {
        BadRequestException exception = assertThrows(
                BadRequestException.class,
                () -> new Room("room123", null, "Host", 6, 10, 20, 1000, null));
        assertEquals("Room name cannot be null or empty.", exception.getMessage());
    }

    @Test
    void testRoomCreationWithEmptyRoomName() {
        BadRequestException exception = assertThrows(
                BadRequestException.class,
                () -> new Room("room123", "   ", "Host", 6, 10, 20, 1000, null));
        assertEquals("Room name cannot be null or empty.", exception.getMessage());
    }

    @Test
    void testRoomCreationWithNullHostName() {
        BadRequestException exception = assertThrows(
                BadRequestException.class,
                () -> new Room("room123", "Room", null, 6, 10, 20, 1000, null));
        assertEquals("Host name cannot be null or empty.", exception.getMessage());
    }

    @Test
    void testRoomCreationWithEmptyHostName() {
        BadRequestException exception = assertThrows(
                BadRequestException.class,
                () -> new Room("room123", "Room", "   ", 6, 10, 20, 1000, null));
        assertEquals("Host name cannot be null or empty.", exception.getMessage());
    }

    @Test
    void testRoomCreationWithTooFewPlayers() {
        BadRequestException exception = assertThrows(
                BadRequestException.class,
                () -> new Room("room123", "Room", "Host", 1, 10, 20, 1000, null));
        assertEquals("Max players must be between 2 and 10.", exception.getMessage());
    }

    @Test
    void testRoomCreationWithTooManyPlayers() {
        BadRequestException exception = assertThrows(
                BadRequestException.class,
                () -> new Room("room123", "Room", "Host", 11, 10, 20, 1000, null));
        assertEquals("Max players must be between 2 and 10.", exception.getMessage());
    }

    @Test
    void testRoomCreationWithMinimumPlayers() {
        Room minRoom = new Room("room123", "Room", "Host", 2, 10, 20, 1000, null);
        assertEquals(2, minRoom.getMaxPlayers());
    }

    @Test
    void testRoomCreationWithMaximumPlayers() {
        Room maxRoom = new Room("room123", "Room", "Host", 10, 10, 20, 1000, null);
        assertEquals(10, maxRoom.getMaxPlayers());
    }

    @Test
    void testRoomCreationWithInvalidSmallBlind() {
        BadRequestException exception = assertThrows(
                BadRequestException.class,
                () -> new Room("room123", "Room", "Host", 6, 0, 20, 1000, null));
        assertEquals("Small blind must be at least 1.", exception.getMessage());
    }

    @Test
    void testRoomCreationWithNegativeSmallBlind() {
        BadRequestException exception = assertThrows(
                BadRequestException.class,
                () -> new Room("room123", "Room", "Host", 6, -5, 20, 1000, null));
        assertEquals("Small blind must be at least 1.", exception.getMessage());
    }

    @Test
    void testRoomCreationWithBigBlindLessThanSmallBlind() {
        BadRequestException exception = assertThrows(
                BadRequestException.class,
                () -> new Room("room123", "Room", "Host", 6, 20, 10, 1000, null));
        assertEquals("Big blind must be at least twice the small blind.", exception.getMessage());
    }

    @Test
    void testRoomCreationWithBigBlindEqualToSmallBlind() {
        Room equalBlindsRoom = new Room("room123", "Room", "Host", 6, 10, 10, 1000, null);
        assertEquals(10, equalBlindsRoom.getSmallBlind());
        assertEquals(10, equalBlindsRoom.getBigBlind());
    }

    @Test
    void testAddPlayer() {
        room.addPlayer("Player1");

        assertEquals(1, room.getPlayers().size());
        assertTrue(room.hasPlayer("Player1"));
    }

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

    @Test
    void testAddDuplicatePlayer() {
        room.addPlayer("Player1");
        room.addPlayer("Player1");

        assertEquals(1, room.getPlayers().size());
        assertTrue(room.hasPlayer("Player1"));
    }

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

    @Test
    void testRemoveNonExistentPlayer() {
        room.addPlayer("Player1");

        room.removePlayer("NonExistent");

        assertEquals(1, room.getPlayers().size());
        assertTrue(room.hasPlayer("Player1"));
    }

    @Test
    void testHasPlayer() {
        assertFalse(room.hasPlayer("Player1"));

        room.addPlayer("Player1");

        assertTrue(room.hasPlayer("Player1"));
        assertFalse(room.hasPlayer("Player2"));
    }

    @Test
    void testHasPasswordForPublicRoom() {
        assertFalse(room.hasPassword());
    }

    @Test
    void testHasPasswordForPrivateRoom() {
        Room privateRoom = new Room("room456", "Private", "Host", 6, 10, 20, 1000, "password");
        assertTrue(privateRoom.hasPassword());
    }

    @Test
    void testHasPasswordWithEmptyPassword() {
        Room emptyPasswordRoom = new Room("room456", "Room", "Host", 6, 10, 20, 1000, "");
        assertFalse(emptyPasswordRoom.hasPassword());
    }

    @Test
    void testHasPasswordWithWhitespacePassword() {
        Room whitespaceRoom = new Room("room456", "Room", "Host", 6, 10, 20, 1000, "   ");
        assertFalse(whitespaceRoom.hasPassword());
    }

    @Test
    void testCheckPasswordForPublicRoom() {
        assertTrue(room.checkPassword(null));
        assertTrue(room.checkPassword(""));
        assertTrue(room.checkPassword("anything"));
    }

    @Test
    void testCheckPasswordForPrivateRoom() {
        Room privateRoom = new Room("room456", "Private", "Host", 6, 10, 20, 1000, "secret123");

        assertTrue(privateRoom.checkPassword("secret123"));
        assertFalse(privateRoom.checkPassword("wrong"));
        assertFalse(privateRoom.checkPassword(""));
        assertFalse(privateRoom.checkPassword(null));
    }

    @Test
    void testCheckPasswordCaseSensitive() {
        Room privateRoom = new Room("room456", "Private", "Host", 6, 10, 20, 1000, "Secret123");

        assertTrue(privateRoom.checkPassword("Secret123"));
        assertFalse(privateRoom.checkPassword("secret123"));
        assertFalse(privateRoom.checkPassword("SECRET123"));
    }

    @Test
    void testToString() {
        String result = room.toString();

        assertTrue(result.contains("Test Room"));
        assertTrue(result.contains("room123"));
        assertTrue(result.contains("HostPlayer"));
        assertTrue(result.contains("0 players"));
    }

    @Test
    void testToStringWithPlayers() {
        room.addPlayer("Player1");
        room.addPlayer("Player2");

        String result = room.toString();

        assertTrue(result.contains("2 players"));
    }

    @Test
    void testGetRoomId() {
        assertEquals("room123", room.getRoomId());
    }

    @Test
    void testGetRoomName() {
        assertEquals("Test Room", room.getRoomName());
    }

    @Test
    void testGetHostName() {
        assertEquals("HostPlayer", room.getHostName());
    }

    @Test
    void testGetPlayers() {
        assertNotNull(room.getPlayers());
        assertEquals(0, room.getPlayers().size());

        room.addPlayer("Player1");
        assertEquals(1, room.getPlayers().size());
        assertEquals("Player1", room.getPlayers().get(0));
    }

    @Test
    void testGetMaxPlayers() {
        assertEquals(6, room.getMaxPlayers());
    }

    @Test
    void testGetSmallBlind() {
        assertEquals(10, room.getSmallBlind());
    }

    @Test
    void testGetBigBlind() {
        assertEquals(20, room.getBigBlind());
    }

    @Test
    void testGetBuyIn() {
        assertEquals(1000, room.getBuyIn());
    }

    @Test
    void testGetCreatedAt() {
        LocalDateTime createdAt = room.getCreatedAt();
        assertNotNull(createdAt);
        assertTrue(createdAt.isBefore(LocalDateTime.now().plusSeconds(1)));
        assertTrue(createdAt.isAfter(LocalDateTime.now().minusSeconds(10)));
    }

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

    @Test
    void testMultiplePlayerOperations() {
        // Add players
        room.addPlayer("Alice");
        room.addPlayer("Bob");
        room.addPlayer("Charlie");

        assertEquals(3, room.getPlayers().size());

        // Try to add duplicate
        room.addPlayer("Bob");
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

    @Test
    void testRoomWithVariousBlinds() {
        Room lowStakes = new Room("low", "Low Stakes", "Host", 6, 1, 2, 100, null);
        assertEquals(1, lowStakes.getSmallBlind());
        assertEquals(2, lowStakes.getBigBlind());

        Room highStakes = new Room("high", "High Stakes", "Host", 6, 100, 200, 10000, null);
        assertEquals(100, highStakes.getSmallBlind());
        assertEquals(200, highStakes.getBigBlind());
    }

    @Test
    void testRoomWithZeroBuyIn() {
        Room freeBuyIn = new Room("free", "Free Game", "Host", 6, 10, 20, 0, null);
        assertEquals(0, freeBuyIn.getBuyIn());
    }

    @Test
    void testRoomWithNegativeBuyIn() {
        Room negativeBuyIn = new Room("negative", "Negative Buy-in", "Host", 6, 10, 20, -100, null);
        assertEquals(-100, negativeBuyIn.getBuyIn());
    }
}
