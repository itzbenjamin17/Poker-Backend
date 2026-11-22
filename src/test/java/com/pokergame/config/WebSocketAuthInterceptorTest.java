package com.pokergame.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.pokergame.model.Room;
import com.pokergame.security.JwtService;
import com.pokergame.security.PlayerPrincipal;
import com.pokergame.service.RoomService;
import com.pokergame.testutil.TestRoomLocks;
import com.pokergame.util.MdcKeys;
import com.pokergame.util.RoomCoordinator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;

/** Unit tests for {@link WebSocketAuthInterceptor}. */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
class WebSocketAuthInterceptorTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);
    private static final String VALID_TOKEN = "valid.jwt.token";
    private static final String ROOM_ID = "room-123";
    private static final String PLAYER_NAME = "Alice";

    @Mock
    private JwtService jwtService;

    @Mock
    private RoomService roomService;

    private RoomCoordinator roomCoordinator;
    private WebSocketAuthInterceptor interceptor;
    private Room liveRoom;
    private String activeMembershipId;
    private PlayerPrincipal principal;

    @BeforeEach
    void setUp() {
        roomCoordinator = new RoomCoordinator();
        interceptor = new WebSocketAuthInterceptor(jwtService, roomCoordinator, roomService);
        liveRoom = new Room(ROOM_ID, "Room", PLAYER_NAME, 4, 10, 20, 500, null, FIXED_CLOCK);
        activeMembershipId = liveRoom.addPlayer(PLAYER_NAME);
        principal = new PlayerPrincipal(PLAYER_NAME, ROOM_ID, activeMembershipId);
        lenient().when(roomService.getRoom(ROOM_ID)).thenReturn(liveRoom);
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        assertThat(roomCoordinator.activeLockCount()).isZero();
    }

    @Test
    @DisplayName("should allow CONNECT with valid token, populate MDC, and clear MDC on afterSendCompletion")
    void givenValidToken_whenConnect_thenPrincipalAndMdcAreSetAndClearedAfterCompletion() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setSessionId("sess-1");
        accessor.setNativeHeader("Authorization", "Bearer " + VALID_TOKEN);
        accessor.setLeaveMutable(true);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        when(jwtService.isTokenValid(VALID_TOKEN)).thenReturn(true);
        when(jwtService.extractPrincipal(VALID_TOKEN)).thenReturn(principal);

        Message<?> result = interceptor.preSend(message, null);
        StompHeaderAccessor resultAccessor = StompHeaderAccessor.wrap(result);

        assertThat(resultAccessor.getUser()).isEqualTo(principal);
        assertThat(MDC.get(MdcKeys.SESSION_ID)).isEqualTo("sess-1");
        assertThat(MDC.get(MdcKeys.ROOM_ID)).isEqualTo(ROOM_ID);
        assertThat(MDC.get(MdcKeys.PLAYER_NAME)).isEqualTo(PLAYER_NAME);

        interceptor.afterSendCompletion(message, null, true, null);
        assertThat(MDC.get(MdcKeys.SESSION_ID)).isNull();
        assertThat(MDC.get(MdcKeys.ROOM_ID)).isNull();
        assertThat(MDC.get(MdcKeys.PLAYER_NAME)).isNull();
    }

    @Test
    @DisplayName("should throw MessagingException on CONNECT with missing token")
    void givenMissingToken_whenConnect_thenThrowException() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThatThrownBy(() -> interceptor.preSend(message, null))
                .isInstanceOf(MessagingException.class)
                .hasMessageContaining("Missing WebSocket authorization token");
    }

    @Test
    @DisplayName("should throw MessagingException on CONNECT with non-Bearer Authorization header")
    void givenNonBearerHeader_whenConnect_thenThrowException() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setNativeHeader("Authorization", "Basic dXNlcjpwYXNz");
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThatThrownBy(() -> interceptor.preSend(message, null))
                .isInstanceOf(MessagingException.class)
                .hasMessageContaining("Missing WebSocket authorization token");
    }

    @Test
    @DisplayName("should throw MessagingException on CONNECT with invalid token")
    void givenInvalidToken_whenConnect_thenThrowException() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setNativeHeader("Authorization", "Bearer invalid");
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        when(jwtService.isTokenValid("invalid")).thenReturn(false);

        assertThatThrownBy(() -> interceptor.preSend(message, null))
                .isInstanceOf(MessagingException.class)
                .hasMessageContaining("Invalid WebSocket authorization token");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/room/" + ROOM_ID, "/game/" + ROOM_ID, "/user/queue/private"})
    @DisplayName("should allow SUBSCRIBE to own room, own game, and user private queue")
    void givenAuthenticated_whenSubscribeToAuthorizedDestinations_thenAllow(String destination) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination(destination);
        accessor.setUser(principal);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        Message<?> result = interceptor.preSend(message, null);
        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("should reject SUBSCRIBE when user principal is null")
    void givenUnauthenticated_whenSubscribe_thenThrowMessagingException() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination("/room/" + ROOM_ID);
        accessor.setUser(null);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThatThrownBy(() -> interceptor.preSend(message, null))
                .isInstanceOf(MessagingException.class)
                .hasMessageContaining("Unauthorized: No principal found for subscription");
    }

    @Test
    @DisplayName("should block SUBSCRIBE to another room")
    void givenAuthenticated_whenSubscribeToOtherRoom_thenThrowException() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination("/room/other-room");
        accessor.setUser(principal);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThatThrownBy(() -> interceptor.preSend(message, null))
                .isInstanceOf(MessagingException.class)
                .hasMessageContaining("Forbidden");
    }

    @Test
    @DisplayName("should block SUBSCRIBE to another game")
    void givenAuthenticated_whenSubscribeToOtherGame_thenThrowException() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination("/game/other-game");
        accessor.setUser(principal);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThatThrownBy(() -> interceptor.preSend(message, null))
                .isInstanceOf(MessagingException.class)
                .hasMessageContaining("Forbidden");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/room/123/updates", "/queue/private", "/game/" + ROOM_ID, "/app/", "/app///"})
    @DisplayName("should reject SEND frames without /app/ prefix or with blank /app/ destination")
    void givenInvalidPrefixOrBlankAppDestination_whenSend_thenThrowAccessDeniedException(String destination) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setDestination(destination);
        accessor.setUser(principal);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThatThrownBy(() -> interceptor.preSend(message, null))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("SEND destination must start with /app/");
    }

    @Test
    @DisplayName("should reject SEND frame with null destination")
    void givenNullDestination_whenSend_thenThrowAccessDeniedException() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setDestination(null);
        accessor.setUser(principal);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThatThrownBy(() -> interceptor.preSend(message, null))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("SEND destination must start with /app/");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/app/other-room/action",
            "/app/other-room/ready",
            "/app/other-room/unready",
            "/app/%ZZ/action"
    })
    @DisplayName("should reject SEND frame with mismatched or malformed URL-encoded roomId")
    void givenMismatchedOrMalformedEncodedRoom_whenSend_thenThrowAccessDeniedException(String destination) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setDestination(destination);
        accessor.setUser(principal);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThatThrownBy(() -> interceptor.preSend(message, null))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("User not authorized for this room destination");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/app/" + ROOM_ID + "/action", "/app/status"})
    @DisplayName("should reject unauthenticated SEND frame to room or non-room destination")
    void givenUnauthenticated_whenSend_thenThrowAccessDeniedException(String destination) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setDestination(destination);
        accessor.setUser(null);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThatThrownBy(() -> interceptor.preSend(message, null))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("User not authorized for this room destination");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/app/" + ROOM_ID + "/action",
            "/app/" + ROOM_ID + "/ready",
            "/app/" + ROOM_ID + "/unready",
            "/app/room%2D123/action",
            "/app/status"
    })
    @DisplayName("should allow SEND frame to matching room, URL-encoded room, or non-room /app/ destination")
    void givenAuthorizedDestination_whenSend_thenAllow(String destination) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setDestination(destination);
        accessor.setUser(principal);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        Message<?> result = interceptor.preSend(message, null);
        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("should allow SEND frame when principal is stored in session attributes")
    void givenSessionAttributesPrincipal_whenSendWithMatchingRoom_thenAllow() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setDestination("/app/" + ROOM_ID + "/action");
        accessor.setUser(null);
        accessor.setSessionAttributes(Map.of("playerPrincipal", principal));
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        Message<?> result = interceptor.preSend(message, null);
        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("should reject CONNECT when token membershipId is stale")
    void givenStaleMembershipId_whenConnect_thenThrowAccessDeniedException() {
        PlayerPrincipal stalePrincipal = new PlayerPrincipal(PLAYER_NAME, ROOM_ID, "stale-mem-id");
        StompHeaderAccessor connectAccessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        connectAccessor.setNativeHeader("Authorization", "Bearer " + VALID_TOKEN);
        connectAccessor.setLeaveMutable(true);
        Message<byte[]> connectMsg = MessageBuilder.createMessage(new byte[0], connectAccessor.getMessageHeaders());
        when(jwtService.isTokenValid(VALID_TOKEN)).thenReturn(true);
        when(jwtService.extractPrincipal(VALID_TOKEN)).thenReturn(stalePrincipal);

        assertThatThrownBy(() -> interceptor.preSend(connectMsg, null))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Token membership is stale or invalid.");
    }

    @Test
    @DisplayName("should reject SUBSCRIBE when token membershipId is stale")
    void givenStaleMembershipId_whenSubscribe_thenThrowAccessDeniedException() {
        PlayerPrincipal stalePrincipal = new PlayerPrincipal(PLAYER_NAME, ROOM_ID, "stale-mem-id");
        StompHeaderAccessor subAccessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        subAccessor.setDestination("/room/" + ROOM_ID);
        subAccessor.setUser(stalePrincipal);
        Message<byte[]> subMsg = MessageBuilder.createMessage(new byte[0], subAccessor.getMessageHeaders());

        assertThatThrownBy(() -> interceptor.preSend(subMsg, null))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Token membership is stale or invalid.");
    }

    @Test
    @DisplayName("should reject SEND when token membershipId is stale or player was removed from room")
    void givenStaleMembershipOrRemovedPlayer_whenSend_thenThrowAccessDeniedException() {
        PlayerPrincipal stalePrincipal = new PlayerPrincipal(PLAYER_NAME, ROOM_ID, "stale-mem-id");
        StompHeaderAccessor staleSend = StompHeaderAccessor.create(StompCommand.SEND);
        staleSend.setDestination("/app/" + ROOM_ID + "/action");
        staleSend.setUser(stalePrincipal);
        Message<byte[]> staleMsg = MessageBuilder.createMessage(new byte[0], staleSend.getMessageHeaders());

        assertThatThrownBy(() -> interceptor.preSend(staleMsg, null))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Token membership is stale or invalid.");

        TestRoomLocks.runWithRoomLock(roomCoordinator, ROOM_ID, () -> liveRoom.removePlayer(PLAYER_NAME));
        StompHeaderAccessor removedSend = StompHeaderAccessor.create(StompCommand.SEND);
        removedSend.setDestination("/app/" + ROOM_ID + "/action");
        removedSend.setUser(principal);
        Message<byte[]> removedMsg = MessageBuilder.createMessage(new byte[0], removedSend.getMessageHeaders());

        assertThatThrownBy(() -> interceptor.preSend(removedMsg, null))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Token membership is stale or invalid.");
    }

    @Test
    @DisplayName("should reject SUBSCRIBE to user private queue when identity lacks membership")
    void signedIdentityWithoutMembershipCannotSubscribeToPrivateQueue() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setUser(new PlayerPrincipal("Alice", "room"));
        accessor.setDestination("/user/queue/private");
        accessor.setLeaveMutable(true);

        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        assertThatThrownBy(() -> interceptor.preSend(message, null))
                .isInstanceOf(AccessDeniedException.class);
    }
}
