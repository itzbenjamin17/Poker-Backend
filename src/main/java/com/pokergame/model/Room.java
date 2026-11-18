package com.pokergame.model;

import com.pokergame.exception.BadRequestException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

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
    private String hostName;
    /**
     * Join-order and arrival timestamps for players in this room.
     * Guarded by the room lock after publication.
     */
    private final LinkedHashMap<String, LocalDateTime> playersWithJoinTime;
    /**
     * Session membership identities created per addPlayer and preserved across WAL snapshots.
     * Reads and writes require the room lock after publication.
     */
    private final Map<String, String> playerMembershipIds = new LinkedHashMap<>();

    /**
     * Process-local absence deadlines for players awaiting first connection or reconnect grace.
     * Guarded by the room lock after publication. Excluded from WAL serialization.
     */
    private final Map<String, Long> playerAbsenceDeadlines = new LinkedHashMap<>();

    /**
     * Process-local active socket presence bounded by room membership.
     * Guarded by the room lock after publication. Excluded from WAL serialization.
     */
    private final Set<String> connectedPlayers = new HashSet<>();
    private final int maxPlayers;
    private final int smallBlind;
    private final int bigBlind;
    private final int buyIn;
    private final String passwordHash;
    private final LocalDateTime createdAt;
    private boolean isGameStarted;
    private boolean isFinished;
    private Long inactivityDeadlineEpochMs;

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
        this(roomId, roomName, hostName, maxPlayers, smallBlind, bigBlind, buyIn, passwordHash,
                LocalDateTime.now(Objects.requireNonNull(clock, "clock must not be null")));
    }

    /**
     * Package-private constructor used to restore persisted Room instances with their
     * original creation timestamp.
     *
     * @param roomId       unique identifier of the room
     * @param roomName     display name of the room
     * @param hostName     name of the creator / initial host
     * @param maxPlayers   maximum capacity of the room
     * @param smallBlind   small blind amount
     * @param bigBlind     big blind amount
     * @param buyIn        buy-in amount
     * @param passwordHash encoded password hash for private rooms, or {@code null}/blank for public rooms
     * @param createdAt    persisted creation timestamp
     * @throws IllegalStateException if any configuration invariant fails
     */
    Room(String roomId, String roomName, String hostName, int maxPlayers,
            int smallBlind, int bigBlind, int buyIn, String passwordHash, LocalDateTime createdAt) {
        Objects.requireNonNull(createdAt, "createdAt must not be null");
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
            throw new IllegalStateException(
                    "Buy-in must be at least the big blind amount.");
        }

        this.roomId = roomId;
        this.roomName = roomName;
        this.hostName = hostName;
        this.maxPlayers = maxPlayers;
        this.smallBlind = smallBlind;
        this.bigBlind = bigBlind;
        this.buyIn = buyIn;
        this.passwordHash = (passwordHash == null || passwordHash.trim().isEmpty()) ? null : passwordHash;
        this.playersWithJoinTime = new LinkedHashMap<>();
        this.createdAt = createdAt;
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
        private Map<String, LocalDateTime> playersWithJoinTime;
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

        public RoomRestoreBuilder playersWithJoinTime(Map<String, LocalDateTime> playersWithJoinTime) {
            this.playersWithJoinTime = playersWithJoinTime;
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
            Room room = new Room(roomId, roomName, hostName, maxPlayers, smallBlind, bigBlind, buyIn,
                    passwordHash, createdAt);
            if (playersWithJoinTime != null) {
                if (playersWithJoinTime.size() > maxPlayers) {
                    throw new IllegalStateException(
                            "Restored players count exceeds maxPlayers: " + playersWithJoinTime.size() + " > " + maxPlayers);
                }
                room.playersWithJoinTime.putAll(new LinkedHashMap<>(playersWithJoinTime));
                for (String playerName : room.playersWithJoinTime.keySet()) {
                    String persistedId = playerMembershipIds != null ? playerMembershipIds.get(playerName) : null;
                    String membershipId = (persistedId != null && !persistedId.isBlank())
                            ? persistedId
                            : UUID.randomUUID().toString();
                    room.playerMembershipIds.put(playerName, membershipId);
                }
            }
            room.isGameStarted = gameStarted;
            room.inactivityDeadlineEpochMs = inactivityDeadlineEpochMs;
            return room;
        }
    }

    /**
     * Checks if the room has reached its maximum player capacity.
     *
     * @return true if the room is full, false otherwise
     */
    public boolean isFull() {
        return playersWithJoinTime.size() >= maxPlayers;
    }

    /**
     * Adds a player to the room using the system default clock if they are not already present
     * and the room has capacity.
     *
     * @param playerName the name of the player to add
     * @return the process-local membership ID for the added player
     * @throws BadRequestException   if the player is already in the room
     * @throws IllegalStateException if the room is full
     */
    public String addPlayer(String playerName) {
        return addPlayer(playerName, Clock.systemDefaultZone());
    }

    /**
     * Adds a player to the room if they are not already present and the room has capacity,
     * recording their join timestamp from {@code clock}.
     *
     * @param playerName the name of the player to add
     * @param clock      clock used to record the player's join timestamp
     * @return the process-local membership ID for the added player
     * @throws BadRequestException   if the player is already in the room
     * @throws IllegalStateException if the room is full
     */
    public String addPlayer(String playerName, Clock clock) {
        Objects.requireNonNull(clock, "clock must not be null");
        if (playersWithJoinTime.containsKey(playerName)) {
            throw new BadRequestException(
                    String.format("Cannot add %s to room as they are already in the room", playerName));
        }
        if (isFull()) {
            throw new IllegalStateException("Room is full");
        }
        playersWithJoinTime.put(playerName, LocalDateTime.now(clock));
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
        if (!playersWithJoinTime.containsKey(playerName)) {
            throw new BadRequestException(
                    String.format("Cannot remove %s from room as they are not already in the room", playerName));
        }
        playersWithJoinTime.remove(playerName);
        playerMembershipIds.remove(playerName);
        playerAbsenceDeadlines.remove(playerName);
        connectedPlayers.remove(playerName);
    }

    /**
     * Checks if a player is currently in the room.
     *
     * @param playerName the name of the player to check
     * @return true if the player is in the room, false otherwise
     */
    public boolean hasPlayer(String playerName) {
        return playersWithJoinTime.containsKey(playerName);
    }

    /**
     * Returns the timestamp when a player joined the room.
     *
     * @param playerName the name of the player
     * @return the join timestamp, or null if player not found
     */
    public LocalDateTime getJoinedAt(String playerName) {
        return playersWithJoinTime.get(playerName);
    }

    /**
     * Checks if this room is password-protected.
     *
     * @return true if the room has a password hash, false otherwise
     */
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
        Objects.requireNonNull(passwordEncoder, "passwordEncoder must not be null");
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
        return roomName + " (" + roomId + ")" + " by " + hostName + " with " + playersWithJoinTime.size()
                + " players [password=" + protectedPass + "]";
    }

    public Long getInactivityDeadlineEpochMs() {
        return inactivityDeadlineEpochMs;
    }

    public void setInactivityDeadlineEpochMs(Long deadlineEpochMs) {
        inactivityDeadlineEpochMs = deadlineEpochMs;
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

    public String getRoomId() {
        return roomId;
    }

    public String getRoomName() {
        return roomName;
    }

    public String getHostName() {
        return hostName;
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

    public List<String> getPlayers() {
        return new ArrayList<>(playersWithJoinTime.keySet());
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

    /**
     * Captures join order and timestamps without exposing the live map guarded by the room lock
     * to persistence serialization.
     *
     * @return independent ordered player-to-join-time snapshot
     */
    public Map<String, LocalDateTime> getPlayersWithJoinTimeSnapshot() {
        return new LinkedHashMap<>(playersWithJoinTime);
    }

    /**
     * Captures player membership IDs without exposing the live map guarded by the room lock
     * to persistence serialization.
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

    public String getPasswordHash() {
        return passwordHash;
    }

    /**
     * Distinguishes lobby host-leave behavior from active-game host transfer.
     *
     * @return whether gameplay has started
     */
    public boolean isGameStarted() {
        return isGameStarted;
    }

    /**
     * Records the lifecycle boundary that changes host and cleanup rules.
     *
     * @param gameStarted whether gameplay has started
     */
    public void setGameStarted(boolean gameStarted) {
        this.isGameStarted = gameStarted;
    }

    /**
     * Checks if gameplay in this room has ended and the room is retained for post-game review.
     *
     * @return {@code true} if gameplay in this room has ended
     */
    public boolean isFinished() {
        return isFinished;
    }

    /**
     * Marks whether gameplay in this room has ended and the room is retained for post-game review.
     *
     * @param finished {@code true} if gameplay has ended
     */
    public void setFinished(boolean finished) {
        this.isFinished = finished;
    }

    /**
     * Returns the opaque process-local membership ID for a player in this room.
     *
     * @param playerName the name of the player
     * @return membership ID, or null if player is not in room
     */
    public String getMembershipId(String playerName) {
        return playerMembershipIds.get(playerName);
    }

    /**
     * Returns the process-local absence deadline epoch ms for a player in this room.
     *
     * @param playerName the name of the player
     * @return absence deadline epoch ms, or null if none set
     */
    public Long getAbsenceDeadline(String playerName) {
        return playerAbsenceDeadlines.get(playerName);
    }

    /**
     * Sets or clears the process-local absence deadline epoch ms for a player.
     *
     * @param playerName the name of the player
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
     * Checks if a player currently has an active confirmed connection in this room.
     *
     * @param playerName the name of the player
     * @return true if connected, false otherwise
     */
    public boolean isPlayerConnected(String playerName) {
        return connectedPlayers.contains(playerName);
    }

    /**
     * Updates the process-local connection status for a player in this room.
     * Confirming connected clears any pending absence deadline.
     *
     * @param playerName the name of the player
     * @param connected true if connected, false if disconnected/absent
     */
    public void setPlayerConnected(String playerName, boolean connected) {
        if (connected) {
            connectedPlayers.add(playerName);
            playerAbsenceDeadlines.remove(playerName);
        } else {
            connectedPlayers.remove(playerName);
        }
    }
}
