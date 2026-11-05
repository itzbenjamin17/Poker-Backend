package com.pokergame.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import com.pokergame.exception.BadRequestException;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the Room class.
 */
@Tag("unit")
@DisplayName("Room model")
class RoomTest {

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
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
     * Protects the expected behavior that whitespace-only passwords are treated as absent (null).
     */
    @Test
    @DisplayName("should treat whitespace-only password as absent (null)")
    void testRoomCreationWithWhitespaceOnlyPassword() {
        Room roomEmptyPass = new Room("room-empty", "Empty Pass Room", "Host", 4, 5, 10, 500, "");
        assertFalse(roomEmptyPass.hasPassword());
        assertNull(roomEmptyPass.getPasswordForPersistence());
        assertTrue(roomEmptyPass.checkPassword(null));
        assertTrue(roomEmptyPass.checkPassword("any"));

        Room roomSpacesPass = new Room("room-spaces", "Spaces Pass Room", "Host", 4, 5, 10, 500, "   ");
        assertFalse(roomSpacesPass.hasPassword());
        assertNull(roomSpacesPass.getPasswordForPersistence());
        assertTrue(roomSpacesPass.checkPassword(null));
        assertTrue(roomSpacesPass.checkPassword("any"));
    }

    /**
     * Protects the expected behavior that non-whitespace passwords preserve leading and trailing spaces.
     */
    @Test
    @DisplayName("should preserve leading and trailing spaces in password")
    void testRoomCreationWithPasswordPreservingSpaces() {
        Room roomPreservedPass = new Room("room-spaces-kept", "Kept Pass Room", "Host", 4, 5, 10, 500, "  secret123  ");
        assertTrue(roomPreservedPass.hasPassword());
        assertNotNull(roomPreservedPass.getPasswordForPersistence());
        assertNotEquals("  secret123  ", roomPreservedPass.getPasswordForPersistence());
        assertTrue(passwordEncoder.matches("  secret123  ", roomPreservedPass.getPasswordForPersistence()));
        assertTrue(roomPreservedPass.checkPassword("  secret123  "));
        assertTrue(roomPreservedPass.checkPassword("  secret123  ", passwordEncoder));
        assertFalse(roomPreservedPass.checkPassword("secret123"));
        assertFalse(roomPreservedPass.checkPassword("secret123", passwordEncoder));
        assertFalse(roomPreservedPass.checkPassword(null));
        assertFalse(roomPreservedPass.checkPassword(null, passwordEncoder));
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
     * The players view reflects insertion order and grows as players join.
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
     * Creation timestamp is stamped at construction and falls within a recent window.
     */
    @Test
    void testGetCreatedAt() {
        LocalDateTime createdAt = room.getCreatedAt();
        assertNotNull(createdAt);
        assertTrue(createdAt.isBefore(LocalDateTime.now().plusSeconds(1)));
        assertTrue(createdAt.isAfter(LocalDateTime.now().minusSeconds(10)));
    }

    /**
     * Configuration (id, blinds, capacity) stays fixed as players join; only the roster changes.
     */
    @Test
    void testRoomImmutability() {
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
     * Rejects a zero buy-in because it cannot cover the big blind.
     */
    @Test
    void testRoomRejectsZeroBuyIn() {
        BadRequestException exception = assertThrows(
                BadRequestException.class,
                () -> new Room("free", "Free Game", "Host", 6, 10, 20, 0, null));

        assertEquals("Buy-in must be at least the big blind amount.", exception.getMessage());
    }

    /**
     * Rejects a negative buy-in because it cannot cover the big blind.
     */
    @Test
    void testRoomRejectsNegativeBuyIn() {
        BadRequestException exception = assertThrows(
                BadRequestException.class,
                () -> new Room("negative", "Negative Buy-in", "Host", 6, 10, 20, -100, null));

        assertEquals("Buy-in must be at least the big blind amount.", exception.getMessage());
    }

    /**
     * Protects the expected behavior for restoring a room via RoomRestoreBuilder.
     */
    @Test
    void testRestoreBuilder() {
        LocalDateTime createdAt = LocalDateTime.now().minusHours(2);
        LocalDateTime player1JoinTime = LocalDateTime.now().minusMinutes(30);
        LocalDateTime player2JoinTime = LocalDateTime.now().minusMinutes(15);
        Map<String, LocalDateTime> playersWithJoinTime = new LinkedHashMap<>();
        playersWithJoinTime.put("Alice", player1JoinTime);
        playersWithJoinTime.put("Bob", player2JoinTime);

        Room restoredRoom = Room.restoreBuilder()
                .roomId("room-restore-999")
                .roomName("Restored High Stakes Room")
                .hostName("Alice")
                .maxPlayers(8)
                .smallBlind(25)
                .bigBlind(50)
                .buyIn(2500)
                .password("secret-pass")
                .createdAt(createdAt)
                .playersWithJoinTime(playersWithJoinTime)
                .gameStarted(true)
                .build();

        assertNotNull(restoredRoom);
        assertEquals("room-restore-999", restoredRoom.getRoomId());
        assertEquals("Restored High Stakes Room", restoredRoom.getRoomName());
        assertEquals("Alice", restoredRoom.getHostName());
        assertEquals(8, restoredRoom.getMaxPlayers());
        assertEquals(25, restoredRoom.getSmallBlind());
        assertEquals(50, restoredRoom.getBigBlind());
        assertEquals(2500, restoredRoom.getBuyIn());
        assertNotNull(restoredRoom.getPasswordForPersistence());
        assertNotEquals("secret-pass", restoredRoom.getPasswordForPersistence());
        assertTrue(passwordEncoder.matches("secret-pass", restoredRoom.getPasswordForPersistence()));
        assertTrue(restoredRoom.hasPassword());
        assertTrue(restoredRoom.checkPassword("secret-pass"));
        assertTrue(restoredRoom.checkPassword("secret-pass", passwordEncoder));
        assertEquals(createdAt, restoredRoom.getCreatedAt());
        assertEquals(playersWithJoinTime, restoredRoom.getPlayersWithJoinTimeSnapshot());
        assertEquals(List.of("Alice", "Bob"), restoredRoom.getPlayers());
        assertEquals(player1JoinTime, restoredRoom.getJoinedAt("Alice"));
        assertEquals(player2JoinTime, restoredRoom.getJoinedAt("Bob"));
        assertTrue(restoredRoom.isGameStarted());
    }

    @Test
    @DisplayName("addPlayer generates opaque process-local membership ID and rejoining generates a different ID")
    void playerMembershipId_GeneratedPerAddAndDistinctOnRejoin() {
        room.addPlayer("Alice");
        String id1 = room.getMembershipId("Alice");
        assertNotNull(id1, "Membership ID must not be null");
        assertFalse(id1.trim().isEmpty(), "Membership ID must not be empty");

        room.removePlayer("Alice");
        assertNull(room.getMembershipId("Alice"), "Membership ID must be cleared after player removal");

        room.addPlayer("Alice");
        String id2 = room.getMembershipId("Alice");
        assertNotNull(id2);
        assertNotEquals(id1, id2, "Rejoining same name must receive different membership ID");
    }

    @Test
    @DisplayName("Restored room assigns fresh runtime membership IDs to all restored players")
    void restoredRoom_AssignsFreshRuntimeMembershipIds() {
        Map<String, LocalDateTime> joinTimes = new LinkedHashMap<>();
        joinTimes.put("Alice", LocalDateTime.now().minusMinutes(5));
        joinTimes.put("Bob", LocalDateTime.now().minusMinutes(3));

        Room restored = Room.restoreBuilder()
                .roomId("restored-1")
                .roomName("Restored Room")
                .hostName("Alice")
                .maxPlayers(6)
                .smallBlind(10)
                .bigBlind(20)
                .buyIn(1000)
                .playersWithJoinTime(joinTimes)
                .build();

        String aliceId = restored.getMembershipId("Alice");
        String bobId = restored.getMembershipId("Bob");
        assertNotNull(aliceId, "Restored Alice must have membership ID");
        assertNotNull(bobId, "Restored Bob must have membership ID");
        assertNotEquals(aliceId, bobId, "Each restored player must have unique membership ID");
    }

    @Test
    @DisplayName("Connection lease tracks absence deadline and connection status bounded by membership")
    void connectionLease_TracksAbsenceDeadlineAndConnectedState() {
        room.addPlayer("Alice");
        assertFalse(room.isPlayerConnected("Alice"), "Newly added player is not connected initially");
        assertNull(room.getAbsenceDeadline("Alice"), "No absence deadline until set");

        long deadline = System.currentTimeMillis() + 60_000;
        room.setAbsenceDeadline("Alice", deadline);
        assertEquals(deadline, room.getAbsenceDeadline("Alice"));

        room.setPlayerConnected("Alice", true);
        assertTrue(room.isPlayerConnected("Alice"));
        assertNull(room.getAbsenceDeadline("Alice"), "Connecting clears absence deadline");

        room.setPlayerConnected("Alice", false);
        assertFalse(room.isPlayerConnected("Alice"));

        room.removePlayer("Alice");
        assertNull(room.getAbsenceDeadline("Alice"), "Removing player clears lease deadline");
        assertFalse(room.isPlayerConnected("Alice"), "Removing player clears connected status");
    }

    @Test
    @DisplayName("Adding players up to maxPlayers capacity succeeds")
    void addPlayer_UpToCapacity_Succeeds() {
        Room smallRoom = new Room("cap-room", "Cap Room", "Host", 3, 5, 10, 500, null);
        assertFalse(smallRoom.isFull());

        smallRoom.addPlayer("P1");
        assertFalse(smallRoom.isFull());
        smallRoom.addPlayer("P2");
        assertFalse(smallRoom.isFull());
        smallRoom.addPlayer("P3");
        assertTrue(smallRoom.isFull());

        assertEquals(3, smallRoom.getPlayers().size());
        assertTrue(smallRoom.hasPlayer("P1"));
        assertTrue(smallRoom.hasPlayer("P2"));
        assertTrue(smallRoom.hasPlayer("P3"));
    }

    @Test
    @DisplayName("Attempting to add player when room is full throws IllegalStateException")
    void addPlayer_WhenRoomFull_ThrowsIllegalStateException() {
        Room smallRoom = new Room("cap-room", "Cap Room", "Host", 2, 5, 10, 500, null);
        smallRoom.addPlayer("P1");
        smallRoom.addPlayer("P2");
        assertTrue(smallRoom.isFull());

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> smallRoom.addPlayer("P3"));
        assertEquals("Room is full", ex.getMessage());
        assertEquals(2, smallRoom.getPlayers().size());
        assertFalse(smallRoom.hasPlayer("P3"));
    }

    @Test
    @DisplayName("Adding existing player when room is full throws BadRequestException, not capacity exception")
    void addPlayer_ExistingPlayerWhenRoomFull_ThrowsBadRequestExceptionNotCapacityException() {
        Room smallRoom = new Room("cap-room", "Cap Room", "Host", 2, 5, 10, 500, null);
        smallRoom.addPlayer("P1");
        smallRoom.addPlayer("P2");
        assertTrue(smallRoom.isFull());

        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> smallRoom.addPlayer("P1"));
        assertTrue(ex.getMessage().contains("already in the room"));
        assertEquals(2, smallRoom.getPlayers().size());
    }

    @Test
    @DisplayName("isFull correctly reflects capacity transitions across adds and removes")
    void isFull_ReflectsCapacityTransitions() {
        Room smallRoom = new Room("cap-room", "Cap Room", "Host", 2, 5, 10, 500, null);
        assertFalse(smallRoom.isFull());

        smallRoom.addPlayer("Alice");
        assertFalse(smallRoom.isFull());

        smallRoom.addPlayer("Bob");
        assertTrue(smallRoom.isFull());

        smallRoom.removePlayer("Alice");
        assertFalse(smallRoom.isFull());

        smallRoom.addPlayer("Charlie");
        assertTrue(smallRoom.isFull());
    }

    @Test
    @DisplayName("Concurrent addPlayer calls strictly enforce capacity invariant")
    void addPlayer_ConcurrentJoins_StrictlyEnforcesMaxPlayers() throws InterruptedException {
        int capacity = 4;
        Room concurrentRoom = new Room("concurrent-room", "Concurrent Room", "Host", capacity, 5, 10, 500, null);
        int totalThreads = 12;
        ExecutorService executor = Executors.newFixedThreadPool(totalThreads);
        CountDownLatch readyLatch = new CountDownLatch(totalThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(totalThreads);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger fullCount = new AtomicInteger(0);

        try {
            for (int i = 0; i < totalThreads; i++) {
                final String playerName = "Player" + i;
                executor.submit(() -> {
                    readyLatch.countDown();
                    try {
                        startLatch.await();
                        concurrentRoom.addPlayer(playerName);
                        successCount.incrementAndGet();
                    } catch (IllegalStateException e) {
                        if ("Room is full".equals(e.getMessage())) {
                            fullCount.incrementAndGet();
                        }
                    } catch (Exception ignored) {
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            readyLatch.await(5, TimeUnit.SECONDS);
            startLatch.countDown();
            assertTrue(doneLatch.await(5, TimeUnit.SECONDS), "All concurrent join threads must finish");
        } finally {
            // Shut the pool down even if an assertion above fails, so no worker thread leaks.
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "Executor must terminate promptly");
        }

        assertEquals(capacity, successCount.get(), "Exactly maxPlayers joins must succeed");
        assertEquals(totalThreads - capacity, fullCount.get(), "Excess join attempts must fail with Room is full");
        assertEquals(capacity, concurrentRoom.getPlayers().size(), "Room player count must equal maxPlayers");
        assertTrue(concurrentRoom.isFull(), "Room must report isFull() == true");
    }

    @Test
    @DisplayName("restoreBuilder rejects snapshot if player count exceeds maxPlayers")
    void restoreBuilder_ExceedingMaxPlayers_ThrowsIllegalStateException() {
        Map<String, LocalDateTime> joinTimes = new LinkedHashMap<>();
        joinTimes.put("P1", LocalDateTime.now());
        joinTimes.put("P2", LocalDateTime.now());
        joinTimes.put("P3", LocalDateTime.now());

        assertThrows(IllegalStateException.class, () -> Room.restoreBuilder()
                .roomId("overflow-room")
                .roomName("Overflow Room")
                .hostName("P1")
                .maxPlayers(2)
                .smallBlind(10)
                .bigBlind(20)
                .buyIn(1000)
                .playersWithJoinTime(joinTimes)
                .build());
    }

    @Test
    @DisplayName("Room hashes raw passwords with BCrypt and protects cleartext from getPassword")
    void passwordHashing_ConstructorHashesRawPassword_NeverStoresPlaintext() {
        String raw = "MySecretPass_42!";
        Room privateRoom = new Room("priv-1", "Private Room", "Host", 4, 10, 20, 500, raw);

        assertTrue(privateRoom.hasPassword());
        assertNull(privateRoom.getPassword(), "getPassword must return null to prevent cleartext exposure");
        assertNotNull(privateRoom.getPasswordHash());
        assertNotEquals(raw, privateRoom.getPasswordHash());
        assertTrue(privateRoom.getPasswordHash().startsWith("$2a$") || privateRoom.getPasswordHash().startsWith("$2b$"));
        assertEquals(privateRoom.getPasswordHash(), privateRoom.getPasswordForPersistence());

        // Constant-time checks with and without PasswordEncoder bean
        assertTrue(privateRoom.checkPassword(raw, passwordEncoder));
        assertTrue(privateRoom.matchesPassword(raw, passwordEncoder));
        assertTrue(privateRoom.checkPassword(raw));

        assertFalse(privateRoom.checkPassword("WrongPassword", passwordEncoder));
        assertFalse(privateRoom.matchesPassword("WrongPassword", passwordEncoder));
        assertFalse(privateRoom.checkPassword("WrongPassword"));

        assertFalse(privateRoom.checkPassword(null, passwordEncoder));
        assertFalse(privateRoom.checkPassword(null));
        assertFalse(privateRoom.checkPassword("", passwordEncoder));
        assertFalse(privateRoom.checkPassword(""));
    }

    @Test
    @DisplayName("Room preserves pre-computed BCrypt hash without double-hashing")
    void passwordHashing_PreHashedPasswordPreservedWithoutDoubleHashing() {
        String raw = "AlreadyHashedSecret";
        String bcryptHash = passwordEncoder.encode(raw);

        Room preHashedRoom = new Room("pre-1", "PreHashed Room", "Host", 4, 10, 20, 500, bcryptHash);
        assertEquals(bcryptHash, preHashedRoom.getPasswordHash(), "Pre-computed hash must be preserved as-is");
        assertTrue(preHashedRoom.checkPassword(raw, passwordEncoder));
        assertTrue(preHashedRoom.checkPassword(raw));
        assertFalse(preHashedRoom.checkPassword("invalid", passwordEncoder));
    }

    @Test
    @DisplayName("RoomRestoreBuilder preserves password hash and matches raw password with PasswordEncoder")
    void restoreBuilder_PreservesPasswordHashAndVerifiesRawPassword() {
        String raw = "RestoredSecretPass";
        String hash = passwordEncoder.encode(raw);

        Room restored = Room.restoreBuilder()
                .roomId("restored-pw")
                .roomName("Restored PW Room")
                .hostName("Host")
                .maxPlayers(4)
                .smallBlind(10)
                .bigBlind(20)
                .buyIn(500)
                .passwordHash(hash)
                .build();

        assertEquals(hash, restored.getPasswordHash());
        assertEquals(hash, restored.getPasswordForPersistence());
        assertTrue(restored.hasPassword());
        assertTrue(restored.checkPassword(raw, passwordEncoder));
        assertTrue(restored.matchesPassword(raw, passwordEncoder));
        assertFalse(restored.checkPassword("wrong-pass", passwordEncoder));
    }

    @Test
    @DisplayName("Room.toString() masks password and does not expose cleartext or hash")
    void toString_ProtectsPasswordAndNeverExposesPlaintextOrHash() {
        String raw = "SuperSecretPassword123";
        Room privateRoom = new Room("sec-room", "Secret Room", "HostUser", 4, 10, 20, 500, raw);

        String str = privateRoom.toString();
        assertTrue(str.contains("[password=[PROTECTED]]"), "toString must mask password with [PROTECTED]");
        assertFalse(str.contains(raw), "toString must not expose raw password");
        assertFalse(str.contains(privateRoom.getPasswordHash()), "toString must not expose password hash");

        Room publicRoom = new Room("pub-room", "Public Room", "HostUser", 4, 10, 20, 500, null);
        assertTrue(publicRoom.toString().contains("[password=none]"), "public room toString displays [password=none]");
    }

    @Test
    @DisplayName("Public room checkPassword always returns true regardless of input")
    void publicRoom_CheckPasswordAlwaysReturnsTrueRegardlessOfInput() {
        Room publicRoom = new Room("pub-room", "Public Room", "HostUser", 4, 10, 20, 500, null);

        assertFalse(publicRoom.hasPassword());
        assertNull(publicRoom.getPasswordHash());
        assertNull(publicRoom.getPassword());
        assertTrue(publicRoom.checkPassword("any-input", passwordEncoder));
        assertTrue(publicRoom.matchesPassword("any-input", passwordEncoder));
        assertTrue(publicRoom.checkPassword(null, passwordEncoder));
        assertTrue(publicRoom.checkPassword(""));
    }
}
