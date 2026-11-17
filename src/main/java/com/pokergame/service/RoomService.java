package com.pokergame.service;

import com.pokergame.util.RoomCoordinator;

import static java.util.Objects.requireNonNull;

import com.pokergame.config.RoomTimingProperties;
import com.pokergame.dto.internal.PlayerJoinInfo;
import com.pokergame.dto.internal.RoomEntryResult;
import com.pokergame.dto.request.CreateRoomRequest;
import com.pokergame.dto.request.JoinRoomRequest;
import com.pokergame.dto.response.ApiResponse;
import com.pokergame.dto.response.RoomDataResponse;
import com.pokergame.enums.ResponseMessage;
import com.pokergame.exception.BadRequestException;
import com.pokergame.exception.ResourceNotFoundException;
import com.pokergame.model.Room;
import com.pokergame.persistence.transaction.DurableMutation;
import com.pokergame.persistence.transaction.PostSaveActions;
import com.pokergame.service.event.RoomDestroyed;
import com.pokergame.service.event.RoomMemberRegistered;
import com.pokergame.service.event.RoomMembershipRetired;
import com.pokergame.util.InputSanitizer;
import com.pokergame.util.RoomPasswordPolicy;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Manages poker rooms during creation, joining, lobby membership changes, host tracking,
 * and lobby inactivity cleanup.
 */
@Service
public class RoomService {
    private static final Logger logger = LoggerFactory.getLogger(RoomService.class);
    private final SimpMessagingTemplate messagingTemplate;
    private final ApplicationEventPublisher eventPublisher;
    private final PasswordEncoder passwordEncoder;
    private final RoomRegistry rooms;
    private final RoomCoordinator roomCoordinator;
    private final Clock clock;
    private final TaskScheduler taskScheduler;
    private final RoomTimingProperties timings;
    private final ObjectProvider<RoomService> selfProvider;

    // Guards room-name uniqueness across different room IDs.
    private final Object roomRegistrationLock = new Object();
    private final Map<String, ScheduledFuture<?>> inactivityFutures = new ConcurrentHashMap<>();

    public RoomService(
            SimpMessagingTemplate messagingTemplate,
            ApplicationEventPublisher eventPublisher,
            PasswordEncoder passwordEncoder,
            RoomRegistry rooms,
            RoomCoordinator roomCoordinator,
            Clock clock,
            @Qualifier("taskScheduler") TaskScheduler taskScheduler,
            RoomTimingProperties timings,
            ObjectProvider<RoomService> selfProvider) {
        this.messagingTemplate = messagingTemplate;
        this.eventPublisher = eventPublisher;
        this.passwordEncoder = passwordEncoder;
        this.rooms = rooms;
        this.roomCoordinator = roomCoordinator;
        this.clock = clock;
        this.taskScheduler = taskScheduler;
        this.timings = timings;
        this.selfProvider = selfProvider;
    }

    /**
     * Resolves a room's unique ID from a user-supplied room name.
     *
     * @param rawRoomName the unsanitized room name
     * @return the matching room's unique ID
     * @throws ResourceNotFoundException if no room matches the sanitized name
     */
    public String resolveRoomIdByName(String rawRoomName) {
        String sanitizedRoomName = InputSanitizer.sanitize(rawRoomName);
        Room target = findRoomByName(sanitizedRoomName);
        if (target == null) {
            logger.warn("Room lookup failed: no room found for name {}", sanitizedRoomName);
            throw new ResourceNotFoundException("Room not found");
        }
        return target.getRoomId();
    }

    /**
     * Creates a new poker room under the specified room ID, registers the creator as the host,
     * and returns the entry details needed to issue credentials.
     *
     * @param roomId  the unique ID assigned to the new room
     * @param request the validated room configuration and host details
     * @return the room ID, canonical sanitized host name, and host membership ID
     * @throws BadRequestException if the room name is already taken, the password is invalid,
     *                             or the blind/buy-in ratios are invalid
     */
    @DurableMutation(roomId = "#roomId")
    public RoomEntryResult createRoom(String roomId, CreateRoomRequest request) {
        requireNonNull(roomId, "roomId must not be null");
        requireNonNull(request, "request must not be null");
        RoomPasswordPolicy.validateLength(request.getPassword());
        if (request.getSmallBlind() != null && request.getBigBlind() != null
                && request.getBigBlind() < 2L * request.getSmallBlind()) {
            throw new BadRequestException("Big blind must be at least twice the small blind.");
        }
        if (request.getBigBlind() != null && request.getBuyIn() != null
                && request.getBuyIn() < request.getBigBlind()) {
            throw new BadRequestException("Buy-in must be at least the big blind amount.");
        }
        String sanitizedRoomName = InputSanitizer.sanitize(request.getRoomName());
        String sanitizedPlayerName = InputSanitizer.sanitize(request.getPlayerName());

        String membershipId;
        synchronized (roomRegistrationLock) {
            if (isRoomNameTaken(sanitizedRoomName)) {
                logger.warn("Attempted to create room with duplicate name: {}", sanitizedRoomName);
                throw new BadRequestException(
                        "Room name '" + sanitizedRoomName + "' is already taken. Please choose a different name.");
            }

            String rawPassword = request.getPassword();
            String passwordToStore = (rawPassword != null && !rawPassword.trim().isEmpty())
                    ? passwordEncoder.encode(rawPassword)
                    : null;

            Room room = new Room(
                    roomId,
                    sanitizedRoomName,
                    sanitizedPlayerName,
                    request.getMaxPlayers(),
                    request.getSmallBlind(),
                    request.getBigBlind(),
                    request.getBuyIn(),
                    passwordToStore);

            room.addPlayer(sanitizedPlayerName);
            membershipId = room.getMembershipId(sanitizedPlayerName);
            room.setInactivityDeadlineEpochMs(clock.millis() + timings.lobbyInactivityMs());
            scheduleLobbyInactivity(roomId, room.getInactivityDeadlineEpochMs());
            long acceptanceDeadline = clock.millis() + timings.disconnectGracePeriodMs();
            room.setAbsenceDeadline(sanitizedPlayerName, acceptanceDeadline);

            rooms.put(room);
        }

        PostSaveActions.runAfterSave(() -> {
            Room createdRoom = rooms.get(roomId);
            String savedMembershipId = createdRoom != null
                    ? createdRoom.getMembershipId(sanitizedPlayerName)
                    : membershipId;
            Long absenceDeadline = createdRoom != null
                    ? createdRoom.getAbsenceDeadline(sanitizedPlayerName)
                    : null;
            long deadline = absenceDeadline != null
                    ? absenceDeadline
                    : clock.millis() + timings.disconnectGracePeriodMs();
            eventPublisher.publishEvent(
                    new RoomMemberRegistered(roomId, sanitizedPlayerName, savedMembershipId, deadline));
        });

        sendAfterSave("/room/" + roomId,
                new ApiResponse<>(ResponseMessage.ROOM_CREATED.getMessage(), getRoomData(roomId)));

        logger.info("Room created: {} (ID: {}) by host: {}", sanitizedRoomName, roomId, sanitizedPlayerName);
        return new RoomEntryResult(roomId, sanitizedPlayerName, membershipId);
    }

    /**
     * Adds a player to an existing unstarted lobby room after verifying capacity, password,
     * expiration, and player name availability.
     *
     * @param roomId      the unique ID of the room to join
     * @param joinRequest the validated join request containing player name and optional password
     * @return the room ID, canonical sanitized player name, and assigned membership ID
     * @throws ResourceNotFoundException if no room exists for {@code roomId}
     * @throws BadRequestException       if the game started, lobby expired, room is full,
     *                                   password is wrong, or player name is already taken
     */
    @DurableMutation(roomId = "#roomId")
    public RoomEntryResult joinRoom(String roomId, JoinRoomRequest joinRequest) {
        requireNonNull(roomId, "roomId must not be null");
        requireNonNull(joinRequest, "joinRequest must not be null");
        RoomPasswordPolicy.validateLength(joinRequest.password());
        String sanitizedPlayerName = InputSanitizer.sanitize(joinRequest.playerName());

        Room room = rooms.get(roomId);
        if (room == null) {
            logger.warn("Join attempt failed: room not found for id {} (player: {})", roomId, sanitizedPlayerName);
            throw new ResourceNotFoundException("Room not found");
        }
        if (room.isGameStarted()) {
            logger.warn("Attempted to join room {} after game started", room.getRoomName());
            throw new BadRequestException("Cannot join room. The game has already started.");
        }
        if (isLobbyExpired(room)) {
            logger.warn("Join attempt failed: room {} has expired due to inactivity", room.getRoomName());
            throw new BadRequestException("Room has expired due to inactivity.");
        }
        if (room.hasPassword() && !room.checkPassword(joinRequest.password(), passwordEncoder)) {
            logger.warn("Invalid password attempt for room {} by player {}",
                    room.getRoomName(), sanitizedPlayerName);
            throw new BadRequestException("Invalid password");
        }
        if (room.isFull()) {
            logger.warn("Join attempt failed: room {} is full (player: {})",
                    room.getRoomName(), sanitizedPlayerName);
            throw new BadRequestException("Room is full");
        }
        if (room.hasPlayer(sanitizedPlayerName)) {
            logger.warn("Join attempt failed: player name '{}' already taken in room {}",
                    sanitizedPlayerName, room.getRoomName());
            throw new BadRequestException("Player name already taken");
        }

        try {
            room.addPlayer(sanitizedPlayerName);
        } catch (IllegalStateException e) {
            if ("Room is full".equals(e.getMessage())
                    || (e.getMessage() != null && e.getMessage().contains("full"))) {
                logger.warn("Join attempt failed: room {} capacity exceeded: {}",
                        room.getRoomName(), e.getMessage());
                throw new BadRequestException("Room is full");
            }
            throw e;
        }

        String membershipId = room.getMembershipId(sanitizedPlayerName);
        room.setInactivityDeadlineEpochMs(clock.millis() + timings.lobbyInactivityMs());
        scheduleLobbyInactivity(roomId, room.getInactivityDeadlineEpochMs());
        long acceptanceDeadline = clock.millis() + timings.disconnectGracePeriodMs();
        room.setAbsenceDeadline(sanitizedPlayerName, acceptanceDeadline);

        PostSaveActions.runAfterSave(() -> {
            Room joinedRoom = rooms.get(roomId);
            String savedMembershipId = joinedRoom != null
                    ? joinedRoom.getMembershipId(sanitizedPlayerName)
                    : membershipId;
            Long absenceDeadline = joinedRoom != null
                    ? joinedRoom.getAbsenceDeadline(sanitizedPlayerName)
                    : null;
            long deadline = absenceDeadline != null
                    ? absenceDeadline
                    : clock.millis() + timings.disconnectGracePeriodMs();
            eventPublisher.publishEvent(
                    new RoomMemberRegistered(roomId, sanitizedPlayerName, savedMembershipId, deadline));
        });

        logger.info("Player {} joined room: {}", sanitizedPlayerName, room.getRoomName());
        sendAfterSave("/room/" + roomId,
                new ApiResponse<>(ResponseMessage.PLAYER_JOINED.getMessage(), getRoomData(roomId)));

        return new RoomEntryResult(roomId, sanitizedPlayerName, membershipId);
    }

    /**
     * Removes a player from a lobby room. If the host leaves or the room becomes empty, the
     * room is closed and cleaned up unless the game has already finished and is awaiting cleanup.
     *
     * @param roomId     the unique ID of the room
     * @param playerName the name of the departing player
     * @throws ResourceNotFoundException if the room does not exist
     * @throws BadRequestException       if the player is not currently in the room
     */
    @DurableMutation(roomId = "#roomId")
    public void leaveRoom(String roomId, String playerName) {
        requireNonNull(roomId, "roomId must not be null");
        requireNonNull(playerName, "playerName must not be null");
        Room room = rooms.get(roomId);
        if (room == null) {
            logger.warn("Leave attempt failed: room not found for id {} (player: {})", roomId, playerName);
            throw new ResourceNotFoundException("Room not found");
        }
        if (!room.hasPlayer(playerName)) {
            logger.warn("Leave attempt failed: player {} not in room {}", playerName, roomId);
            throw new BadRequestException(
                    String.format("Cannot remove %s from room as they are not already in the room", playerName));
        }

        boolean isHost = playerName.equals(room.getHostName());
        String membershipId = room.getMembershipId(playerName);

        if (isHost) {
            cancelLobbyInactivity(roomId);
            logger.info("Host {} leaving room {} during lobby phase, destroying room",
                    playerName, room.getRoomName());
            sendAfterSave("/room/" + roomId,
                    new ApiResponse<>(ResponseMessage.ROOM_CLOSED.getMessage(), null));
            destroyRoom(roomId);
            return;
        }

        room.removePlayer(playerName);
        PostSaveActions.runAfterSave(() ->
                eventPublisher.publishEvent(new RoomMembershipRetired(roomId, playerName, membershipId)));
        logger.info("Player {} left room: {}", playerName, room.getRoomName());

        if (room.getPlayers().isEmpty()) {
            cancelLobbyInactivity(roomId);
            if (room.isTerminal()) {
                logger.info("No players remaining in finished room {}, keeping container until cleanup",
                        room.getRoomName());
                sendAfterSave("/room/" + roomId,
                        new ApiResponse<>(ResponseMessage.ROOM_CLOSED.getMessage(), null));
            } else {
                logger.info("No players remaining in room {}, destroying room", room.getRoomName());
                sendAfterSave("/room/" + roomId,
                        new ApiResponse<>(ResponseMessage.ROOM_CLOSED.getMessage(), null));
                destroyRoom(roomId);
            }
        } else {
            room.setInactivityDeadlineEpochMs(clock.millis() + timings.lobbyInactivityMs());
            scheduleLobbyInactivity(roomId, room.getInactivityDeadlineEpochMs());
            sendAfterSave("/room/" + roomId,
                    new ApiResponse<>(ResponseMessage.PLAYER_LEFT.getMessage(), getRoomData(roomId)));
        }
    }

    /**
     * Removes one or more players from a room during an active or completed game and updates
     * the room host when the previous host departs.
     *
     * @param roomId        the unique ID of the room
     * @param playerNames   the departing player names
     * @param survivingHost the preselected next host when host transfer occurs, or {@code null}
     */
    @DurableMutation(roomId = "#roomId")
    public void leavePlayersBatch(String roomId, List<String> playerNames, String survivingHost) {
        roomCoordinator.assertHeldByCurrentThread(roomId);
        Room room = rooms.get(roomId);
        if (room == null || playerNames == null || playerNames.isEmpty()) {
            return;
        }

        List<String> existingPlayers = room.getPlayers();
        List<String> present = playerNames.stream()
                .filter(existingPlayers::contains)
                .distinct()
                .toList();
        if (present.isEmpty()) {
            return;
        }

        Map<String, String> retiredMemberships = new HashMap<>();
        for (String name : present) {
            retiredMemberships.put(name, room.getMembershipId(name));
        }
        present.forEach(room::removePlayer);

        PostSaveActions.runAfterSave(() -> retiredMemberships.forEach((name, mid) ->
                eventPublisher.publishEvent(new RoomMembershipRetired(roomId, name, mid))));

        if (room.getPlayers().isEmpty()) {
            cancelLobbyInactivity(roomId);
            sendAfterSave("/room/" + roomId, new ApiResponse<>(ResponseMessage.ROOM_CLOSED.getMessage(), null));
            if (!room.isTerminal()) {
                destroyRoom(roomId);
            }
            return;
        }

        if (!room.isGameStarted()) {
            room.setInactivityDeadlineEpochMs(clock.millis() + timings.lobbyInactivityMs());
            scheduleLobbyInactivity(roomId, room.getInactivityDeadlineEpochMs());
        }
        if (survivingHost != null && room.getPlayers().contains(survivingHost)) {
            room.setHostName(survivingHost);
        } else if (room.getHostName() == null || !room.getPlayers().contains(room.getHostName())) {
            room.setHostName(room.getPlayers().getFirst());
        }

        sendAfterSave("/room/" + roomId,
                new ApiResponse<>(ResponseMessage.PLAYER_LEFT.getMessage(), getRoomData(roomId)));
    }

    /**
     * Broadcasts after save that the room's game has started, attaching the current room snapshot.
     *
     * @param roomId the unique ID of the room whose game just started
     */
    public void broadcastGameStarted(String roomId) {
        requireNonNull(roomId, "roomId must not be null");
        roomCoordinator.assertHeldByCurrentThread(roomId);
        RoomDataResponse startPayload = getRoomData(roomId);
        sendAfterSave("/room/" + roomId,
                new ApiResponse<>(ResponseMessage.GAME_STARTED.getMessage(), startPayload));
    }

    /**
     * Builds the client-facing room summary DTO including settings, host indicator, and players.
     *
     * @param roomId the unique ID of the room
     * @return formatted room state for REST and WebSocket responses
     * @throws NullPointerException      if {@code roomId} is null
     * @throws ResourceNotFoundException if the room does not exist or has expired
     */
    public RoomDataResponse getRoomData(String roomId) {
        requireNonNull(roomId, "roomId must not be null");
        roomCoordinator.assertHeldByCurrentThread(roomId);
        Room room = rooms.get(roomId);
        if (room == null || isLobbyExpired(room)) {
            logger.warn("Room data request failed: room not found for id {}", roomId);
            throw new ResourceNotFoundException("Room not found");
        }

        String currentHost = room.getHostName();
        List<PlayerJoinInfo> playerObjects = room.getPlayers().stream()
                .map(playerName -> {
                    String joinedAtStr = requireNonNull(
                            room.getJoinedAt(playerName),
                            "joinedAt must not be null for member " + playerName).toString();
                    return new PlayerJoinInfo(
                            playerName,
                            currentHost != null && currentHost.equals(playerName),
                            joinedAtStr);
                })
                .toList();

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
    }

    /**
     * Returns the live room for {@code roomId} when the calling thread already holds its room lock.
     *
     * @param roomId the unique ID of the room
     * @return the live room, or {@code null} if not found
     * @throws NullPointerException if {@code roomId} is null
     */
    public Room getRoom(String roomId) {
        requireNonNull(roomId, "roomId must not be null");
        roomCoordinator.assertHeldByCurrentThread(roomId);
        return rooms.get(roomId);
    }

    /**
     * Finds an active room by name using case-insensitive matching.
     * Callers must not read mutable {@link Room} fields without acquiring the room lock
     * ({@link RoomCoordinator#acquire(String) RoomCoordinator.acquire(room.getRoomId())}).
     *
     * @param roomName the room name to search for
     * @return the matching room, or {@code null} if not found
     */
    public Room findRoomByName(String roomName) {
        if (roomName == null) {
            return null;
        }
        return rooms.all().stream()
                .filter(r -> r.getRoomName().equalsIgnoreCase(roomName))
                .findFirst()
                .orElse(null);
    }

    /**
     * Returns whether {@code playerName} is currently the host of {@code roomId}.
     *
     * @param roomId     the unique ID of the room
     * @param playerName the player name to check
     * @return {@code true} if the room exists and its current host equals {@code playerName}
     */
    public boolean isRoomHost(String roomId, String playerName) {
        roomCoordinator.assertHeldByCurrentThread(roomId);
        Room room = rooms.get(roomId);
        String hostName = room == null ? null : room.getHostName();
        return hostName != null && hostName.equals(playerName);
    }

    /**
     * Permanently deletes a room, cancels its lobby timer, and publishes a {@link RoomDestroyed}
     * event after save.
     *
     * @param roomId the unique ID of the room to destroy
     */
    @DurableMutation(roomId = "#roomId")
    public void destroyRoom(String roomId) {
        cancelLobbyInactivity(roomId);
        Room room = rooms.remove(roomId);
        if (room != null) {
            logger.info("Room destroyed: {} (ID: {})", room.getRoomName(), roomId);
            PostSaveActions.runAfterSave(() -> eventPublisher.publishEvent(new RoomDestroyed(roomId)));
        }
    }

    /**
     * Schedules a timer to close the unstarted lobby room if no activity occurs before
     * {@code deadlineEpochMs}.
     *
     * @param roomId          the unique ID of the room
     * @param deadlineEpochMs target expiration epoch time in milliseconds
     */
    public void scheduleLobbyInactivity(String roomId, Long deadlineEpochMs) {
        if (roomId == null || deadlineEpochMs == null) {
            return;
        }
        cancelLobbyInactivity(roomId);
        long now = clock.millis();
        long delay = Math.max(0, deadlineEpochMs - now);
        try {
            ScheduledFuture<?> future = taskScheduler.schedule(
                    () -> {
                        try {
                            self().closeLobbyDueToInactivity(roomId, deadlineEpochMs);
                        } catch (Exception e) {
                            logger.error("Error executing scheduled lobby inactivity for room {}", roomId, e);
                        }
                    },
                    Instant.ofEpochMilli(now + delay));
            if (future != null) {
                inactivityFutures.put(roomId, future);
            }
        } catch (Exception e) {
            logger.warn("Scheduler rejected lobby inactivity for room {}", roomId, e);
        }
    }

    /**
     * Cancels any pending lobby inactivity timer for {@code roomId}.
     *
     * @param roomId the unique ID of the room
     */
    public void cancelLobbyInactivity(String roomId) {
        if (roomId == null) {
            return;
        }
        ScheduledFuture<?> future = inactivityFutures.remove(roomId);
        if (future != null) {
            future.cancel(false);
        }
    }

    /**
     * Returns whether {@code room} is an unstarted lobby whose inactivity deadline has passed.
     * Caller must hold the room lock for {@code room.getRoomId()} when {@code room != null}.
     *
     * @param room the room candidate to check
     * @return {@code true} if the room is unstarted and its inactivity deadline has elapsed
     */
    public boolean isLobbyExpired(Room room) {
        if (room == null) {
            return false;
        }
        roomCoordinator.assertHeldByCurrentThread(room.getRoomId());
        return !room.isGameStarted()
                && room.isInactivityExpired(clock.millis());
    }

    /**
     * Checks whether the lobby for {@code roomId} has expired and closes it if so.
     *
     * @param roomId the unique ID of the room
     * @return {@code true} if the room was expired and closed
     */
    public boolean checkAndCloseIfExpired(String roomId) {
        if (roomId == null) {
            return false;
        }
        roomCoordinator.assertHeldByCurrentThread(roomId);
        Room room = rooms.get(roomId);
        if (room == null) {
            return false;
        }
        if (!room.isGameStarted() && room.isInactivityExpired(clock.millis())) {
            self().closeLobbyDueToInactivity(roomId, room.getInactivityDeadlineEpochMs());
            return true;
        }
        return false;
    }

    /**
     * Closes an expired unstarted lobby room, broadcasts {@code ROOM_CLOSED}, and publishes
     * {@link RoomDestroyed} after save.
     *
     * @param roomId          the unique ID of the room
     * @param deadlineEpochMs the deadline timestamp this expiration check was scheduled for
     */
    @DurableMutation(roomId = "#roomId")
    public void closeLobbyDueToInactivity(String roomId, long deadlineEpochMs) {
        Room room = rooms.get(roomId);
        if (room == null || room.isGameStarted()) {
            return;
        }
        Long currentDeadline = room.getInactivityDeadlineEpochMs();
        if (currentDeadline == null) {
            return;
        }
        long now = clock.millis();
        if (currentDeadline > deadlineEpochMs && now < currentDeadline) {
            return;
        }
        if (now < currentDeadline) {
            return;
        }

        cancelLobbyInactivity(roomId);
        room.setTerminal(true);
        rooms.remove(roomId);
        logger.info("Lobby {} closed due to inactivity after deadline {}", roomId, currentDeadline);
        sendAfterSave("/room/" + roomId,
                new ApiResponse<>(ResponseMessage.ROOM_CLOSED.getMessage(),
                        Map.of("roomId", roomId, "reason", "INACTIVITY")));
        PostSaveActions.runAfterSave(() -> eventPublisher.publishEvent(new RoomDestroyed(roomId)));
    }

    private boolean isRoomNameTaken(String roomName) {
        return rooms.all().stream()
                .anyMatch(room -> room.getRoomName().equalsIgnoreCase(roomName));
    }

    private void sendAfterSave(String destination, Object payload) {
        PostSaveActions.runAfterSave(() -> messagingTemplate.convertAndSend(destination, payload));
    }

    private RoomService self() {
        RoomService proxy = selfProvider != null ? selfProvider.getIfAvailable() : null;
        return proxy != null ? proxy : this;
    }
}
