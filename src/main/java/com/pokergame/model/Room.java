package com.pokergame.model;

import com.pokergame.exception.BadRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

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
    private final List<String> players;
    private final int maxPlayers;
    private final int smallBlind;
    private final int bigBlind;
    private final int buyIn;
    private final String password;
    private final LocalDateTime createdAt;
    private boolean isGameStarted;


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


        this.roomId = roomId;
        this.roomName = roomName;
        this.hostName = hostName;
        this.maxPlayers = maxPlayers;
        this.smallBlind = smallBlind;
        this.bigBlind = bigBlind;
        this.buyIn = buyIn;
        this.password = password;
        this.players = new ArrayList<>();
        this.createdAt = LocalDateTime.now();
    }

    /**
     * Adds a player to the room if they are not already present.
     *
     * @param playerName the name of the player to add
     */
    public void addPlayer(String playerName) {
        if (!players.contains(playerName)) {
            players.add(playerName);
        }
        else{
            throw new BadRequestException(String.format("Cannot add %s to room as they are already in the room", playerName));
        }
    }

    /**
     * Removes a player from the room.
     *
     * @param playerName the name of the player to remove
     */
    public void removePlayer(String playerName) {
        if (players.contains(playerName)){
            players.remove(playerName);
        }
        else{
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
    public boolean hasPlayer(String playerName) {
        return players.contains(playerName);
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
    public String toString() {
        return roomName + " (" + roomId + ")" + " by " + hostName + " with " + players.size() + " players";
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
    public List<String> getPlayers() {
        return players;
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

    public boolean isGameStarted() {
        return isGameStarted;
    }

    public void setGameStarted(boolean gameStarted) {
        isGameStarted = gameStarted;
    }
}