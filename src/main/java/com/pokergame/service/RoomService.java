package com.pokergame.service;

import com.pokergame.util.RoomPasswordPolicy;
import com.pokergame.dto.internal.PlayerJoinInfo;
import com.pokergame.dto.internal.RoomEntryResult;
import com.pokergame.dto.response.RoomDataResponse;
import com.pokergame.dto.request.CreateRoomRequest;
import com.pokergame.dto.request.JoinRoomRequest;
import com.pokergame.dto.response.ApiResponse;
import com.pokergame.enums.ResponseMessage;
import com.pokergame.service.event.RoomDestroyed;
import com.pokergame.service.event.RoomMemberRegistered;
import com.pokergame.service.event.RoomMembershipRetired;
import org.springframework.beans.factory.ObjectProvider;
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
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.stream.Collectors;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.beans.factory.annotation.Qualifier;

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
    private final RoomCoordinator roomCoordinator;
    private final Object roomRegistrationLock = new Object();
    private RoomService self;
    private Clock clock = Clock.systemUTC();
    private TaskScheduler taskScheduler;
    private final Map<String, ScheduledFuture<?>> inactivityFutures = new ConcurrentHashMap<>();
    private static final long LOBBY_INACTIVITY_MS = 30 * 60_000L;

    /** @param clock authoritative lifecycle time source */
    @Autowired
    void setClock(Clock clock) { this.clock = clock; }

    @Autowired(required = false)
    void setTaskScheduler(@Qualifier("taskScheduler") TaskScheduler taskScheduler) {
        this.taskScheduler = taskScheduler;
    }

    /**
     * Creates the room registry with the publisher used for committed lobby state.
     *
     * @param messagingTemplate WebSocket messaging collaborator
     */
    public RoomService(SimpMessagingTemplate messagingTemplate) {
        this(messagingTemplate, event -> { }, 120_000L, new BCryptPasswordEncoder(), new RoomCoordinator());
    }

    /**
     * Creates the room registry with WebSocket messaging collaborator and password encoder.
     *
     * @param messagingTemplate WebSocket messaging collaborator
     * @param passwordEncoder   password encoder for hashing and validating credentials
     */
    public RoomService(SimpMessagingTemplate messagingTemplate, PasswordEncoder passwordEncoder) {
        this(messagingTemplate, event -> { }, 120_000L, passwordEncoder, new RoomCoordinator());
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
        this(messagingTemplate, eventPublisher, disconnectGracePeriodMs, new BCryptPasswordEncoder(), new RoomCoordinator());
    }

    /**
     * Creates the room registry with an event publisher and password encoder.
     *
     * @param messagingTemplate       WebSocket messaging collaborator
     * @param eventPublisher          publishes room lifecycle events once a mutation is accepted
     * @param disconnectGracePeriodMs reconnect grace interval in ms
     * @param passwordEncoder         password encoder for hashing and verifying room passwords
     */
    public RoomService(SimpMessagingTemplate messagingTemplate,
            ApplicationEventPublisher eventPublisher,
            long disconnectGracePeriodMs,
            PasswordEncoder passwordEncoder) {
        this(messagingTemplate, eventPublisher, disconnectGracePeriodMs, passwordEncoder, new RoomCoordinator());
    }

    /**
     * Autowired constructor with all dependencies including optional/provided RoomCoordinator.
     *
     * @param messagingTemplate       WebSocket messaging collaborator
     * @param eventPublisher          publishes room lifecycle events once a mutation is accepted
     * @param disconnectGracePeriodMs reconnect grace interval in ms
     * @param passwordEncoder         password encoder for hashing and verifying room passwords
     * @param roomCoordinatorProvider provider for the room-scoped coordinator
     */
    @Autowired
    public RoomService(SimpMessagingTemplate messagingTemplate,
            ApplicationEventPublisher eventPublisher,
            @Value("${poker.disconnect.grace-period-ms:120000}") long disconnectGracePeriodMs,
            PasswordEncoder passwordEncoder,
            ObjectProvider<RoomCoordinator> roomCoordinatorProvider) {
        this.messagingTemplate = messagingTemplate;
        this.eventPublisher = eventPublisher;
        this.disconnectGracePeriodMs = disconnectGracePeriodMs;
        this.passwordEncoder = passwordEncoder != null ? passwordEncoder : new BCryptPasswordEncoder();
        this.roomCoordinator = roomCoordinatorProvider.getIfAvailable(RoomCoordinator::new);
    }

    /**
     * Fully specified constructor.
     *
     * @param messagingTemplate       WebSocket messaging collaborator
     * @param eventPublisher          publishes room lifecycle events once a mutation is accepted
     * @param disconnectGracePeriodMs reconnect grace interval in ms
     * @param passwordEncoder         password encoder for hashing and verifying room passwords
     * @param roomCoordinator         coordinator managing room-scoped concurrency boundaries
     */
    public RoomService(SimpMessagingTemplate messagingTemplate,
            ApplicationEventPublisher eventPublisher,
            long disconnectGracePeriodMs,
            PasswordEncoder passwordEncoder,
            RoomCoordinator roomCoordinator) {
        this.messagingTemplate = messagingTemplate;
        this.eventPublisher = eventPublisher;
        this.disconnectGracePeriodMs = disconnectGracePeriodMs;
        this.passwordEncoder = passwordEncoder != null ? passwordEncoder : new BCryptPasswordEncoder();
        this.roomCoordinator = roomCoordinator != null ? roomCoordinator : new RoomCoordinator();
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

    /**
     * Returns the room-scoped concurrency coordinator.
     *
     * @return room coordinator
     */
    public RoomCoordinator getRoomCoordinator() {
        return roomCoordinator;
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
     * Creates a room and returns both its identity and the canonical (sanitized) host
     * name as stored in the room. This is the entry point for callers that must bind a
     * credential to the stored name (for example the REST controller issuing a JWT),
     * so sanitization happens exactly once here in the service rather than being
     * repeated by the caller.
     *
     * @param request validated room configuration
     * @return admitted room identity, canonical host name, and MembershipId
     * @throws BadRequestException if the room name is already taken
     */
    public RoomEntryResult createRoomForEntry(CreateRoomRequest request) {
        String roomId = UUID.randomUUID().toString();
        return roomCoordinator.coordinate(roomId, () -> {
            (self == null ? this : self).createRoom(roomId, request);
            String canonicalPlayerName = getCurrentHost(roomId);
            return new RoomEntryResult(roomId, canonicalPlayerName,
                    rooms.get(roomId).getMembershipId(canonicalPlayerName));
        });
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

        return roomCoordinator.coordinate(roomId, () -> {
            synchronized (roomRegistrationLock) {
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
                room.setInactivityDeadlineEpochMs(clock.millis() + LOBBY_INACTIVITY_MS);
                scheduleLobbyInactivity(roomId, room.getInactivityDeadlineEpochMs());
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
        });
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
     * Joins a room and returns both its identity and the canonical (sanitized) player
     * name as stored in the room. This is the entry point for callers that must bind a
     * credential to the stored name (for example the REST controller issuing a JWT),
     * so sanitization happens exactly once here in the service rather than being
     * repeated by the caller.
     *
     * @param joinRequest validated join request
     * @return admitted room identity, canonical player name, and MembershipId
     * @throws ResourceNotFoundException if the room isn't found
     * @throws BadRequestException if the password is invalid, the player name is taken, or the room is full
     */
    public RoomEntryResult joinRoomForEntry(JoinRoomRequest joinRequest) {
        String roomName = InputSanitizer.sanitize(joinRequest.roomName());
        String canonicalPlayerName = InputSanitizer.sanitize(joinRequest.playerName());
        Room target = findRoomByName(roomName);
        if (target == null) throw new ResourceNotFoundException("Room not found");
        String roomId = target.getRoomId();
        return roomCoordinator.coordinate(roomId, () -> {
            (self == null ? this : self).joinRoom(roomId, joinRequest);
            return new RoomEntryResult(roomId, canonicalPlayerName,
                    rooms.get(roomId).getMembershipId(canonicalPlayerName));
        });
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
        if (roomId == null) {
            throw new ResourceNotFoundException("Room not found");
        }
        return roomCoordinator.coordinate(roomId, () -> {
            Room room = rooms.get(roomId);
            if (room == null) {
                throw new ResourceNotFoundException("Room not found");
            }
            if (isLobbyExpired(room)) {
                checkAndCloseIfExpired(roomId);
                throw new BadRequestException("Room has expired due to inactivity.");
            }
            return (self == null ? this : self).joinRoom(roomId, new JoinRoomRequest(room.getRoomName(), playerName, password));
        });
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

        return roomCoordinator.coordinate(roomId, () -> {
            Room room = rooms.get(roomId);
            if (room == null) {
                throw new ResourceNotFoundException("Room not found");
            }

            if (rooms.get(roomId) != room) {
                logger.warn("Join attempt failed: room not found for id {} (player: {})", roomId, sanitizedPlayerName);
                throw new ResourceNotFoundException("Room not found");
            }

            if (room.isGameStarted()) {
                logger.warn("Attempted to join room {} after game started", room.getRoomName());
                throw new BadRequestException("Cannot join room. The game has already started.");
            }

            if (isLobbyExpired(room)) {
                logger.warn("Join attempt failed: room {} has expired due to inactivity", room.getRoomName());
                checkAndCloseIfExpired(roomId);
                throw new BadRequestException("Room has expired due to inactivity.");
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
            room.setInactivityDeadlineEpochMs(clock.millis() + LOBBY_INACTIVITY_MS);
            scheduleLobbyInactivity(roomId, room.getInactivityDeadlineEpochMs());
            long acceptanceDeadline = System.currentTimeMillis() + disconnectGracePeriodMs;
            room.setAbsenceDeadline(sanitizedPlayerName, acceptanceDeadline);

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
        });
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
        roomCoordinator.coordinate(roomId, () -> {
            roomCoordinator.assertHeldByCurrentThread(roomId);
            Room room = rooms.get(roomId);
            if (room == null) {
                logger.warn("Leave attempt failed: room not found for id {} (player: {})", roomId, playerName);
                throw new ResourceNotFoundException("Room not found");
            }

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
                cancelLobbyInactivity(roomId);
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
                    cancelLobbyInactivity(roomId);
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
                    room.setInactivityDeadlineEpochMs(clock.millis() + LOBBY_INACTIVITY_MS);
                    scheduleLobbyInactivity(roomId, room.getInactivityDeadlineEpochMs());
                    sendAfterCommit("/room/" + roomId,
                            new ApiResponse<>(ResponseMessage.PLAYER_LEFT.getMessage(), getRoomData(roomId)));
                }
            }
        });
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
        roomCoordinator.coordinate(roomId, () -> {
            leavePlayersBatch(roomId, List.of(playerName), preselectedSuccessor);
            return null;
        });
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
        roomCoordinator.assertHeldByCurrentThread(roomId);
        Room room = rooms.get(roomId);
        if (room == null || playerNames == null || playerNames.isEmpty()) return;
        if (rooms.get(roomId) != room) return;
        List<String> present = playerNames.stream().filter(room.getPlayers()::contains).distinct().toList();
        if (present.isEmpty()) return;
        Map<String, String> retiredMemberships = new HashMap<>();
        for (String name : present) {
            retiredMemberships.put(name, room.getMembershipId(name));
        }
        present.forEach(room::removePlayer);
        DurableTransactionContext.afterCommit(() ->
                retiredMemberships.forEach((name, mid) ->
                        eventPublisher.publishEvent(new RoomMembershipRetired(roomId, name, mid))));
        if (room.getPlayers().isEmpty()) {
            cancelLobbyInactivity(roomId);
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
            if (!room.isGameStarted()) {
                room.setInactivityDeadlineEpochMs(clock.millis() + LOBBY_INACTIVITY_MS);
                scheduleLobbyInactivity(roomId, room.getInactivityDeadlineEpochMs());
            }
            if (survivingHost != null && room.getPlayers().contains(survivingHost)) {
                roomHosts.put(roomId, survivingHost);
            } else if (!roomHosts.containsKey(roomId) || !room.getPlayers().contains(roomHosts.get(roomId))) {
                roomHosts.put(roomId, room.getPlayers().getFirst());
            }
            sendAfterCommit("/room/" + roomId,
                    new ApiResponse<>(ResponseMessage.PLAYER_LEFT.getMessage(), getRoomData(roomId)));
        }
    }

    /**
     * Retrieves all currently available rooms by resolving identities individually.
     *
     * @return a list of all Room objects
     */
    public List<Room> getRooms() {
        List<String> roomIds = new ArrayList<>(rooms.keySet());
        return roomIds.stream()
                .map(this::getRoom)
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * Retrieves formatted room data for API responses and WebSocket broadcasts.
     * Includes player information with host status indicators.
     * Coordinated under the room boundary before reading published state.
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
        return roomCoordinator.coordinate(roomId, () -> {
            Room room = rooms.get(roomId);
            if (room == null || isLobbyExpired(room)) {
                if (room != null) {
                    checkAndCloseIfExpired(roomId);
                }
                logger.warn("Room data request failed: room not found for id {}", roomId);
                throw new ResourceNotFoundException("Room not found");
            }

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
                    room.isGameStarted(),
                    room.getInactivityDeadlineEpochMs(),
                    clock.millis());
        });
    }

    /**
     * Retrieves a room by its unique identifier as a pure internal lookup without lifecycle side effects.
     * Callers must hold the room lease while accessing mutable state and must not retain the result.
     *
     * @param roomId the unique identifier of the room
     * @return the Room object if found, null otherwise
     */
    public Room getRoom(String roomId) {
        if (roomId == null) {
            return null;
        }
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
        if (roomId == null) {
            return false;
        }
        return roomCoordinator.coordinate(roomId, () -> {
            Room room = rooms.get(roomId);
            return room != null && room.hasPlayer(playerName);
        });
    }

    /**
     * Finds a room by its name (case-insensitive) as a pure lookup.
     *
     * @param roomName the name of the room to find
     * @return the Room object if found, null otherwise
     */
    public Room findRoomByName(String roomName) {
        if (roomName == null) {
            return null;
        }
        return rooms.values().stream()
                .filter(r -> r.getRoomName().equalsIgnoreCase(roomName))
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
        return roomCoordinator.coordinate(roomId, () -> {
            Room room = rooms.get(roomId);
            String hostName = roomHosts.getOrDefault(roomId, room == null ? null : room.getHostName());
            return hostName != null && hostName.equals(playerName);
        });
    }

    /**
     * Returns the transferable current host separately from the room's original host,
     * preserving active-game host succession across restart.
     *
     * @param roomId room identity
     * @return current host, original host as fallback, or {@code null} if absent
     */
    public String getCurrentHost(String roomId) {
        return roomCoordinator.coordinate(roomId, () -> {
            Room room = rooms.get(roomId);
            return roomHosts.getOrDefault(roomId, room == null ? null : room.getHostName());
        });
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
        roomCoordinator.coordinate(roomId, () -> {
            Room existing = rooms.get(roomId);
            applyRestoreRoom(room, currentHost, existing);
            return null;
        });
    }

    private void applyRestoreRoom(Room room, String currentHost, Room existing) {
        String roomId = room.getRoomId();
        roomCoordinator.assertHeldByCurrentThread(roomId);
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
        roomCoordinator.coordinate(roomId, () -> {
            Room room = rooms.remove(roomId);
            roomHosts.remove(roomId);
            if (room != null) {
                logger.info("Room evicted from memory: {} (ID: {})", room.getRoomName(), roomId);
            }
            return null;
        });
    }

    /**
     * Completely destroys and removes a room from the system.
     * Removes both the room and its host mapping.
     *
     * @param roomId the unique identifier of the room to destroy
     */
    @DurableMutation(roomId = "#roomId")
    public void destroyRoom(String roomId) {
        if (roomId == null) {
            return;
        }
        roomCoordinator.coordinate(roomId, () -> {
            roomCoordinator.assertHeldByCurrentThread(roomId);
            cancelLobbyInactivity(roomId);
            Room room = rooms.remove(roomId);
            roomHosts.remove(roomId);
            if (room != null) {
                logger.info("Room destroyed: {} (ID: {})", room.getRoomName(), roomId);
            }
            DurableTransactionContext.afterCommit(() -> eventPublisher.publishEvent(new RoomDestroyed(roomId)));
            return null;
        });
    }

    /**
     * Schedules the authoritative lobby inactivity expiration task.
     *
     * @param roomId room identity
     * @param deadlineEpochMs absolute deadline timestamp
     */
    public void scheduleLobbyInactivity(String roomId, Long deadlineEpochMs) {
        if (taskScheduler == null || deadlineEpochMs == null) return;
        cancelLobbyInactivity(roomId);
        long delay = Math.max(0, deadlineEpochMs - clock.millis());
        try {
            ScheduledFuture<?> future = taskScheduler.schedule(
                    () -> {
                        try {
                            (self != null ? self : this).closeLobbyDueToInactivity(roomId, deadlineEpochMs);
                        } catch (Exception e) {
                            logger.error("Error executing scheduled lobby inactivity for room {}", roomId, e);
                        }
                    },
                    Instant.ofEpochMilli(System.currentTimeMillis() + delay)
            );
            inactivityFutures.put(roomId, future);
        } catch (Exception e) {
            logger.warn("Scheduler rejected lobby inactivity for room {}", roomId, e);
        }
    }

    /** Cancels pending lobby inactivity expiration task for the room. */
    public void cancelLobbyInactivity(String roomId) {
        ScheduledFuture<?> future = inactivityFutures.remove(roomId);
        if (future != null) {
            future.cancel(false);
        }
    }

    /**
     * @param room room candidate
     * @return true if room is an unstarted lobby whose inactivity deadline has elapsed
     */
    public boolean isLobbyExpired(Room room) {
        if (room == null) return false;
        return !room.isGameStarted()
                && room.getInactivityDeadlineEpochMs() != null
                && clock.millis() >= room.getInactivityDeadlineEpochMs();
    }

    /**
     * Checks if a lobby has expired and closes it if so under the room coordination boundary.
     *
     * @param roomId room identity
     * @return true if room was closed due to inactivity
     */
    public boolean checkAndCloseIfExpired(String roomId) {
        if (roomId == null) return false;
        return roomCoordinator.coordinate(roomId, () -> {
            Room room = rooms.get(roomId);
            if (room == null) return false;
            if (rooms.get(roomId) != room) return false;
            if (!room.isGameStarted() && room.getInactivityDeadlineEpochMs() != null
                    && clock.millis() >= room.getInactivityDeadlineEpochMs()) {
                (self != null ? self : this).closeLobbyDueToInactivity(roomId, room.getInactivityDeadlineEpochMs());
                return true;
            }
            return false;
        });
    }

    /**
     * Closes an inactive lobby authoritatively, marks it terminal/destroyed,
     * and broadcasts ROOM_CLOSED with an inactivity reason.
     *
     * @param roomId room identity
     * @param deadlineEpochMs deadline at which closure was scheduled
     */
    @DurableMutation(roomId = "#roomId")
    public void closeLobbyDueToInactivity(String roomId, long deadlineEpochMs) {
        if (roomId == null) return;
        roomCoordinator.coordinate(roomId, () -> {
            roomCoordinator.assertHeldByCurrentThread(roomId);
            Room room = rooms.get(roomId);
            if (room == null) return null;
            if (rooms.get(roomId) != room) return null;
            if (room.isGameStarted()) return null;
            Long currentDeadline = room.getInactivityDeadlineEpochMs();
            if (currentDeadline == null) return null;
            if (currentDeadline > deadlineEpochMs && clock.millis() < currentDeadline) return null;
            if (clock.millis() < currentDeadline) return null;

            cancelLobbyInactivity(roomId);
            room.setTerminal(true);
            rooms.remove(roomId);
            roomHosts.remove(roomId);
            logger.info("Lobby {} closed due to inactivity after deadline {}", roomId, currentDeadline);
            sendAfterCommit("/room/" + roomId,
                    new ApiResponse<>(ResponseMessage.ROOM_CLOSED.getMessage(), Map.of("roomId", roomId, "reason", "INACTIVITY")));
            DurableTransactionContext.afterCommit(() -> eventPublisher.publishEvent(new RoomDestroyed(roomId)));
            return null;
        });
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
