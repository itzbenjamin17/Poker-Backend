package com.pokergame.service;

import com.pokergame.dto.internal.PlayerJoinInfo;
import com.pokergame.dto.response.RoomDataResponse;
import com.pokergame.dto.request.CreateRoomRequest;
import com.pokergame.dto.request.JoinRoomRequest;
import com.pokergame.dto.response.ApiResponse;
import com.pokergame.enums.ResponseMessage;
import com.pokergame.exception.BadRequestException;
import com.pokergame.exception.ResourceNotFoundException;
import com.pokergame.exception.UnauthorisedActionException;
import com.pokergame.model.Room;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Service class responsible for poker room management.
 * Handles room creation, joining, leaving, and room data retrieval.
 */
@Service
public class RoomService {

    private static final Logger logger = LoggerFactory.getLogger(RoomService.class);

    private final SimpMessagingTemplate messagingTemplate;

    public RoomService(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    // Room storage
    private final Map<String, Room> rooms = new ConcurrentHashMap<>();
    private final Map<String, String> roomHosts = new ConcurrentHashMap<>();

    /**
     * Creates a new poker room with the specified configuration.
     *
     * @param request The room creation request containing room name, host, and game
     *                settings
     * @return The unique room ID for the created room
     * @throws BadRequestException if the room name is already taken
     */
    public String createRoom(CreateRoomRequest request) {
        String roomId;
        synchronized (this) {
            if (isRoomNameTaken(request.getRoomName())) {
                logger.warn("Attempted to create room with duplicate name: {}", request.getRoomName());
                throw new BadRequestException(
                        "Room name '" + request.getRoomName() + "' is already taken. Please choose a different name.");
            }

            roomId = UUID.randomUUID().toString();

            Room room = new Room(
                    roomId,
                    request.getRoomName(),
                    request.getPlayerName(), // Host name
                    request.getMaxPlayers(),
                    request.getSmallBlind(),
                    request.getBigBlind(),
                    request.getBuyIn(),
                    request.getPassword());

            // Add the host as the first player
            room.addPlayer(request.getPlayerName());

            rooms.put(roomId, room);
            roomHosts.put(roomId, request.getPlayerName());
        }

        messagingTemplate.convertAndSend("/rooms" + roomId,
                new ApiResponse<>(ResponseMessage.ROOM_CREATED.getMessage(), getRoomData(roomId)));

        logger.info("Room created: {} (ID: {}) by host: {}",
                request.getRoomName(), roomId, request.getPlayerName());

        return roomId;
    }

    /**
     * Adds a player to an existing room (not a started game).
     * Validates password for private rooms and checks for room capacity and
     * duplicate names.
     *
     * @param joinRequest the request containing room name, player name, and
     *                    password
     * @return the room ID of the joined room
     * @throws ResourceNotFoundException   if the room isn't found
     * @throws BadRequestException         if the password is invalid or the player
     *                                     name is taken
     * @throws UnauthorisedActionException if the room is full
     */
    public String joinRoom(JoinRoomRequest joinRequest) {
        Room foundRoom = findRoomByName(joinRequest.roomName());
        if (foundRoom == null) {
            logger.warn("Join attempt failed: room not found for name {}", joinRequest.roomName());
            throw new ResourceNotFoundException("Room not found");
        }

        String roomId = foundRoom.getRoomId();
        Room room = rooms.get(roomId);
        if (room == null) {
            logger.warn("Join attempt failed: room not found for id {}", roomId);
            throw new ResourceNotFoundException("Room not found");
        }

        // Check password if the room is private
        if (room.hasPassword() && !room.checkPassword(joinRequest.password())) {
            logger.warn("Invalid password attempt for room {} by player {}", room.getRoomName(),
                    joinRequest.playerName());
            throw new BadRequestException("Invalid password");
        }

        // Check if the room is full
        if (room.getPlayers().size() >= room.getMaxPlayers()) {
            logger.warn("Join attempt failed: room {} is full (player: {})", room.getRoomName(),
                    joinRequest.playerName());
            throw new UnauthorisedActionException("Room is full");
        }

        // Check if the player name already exists
        if (room.hasPlayer(joinRequest.playerName())) {
            logger.warn("Join attempt failed: player name '{}' already taken in room {}", joinRequest.playerName(),
                    room.getRoomName());
            throw new BadRequestException("Player name already taken");
        }

        room.addPlayer(joinRequest.playerName());

        logger.info("Player {} joined room: {}", joinRequest.playerName(), room.getRoomName());

        messagingTemplate.convertAndSend("/rooms" + roomId,
                new ApiResponse<>(ResponseMessage.PLAYER_JOINED.getMessage(), getRoomData(roomId)));

        return roomId;
    }

    /**
     * Removes a player from a room. If the host leaves, the entire room is
     * destroyed.
     * If all players leave, the room is also destroyed.
     *
     * @param roomId     the unique identifier of the room
     * @param playerName the name of the player leaving
     * @throws ResourceNotFoundException if room not found
     */
    public void leaveRoom(String roomId, String playerName) {
        leaveRoom(roomId, playerName, true);
    }

    /**
     * Removes a player from a room with contextual host-leave behaviour.
     *
     * <p>
     * When the host leaves and {@code closeRoomWhenHostLeaves} is true,
     * the room is closed (lobby behaviour). When false, the host is transferred
     * to the next remaining player and the room stays open (active game behaviour).
     * </p>
     *
     * @param roomId                  the unique identifier of the room
     * @param playerName              the name of the player leaving
     * @param closeRoomWhenHostLeaves true to close room on host leave, false to
     *                                transfer host
     * @throws ResourceNotFoundException if room not found
     */
    public void leaveRoom(String roomId, String playerName, boolean closeRoomWhenHostLeaves) {
        Room room = rooms.get(roomId);
        if (room == null) {
            logger.warn("Leave attempt failed: room not found for id {} (player: {})", roomId, playerName);
            throw new ResourceNotFoundException("Room not found");
        }

        // Check if the leaving player is the host
        if (isRoomHost(roomId, playerName)) {
            if (closeRoomWhenHostLeaves) {
                // Host is leaving the lobby phase - destroy the room.
                logger.info("Host {} leaving room {} during lobby phase, destroying room", playerName,
                        room.getRoomName());
                messagingTemplate.convertAndSend("/rooms" + roomId,
                        new ApiResponse<>(ResponseMessage.ROOM_CLOSED.getMessage(), null));
                destroyRoom(roomId);
            } else {
                // Host is leaving during an active game - transfer host and keep room alive.
                room.removePlayer(playerName);

                if (room.getPlayers().isEmpty()) {
                    logger.info("Host {} left room {} and no players remain, destroying room", playerName,
                            room.getRoomName());
                    messagingTemplate.convertAndSend("/rooms" + roomId,
                            new ApiResponse<>(ResponseMessage.ROOM_CLOSED.getMessage(), null));
                    destroyRoom(roomId);
                } else {
                    String newHost = room.getPlayers().getFirst();
                    roomHosts.put(roomId, newHost);

                    logger.info("Host {} left room {} during active game. New host is {}",
                            playerName,
                            room.getRoomName(),
                            newHost);
                    messagingTemplate.convertAndSend("/rooms" + roomId,
                            new ApiResponse<>(ResponseMessage.PLAYER_LEFT.getMessage(), getRoomData(roomId)));
                }
            }
        } else {
            // Regular player leaving - just remove them from the room
            room.removePlayer(playerName);
            logger.info("Player {} left room: {}", playerName, room.getRoomName());
            messagingTemplate.convertAndSend("/rooms" + roomId,
                    new ApiResponse<>(ResponseMessage.PLAYER_LEFT.getMessage(), getRoomData(roomId)));

            // If no players left after removal, also destroy the room
            if (room.getPlayers().isEmpty()) {
                logger.info("No players remaining in room {}, destroying room", room.getRoomName());
                messagingTemplate.convertAndSend("/rooms" + roomId,
                        new ApiResponse<>(ResponseMessage.ROOM_CLOSED.getMessage(), null));
                destroyRoom(roomId);
            }
        }
    }

    /**
     * Retrieves all currently available rooms.
     *
     * @return a list of all Room objects
     */
    public List<Room> getRooms() {
        return new ArrayList<>(rooms.values());
    }

    /**
     * Retrieves formatted room data for API responses and WebSocket broadcasts.
     * Includes player information with host status indicators.
     *
     * @param roomId the unique identifier of the room
     * @return a RoomDataResponse object containing formatted room information
     * @throws BadRequestException       if roomId is null
     * @throws ResourceNotFoundException if room not found
     */
    public RoomDataResponse getRoomData(String roomId) {
        if (roomId == null) {
            logger.error("Room data request failed: roomId is null");
            throw new BadRequestException("Room ID cannot be null");
        }
        Room room = rooms.get(roomId);
        if (room == null) {
            logger.warn("Room data request failed: room not found for id {}", roomId);
            throw new ResourceNotFoundException("Room not found");
        }

        String currentHost = roomHosts.getOrDefault(roomId, room.getHostName());

        // Convert player names to PlayerInfoDTO objects
        List<PlayerJoinInfo> playerObjects = room.getPlayers().stream()
                .map(playerName -> new PlayerJoinInfo(
                        playerName,
                        currentHost.equals(playerName),
                        "recently" // come back to this and change to actual time
                ))
                .collect(Collectors.toList());

        // Create and return the RoomDataDTO
        return new RoomDataResponse(
                roomId,
                room.getRoomName(),
                room.getMaxPlayers(),
                room.getBuyIn(),
                room.getSmallBlind(),
                room.getBigBlind(),
                room.getCreatedAt(),
                currentHost,
                playerObjects,
                playerObjects.size(),
                playerObjects.size() >= 2,
                room.isGameStarted());
    }


    /**
     * Retrieves a room by its unique identifier.
     *
     * @param roomId the unique identifier of the room
     * @return the Room object if found, null otherwise
     */
    public Room getRoom(String roomId) {
        return rooms.get(roomId);
    }

    /**
     * Finds a room by its name (case-insensitive).
     *
     * @param roomName the name of the room to find
     * @return the Room object if found, null otherwise
     */
    public Room findRoomByName(String roomName) {
        return rooms.values().stream()
                .filter(room -> room.getRoomName().equalsIgnoreCase(roomName))
                .findFirst()
                .orElse(null);
    }

    /**
     * Checks if a player is the host of a specific room.
     *
     * @param roomId     the unique identifier of the room
     * @param playerName the name of the player to check
     * @return true if the player is the room host, false otherwise
     */
    public boolean isRoomHost(String roomId, String playerName) {
        String hostName = roomHosts.get(roomId);
        return hostName != null && hostName.equals(playerName);
    }

    /**
     * Completely destroys and removes a room from the system.
     * Removes both the room and its host mapping.
     *
     * @param roomId the unique identifier of the room to destroy
     */
    public void destroyRoom(String roomId) {
        Room room = rooms.remove(roomId);
        roomHosts.remove(roomId);
        if (room != null) {
            logger.info("Room destroyed: {} (ID: {})", room.getRoomName(), roomId);
        }
    }

    /**
     * Checks if a room name is already taken (case-insensitive).
     *
     * @param roomName the name to check for availability
     * @return true if the name is already in use, false otherwise
     */
    private boolean isRoomNameTaken(String roomName) {
        return rooms.values().stream()
                .anyMatch(room -> room.getRoomName().equalsIgnoreCase(roomName));
    }
}
