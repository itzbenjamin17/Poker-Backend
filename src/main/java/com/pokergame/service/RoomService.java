package com.pokergame.service;

import static java.util.Objects.requireNonNull;

import com.pokergame.config.RoomTimingProperties;
import com.pokergame.dto.request.CreateRoomRequest;
import com.pokergame.dto.request.JoinRoomRequest;
import com.pokergame.dto.response.RoomDataResponse;
import com.pokergame.exception.BadRequestException;
import com.pokergame.exception.InvalidRoomPasswordException;
import com.pokergame.exception.ResourceNotFoundException;
import com.pokergame.model.Room;
import com.pokergame.persistence.transaction.DurableMutation;
import com.pokergame.persistence.transaction.PostSaveActions;
import com.pokergame.persistence.transaction.RoomId;
import com.pokergame.service.event.RoomDestroyed;
import com.pokergame.service.event.RoomMemberRegistered;
import com.pokergame.service.event.RoomMembershipEnded;
import com.pokergame.util.InputSanitizer;
import com.pokergame.util.RoomCoordinator;
import com.pokergame.util.RoomPasswordPolicy;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Manages poker rooms during creation, joining, lobby membership changes, host tracking,
 * and lobby inactivity cleanup.
 *
 * <p>Room-ID lookups and reads require the calling thread to hold the room lock via
 * {@link RoomCoordinator}. Name lookups synchronize on {@code roomNameLock} to keep room
 * names unique across IDs.
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
    private final RoomTimingProperties timings;
    private final LobbyInactivityScheduler lobbyInactivity;

    // Guards room-name uniqueness across different room IDs: room locks are per ID, so two creates
    // with different IDs but the same name need this lock for the name check and registration.
    private final Object roomNameLock = new Object();

    public RoomService(
            SimpMessagingTemplate messagingTemplate,
            ApplicationEventPublisher eventPublisher,
            PasswordEncoder passwordEncoder,
            RoomRegistry rooms,
            RoomCoordinator roomCoordinator,
            Clock clock,
            RoomTimingProperties timings,
            LobbyInactivityScheduler lobbyInactivity) {
        this.messagingTemplate = messagingTemplate;
        this.eventPublisher = eventPublisher;
        this.passwordEncoder = passwordEncoder;
        this.rooms = rooms;
        this.roomCoordinator = roomCoordinator;
        this.clock = clock;
        this.timings = timings;
        this.lobbyInactivity = lobbyInactivity;
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
    @DurableMutation
    public EntryResult createRoom(@RoomId String roomId, CreateRoomRequest request) {
        requireNonNull(roomId, "roomId must not be null");
        requireNonNull(request, "request must not be null");
        RoomPasswordPolicy.validateLength(request.password());
        if (request.smallBlind() != null && request.bigBlind() != null
                && request.bigBlind() < (long) Room.BIG_BLIND_MULTIPLE * request.smallBlind()) {
            throw new BadRequestException("Big blind must be at least twice the small blind.");
        }
        if (request.bigBlind() != null && request.buyIn() != null
                && request.buyIn() < request.bigBlind()) {
            throw new BadRequestException("Buy-in must be at least the big blind amount.");
        }
        String sanitizedRoomName = InputSanitizer.sanitize(request.roomName());
        String sanitizedPlayerName = InputSanitizer.sanitize(request.playerName());

        String rawPassword = request.password();
        String passwordToStore = (rawPassword != null && !rawPassword.trim().isEmpty())
                ? passwordEncoder.encode(rawPassword)
                : null;
        Room room = new Room(
                roomId,
                sanitizedRoomName,
                sanitizedPlayerName,
                request.maxPlayers(),
                request.smallBlind(),
                request.bigBlind(),
                request.buyIn(),
                passwordToStore,
                clock);

        synchronized (roomNameLock) {
            if (isRoomNameTaken(sanitizedRoomName)) {
                logger.warn("Attempted to create room with duplicate name: {}", sanitizedRoomName);
                throw new BadRequestException(
                        "Room name '" + sanitizedRoomName + "' is already taken. Please choose a different name.");
            }
            rooms.put(room);
        }

        long now = clock.millis();
        String membershipId = registerMember(room, sanitizedPlayerName, now);
        extendLobbyInactivity(room, now);

        broadcastRoomState(roomId, buildRoomData(room, null));

        logger.info("Room created: {} (ID: {}) by host: {}", sanitizedRoomName, roomId, sanitizedPlayerName);
        return new EntryResult(roomId, sanitizedPlayerName, membershipId);
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
     *                                   (compared ignoring case)
     */
    @DurableMutation
    public EntryResult joinRoom(@RoomId String roomId, JoinRoomRequest joinRequest) {
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
            throw new InvalidRoomPasswordException();
        }
        if (room.isFull()) {
            logger.warn("Join attempt failed: room {} is full (player: {})",
                    room.getRoomName(), sanitizedPlayerName);
            throw new BadRequestException("Room is full");
        }
        if (room.hasPlayerNameIgnoringCase(sanitizedPlayerName)) {
            logger.warn("Join attempt failed: player name '{}' already taken in room {}",
                    sanitizedPlayerName, room.getRoomName());
            throw new BadRequestException("Player name already taken");
        }

        long now = clock.millis();
        String membershipId = registerMember(room, sanitizedPlayerName, now);
        extendLobbyInactivity(room, now);

        logger.info("Player {} joined room: {}", sanitizedPlayerName, room.getRoomName());
        broadcastRoomState(roomId, buildRoomData(room, null));

        return new EntryResult(roomId, sanitizedPlayerName, membershipId);
    }

    /**
     * Closes a lobby room because its host left: broadcasts the closed room state and destroys the
     * room. The other members need no separate removal.
     *
     * @param roomId     the unique ID of the room
     * @param playerName the departing host's name
     * @throws ResourceNotFoundException if the room does not exist
     */
    @DurableMutation
    public void leaveRoom(@RoomId String roomId, String playerName) {
        requireNonNull(roomId, "roomId must not be null");
        requireNonNull(playerName, "playerName must not be null");
        Room room = rooms.get(roomId);
        if (room == null) {
            logger.warn("Leave attempt failed: room not found for id {} (player: {})", roomId, playerName);
            throw new ResourceNotFoundException("Room not found");
        }

        logger.info("Host {} leaving room {} during lobby phase, destroying room",
                playerName, room.getRoomName());
        broadcastRoomState(roomId, buildRoomData(room, "HOST_LEFT"));
        destroyRoom(roomId);
    }

    /**
     * Removes one or more players from a room, whether it is a lobby or has a started or finished
     * game, and sets the room host to {@code nextHost}. A room left with no players is closed.
     * Caller must hold the room lock.
     *
     * @param roomId      the unique ID of the room
     * @param playerNames the departing player names; names not in the room are ignored
     * @param nextHost    the next host, who must remain in the room after the departures, or
     *                    {@code null} to keep the current host
     */
    @DurableMutation
    public void removeMembers(@RoomId String roomId, List<String> playerNames, String nextHost) {
        roomCoordinator.assertHeldByCurrentThread(roomId);
        Room room = rooms.get(roomId);
        if (room == null) {
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

        Map<String, String> endedMemberships = new HashMap<>();
        for (String name : present) {
            endedMemberships.put(name, room.getMembershipId(name));
        }
        present.forEach(room::removePlayer);

        PostSaveActions.runAfterSave(() -> endedMemberships.forEach((name, mid) ->
                eventPublisher.publishEvent(new RoomMembershipEnded(roomId, name, mid))));

        if (room.getPlayers().isEmpty()) {
            cancelLobbyInactivity(roomId);
            broadcastRoomState(roomId, buildRoomData(room, "HOST_LEFT"));
            if (!room.isFinished()) {
                destroyRoom(roomId);
            }
            return;
        }

        if (!room.isGameStarted()) {
            extendLobbyInactivity(room, clock.millis());
        }
        if (nextHost != null) {
            room.setHostName(nextHost);
        }

        broadcastRoomState(roomId, buildRoomData(room, null));
    }

    /**
     * Broadcasts after save that the room's game has started, attaching the current room snapshot.
     * Caller must hold the room lock.
     *
     * @param roomId the unique ID of the room whose game just started
     */
    public void broadcastGameStarted(String roomId) {
        requireNonNull(roomId, "roomId must not be null");
        roomCoordinator.assertHeldByCurrentThread(roomId);
        broadcastRoomState(roomId, getRoomData(roomId));
    }

    /**
     * Builds the client-facing room summary DTO including settings, host indicator, and players.
     * Caller must hold the room lock.
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

        return buildRoomData(room, null);
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
     * Returns whether {@code playerName} is currently the host of {@code roomId}.
     * Caller must hold the room lock.
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
    @DurableMutation
    public void destroyRoom(@RoomId String roomId) {
        cancelLobbyInactivity(roomId);
        Room room = rooms.remove(roomId);
        if (room != null) {
            logger.info("Room destroyed: {} (ID: {})", room.getRoomName(), roomId);
            PostSaveActions.runAfterSave(() -> eventPublisher.publishEvent(new RoomDestroyed(roomId)));
        }
    }

    /**
     * Schedules a timer to close the unstarted lobby room if no activity occurs before
     * {@code deadlineEpochMs}, replacing any earlier lobby timer for the room.
     *
     * @param roomId          the unique ID of the room
     * @param deadlineEpochMs target expiration epoch time in milliseconds
     */
    public void scheduleLobbyInactivity(String roomId, long deadlineEpochMs) {
        lobbyInactivity.schedule(roomId, deadlineEpochMs);
    }

    /**
     * Cancels any pending lobby inactivity timer for {@code roomId}.
     *
     * @param roomId the unique ID of the room
     */
    public void cancelLobbyInactivity(String roomId) {
        lobbyInactivity.cancel(roomId);
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
     * Caller must hold the room lock.
     *
     * @param roomId the unique ID of the room
     * @return {@code true} if the room was expired and closed
     */
    public boolean closeLobbyIfExpired(String roomId) {
        if (roomId == null) {
            return false;
        }
        roomCoordinator.assertHeldByCurrentThread(roomId);
        return lobbyInactivity.closeIfExpired(roomId);
    }

    /**
     * Closes an expired unstarted lobby room, broadcasts the closed room state with
     * {@code "INACTIVITY"}, and publishes {@link RoomDestroyed} after save. Does nothing if the
     * room is gone, started, or not yet past its current deadline.
     *
     * @param roomId the unique ID of the room
     */
    @DurableMutation
    public void closeLobbyDueToInactivity(@RoomId String roomId) {
        Room room = rooms.get(roomId);
        if (room == null || room.isGameStarted()) {
            return;
        }
        Long currentDeadline = room.getInactivityDeadlineEpochMs();
        if (currentDeadline == null) {
            return;
        }
        if (clock.millis() < currentDeadline) {
            return;
        }

        logger.info("Lobby {} closed due to inactivity after deadline {}", roomId, currentDeadline);
        broadcastRoomState(roomId, buildRoomData(room, "INACTIVITY"));
        destroyRoom(roomId);
    }

    /*
     * Finds an active room by name using case-insensitive matching.
     * A finished room gives up its name as soon as its game ends: it stays registered by ID until
     * cleanup so its players can still read the review, but a new room may reuse the name meanwhile.
     * Callers must not read mutable Room fields without acquiring the room lock.
     */
    private Room findRoomByName(String roomName) {
        if (roomName == null) {
            return null;
        }
        return rooms.all().stream()
                .filter(r -> !r.isFinished())
                .filter(r -> r.getRoomName().equalsIgnoreCase(roomName))
                .findFirst()
                .orElse(null);
    }

    private boolean isRoomNameTaken(String roomName) {
        return findRoomByName(roomName) != null;
    }

    private String registerMember(Room room, String playerName, long now) {
        String roomId = room.getRoomId();
        String membershipId = room.addPlayer(playerName);
        long firstConnectDeadline = now + timings.disconnectGracePeriodMs();
        room.setAbsenceDeadline(playerName, firstConnectDeadline);
        PostSaveActions.runAfterSave(() -> eventPublisher.publishEvent(
                new RoomMemberRegistered(roomId, playerName, membershipId, firstConnectDeadline)));
        return membershipId;
    }

    private void extendLobbyInactivity(Room room, long now) {
        long deadlineEpochMs = now + timings.lobbyInactivityMs();
        room.setInactivityDeadlineEpochMs(deadlineEpochMs);
        scheduleLobbyInactivity(room.getRoomId(), deadlineEpochMs);
    }

    private RoomDataResponse buildRoomData(Room room, String closedReason) {
        String currentHost = room.getHostName();
        List<RoomDataResponse.RoomPlayer> playerObjects = room.getPlayers().stream()
                .map(playerName -> new RoomDataResponse.RoomPlayer(
                        playerName,
                        currentHost != null && currentHost.equals(playerName)))
                .toList();

        return new RoomDataResponse(
                room.getRoomId(),
                room.getRoomName(),
                room.getMaxPlayers(),
                room.getBuyIn(),
                room.getSmallBlind(),
                room.getBigBlind(),
                playerObjects,
                !room.isGameStarted() && playerObjects.size() >= Room.MIN_PLAYERS,
                room.isGameStarted(),
                room.getInactivityDeadlineEpochMs(),
                clock.millis(),
                closedReason);
    }

    private void broadcastRoomState(String roomId, RoomDataResponse state) {
        PostSaveActions.runAfterSave(() -> messagingTemplate.convertAndSend("/room/" + roomId, state));
    }

    /**
     * Result of a room entry command (create or join), carrying the target room identity,
     * the canonical (sanitized) player name stored in the room, and the assigned membership ID.
     *
     * @param roomId              identity of the created or joined room
     * @param canonicalPlayerName sanitized player name as stored in the room
     * @param membershipId        membership admitted by this command
     */
    public record EntryResult(
            String roomId,
            String canonicalPlayerName,
            String membershipId) {
    }
}
