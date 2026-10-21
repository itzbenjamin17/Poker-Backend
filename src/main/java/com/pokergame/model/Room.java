package com.pokergame.model;

import com.pokergame.exception.BadRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Represents a poker game room/lobby where players can join and play together.
 * Manages room configuration including blinds, buy-in, player capacity, and
 * access control.
 *
 * <p>
 * Rooms are created with fixed game parameters (blinds, buy-in, max players)
 * that
 * cannot be changed after creation. Each room can be password-protected for
 * private games.
 * </p>
 */
public class Room {
    private static final Logger logger = LoggerFactory.getLogger(Room.class);
    private final String roomId;
    private final String roomName;
    private final String hostName;
    /**
     * Join-order and arrival timestamps for players in this room.
     * Guarded by this Room instance monitor (all mutations and accesses must synchronize on this).
     */
    private final LinkedHashMap<String, LocalDateTime> playersWithJoinTime;
    /**
     * Process-local membership identities created per addPlayer.
     * Guarded by this Room instance monitor. Excluded from WAL serialization.
     */
    private final Map<String, String> playerMembershipIds = new LinkedHashMap<>();

    /**
     * Process-local absence deadlines for players awaiting first connection or reconnect grace.
     * Guarded by this Room instance monitor. Excluded from WAL serialization.
     */
    private final Map<String, Long> playerAbsenceDeadlines = new LinkedHashMap<>();

    /**
     * Process-local active socket presence bounded by room membership.
     * Guarded by this Room instance monitor. Excluded from WAL serialization.
     */
    private final java.util.Set<String> connectedPlayers = new java.util.HashSet<>();
    private final int maxPlayers;
    private final int smallBlind;
    private final int bigBlind;
    private final int buyIn;
    private final String password;
    private final LocalDateTime createdAt;
    private boolean isGameStarted;
    private volatile boolean isTerminal;


    /**
     * Creates a new poker room with the specified configuration.
     *
     * @param roomId     unique identifier for the room
     * @param roomName   display name for the room
     * @param hostName   name of the player creating/hosting the room
     * @param maxPlayers maximum number of players allowed (must be 2-10)
     * @param smallBlind the small blind amount (must be at least 1)
     * @param bigBlind   the big blind amount (must be >= small blind)
     * @param buyIn      the buy-in amount for joining the game
     * @param password   optional password for room access (null or empty for public
     *                   rooms)
     * @throws BadRequestException if any validation fails
     */
    public Room(String roomId, String roomName, String hostName, int maxPlayers,
            int smallBlind, int bigBlind, int buyIn, String password) {
        this(roomId, roomName, hostName, maxPlayers, smallBlind, bigBlind, buyIn, password, LocalDateTime.now());
    }

    /**
     * Package-private constructor used to restore persisted Room instances with their
     * original creation timestamp.
     *
     * @param roomId     unique identifier of the room
     * @param roomName   display name of the room
     * @param hostName   name of the creator / initial host
     * @param maxPlayers maximum capacity of the room
     * @param smallBlind small blind amount
     * @param bigBlind   big blind amount
     * @param buyIn      buy-in amount
     * @param password   optional password for private rooms
     * @param createdAt  persisted creation timestamp
     */
    Room(String roomId, String roomName, String hostName, int maxPlayers,
            int smallBlind, int bigBlind, int buyIn, String password, LocalDateTime createdAt) {

        if (roomId == null || roomId.trim().isEmpty()) {
            logger.error("Invalid roomId: '{}'", roomId);
            throw new BadRequestException("Room ID cannot be null or empty.");
        }
        if (roomName == null || roomName.trim().isEmpty()) {
            logger.error("Invalid roomName: '{}'", roomName);
            throw new BadRequestException("Room name cannot be null or empty.");
        }
        if (hostName == null || hostName.trim().isEmpty()) {
            logger.error("Invalid hostName: '{}'", hostName);
            throw new BadRequestException("Host name cannot be null or empty.");
        }
        if (maxPlayers < 2 || maxPlayers > 10) {
            logger.error("Invalid maxPlayers: {}", maxPlayers);
            throw new BadRequestException("Max players must be between 2 and 10.");
        }
        if (smallBlind < 1) {
            logger.error("Invalid smallBlind: {}", smallBlind);
            throw new BadRequestException("Small blind must be at least 1.");
        }
        if (bigBlind < 2 * smallBlind) {
            logger.error("Invalid bigBlind: {}, smallBlind: {}", bigBlind, smallBlind);
            throw new BadRequestException("Big blind must be at least twice the small blind.");
        }
        if (buyIn < bigBlind) {
            logger.error("Invalid buyIn: {}, bigBlind: {}", buyIn, bigBlind);
            throw new BadRequestException(
                    "Buy-in must be at least the big blind amount.");
        }


        this.roomId = roomId;
        this.roomName = roomName;
        this.hostName = hostName;
        this.maxPlayers = maxPlayers;
        this.smallBlind = smallBlind;
        this.bigBlind = bigBlind;
        this.buyIn = buyIn;
        this.password = (password != null && password.trim().isEmpty()) ? null : password;
        this.playersWithJoinTime = new LinkedHashMap<>();
        this.createdAt = createdAt != null ? createdAt : LocalDateTime.now();
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
        private String password;
        private LocalDateTime createdAt;
        private Map<String, LocalDateTime> playersWithJoinTime;
        private boolean gameStarted;

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

        public RoomRestoreBuilder password(String password) {
            this.password = password;
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

        public RoomRestoreBuilder gameStarted(boolean gameStarted) {
            this.gameStarted = gameStarted;
            return this;
        }

        private boolean terminal;

        /**
         * Sets whether the restored room has reached a terminal state (post-game review or closure).
         *
         * @param terminal {@code true} if the room is terminal, {@code false} otherwise
         * @return this builder instance
         */
        public RoomRestoreBuilder terminal(boolean terminal) {
            this.terminal = terminal;
            return this;
        }

        public Room build() {
            Room room = new Room(roomId, roomName, hostName, maxPlayers, smallBlind, bigBlind, buyIn, password, createdAt);
            synchronized (room) {
                room.playersWithJoinTime.clear();
                if (playersWithJoinTime != null) {
                    room.playersWithJoinTime.putAll(new LinkedHashMap<>(playersWithJoinTime));
                    for (String playerName : room.playersWithJoinTime.keySet()) {
                        room.playerMembershipIds.put(playerName, java.util.UUID.randomUUID().toString());
                    }
                }
                room.isGameStarted = gameStarted;
                room.isTerminal = terminal;
            }
            return room;
        }
    }

    /**
     * Adds a player to the room if they are not already present.
     *
     * @param playerName the name of the player to add
     */
    public synchronized String addPlayer(String playerName) {
        if (!playersWithJoinTime.containsKey(playerName)) {
            playersWithJoinTime.put(playerName, LocalDateTime.now());
            String membershipId = java.util.UUID.randomUUID().toString();
            playerMembershipIds.put(playerName, membershipId);
            return membershipId;
        } else {
            throw new BadRequestException(String.format("Cannot add %s to room as they are already in the room", playerName));
        }
    }

    /**
     * Removes a player from the room.
     *
     * @param playerName the name of the player to remove
     */
    public synchronized void removePlayer(String playerName) {
        if (playersWithJoinTime.containsKey(playerName)) {
            playersWithJoinTime.remove(playerName);
            playerMembershipIds.remove(playerName);
            playerAbsenceDeadlines.remove(playerName);
            connectedPlayers.remove(playerName);
        } else {
            throw new BadRequestException(
                String.format("Cannot remove %s from room as they are not already in the room", playerName));
        }
    }

    /**
     * Checks if a player is currently in the room.
     *
     * @param playerName the name of the player to check
     * @return true if the player is in the room, false otherwise
     */
    public synchronized boolean hasPlayer(String playerName) {
        return playersWithJoinTime.containsKey(playerName);
    }

    /**
     * Returns the timestamp when a player joined the room.
     *
     * @param playerName the name of the player
     * @return the join timestamp, or null if player not found
     */
    public synchronized LocalDateTime getJoinedAt(String playerName) {
        return playersWithJoinTime.get(playerName);
    }

    /**
     * Checks if this room is password-protected.
     *
     * @return true if the room has a password, false otherwise
     */
    public boolean hasPassword() {
        return password != null && !password.trim().isEmpty();
    }

    /**
     * Verifies if the provided password matches the room's password.
     * Returns true for public rooms (no password required).
     *
     * @param inputPassword the password to check
     * @return true if the password matches or room is public, false otherwise
     */
    public boolean checkPassword(String inputPassword) {
        if (!hasPassword())
            return true;
        return password.equals(inputPassword);
    }

    /**
     * Returns a string representation of this room including name, ID, host, and
     * player count.
     *
     * @return formatted string describing the room
     */
    @Override
    public synchronized String toString() {
        return roomName + " (" + roomId + ")" + " by " + hostName + " with " + playersWithJoinTime.size() + " players";
    }

    /**
     * Returns the unique room identifier.
     *
     * @return the room ID
     */
    public String getRoomId() {
        return roomId;
    }

    /**
     * Returns the display name of the room.
     *
     * @return the room name
     */
    public String getRoomName() {
        return roomName;
    }

    /**
     * Returns the name of the player hosting the room.
     *
     * @return the host's name
     */
    public String getHostName() {
        return hostName;
    }

    /**
     * Returns the list of player names currently in the room.
     *
     * @return the player list
     */
    public synchronized List<String> getPlayers() {
        return new ArrayList<>(playersWithJoinTime.keySet());
    }

    /**
     * Returns the maximum number of players allowed in the room.
     *
     * @return the maximum player count
     */
    public int getMaxPlayers() {
        return maxPlayers;
    }

    /**
     * Returns the small blind amount for this room.
     *
     * @return the small blind value
     */
    public int getSmallBlind() {
        return smallBlind;
    }

    /**
     * Returns the big blind amount for this room.
     *
     * @return the big blind value
     */
    public int getBigBlind() {
        return bigBlind;
    }

    /**
     * Returns the buy-in amount required to join the game.
     *
     * @return the buy-in value
     */
    public int getBuyIn() {
        return buyIn;
    }

    /**
     * Returns the timestamp when the room was created.
     *
     * @return the creation time
     */
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    /**
     * Captures join order and timestamps without exposing the synchronized live map
     * to persistence serialization.
     *
     * @return independent ordered player-to-join-time snapshot
     */
    public synchronized Map<String, LocalDateTime> getPlayersWithJoinTimeSnapshot() {
        return new LinkedHashMap<>(playersWithJoinTime);
    }

    /**
     * Exposes the password only to the encrypted aggregate mapper. Public room DTOs
     * continue to reveal only whether a password exists.
     *
     * @return room password, or {@code null} for a public room
     */
    public String getPasswordForPersistence() {
        return password;
    }

    /**
     * Distinguishes lobby host-leave behavior from active-game host transfer.
     *
     * @return whether gameplay has started
     */
    public synchronized boolean isGameStarted() {
        return isGameStarted;
    }

    /**
     * Records the lifecycle boundary that changes host and cleanup rules.
     *
     * @param gameStarted whether gameplay has started
     */
    public synchronized void setGameStarted(boolean gameStarted) {
        this.isGameStarted = gameStarted;
    }

    /**
     * Checks if this room is in a terminal post-game state.
     *
     * @return true if game is terminal
     */
    public boolean isTerminal() {
        return isTerminal;
    }

    /**
     * Marks this room as a terminal post-game persistence container.
     *
     * @param terminal whether the room is in post-game review
     */
    public void setTerminal(boolean terminal) {
        this.isTerminal = terminal;
    }

    /**
     * Returns the opaque process-local membership ID for a player in this room.
     *
     * @param playerName the name of the player
     * @return membership ID, or null if player is not in room
     */
    public synchronized String getMembershipId(String playerName) {
        return playerMembershipIds.get(playerName);
    }

    /**
     * Returns the process-local absence deadline epoch ms for a player in this room.
     *
     * @param playerName the name of the player
     * @return absence deadline epoch ms, or null if none set
     */
    public synchronized Long getAbsenceDeadline(String playerName) {
        return playerAbsenceDeadlines.get(playerName);
    }

    /**
     * Sets or clears the process-local absence deadline epoch ms for a player.
     *
     * @param playerName the name of the player
     * @param deadlineEpochMs the absence deadline epoch ms, or null to clear
     */
    public synchronized void setAbsenceDeadline(String playerName, Long deadlineEpochMs) {
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
    public synchronized boolean isPlayerConnected(String playerName) {
        return connectedPlayers.contains(playerName);
    }

    /**
     * Updates the process-local connection status for a player in this room.
     * Confirming connected clears any pending absence deadline.
     *
     * @param playerName the name of the player
     * @param connected true if connected, false if disconnected/absent
     */
    public synchronized void setPlayerConnected(String playerName, boolean connected) {
        if (connected) {
            connectedPlayers.add(playerName);
            playerAbsenceDeadlines.remove(playerName);
        } else {
            connectedPlayers.remove(playerName);
        }
    }
}
