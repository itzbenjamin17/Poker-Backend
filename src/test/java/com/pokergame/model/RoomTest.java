package com.pokergame.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pokergame.exception.BadRequestException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Unit tests for {@link Room}.
 */
@Tag("unit")
@DisplayName("Room model")
class RoomTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-01-15T10:00:00Z"), ZoneOffset.UTC);
    private static final LocalDateTime FIXED_CREATED_AT = LocalDateTime.of(2026, 1, 15, 10, 0, 0);

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);
    private Room room;

    @BeforeEach
    void setUp() {
        room = new Room("room123", "Test Room", "HostPlayer", 6, 10, 20, 1000, null, FIXED_CLOCK);
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
        assertEquals(FIXED_CREATED_AT, room.getCreatedAt());
        assertEquals(0, room.getPlayers().size());
    }

    @Test
    void testRoomCreationWithPassword() {
        Room secureRoom = new Room("room456", "Private Room", "Host", 4, 5, 10, 500,
                passwordEncoder.encode("secret123"), FIXED_CLOCK);

        assertTrue(secureRoom.hasPassword());
        assertTrue(secureRoom.checkPassword("secret123", passwordEncoder));
        assertFalse(secureRoom.checkPassword("wrong", passwordEncoder));
        assertFalse(secureRoom.checkPassword("", passwordEncoder));
        assertFalse(secureRoom.checkPassword(null, passwordEncoder));
    }

    @Test
    @DisplayName("should treat whitespace-only password as absent (null)")
    void testRoomCreationWithWhitespaceOnlyPassword() {
        Room roomEmptyPass = new Room("room-empty", "Empty Pass Room", "Host", 4, 5, 10, 500, "", FIXED_CLOCK);
        assertFalse(roomEmptyPass.hasPassword());
        assertNull(roomEmptyPass.getPasswordHash());
        assertTrue(roomEmptyPass.checkPassword(null, passwordEncoder));
        assertTrue(roomEmptyPass.checkPassword("any", passwordEncoder));

        Room roomSpacesPass = new Room("room-spaces", "Spaces Pass Room", "Host", 4, 5, 10, 500, "   ", FIXED_CLOCK);
        assertFalse(roomSpacesPass.hasPassword());
        assertNull(roomSpacesPass.getPasswordHash());
        assertTrue(roomSpacesPass.checkPassword(null, passwordEncoder));
        assertTrue(roomSpacesPass.checkPassword("any", passwordEncoder));
    }

    @Test
    @DisplayName("should preserve leading and trailing spaces in password")
    void testRoomCreationWithPasswordPreservingSpaces() {
        Room roomPreservedPass = new Room("room-spaces-kept", "Kept Pass Room", "Host", 4, 5, 10, 500,
                passwordEncoder.encode("  secret123  "), FIXED_CLOCK);
        assertTrue(roomPreservedPass.hasPassword());
        assertNotNull(roomPreservedPass.getPasswordHash());
        assertNotEquals("  secret123  ", roomPreservedPass.getPasswordHash());
        assertTrue(roomPreservedPass.checkPassword("  secret123  ", passwordEncoder));
        assertFalse(roomPreservedPass.checkPassword("secret123", passwordEncoder));
        assertFalse(roomPreservedPass.checkPassword(null, passwordEncoder));
    }

    @Test
    void testRoomCreationValidationInvariants() {
        assertEquals("Room ID cannot be null or empty.",
                assertThrows(IllegalStateException.class,
                        () -> new Room(null, "Room", "Host", 6, 10, 20, 1000, null, FIXED_CLOCK)).getMessage());
        assertEquals("Room ID cannot be null or empty.",
                assertThrows(IllegalStateException.class,
                        () -> new Room("   ", "Room", "Host", 6, 10, 20, 1000, null, FIXED_CLOCK)).getMessage());

        assertEquals("Room name cannot be null or empty.",
                assertThrows(IllegalStateException.class,
                        () -> new Room("room123", null, "Host", 6, 10, 20, 1000, null, FIXED_CLOCK)).getMessage());
        assertEquals("Room name cannot be null or empty.",
                assertThrows(IllegalStateException.class,
                        () -> new Room("room123", "   ", "Host", 6, 10, 20, 1000, null, FIXED_CLOCK)).getMessage());

        assertEquals("Host name cannot be null or empty.",
                assertThrows(IllegalStateException.class,
                        () -> new Room("room123", "Room", null, 6, 10, 20, 1000, null, FIXED_CLOCK)).getMessage());
        assertEquals("Host name cannot be null or empty.",
                assertThrows(IllegalStateException.class,
                        () -> new Room("room123", "Room", "   ", 6, 10, 20, 1000, null, FIXED_CLOCK)).getMessage());

        assertEquals("Max players must be between 2 and 10.",
                assertThrows(IllegalStateException.class,
                        () -> new Room("room123", "Room", "Host", 1, 10, 20, 1000, null, FIXED_CLOCK)).getMessage());
        assertEquals("Max players must be between 2 and 10.",
                assertThrows(IllegalStateException.class,
                        () -> new Room("room123", "Room", "Host", 11, 10, 20, 1000, null, FIXED_CLOCK)).getMessage());

        assertEquals(2, new Room("room123", "Room", "Host", 2, 10, 20, 1000, null, FIXED_CLOCK).getMaxPlayers());
        assertEquals(10, new Room("room123", "Room", "Host", 10, 10, 20, 1000, null, FIXED_CLOCK).getMaxPlayers());

        assertEquals("Small blind must be at least 1.",
                assertThrows(IllegalStateException.class,
                        () -> new Room("room123", "Room", "Host", 6, 0, 20, 1000, null, FIXED_CLOCK)).getMessage());
        assertEquals("Small blind must be at least 1.",
                assertThrows(IllegalStateException.class,
                        () -> new Room("room123", "Room", "Host", 6, -5, 20, 1000, null, FIXED_CLOCK)).getMessage());

        assertEquals("Big blind must be at least twice the small blind.",
                assertThrows(IllegalStateException.class,
                        () -> new Room("room123", "Room", "Host", 6, 20, 10, 1000, null, FIXED_CLOCK)).getMessage());
        assertEquals("Big blind must be at least twice the small blind.",
                assertThrows(IllegalStateException.class,
                        () -> new Room("room123", "Room", "Host", 6, 10, 10, 1000, null, FIXED_CLOCK)).getMessage());

        assertEquals("Buy-in must be at least the big blind amount.",
                assertThrows(IllegalStateException.class,
                        () -> new Room("free", "Free Game", "Host", 6, 10, 20, 0, null, FIXED_CLOCK)).getMessage());
        assertEquals("Buy-in must be at least the big blind amount.",
                assertThrows(IllegalStateException.class,
                        () -> new Room("negative", "Negative Buy-in", "Host", 6, 10, 20, -100, null, FIXED_CLOCK)).getMessage());
    }

    @Test
    void testAddRemoveAndHasPlayer() {
        assertFalse(room.hasPlayer("Alice"));
        room.addPlayer("Alice");
        room.addPlayer("Bob");
        room.addPlayer("Charlie");

        assertEquals(List.of("Alice", "Bob", "Charlie"), room.getPlayers());
        assertTrue(room.hasPlayer("Alice"));
        assertTrue(room.hasPlayer("Bob"));
        assertTrue(room.hasPlayer("Charlie"));

        assertThrows(BadRequestException.class, () -> room.addPlayer("Bob"));
        assertEquals(3, room.getPlayers().size());

        room.removePlayer("Bob");
        assertEquals(List.of("Alice", "Charlie"), room.getPlayers());
        assertFalse(room.hasPlayer("Bob"));
        assertThrows(BadRequestException.class, () -> room.removePlayer("NonExistent"));

        room.addPlayer("Bob");
        assertEquals(List.of("Alice", "Charlie", "Bob"), room.getPlayers());
    }

    @Test
    void testHasPlayerNameIgnoringCase() {
        room.addPlayer("Alice");

        assertTrue(room.hasPlayerNameIgnoringCase("alice"));
        assertTrue(room.hasPlayerNameIgnoringCase("ALICE"));
        assertFalse(room.hasPlayerNameIgnoringCase("Alicia"));
        assertFalse(room.hasPlayer("alice"));
    }

    @Test
    void testCheckPasswordCaseSensitive() {
        Room privateRoom = new Room("room456", "Private", "Host", 6, 10, 20, 1000,
                passwordEncoder.encode("Secret123"), FIXED_CLOCK);

        assertTrue(privateRoom.checkPassword("Secret123", passwordEncoder));
        assertFalse(privateRoom.checkPassword("secret123", passwordEncoder));
        assertFalse(privateRoom.checkPassword("SECRET123", passwordEncoder));
    }

    @Test
    void testRestoreBuilderPreservesFieldsAndExplicitMembershipIds() {
        LocalDateTime createdAt = FIXED_CREATED_AT.minusHours(2);
        List<String> players = List.of("Alice", "Bob", "Charlie");
        Map<String, String> persistedMembershipIds = Map.of("Alice", "mem-alice-1", "Bob", "   ");

        String encodedPassword = passwordEncoder.encode("secret-pass");
        Room restoredRoom = Room.restoreBuilder()
                .roomId("room-restore-999")
                .roomName("Restored High Stakes Room")
                .hostName("Alice")
                .maxPlayers(8)
                .smallBlind(25)
                .bigBlind(50)
                .buyIn(2500)
                .passwordHash(encodedPassword)
                .createdAt(createdAt)
                .players(players)
                .playerMembershipIds(persistedMembershipIds)
                .gameStarted(true)
                .inactivityDeadlineEpochMs(777_000L)
                .build();

        assertEquals("room-restore-999", restoredRoom.getRoomId());
        assertEquals("Restored High Stakes Room", restoredRoom.getRoomName());
        assertEquals("Alice", restoredRoom.getHostName());
        assertEquals(8, restoredRoom.getMaxPlayers());
        assertEquals(25, restoredRoom.getSmallBlind());
        assertEquals(50, restoredRoom.getBigBlind());
        assertEquals(2500, restoredRoom.getBuyIn());
        assertEquals(encodedPassword, restoredRoom.getPasswordHash());
        assertTrue(restoredRoom.hasPassword());
        assertTrue(restoredRoom.checkPassword("secret-pass", passwordEncoder));
        assertFalse(restoredRoom.checkPassword("wrong-pass", passwordEncoder));
        assertEquals(createdAt, restoredRoom.getCreatedAt());
        assertEquals(List.of("Alice", "Bob", "Charlie"), restoredRoom.getPlayers());
        assertTrue(restoredRoom.isGameStarted());
        assertEquals(777_000L, restoredRoom.getInactivityDeadlineEpochMs());

        assertEquals("mem-alice-1", restoredRoom.getMembershipId("Alice"));
        assertNotNull(restoredRoom.getMembershipId("Bob"));
        assertFalse(restoredRoom.getMembershipId("Bob").isBlank());
        assertNotNull(restoredRoom.getMembershipId("Charlie"));
        assertNotEquals(restoredRoom.getMembershipId("Bob"), restoredRoom.getMembershipId("Charlie"));
    }

    @Test
    @DisplayName("addPlayer generates opaque process-local membership ID and rejoining generates a different ID")
    void playerMembershipId_GeneratedPerAddAndDistinctOnRejoin() {
        room.addPlayer("Alice");
        String id1 = room.getMembershipId("Alice");
        assertNotNull(id1);
        assertFalse(id1.trim().isEmpty());

        room.removePlayer("Alice");
        assertNull(room.getMembershipId("Alice"));

        room.addPlayer("Alice");
        String id2 = room.getMembershipId("Alice");
        assertNotNull(id2);
        assertNotEquals(id1, id2);
    }

    @Test
    @DisplayName("Absence deadline and connection status are tracked per membership and cleared on connect or removal")
    void connectionState_TracksAbsenceDeadlineAndConnectedState() {
        room.addPlayer("Alice");
        assertFalse(room.isPlayerConnected("Alice"));
        assertNull(room.getAbsenceDeadline("Alice"));

        long deadline = 1_700_000_060_000L;
        room.setAbsenceDeadline("Alice", deadline);
        assertEquals(deadline, room.getAbsenceDeadline("Alice"));

        room.setPlayerConnected("Alice", true);
        assertTrue(room.isPlayerConnected("Alice"));
        assertNull(room.getAbsenceDeadline("Alice"));

        room.setPlayerConnected("Alice", false);
        assertFalse(room.isPlayerConnected("Alice"));

        room.setAbsenceDeadline("Alice", deadline);
        room.setAbsenceDeadline("Alice", null);
        assertNull(room.getAbsenceDeadline("Alice"));

        room.setAbsenceDeadline("Alice", deadline);
        room.setPlayerConnected("Alice", true);
        room.removePlayer("Alice");
        assertNull(room.getAbsenceDeadline("Alice"));
        assertFalse(room.isPlayerConnected("Alice"));
    }

    @Test
    @DisplayName("Inactivity deadline methods evaluate exact boundary (<, ==, >) and null state")
    void inactivityDeadline_EvaluatesBoundariesAndNullState() {
        assertNull(room.getInactivityDeadlineEpochMs());
        assertFalse(room.isInactivityExpired(10_000L));
        assertFalse(room.hasInactivityDeadline(10_000L));

        room.extendGameInactivityDeadline(1_000L, 9_000L);
        assertEquals(10_000L, room.getInactivityDeadlineEpochMs());
        assertTrue(room.hasInactivityDeadline(10_000L));
        assertFalse(room.hasInactivityDeadline(9_999L));

        assertFalse(room.isInactivityExpired(9_999L));
        assertTrue(room.isInactivityExpired(10_000L));
        assertTrue(room.isInactivityExpired(10_001L));

        room.setInactivityDeadlineEpochMs(null);
        assertFalse(room.isInactivityExpired(10_000L));
        assertFalse(room.hasInactivityDeadline(10_000L));
    }

    @Test
    @DisplayName("setHostName updates host and rejects null or blank names")
    void setHostName_UpdatesHostAndRejectsNullOrBlank() {
        room.setHostName("NewHost");
        assertEquals("NewHost", room.getHostName());

        assertThrows(IllegalArgumentException.class, () -> room.setHostName(null));
        assertThrows(IllegalArgumentException.class, () -> room.setHostName("   "));
        assertEquals("NewHost", room.getHostName());
    }

    @Test
    @DisplayName("Adding players up to maxPlayers capacity succeeds and rejects overflow")
    void addPlayer_CapacityTransitionsAndFullRejection() {
        Room smallRoom = new Room("cap-room", "Cap Room", "Host", 2, 5, 10, 500, null, FIXED_CLOCK);
        assertFalse(smallRoom.isFull());

        smallRoom.addPlayer("P1");
        assertFalse(smallRoom.isFull());
        smallRoom.addPlayer("P2");
        assertTrue(smallRoom.isFull());

        BadRequestException duplicateWhenFull = assertThrows(BadRequestException.class,
                () -> smallRoom.addPlayer("P1"));
        assertTrue(duplicateWhenFull.getMessage().contains("already in the room"));

        IllegalStateException fullEx = assertThrows(IllegalStateException.class,
                () -> smallRoom.addPlayer("P3"));
        assertEquals("Room is full", fullEx.getMessage());

        smallRoom.removePlayer("P1");
        assertFalse(smallRoom.isFull());
        smallRoom.addPlayer("P3");
        assertTrue(smallRoom.isFull());
    }

    @Test
    @DisplayName("getPlayerMembershipIdsSnapshot returns an independent copy")
    void playerMembershipIdsSnapshot_ReturnsIndependentCopy() {
        room.addPlayer("Alice");
        room.addPlayer("Bob");
        String aliceId = room.getMembershipId("Alice");
        String bobId = room.getMembershipId("Bob");

        Map<String, String> snapshot = room.getPlayerMembershipIdsSnapshot();
        assertEquals(2, snapshot.size());
        assertEquals(aliceId, snapshot.get("Alice"));
        assertEquals(bobId, snapshot.get("Bob"));

        room.addPlayer("Charlie");
        room.removePlayer("Alice");
        assertEquals(2, snapshot.size());
        assertEquals(aliceId, snapshot.get("Alice"));
        assertFalse(snapshot.containsKey("Charlie"));
    }

    @Test
    @DisplayName("restoreBuilder rejects snapshot if player count exceeds maxPlayers")
    void restoreBuilder_ExceedingMaxPlayers_ThrowsIllegalStateException() {
        assertThrows(IllegalStateException.class, () -> Room.restoreBuilder()
                .roomId("overflow-room")
                .roomName("Overflow Room")
                .hostName("P1")
                .maxPlayers(2)
                .smallBlind(10)
                .bigBlind(20)
                .buyIn(1000)
                .createdAt(FIXED_CREATED_AT)
                .players(List.of("P1", "P2", "P3"))
                .build());
    }

    @Test
    @DisplayName("Room.toString() masks password and does not expose cleartext or hash")
    void toString_ProtectsPasswordAndNeverExposesPlaintextOrHash() {
        String raw = "SuperSecretPassword123";
        Room privateRoom = new Room("sec-room", "Secret Room", "HostUser", 4, 10, 20, 500,
                passwordEncoder.encode(raw), FIXED_CLOCK);

        String str = privateRoom.toString();
        assertTrue(str.contains("[password=[PROTECTED]]"));
        assertFalse(str.contains(raw));
        assertFalse(str.contains(privateRoom.getPasswordHash()));

        room.addPlayer("Player1");
        room.addPlayer("Player2");
        assertTrue(room.toString().contains("2 players [password=none]"));
    }

    @Test
    @DisplayName("Public room checkPassword always returns true regardless of input")
    void publicRoom_CheckPasswordAlwaysReturnsTrueRegardlessOfInput() {
        assertFalse(room.hasPassword());
        assertNull(room.getPasswordHash());
        assertTrue(room.checkPassword("any-input", passwordEncoder));
        assertTrue(room.checkPassword(null, passwordEncoder));
        assertTrue(room.checkPassword("", passwordEncoder));
    }

    @Test
    @DisplayName("setMembershipId rejects non-members and blank membership IDs")
    void setMembershipId_RejectsNonMembersAndBlankIds() {
        assertThrows(IllegalStateException.class, () -> room.setMembershipId("Unknown", "mem-1"));

        room.addPlayer("Alice");
        assertThrows(IllegalArgumentException.class, () -> room.setMembershipId("Alice", null));
        assertThrows(IllegalArgumentException.class, () -> room.setMembershipId("Alice", "   "));

        room.setMembershipId("Alice", "mem-custom");
        assertEquals("mem-custom", room.getMembershipId("Alice"));
    }

    @Test
    @DisplayName("checkPassword rejects null PasswordEncoder")
    void checkPassword_RejectsNullEncoder() {
        Room privateRoom = new Room("priv-null-enc", "Private", "Host", 4, 10, 20, 500,
                passwordEncoder.encode("secret"), FIXED_CLOCK);

        assertThrows(NullPointerException.class, () -> privateRoom.checkPassword("secret", null));
        assertThrows(NullPointerException.class, () -> room.checkPassword("any", null));
    }

    @Test
    @DisplayName("restoreBuilder rejects null createdAt and null Clock in constructor")
    void restoreBuilderAndClockConstructor_RejectNullTimestamps() {
        assertThrows(NullPointerException.class, () -> Room.restoreBuilder()
                .roomId("restored-null-created")
                .roomName("Restored Room")
                .hostName("Alice")
                .maxPlayers(6)
                .smallBlind(10)
                .bigBlind(20)
                .buyIn(1000)
                .build());

        assertThrows(NullPointerException.class,
                () -> new Room("clock-room", "Clock Room", "Host", 6, 10, 20, 1000, null, (Clock) null));
    }

    @Test
    @DisplayName("restoreBuilder rejects duplicate, null, or blank player names")
    void restoreBuilder_RejectsInvalidPlayerNames() {
        assertThrows(IllegalStateException.class, () -> Room.restoreBuilder()
                .roomId("dup-room").roomName("Dup Room").hostName("Alice")
                .maxPlayers(4).smallBlind(10).bigBlind(20).buyIn(1000).createdAt(FIXED_CREATED_AT)
                .players(List.of("Alice", "Alice"))
                .build());

        assertThrows(IllegalStateException.class, () -> Room.restoreBuilder()
                .roomId("null-player-room").roomName("Null Player Room").hostName("Alice")
                .maxPlayers(4).smallBlind(10).bigBlind(20).buyIn(1000).createdAt(FIXED_CREATED_AT)
                .players(Arrays.asList("Alice", null))
                .build());

        assertThrows(IllegalStateException.class, () -> Room.restoreBuilder()
                .roomId("blank-player-room").roomName("Blank Player Room").hostName("Alice")
                .maxPlayers(4).smallBlind(10).bigBlind(20).buyIn(1000).createdAt(FIXED_CREATED_AT)
                .players(List.of("Alice", "   "))
                .build());
    }
}
