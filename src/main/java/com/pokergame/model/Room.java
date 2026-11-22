package com.pokergame.model;

import static java.util.Objects.requireNonNull;

import com.pokergame.exception.BadRequestException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Represents a poker game room/lobby where players can join and play together.
 * Manages room configuration including blinds, buy-in, player capacity, and
 * access control.
 *
 * <p>
 * Rooms are created with fixed game parameters (blinds, buy-in, max players)
 * that cannot be changed after creation. Each room can be password-protected
 * for private games. Callers hold the room lock when reading
 * or mutating a published room.
 * </p>
 */
public class Room {
    /** Fewest players a room can be created for, and the fewest a game can start with. */
    public static final int MIN_PLAYERS = 2;
    /** Most players a room can seat. */
    public static final int MAX_PLAYERS = 10;
    /** Smallest allowed small blind. */
    public static final int MIN_SMALL_BLIND = 1;
    /** The big blind must be at least this many times the small blind. */
    public static final int BIG_BLIND_MULTIPLE = 2;

    private final String roomId;
    private final String roomName;
    private final int maxPlayers;
    private final int smallBlind;
    private final int bigBlind;
    private final int buyIn;
    private final String passwordHash;
    private final LocalDateTime createdAt;

    // Mutable state below requires the room lock once the room is registered/live.

    private String hostName;
    // Players in join order (saved in WAL snapshots).
    private final Set<String> players;
    // Per-join session IDs used to validate JWTs (saved in WAL snapshots).
    private final Map<String, String> playerMembershipIds = new LinkedHashMap<>();
    private boolean isGameStarted;
    private Long inactivityDeadlineEpochMs;

    // Live server state (rebuilt on restart, not saved to WAL).
    // Volatile because the lock-free room-name lookup reads it to let a finished room's name be reused.
    private volatile boolean isFinished;
    private final Map<String, Long> playerAbsenceDeadlines = new LinkedHashMap<>();
    private final Set<String> connectedPlayers = new HashSet<>();

    /**
     * Creates a new poker room with the specified configuration using the system default clock.
     *
     * @param roomId       unique identifier for the room
     * @param roomName     display name for the room
     * @param hostName     name of the player creating/hosting the room
     * @param maxPlayers   maximum number of players allowed (must be 2-10)
     * @param smallBlind   the small blind amount (must be at least 1)
     * @param bigBlind     the big blind amount (must be at least twice the small blind)
     * @param buyIn        the buy-in amount for joining the game
     * @param passwordHash encoded password hash for private rooms, or {@code null}/blank for public rooms
     * @throws IllegalStateException if any configuration invariant fails
     */
    public Room(String roomId, String roomName, String hostName, int maxPlayers,
            int smallBlind, int bigBlind, int buyIn, String passwordHash) {
        this(roomId, roomName, hostName, maxPlayers, smallBlind, bigBlind, buyIn, passwordHash,
                Clock.systemDefaultZone());
    }

    /**
     * Creates a new poker room with the specified configuration and clock.
     *
     * @param roomId       unique identifier for the room
     * @param roomName     display name for the room
     * @param hostName     name of the player creating/hosting the room
     * @param maxPlayers   maximum number of players allowed (must be 2-10)
     * @param smallBlind   the small blind amount (must be at least 1)
     * @param bigBlind     the big blind amount (must be at least twice the small blind)
     * @param buyIn        the buy-in amount for joining the game
     * @param passwordHash encoded password hash for private rooms, or {@code null}/blank for public rooms
     * @param clock        clock used to record the room creation timestamp
     * @throws IllegalStateException if any configuration invariant fails
     */
    public Room(String roomId, String roomName, String hostName, int maxPlayers,
            int smallBlind, int bigBlind, int buyIn, String passwordHash, Clock clock) {
        requireNonNull(clock, "clock must not be null");
        validateConfig(roomId, roomName, hostName, maxPlayers, smallBlind, bigBlind, buyIn);

        this.roomId = roomId;
        this.roomName = roomName;
        this.hostName = hostName;
        this.maxPlayers = maxPlayers;
        this.smallBlind = smallBlind;
        this.bigBlind = bigBlind;
        this.buyIn = buyIn;
        this.passwordHash = normalizePasswordHash(passwordHash);
        this.players = new LinkedHashSet<>();
        this.createdAt = LocalDateTime.now(clock);
    }

    private Room(RoomRestoreBuilder builder) {
        requireNonNull(builder.createdAt, "createdAt must not be null");
        validateConfig(
                builder.roomId,
                builder.roomName,
                builder.hostName,
                builder.maxPlayers,
                builder.smallBlind,
                builder.bigBlind,
                builder.buyIn);
        validateRestoredPlayers(builder.players, builder.maxPlayers);

        this.roomId = builder.roomId;
        this.roomName = builder.roomName;
        this.hostName = builder.hostName;
        this.maxPlayers = builder.maxPlayers;
        this.smallBlind = builder.smallBlind;
        this.bigBlind = builder.bigBlind;
        this.buyIn = builder.buyIn;
        this.passwordHash = normalizePasswordHash(builder.passwordHash);
        this.createdAt = builder.createdAt;
        this.players = new LinkedHashSet<>();
        if (builder.players != null) {
            this.players.addAll(builder.players);
            for (String playerName : this.players) {
                String persistedId = builder.playerMembershipIds != null
                        ? builder.playerMembershipIds.get(playerName)
                        : null;
                String membershipId = (persistedId != null && !persistedId.isBlank())
                        ? persistedId
                        : UUID.randomUUID().toString();
                this.playerMembershipIds.put(playerName, membershipId);
            }
        }
        this.isGameStarted = builder.gameStarted;
        this.inactivityDeadlineEpochMs = builder.inactivityDeadlineEpochMs;
    }

    /**
     * Creates a builder for restoring a room instance from snapshot state without
     * regenerating creation time or join order.
     *
     * @return a new RoomRestoreBuilder
     */
    public static RoomRestoreBuilder restoreBuilder() {
        return new RoomRestoreBuilder();
    }

    public boolean isFull() {
        return players.size() >= maxPlayers;
    }

    /**
     * Adds a player to the room if they are not already present and the room has capacity.
     *
     * @param playerName the name of the player to add
     * @return the session-bound membership ID for the added player
     * @throws BadRequestException   if the player is already in the room
     * @throws IllegalStateException if the room is full
     */
    public String addPlayer(String playerName) {
        if (players.contains(playerName)) {
            throw new BadRequestException(
                    String.format("Cannot add %s to room as they are already in the room", playerName));
        }
        if (isFull()) {
            throw new IllegalStateException("Room is full");
        }
        players.add(playerName);
        String membershipId = UUID.randomUUID().toString();
        playerMembershipIds.put(playerName, membershipId);
        return membershipId;
    }

    /**
     * Removes a player from the room.
     *
     * @param playerName the name of the player to remove
     * @throws BadRequestException if the player is not currently in the room
     */
    public void removePlayer(String playerName) {
        if (!players.contains(playerName)) {
            throw new BadRequestException(
                    String.format("Cannot remove %s from room as they are not already in the room", playerName));
        }
        players.remove(playerName);
        playerMembershipIds.remove(playerName);
        playerAbsenceDeadlines.remove(playerName);
        connectedPlayers.remove(playerName);
    }

    public boolean hasPlayer(String playerName) {
        return players.contains(playerName);
    }

    /**
     * Checks whether a player in this room has the given name, ignoring case. Used at join
     * so names such as "Alice" and "alice" cannot share a table (room names are already
     * case-insensitive); identity checks keep using the exact {@link #hasPlayer(String)}.
     *
     * @param playerName sanitized player name to look for
     * @return {@code true} if a player's name matches ignoring case
     */
    public boolean hasPlayerNameIgnoringCase(String playerName) {
        return players.stream().anyMatch(existing -> existing.equalsIgnoreCase(playerName));
    }

    public boolean hasPassword() {
        return passwordHash != null && !passwordHash.isEmpty();
    }

    /**
     * Verifies if the provided raw password matches the room's password hash using
     * {@code passwordEncoder}. Returns {@code true} for public rooms.
     *
     * @param rawPassword     the cleartext password to check
     * @param passwordEncoder the password encoder to use for verification
     * @return {@code true} if password matches or room is public, {@code false} otherwise
     */
    public boolean checkPassword(String rawPassword, PasswordEncoder passwordEncoder) {
        requireNonNull(passwordEncoder, "passwordEncoder must not be null");
        if (!hasPassword()) {
            return true;
        }
        if (rawPassword == null) {
            return false;
        }
        return passwordEncoder.matches(rawPassword, passwordHash);
    }

    @Override
    public String toString() {
        String protectedPass = hasPassword() ? "[PROTECTED]" : "none";
        return roomName + " (" + roomId + ")" + " by " + hostName + " with " + players.size()
                + " players [password=" + protectedPass + "]";
    }

    /**
     * Extends the active game's inactivity deadline relative to {@code nowMs}.
     *
     * @param nowMs       current epoch time in milliseconds
     * @param allowanceMs how long the game may stay idle before it expires, in milliseconds
     */
    public void extendGameInactivityDeadline(long nowMs, long allowanceMs) {
        inactivityDeadlineEpochMs = nowMs + allowanceMs;
    }

    /**
     * Returns whether the configured inactivity deadline has elapsed by {@code nowMs}.
     *
     * @param nowMs current epoch time in milliseconds
     * @return {@code true} if a deadline is configured and {@code nowMs} has reached it
     */
    public boolean isInactivityExpired(long nowMs) {
        Long deadline = inactivityDeadlineEpochMs;
        return deadline != null && nowMs >= deadline;
    }

    /**
     * Returns whether this room currently carries the exact expected inactivity deadline.
     *
     * @param expectedDeadlineMs expected epoch millisecond deadline
     * @return {@code true} if the current deadline matches {@code expectedDeadlineMs}
     */
    public boolean hasInactivityDeadline(long expectedDeadlineMs) {
        Long deadline = inactivityDeadlineEpochMs;
        return deadline != null && deadline == expectedDeadlineMs;
    }

    /**
     * Updates the current host for the room after host transfer.
     *
     * @param hostName the new host's name
     */
    public void setHostName(String hostName) {
        if (hostName == null || hostName.trim().isEmpty()) {
            throw new IllegalArgumentException("hostName must not be blank");
        }
        this.hostName = hostName;
    }

    /**
     * Captures player membership IDs without exposing the live map to persistence serialization.
     *
     * @return independent ordered player-to-membership-ID snapshot
     */
    public Map<String, String> getPlayerMembershipIdsSnapshot() {
        return new LinkedHashMap<>(playerMembershipIds);
    }

    /**
     * Updates the membership ID for an existing player in the room.
     *
     * @param playerName   the name of the player
     * @param membershipId the membership ID to assign
     * @throws IllegalStateException    if {@code playerName} is not a member of the room
     * @throws IllegalArgumentException if {@code membershipId} is null or blank
     */
    public void setMembershipId(String playerName, String membershipId) {
        if (!hasPlayer(playerName)) {
            throw new IllegalStateException("Cannot set membership ID for non-member: " + playerName);
        }
        if (membershipId == null || membershipId.isBlank()) {
            throw new IllegalArgumentException("membershipId must not be blank");
        }
        playerMembershipIds.put(playerName, membershipId);
    }

    /**
     * Sets or clears the live server absence deadline epoch ms for a player.
     *
     * @param playerName      the name of the player
     * @param deadlineEpochMs the absence deadline epoch ms, or null to clear
     */
    public void setAbsenceDeadline(String playerName, Long deadlineEpochMs) {
        if (deadlineEpochMs == null) {
            playerAbsenceDeadlines.remove(playerName);
        } else {
            playerAbsenceDeadlines.put(playerName, deadlineEpochMs);
        }
    }

    /**
     * Updates the live server connection status for a player in this room.
     * Confirming connected clears any pending absence deadline.
     *
     * @param playerName the name of the player
     * @param connected  true if connected, false if disconnected/absent
     */
    public void setPlayerConnected(String playerName, boolean connected) {
        if (connected) {
            connectedPlayers.add(playerName);
            playerAbsenceDeadlines.remove(playerName);
        } else {
            connectedPlayers.remove(playerName);
        }
    }

    private static void validateConfig(
            String roomId,
            String roomName,
            String hostName,
            int maxPlayers,
            int smallBlind,
            int bigBlind,
            int buyIn) {
        if (roomId == null || roomId.trim().isEmpty()) {
            throw new IllegalStateException("Room ID cannot be null or empty.");
        }
        if (roomName == null || roomName.trim().isEmpty()) {
            throw new IllegalStateException("Room name cannot be null or empty.");
        }
        if (hostName == null || hostName.trim().isEmpty()) {
            throw new IllegalStateException("Host name cannot be null or empty.");
        }
        if (maxPlayers < MIN_PLAYERS || maxPlayers > MAX_PLAYERS) {
            throw new IllegalStateException(
                    "Max players must be between " + MIN_PLAYERS + " and " + MAX_PLAYERS + ".");
        }
        if (smallBlind < MIN_SMALL_BLIND) {
            throw new IllegalStateException("Small blind must be at least " + MIN_SMALL_BLIND + ".");
        }
        if (bigBlind < (long) BIG_BLIND_MULTIPLE * smallBlind) {
            throw new IllegalStateException("Big blind must be at least twice the small blind.");
        }
        if (buyIn < bigBlind) {
            throw new IllegalStateException("Buy-in must be at least the big blind amount.");
        }
    }

    private static void validateRestoredPlayers(List<String> players, int maxPlayers) {
        if (players == null) {
            return;
        }
        if (players.size() > maxPlayers) {
            throw new IllegalStateException(
                    "Restored players count exceeds maxPlayers: " + players.size() + " > " + maxPlayers);
        }
        Set<String> seenPlayers = new HashSet<>();
        for (String playerName : players) {
            if (playerName == null || playerName.isBlank()) {
                throw new IllegalStateException("Restored players cannot contain null or blank names.");
            }
            if (!seenPlayers.add(playerName)) {
                throw new IllegalStateException("Duplicate restored player: " + playerName);
            }
        }
    }

    private static String normalizePasswordHash(String passwordHash) {
        return (passwordHash == null || passwordHash.trim().isEmpty()) ? null : passwordHash;
    }

    public Long getInactivityDeadlineEpochMs() {
        return inactivityDeadlineEpochMs;
    }

    public void setInactivityDeadlineEpochMs(Long deadlineEpochMs) {
        inactivityDeadlineEpochMs = deadlineEpochMs;
    }

    public String getRoomId() {
        return roomId;
    }

    public String getRoomName() {
        return roomName;
    }

    public String getHostName() {
        return hostName;
    }

    public List<String> getPlayers() {
        return new ArrayList<>(players);
    }

    public int getMaxPlayers() {
        return maxPlayers;
    }

    public int getSmallBlind() {
        return smallBlind;
    }

    public int getBigBlind() {
        return bigBlind;
    }

    public int getBuyIn() {
        return buyIn;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public boolean isGameStarted() {
        return isGameStarted;
    }

    public void setGameStarted(boolean gameStarted) {
        this.isGameStarted = gameStarted;
    }

    public boolean isFinished() {
        return isFinished;
    }

    public void setFinished(boolean finished) {
        this.isFinished = finished;
    }

    public String getMembershipId(String playerName) {
        return playerMembershipIds.get(playerName);
    }

    public Long getAbsenceDeadline(String playerName) {
        return playerAbsenceDeadlines.get(playerName);
    }

    public boolean isPlayerConnected(String playerName) {
        return connectedPlayers.contains(playerName);
    }

    /**
     * Builder for reconstructing a room instance from snapshot state.
     */
    public static class RoomRestoreBuilder {
        private String roomId;
        private String roomName;
        private String hostName;
        private int maxPlayers;
        private int smallBlind;
        private int bigBlind;
        private int buyIn;
        private String passwordHash;
        private LocalDateTime createdAt;
        private List<String> players;
        private Map<String, String> playerMembershipIds;
        private boolean gameStarted;
        private Long inactivityDeadlineEpochMs;

        public RoomRestoreBuilder roomId(String roomId) {
            this.roomId = roomId;
            return this;
        }

        public RoomRestoreBuilder roomName(String roomName) {
            this.roomName = roomName;
            return this;
        }

        public RoomRestoreBuilder hostName(String hostName) {
            this.hostName = hostName;
            return this;
        }

        public RoomRestoreBuilder maxPlayers(int maxPlayers) {
            this.maxPlayers = maxPlayers;
            return this;
        }

        public RoomRestoreBuilder smallBlind(int smallBlind) {
            this.smallBlind = smallBlind;
            return this;
        }

        public RoomRestoreBuilder bigBlind(int bigBlind) {
            this.bigBlind = bigBlind;
            return this;
        }

        public RoomRestoreBuilder buyIn(int buyIn) {
            this.buyIn = buyIn;
            return this;
        }

        public RoomRestoreBuilder passwordHash(String passwordHash) {
            this.passwordHash = passwordHash;
            return this;
        }

        public RoomRestoreBuilder createdAt(LocalDateTime createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public RoomRestoreBuilder players(List<String> players) {
            this.players = players;
            return this;
        }

        public RoomRestoreBuilder playerMembershipIds(Map<String, String> playerMembershipIds) {
            this.playerMembershipIds = playerMembershipIds;
            return this;
        }

        public RoomRestoreBuilder gameStarted(boolean gameStarted) {
            this.gameStarted = gameStarted;
            return this;
        }

        public RoomRestoreBuilder inactivityDeadlineEpochMs(Long inactivityDeadlineEpochMs) {
            this.inactivityDeadlineEpochMs = inactivityDeadlineEpochMs;
            return this;
        }

        public Room build() {
            return new Room(this);
        }
    }
}
