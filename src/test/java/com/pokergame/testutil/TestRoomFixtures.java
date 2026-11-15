package com.pokergame.testutil;

import com.pokergame.dto.request.CreateRoomRequest;
import com.pokergame.dto.request.JoinRoomRequest;
import com.pokergame.model.Room;
import com.pokergame.security.PlayerPrincipal;
import com.pokergame.util.RoomCoordinator;
import com.pokergame.service.RoomService;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

/**
 * Shared room/principal test fixture helper for integration suites without shared inheritance.
 */
public final class TestRoomFixtures {
    private static final int DEFAULT_BUY_IN = 1000;

    private final RoomService roomService;
    private final RoomCoordinator coordinator;
    private final Set<String> trackedRoomIds;

    /**
     * Binds room fixture creation and principal lookup helpers to a test suite's services and cleanup registry.
     *
     * @param roomService    room service under test
     * @param coordinator    authoritative room coordinator for lease-protected setup reads/writes
     * @param trackedRoomIds mutable set collecting created room IDs for {@code @AfterEach} cleanup
     */
    public TestRoomFixtures(RoomService roomService, RoomCoordinator coordinator, Set<String> trackedRoomIds) {
        this.roomService = Objects.requireNonNull(roomService, "roomService must not be null");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator must not be null");
        this.trackedRoomIds = Objects.requireNonNull(trackedRoomIds, "trackedRoomIds must not be null");
    }

    /**
     * Creates a room with the default 1,000 buy-in, joins all {@code guests}, marks every participant
     * connected under the room lease, and registers the room ID for cleanup.
     *
     * @param roomName display name for the new room
     * @param hostName host player name
     * @param guests   optional guest player names to join
     * @return the newly generated room ID
     */
    public String createAndConnectRoom(String roomName, String hostName, String... guests) {
        return createAndConnectRoom(roomName, hostName, DEFAULT_BUY_IN, guests);
    }

    /**
     * Creates a room with the specified buy-in, joins all {@code guests}, marks every participant
     * connected under the room lease, and registers the room ID for cleanup.
     *
     * @param roomName display name for the new room
     * @param hostName host player name
     * @param buyIn    starting chip stack for each participant
     * @param guests   optional guest player names to join
     * @return the newly generated room ID
     */
    public String createAndConnectRoom(String roomName, String hostName, int buyIn, String... guests) {
        String roomId = UUID.randomUUID().toString();
        trackedRoomIds.add(roomId);
        roomService.createRoom(roomId, new CreateRoomRequest(roomName, hostName, 6, 5, 10, buyIn, null));
        for (String guest : guests) {
            roomService.joinRoom(roomId, new JoinRoomRequest(roomName, guest, null));
        }
        TestRoomLocks.withRoomLock(coordinator, roomId, () -> {
            Room room = roomService.getRoom(roomId);
            room.setPlayerConnected(hostName, true);
            for (String guest : guests) {
                room.setPlayerConnected(guest, true);
            }
            return null;
        });
        return roomId;
    }

    /**
     * Builds an authenticated {@link PlayerPrincipal} using {@code playerName}'s current membership ID
     * read under the room lease.
     *
     * @param roomId     room identifier
     * @param playerName player name in the room
     * @return authenticated principal bound to the player's current membership
     */
    public PlayerPrincipal principalFor(String roomId, String playerName) {
        return TestRoomLocks.withRoomLock(coordinator, roomId, () -> {
            Room room = roomService.getRoom(roomId);
            return new PlayerPrincipal(playerName, roomId, room.getMembershipId(playerName));
        });
    }

    /**
     * Builds a synthetic STOMP {@code SUBSCRIBE} frame targeting the room topic for {@code principal}.
     *
     * @param principal authenticated player principal
     * @return STOMP subscribe message for authorization interceptor checks
     */
    public static Message<byte[]> stompSubscription(PlayerPrincipal principal) {
        StompHeaderAccessor headers = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        headers.setDestination("/room/" + principal.roomId());
        headers.setUser(principal);
        return MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
    }
}
