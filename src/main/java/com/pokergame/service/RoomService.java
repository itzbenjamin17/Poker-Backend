package com.pokergame.service;

import com.pokergame.util.RoomPasswordPolicy;
import com.pokergame.dto.internal.PlayerJoinInfo;
import com.pokergame.dto.response.RoomDataResponse;
import com.pokergame.dto.request.CreateRoomRequest;
import com.pokergame.dto.request.JoinRoomRequest;
import com.pokergame.dto.response.ApiResponse;
import com.pokergame.enums.ResponseMessage;
import com.pokergame.service.event.RoomDestroyed;
import com.pokergame.service.event.RoomMemberRegistered;
import com.pokergame.service.event.RoomMembershipRetired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import com.pokergame.exception.BadRequestException;
import com.pokergame.exception.ResourceNotFoundException;
import com.pokergame.model.Room;
import com.pokergame.persistence.transaction.DurableMutation;
import com.pokergame.util.InputSanitizer;
import com.pokergame.persistence.transaction.DurableTransactionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
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
    private final ApplicationEventPublisher eventPublisher;
    private final long disconnectGracePeriodMs;
    private final PasswordEncoder passwordEncoder;
    private RoomService self;

    /**
     * Creates the room registry with the publisher used for committed lobby state.
     *
     * @param messagingTemplate WebSocket messaging collaborator
     */
    public RoomService(SimpMessagingTemplate messagingTemplate) {
        this(messagingTemplate, event -> { }, 120_000L, new BCryptPasswordEncoder());
    }

    /**
     * Creates the room registry with WebSocket messaging collaborator and password encoder.
     *
     * @param messagingTemplate WebSocket messaging collaborator
     * @param passwordEncoder   password encoder for hashing and validating credentials
     */
    public RoomService(SimpMessagingTemplate messagingTemplate, PasswordEncoder passwordEncoder) {
        this(messagingTemplate, event -> { }, 120_000L, passwordEncoder);
    }

    /**
     * Creates the room registry with an event publisher for membership and room lifecycle notifications.
     *
     * @param messagingTemplate       WebSocket messaging collaborator
     * @param eventPublisher          publishes room lifecycle events once a mutation is accepted
     * @param disconnectGracePeriodMs reconnect grace interval in ms
     */
    public RoomService(SimpMessagingTemplate messagingTemplate,
            ApplicationEventPublisher eventPublisher,
            long disconnectGracePeriodMs) {
        this(messagingTemplate, eventPublisher, disconnectGracePeriodMs, new BCryptPasswordEncoder());
    }

    /**
     * Creates the room registry with an event publisher and password encoder.
     *
     * @param messagingTemplate       WebSocket messaging collaborator
     * @param eventPublisher          publishes room lifecycle events once a mutation is accepted
     * @param disconnectGracePeriodMs reconnect grace interval in ms
     * @param passwordEncoder         password encoder for hashing and verifying room passwords
     */
    @Autowired
    public RoomService(SimpMessagingTemplate messagingTemplate,
            ApplicationEventPublisher eventPublisher,
            @Value("${poker.disconnect.grace-period-ms:120000}") long disconnectGracePeriodMs,
            PasswordEncoder passwordEncoder) {
        this.messagingTemplate = messagingTemplate;
        this.eventPublisher = eventPublisher;
        this.disconnectGracePeriodMs = disconnectGracePeriodMs;
        this.passwordEncoder = passwordEncoder != null ? passwordEncoder : new BCryptPasswordEncoder();
    }

    /**
     * Retains the Spring proxy because public convenience methods must call explicit
     * room-ID mutation seams through {@link DurableMutation} advice.
     *
     * @param self proxied room service
     */
    @Autowired
    void setSelf(@Lazy RoomService self) {
        this.self = self;
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
        String roomId = UUID.randomUUID().toString();
        return (self == null ? this : self).createRoom(roomId, request);
    }

    /**
     * Creates a room through an explicit identity seam so the prepare record can be
     * written before the room exists in memory. Generating the ID outside this method
     * avoids trying to infer a WAL filename from state that has not yet been created.
     *
     * @param roomId stable identity generated by the caller
     * @param request validated room configuration
     * @return created room identity
     * @throws BadRequestException if the room name is already taken
     */
    @DurableMutation(roomId = "#roomId")
    public String createRoom(String roomId, CreateRoomRequest request) {
        RoomPasswordPolicy.validateLength(request.getPassword());
        String sanitizedRoomName = InputSanitizer.sanitize(request.getRoomName());
        String sanitizedPlayerName = InputSanitizer.sanitize(request.getPlayerName());

        synchronized (this) {
            if (isRoomNameTaken(sanitizedRoomName)) {
                logger.warn("Attempted to create room with duplicate name: {}", sanitizedRoomName);
                throw new BadRequestException(
                        "Room name '" + sanitizedRoomName + "' is already taken. Please choose a different name.");
            }

            String rawPassword = request.getPassword();
            String passwordToStore = null;
            if (rawPassword != null && !rawPassword.trim().isEmpty()) {
                passwordToStore = passwordEncoder.encode(rawPassword);
            }

            Room room = new Room(
                    roomId,
                    sanitizedRoomName,
                    sanitizedPlayerName, // Host name
                    request.getMaxPlayers(),
                    request.getSmallBlind(),
                    request.getBigBlind(),
                    request.getBuyIn(),
                    passwordToStore);

            // Add the host as the first player
            room.addPlayer(sanitizedPlayerName);
            long acceptanceDeadline = System.currentTimeMillis() + disconnectGracePeriodMs;
            room.setAbsenceDeadline(sanitizedPlayerName, acceptanceDeadline);


            rooms.put(roomId, room);
            roomHosts.put(roomId, sanitizedPlayerName);
        }

        DurableTransactionContext.afterCommit(() -> {
            Room createdRoom = rooms.get(roomId);
            String membershipId = createdRoom != null ? createdRoom.getMembershipId(sanitizedPlayerName) : null;
            Long absenceDeadline = createdRoom != null ? createdRoom.getAbsenceDeadline(sanitizedPlayerName) : null;
            long deadline = absenceDeadline != null ? absenceDeadline : (System.currentTimeMillis() + disconnectGracePeriodMs);
            eventPublisher.publishEvent(new RoomMemberRegistered(roomId, sanitizedPlayerName, membershipId, deadline));
        });

        sendAfterCommit("/room/" + roomId,
                new ApiResponse<>(ResponseMessage.ROOM_CREATED.getMessage(), getRoomData(roomId)));

        logger.info("Room created: {} (ID: {}) by host: {}",
                sanitizedRoomName, roomId, sanitizedPlayerName);

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
     * @throws BadRequestException         if the password is invalid, the player
     *                                     name is taken, or the room is full
     */
    public String joinRoom(JoinRoomRequest joinRequest) {
        String sanitizedRoomName = InputSanitizer.sanitize(joinRequest.roomName());
        InputSanitizer.sanitize(joinRequest.playerName());

        Room room = findRoomByName(sanitizedRoomName);
        if (room == null) {
            logger.warn("Join attempt failed: room not found for name {}", sanitizedRoomName);
            throw new ResourceNotFoundException("Room not found");
        }

        return (self == null ? this : self).joinRoom(room.getRoomId(), joinRequest);
    }

    /**
     * Convenience method for joining a room by ID with player name and optional password.
     *
     * @param roomId     stable target room identity
     * @param playerName player name joining the room
     * @param password   optional room password
     * @return joined room identity
     * @throws ResourceNotFoundException if the room disappeared after name resolution
     * @throws BadRequestException       if capacity, password, or name rules reject the join
     */
    public String joinRoom(String roomId, String playerName, String password) {
        Room room = rooms.get(roomId);
        if (room == null) {
            throw new ResourceNotFoundException("Room not found");
        }
        return (self == null ? this : self).joinRoom(roomId, new JoinRoomRequest(room.getRoomName(), playerName, password));
    }

    /**
     * Joins by stable room ID after the name-based public API resolves the target.
     * This keeps the persistence lock independent of mutable or case-insensitive room
     * names.
     *
     * @param roomId      stable target room identity
     * @param joinRequest validated join request
     * @return joined room identity
     * @throws ResourceNotFoundException if the room disappeared after name resolution
     * @throws BadRequestException if capacity, password, or name rules reject the join
     */
    @DurableMutation(roomId = "#roomId")
    public String joinRoom(String roomId, JoinRoomRequest joinRequest) {
        RoomPasswordPolicy.validateLength(joinRequest.password());
        String sanitizedPlayerName = InputSanitizer.sanitize(joinRequest.playerName());
        Room room = rooms.get(roomId);
        if (room == null) {
            throw new ResourceNotFoundException("Room not found");
        }

        synchronized (room) {
            if (rooms.get(roomId) != room) {
                logger.warn("Join attempt failed: room not found for id {} (player: {})", roomId, sanitizedPlayerName);
                throw new ResourceNotFoundException("Room not found");
            }

            if (room.isGameStarted()) {
                logger.warn("Attempted to join room {} after game started", room.getRoomName());
                throw new BadRequestException("Cannot join room. The game has already started.");
            }

            // Check password if the room is private
            if (room.hasPassword() && !room.checkPassword(joinRequest.password(), passwordEncoder)) {
                logger.warn("Invalid password attempt for room {} by player {}", room.getRoomName(),
                        sanitizedPlayerName);
                throw new BadRequestException("Invalid password");
            }

            // Check if the room is full
            if (room.isFull()) {
                logger.warn("Join attempt failed: room {} is full (player: {})", room.getRoomName(),
                        sanitizedPlayerName);
                throw new BadRequestException("Room is full");
            }

            // Check if the player name already exists
            if (room.hasPlayer(sanitizedPlayerName)) {
                logger.warn("Join attempt failed: player name '{}' already taken in room {}", sanitizedPlayerName,
                        room.getRoomName());
                throw new BadRequestException("Player name already taken");
            }

            try {
                room.addPlayer(sanitizedPlayerName);
            } catch (IllegalStateException e) {
                if ("Room is full".equals(e.getMessage()) || (e.getMessage() != null && e.getMessage().contains("full"))) {
                    logger.warn("Join attempt failed: room {} capacity exceeded: {}", room.getRoomName(), e.getMessage());
                    throw new BadRequestException("Room is full");
                }
                throw e;
            }
            long acceptanceDeadline = System.currentTimeMillis() + disconnectGracePeriodMs;
            room.setAbsenceDeadline(sanitizedPlayerName, acceptanceDeadline);

        }

        DurableTransactionContext.afterCommit(() -> {
            Room joinedRoom = rooms.get(roomId);
            String mId = joinedRoom != null ? joinedRoom.getMembershipId(sanitizedPlayerName) : null;
            Long absenceDeadline = joinedRoom != null ? joinedRoom.getAbsenceDeadline(sanitizedPlayerName) : null;
            long deadline = absenceDeadline != null ? absenceDeadline : (System.currentTimeMillis() + disconnectGracePeriodMs);
            eventPublisher.publishEvent(new RoomMemberRegistered(roomId, sanitizedPlayerName, mId, deadline));
        });

        logger.info("Player {} joined room: {}", sanitizedPlayerName, room.getRoomName());

        sendAfterCommit("/room/" + roomId,
                new ApiResponse<>(ResponseMessage.PLAYER_JOINED.getMessage(), getRoomData(roomId)));

        return roomId;
    }

    /**
     * Removes a player from a room during the lobby phase.
     * If the host leaves, the entire room is destroyed.
     * If all players leave, the room is also destroyed.
     *
     * @param roomId     the unique identifier of the room
     * @param playerName the name of the player leaving
     * @throws ResourceNotFoundException if room not found
     */
    @DurableMutation(roomId = "#roomId")
    public void leaveRoom(String roomId, String playerName) {
        Room room = rooms.get(roomId);
        if (room == null) {
            logger.warn("Leave attempt failed: room not found for id {} (player: {})", roomId, playerName);
            throw new ResourceNotFoundException("Room not found");
        }

        synchronized (room) {
            if (rooms.get(roomId) != room) {
                logger.warn("Leave attempt failed: room not found for id {} (player: {})", roomId, playerName);
                throw new ResourceNotFoundException("Room not found");
            }

            if (!room.hasPlayer(playerName)) {
                logger.warn("Leave attempt failed: player {} not in room {}", playerName, roomId);
                throw new BadRequestException(
                        String.format("Cannot remove %s from room as they are not already in the room", playerName));
            }

            String currentHost = roomHosts.getOrDefault(roomId, room.getHostName());
            boolean isHost = playerName.equals(currentHost);
            String membershipId = room.getMembershipId(playerName);

            if (isHost) {
                // Host is leaving the lobby phase - destroy the room.
                logger.info("Host {} leaving room {} during lobby phase, destroying room", playerName,
                        room.getRoomName());
                sendAfterCommit("/room/" + roomId,
                        new ApiResponse<>(ResponseMessage.ROOM_CLOSED.getMessage(), null));
                destroyRoom(roomId);
            } else {
                // Regular player leaving lobby
                room.removePlayer(playerName);
                DurableTransactionContext.afterCommit(() ->
                        eventPublisher.publishEvent(new RoomMembershipRetired(roomId, playerName, membershipId)));
                logger.info("Player {} left room: {}", playerName, room.getRoomName());

                if (room.getPlayers().isEmpty()) {
                    if (room.isTerminal()) {
                        roomHosts.remove(roomId);
                        logger.info("No players remaining in terminal room {}, preserving container until cleanup", room.getRoomName());
                        sendAfterCommit("/room/" + roomId,
                                new ApiResponse<>(ResponseMessage.ROOM_CLOSED.getMessage(), null));
                    } else {
                        logger.info("No players remaining in room {}, destroying room", room.getRoomName());
                        sendAfterCommit("/room/" + roomId,
                                new ApiResponse<>(ResponseMessage.ROOM_CLOSED.getMessage(), null));
                        destroyRoom(roomId);
                    }
                } else {
                    sendAfterCommit("/room/" + roomId,
                            new ApiResponse<>(ResponseMessage.PLAYER_LEFT.getMessage(), getRoomData(roomId)));
                }
            }
        }
    }

    /**
     * Active-game single-player removal helper requiring an explicit preselected successor.
     * All production active departures enter through the lifecycle command.
     *
     * @param roomId                the unique identifier of the room
     * @param playerName            the name of the player leaving
     * @param preselectedSuccessor  the preselected successor host if host changes, or current host if preserved
     */
    @DurableMutation(roomId = "#roomId")
    public void leaveRoom(String roomId, String playerName, String preselectedSuccessor) {
        leavePlayersBatch(roomId, List.of(playerName), preselectedSuccessor);
    }

    /**
     * Detaches a set of active-game members with one host reconciliation and projection.
     * Requires the preselected surviving host computed by the lifecycle layer.
     *
     * @param roomId         the unique identifier of the room
     * @param playerNames    the names of players departing
     * @param survivingHost  the preselected surviving host, or null if no players remain
     */
    @DurableMutation(roomId = "#roomId")
    public void leavePlayersBatch(String roomId, List<String> playerNames, String survivingHost) {
        Room room = rooms.get(roomId);
        if (room == null || playerNames == null || playerNames.isEmpty()) return;
        synchronized (room) {
            if (rooms.get(roomId) != room) return;
            List<String> present = playerNames.stream().filter(room.getPlayers()::contains).distinct().toList();
            if (present.isEmpty()) return;
            java.util.Map<String, String> retiredMemberships = new java.util.HashMap<>();
            for (String name : present) {
                retiredMemberships.put(name, room.getMembershipId(name));
            }
            present.forEach(room::removePlayer);
            DurableTransactionContext.afterCommit(() ->
                    retiredMemberships.forEach((name, mid) ->
                            eventPublisher.publishEvent(new RoomMembershipRetired(roomId, name, mid))));
            if (room.getPlayers().isEmpty()) {
                roomHosts.remove(roomId);
                if (room.isTerminal()) {
                    sendAfterCommit("/room/" + roomId,
                            new ApiResponse<>(ResponseMessage.ROOM_CLOSED.getMessage(), null));
                } else {
                    sendAfterCommit("/room/" + roomId,
                            new ApiResponse<>(ResponseMessage.ROOM_CLOSED.getMessage(), null));
                    destroyRoom(roomId);
                }
            } else {
                if (survivingHost != null && room.getPlayers().contains(survivingHost)) {
                    roomHosts.put(roomId, survivingHost);
                } else if (!roomHosts.containsKey(roomId) || !room.getPlayers().contains(roomHosts.get(roomId))) {
                    roomHosts.put(roomId, room.getPlayers().getFirst());
                }
                sendAfterCommit("/room/" + roomId,
                        new ApiResponse<>(ResponseMessage.PLAYER_LEFT.getMessage(), getRoomData(roomId)));
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

        synchronized (room) {
            String currentHost = roomHosts.getOrDefault(roomId, room.getHostName());

            // Convert player names to PlayerInfoDTO objects
            List<PlayerJoinInfo> playerObjects = room.getPlayers().stream()
                    .map(playerName -> {
                        LocalDateTime joinedAt = room.getJoinedAt(playerName);
                        String joinedAtStr = (joinedAt != null) ? joinedAt.toString() : "recently";
                        return new PlayerJoinInfo(
                                playerName,
                                currentHost.equals(playerName),
                                joinedAtStr
                        );
                    })
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
                    !room.isGameStarted() && playerObjects.size() >= 2,
                    room.isGameStarted());
        }
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
     * Checks if a player is currently a member of a specific room.
     *
     * @param roomId room identifier
     * @param playerName player name to check
     * @return true if player is currently in the room, false otherwise
     */
    public boolean isPlayerInRoom(String roomId, String playerName) {
        Room room = rooms.get(roomId);
        return room != null && room.hasPlayer(playerName);
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
        Room room = rooms.get(roomId);
        String hostName = roomHosts.getOrDefault(roomId, room == null ? null : room.getHostName());
        return hostName != null && hostName.equals(playerName);
    }

    /**
     * Returns the transferable current host separately from the room's original host,
     * preserving active-game host succession across restart.
     *
     * @param roomId room identity
     * @return current host, original host as fallback, or {@code null} if absent
     */
    public String getCurrentHost(String roomId) {
        Room room = rooms.get(roomId);
        return roomHosts.getOrDefault(roomId, room == null ? null : room.getHostName());
    }

    /**
     * Registers an explicitly rehydrated room without publishing creation events or
     * revalidating historical lobby choices. Restores in-memory state under the room
     * lock without dropping existing locks or leaking inconsistent player mapping.
     *
     * @param room        restored room
     * @param currentHost restored current host
     */
    public void restoreRoom(Room room, String currentHost) {
        if (room == null) {
            return;
        }
        String roomId = room.getRoomId();
        Room existing = rooms.get(roomId);
        Room lockTarget = existing != null ? existing : room;
        synchronized (lockTarget) {
            if (existing != null && existing != room) {
                synchronized (room) {
                    applyRestoreRoom(room, currentHost, existing);
                }
            } else {
                applyRestoreRoom(room, currentHost, existing);
            }
        }
    }

    private void applyRestoreRoom(Room room, String currentHost, Room existing) {
        String roomId = room.getRoomId();
        if (existing != null && existing != room) {
            for (String playerName : room.getPlayers()) {
                if (existing.hasPlayer(playerName)) {
                    if (!room.hasPersistedMembershipId(playerName)) {
                        String existingMembershipId = existing.getMembershipId(playerName);
                        if (existingMembershipId != null && !existingMembershipId.isBlank()) {
                            room.setMembershipId(playerName, existingMembershipId);
                        }
                    }
                    Long deadline = existing.getAbsenceDeadline(playerName);
                    if (deadline != null && room.getAbsenceDeadline(playerName) == null) {
                        room.setAbsenceDeadline(playerName, deadline);
                    }
                    if (existing.isPlayerConnected(playerName)) {
                        room.setPlayerConnected(playerName, true);
                    }
                }
            }
        }
        rooms.put(roomId, room);
        String host = null;
        if (currentHost != null && !currentHost.trim().isEmpty() && room.hasPlayer(currentHost)) {
            host = currentHost;
        } else if (room.getHostName() != null && room.hasPlayer(room.getHostName())) {
            host = room.getHostName();
        } else if (!room.getPlayers().isEmpty()) {
            host = room.getPlayers().getFirst();
        }
        if (host != null) {
            roomHosts.put(roomId, host);
        } else {
            roomHosts.remove(roomId);
        }
    }

    /**
     * Evicts a room and its host mapping from in-memory registries without
     * triggering durable deletion or publishing room-closed broadcasts.
     *
     * @param roomId the unique identifier of the room to evict
     */
    public void evictRoom(String roomId) {
        if (roomId == null) {
            return;
        }
        Room room = rooms.remove(roomId);
        roomHosts.remove(roomId);
        if (room != null) {
            synchronized (room) {
                logger.info("Room evicted from memory: {} (ID: {})", room.getRoomName(), roomId);
            }
        }
    }

    /**
     * Completely destroys and removes a room from the system.
     * Removes both the room and its host mapping.
     *
     * @param roomId the unique identifier of the room to destroy
     */
    @DurableMutation(roomId = "#roomId")
    public void destroyRoom(String roomId) {
        Room room = rooms.remove(roomId);
        roomHosts.remove(roomId);
        if (room != null) {
            synchronized (room) {
                logger.info("Room destroyed: {} (ID: {})", room.getRoomName(), roomId);
            }
        }
        DurableTransactionContext.afterCommit(() -> eventPublisher.publishEvent(new RoomDestroyed(roomId)));
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

    /**
     * Delays lobby messages until the state clients will query is durably committed.
     * Outside a durable mutation the context executes immediately.
     *
     * @param destination STOMP destination
     * @param payload     client-visible message
     */
    private void sendAfterCommit(String destination, Object payload) {
        DurableTransactionContext.afterCommit(() -> messagingTemplate.convertAndSend(destination, payload));
    }
}
