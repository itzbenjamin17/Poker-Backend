package com.pokergame.config;

import com.pokergame.security.JwtService;
import com.pokergame.security.PlayerPrincipal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import org.springframework.security.access.AccessDeniedException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/** Tests WebSocket auth interceptor behavior. */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
class WebSocketAuthInterceptorTest {

    @Mock
    private JwtService jwtService;

    @InjectMocks
    private WebSocketAuthInterceptor interceptor;

    private static final String VALID_TOKEN = "valid.jwt.token";
    private static final String ROOM_ID = "room-123";
    private static final String PLAYER_NAME = "Alice";
    private static final PlayerPrincipal PRINCIPAL = new PlayerPrincipal(PLAYER_NAME, ROOM_ID);

    /**
     * Protects the contract that the system should allow CONNECT with valid token.
     */
    @Test
    @DisplayName("should allow CONNECT with valid token")
    void givenValidToken_whenConnect_thenPrincipalIsSet() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setNativeHeader("Authorization", "Bearer " + VALID_TOKEN);
        accessor.setLeaveMutable(true); // Ensure it can be modified by interceptor
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        when(jwtService.isTokenValid(VALID_TOKEN)).thenReturn(true);
        when(jwtService.extractPrincipal(VALID_TOKEN)).thenReturn(PRINCIPAL);

        Message<?> result = interceptor.preSend(message, null);
        StompHeaderAccessor resultAccessor = StompHeaderAccessor.wrap(result);

        assertThat(resultAccessor.getUser()).isInstanceOf(PlayerPrincipal.class);
        PlayerPrincipal p = (PlayerPrincipal) resultAccessor.getUser();
        assertThat(p.playerName()).isEqualTo(PLAYER_NAME);
        assertThat(p.roomId()).isEqualTo(ROOM_ID);
    }

    /**
     * Protects the contract that the system should throw MessagingException on CONNECT with missing token.
     */
    @Test
    @DisplayName("should throw MessagingException on CONNECT with missing token")
    void givenMissingToken_whenConnect_thenThrowException() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThatThrownBy(() -> interceptor.preSend(message, null))
                .isInstanceOf(MessagingException.class)
                .hasMessageContaining("Missing WebSocket authorization token");
    }

    /**
     * Protects the contract that the system should throw MessagingException on CONNECT with invalid token.
     */
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

    /**
     * Protects the contract that the system should allow SUBSCRIBE to own room.
     */
    @Test
    @DisplayName("should allow SUBSCRIBE to own room")
    void givenAuthenticated_whenSubscribeToOwnRoom_thenAllow() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination("/room/" + ROOM_ID);
        accessor.setUser(PRINCIPAL);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        Message<?> result = interceptor.preSend(message, null);
        assertThat(result).isNotNull();
    }

    /**
     * Protects the contract that the system should block SUBSCRIBE to another room.
     */
    @Test
    @DisplayName("should block SUBSCRIBE to another room")
    void givenAuthenticated_whenSubscribeToOtherRoom_thenThrowException() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination("/room/other-room");
        accessor.setUser(PRINCIPAL);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThatThrownBy(() -> interceptor.preSend(message, null))
                .isInstanceOf(MessagingException.class)
                .hasMessageContaining("Forbidden");
    }

    /**
     * Protects the contract that the system should block SUBSCRIBE to another game.
     */
    @Test
    @DisplayName("should block SUBSCRIBE to another game")
    void givenAuthenticated_whenSubscribeToOtherGame_thenThrowException() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination("/game/other-game");
        accessor.setUser(PRINCIPAL);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThatThrownBy(() -> interceptor.preSend(message, null))
                .isInstanceOf(MessagingException.class)
                .hasMessageContaining("Forbidden");
    }

    /**
     * Protects the contract that the system should allow SUBSCRIBE to user queue.
     */
    @Test
    @DisplayName("should allow SUBSCRIBE to user queue")
    void givenAuthenticated_whenSubscribeToUserQueue_thenAllow() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination("/user/queue/private");
        accessor.setUser(PRINCIPAL);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        Message<?> result = interceptor.preSend(message, null);
        assertThat(result).isNotNull();
    }

    /**
     * Protects the contract that SEND frames to broker topics without /app/ prefix are rejected.
     */
    @Test
    @DisplayName("should reject SEND frame without /app/ prefix targeting room broker")
    void givenNoAppPrefix_whenSendToRoomBroker_thenThrowAccessDeniedException() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setDestination("/room/123/updates");
        accessor.setUser(PRINCIPAL);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThatThrownBy(() -> interceptor.preSend(message, null))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("SEND destination must start with /app/");
    }

    /**
     * Protects the contract that SEND frames targeting queue broker are rejected.
     */
    @Test
    @DisplayName("should reject SEND frame without /app/ prefix targeting queue broker")
    void givenNoAppPrefix_whenSendToQueueBroker_thenThrowAccessDeniedException() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setDestination("/queue/private");
        accessor.setUser(PRINCIPAL);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThatThrownBy(() -> interceptor.preSend(message, null))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("SEND destination must start with /app/");
    }

    /**
     * Protects the contract that SEND frames targeting game broker directly are rejected.
     */
    @Test
    @DisplayName("should reject SEND frame without /app/ prefix targeting game broker")
    void givenNoAppPrefix_whenSendToGameBroker_thenThrowAccessDeniedException() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setDestination("/game/" + ROOM_ID);
        accessor.setUser(PRINCIPAL);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThatThrownBy(() -> interceptor.preSend(message, null))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("SEND destination must start with /app/");
    }

    /**
     * Protects the contract that SEND frames with null destination are rejected.
     */
    @Test
    @DisplayName("should reject SEND frame with null destination")
    void givenNullDestination_whenSend_thenThrowAccessDeniedException() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setDestination(null);
        accessor.setUser(PRINCIPAL);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThatThrownBy(() -> interceptor.preSend(message, null))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("SEND destination must start with /app/");
    }

    /**
     * Protects the contract that SEND frames to /app/game/{roomId}/action with mismatched principal roomId are rejected.
     */
    @Test
    @DisplayName("should reject SEND frame to /app/game/{roomId}/action with mismatched roomId")
    void givenMismatchedRoom_whenSendToAppGameAction_thenThrowAccessDeniedException() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setDestination("/app/game/other-room/action");
        accessor.setUser(PRINCIPAL);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThatThrownBy(() -> interceptor.preSend(message, null))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("User not authorized for this room destination");
    }

    /**
     * Protects the contract that SEND frames to /app/{roomId}/action with mismatched principal roomId are rejected.
     */
    @Test
    @DisplayName("should reject SEND frame to /app/{roomId}/action with mismatched roomId")
    void givenMismatchedRoom_whenSendToAppRoomAction_thenThrowAccessDeniedException() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setDestination("/app/other-room/action");
        accessor.setUser(PRINCIPAL);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThatThrownBy(() -> interceptor.preSend(message, null))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("User not authorized for this room destination");
    }

    /**
     * Protects the contract that SEND frames to /app/room/{roomId}/action with mismatched principal roomId are rejected.
     */
    @Test
    @DisplayName("should reject SEND frame to /app/room/{roomId}/action with mismatched roomId")
    void givenMismatchedRoom_whenSendToAppRoomPrefixAction_thenThrowAccessDeniedException() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setDestination("/app/room/other-room/action");
        accessor.setUser(PRINCIPAL);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThatThrownBy(() -> interceptor.preSend(message, null))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("User not authorized for this room destination");
    }

    /**
     * Protects the contract that unauthenticated SEND frames to room destinations are rejected.
     */
    @Test
    @DisplayName("should reject SEND frame to room destination when unauthenticated")
    void givenUnauthenticated_whenSendToRoomDestination_thenThrowAccessDeniedException() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setDestination("/app/game/" + ROOM_ID + "/action");
        accessor.setUser(null);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThatThrownBy(() -> interceptor.preSend(message, null))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("User not authorized for this room destination");
    }

    /**
     * Protects the contract that SEND frames to /app/game/{roomId}/action with matching roomId succeed.
     */
    @Test
    @DisplayName("should allow SEND frame to /app/game/{roomId}/action with matching roomId")
    void givenMatchingRoom_whenSendToAppGameAction_thenAllow() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setDestination("/app/game/" + ROOM_ID + "/action");
        accessor.setUser(PRINCIPAL);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        Message<?> result = interceptor.preSend(message, null);
        assertThat(result).isNotNull();
    }

    /**
     * Protects the contract that SEND frames to /app/{roomId}/action with matching roomId succeed.
     */
    @Test
    @DisplayName("should allow SEND frame to /app/{roomId}/action with matching roomId")
    void givenMatchingRoom_whenSendToAppRoomAction_thenAllow() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setDestination("/app/" + ROOM_ID + "/action");
        accessor.setUser(PRINCIPAL);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        Message<?> result = interceptor.preSend(message, null);
        assertThat(result).isNotNull();
    }

    /**
     * Protects the contract that SEND frames with principal in session attributes succeed when roomId matches.
     */
    @Test
    @DisplayName("should allow SEND frame when principal is stored in session attributes")
    void givenSessionAttributesPrincipal_whenSendWithMatchingRoom_thenAllow() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setDestination("/app/game/" + ROOM_ID + "/action");
        accessor.setUser(null);
        accessor.setSessionAttributes(Map.of("playerPrincipal", PRINCIPAL));
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        Message<?> result = interceptor.preSend(message, null);
        assertThat(result).isNotNull();
    }

    /**
     * Protects the contract that SEND frames to non-room /app/ destinations succeed.
     */
    @Test
    @DisplayName("should allow SEND frame to valid non-room /app/ destination")
    void givenNonRoomDestination_whenSendToAppStatus_thenAllow() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setDestination("/app/status");
        accessor.setUser(PRINCIPAL);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        Message<?> result = interceptor.preSend(message, null);
        assertThat(result).isNotNull();
    }

    /**
     * Protects the contract that SEND frames with blank /app/ destination are rejected.
     */
    @Test
    @DisplayName("should reject SEND frame with blank /app/ destination")
    void givenBlankAppDestination_whenSend_thenThrowAccessDeniedException() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setDestination("/app/");
        accessor.setUser(PRINCIPAL);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThatThrownBy(() -> interceptor.preSend(message, null))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("SEND destination must start with /app/");
    }

    /**
     * Protects the contract that unauthenticated SEND frames to non-room destinations are rejected.
     */
    @Test
    @DisplayName("should reject unauthenticated SEND frame to non-room destination")
    void givenUnauthenticated_whenSendToNonRoomDestination_thenThrowAccessDeniedException() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setDestination("/app/status");
        accessor.setUser(null);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThatThrownBy(() -> interceptor.preSend(message, null))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("User not authorized for this room destination");
    }

    /**
     * Protects the contract that SEND frames to /app/{roomId}/ready with matching roomId succeed.
     */
    @Test
    @DisplayName("should allow SEND frame to /app/{roomId}/ready with matching roomId")
    void givenMatchingRoom_whenSendToReadyDestination_thenAllow() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setDestination("/app/" + ROOM_ID + "/ready");
        accessor.setUser(PRINCIPAL);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        Message<?> result = interceptor.preSend(message, null);
        assertThat(result).isNotNull();
    }

    /**
     * Protects the contract that SEND frames to /app/{roomId}/ready with mismatched roomId are rejected.
     */
    @Test
    @DisplayName("should reject SEND frame to /app/{roomId}/ready with mismatched roomId")
    void givenMismatchedRoom_whenSendToReadyDestination_thenThrowAccessDeniedException() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setDestination("/app/other-room/ready");
        accessor.setUser(PRINCIPAL);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThatThrownBy(() -> interceptor.preSend(message, null))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("User not authorized for this room destination");
    }

    /**
     * Protects the contract that SEND frames to /app/{roomId}/unready with matching roomId succeed.
     */
    @Test
    @DisplayName("should allow SEND frame to /app/{roomId}/unready with matching roomId")
    void givenMatchingRoom_whenSendToUnreadyDestination_thenAllow() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setDestination("/app/" + ROOM_ID + "/unready");
        accessor.setUser(PRINCIPAL);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        Message<?> result = interceptor.preSend(message, null);
        assertThat(result).isNotNull();
    }

    /**
     * Protects the contract that SEND frames to /app/{roomId}/unready with mismatched roomId are rejected.
     */
    @Test
    @DisplayName("should reject SEND frame to /app/{roomId}/unready with mismatched roomId")
    void givenMismatchedRoom_whenSendToUnreadyDestination_thenThrowAccessDeniedException() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setDestination("/app/other-room/unready");
        accessor.setUser(PRINCIPAL);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThatThrownBy(() -> interceptor.preSend(message, null))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("User not authorized for this room destination");
    }

    /**
     * Protects the contract that SEND frames to /app/game/{roomId}/ready with matching roomId succeed.
     */
    @Test
    @DisplayName("should allow SEND frame to /app/game/{roomId}/ready with matching roomId")
    void givenMatchingRoom_whenSendToAppGameReadyDestination_thenAllow() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setDestination("/app/game/" + ROOM_ID + "/ready");
        accessor.setUser(PRINCIPAL);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        Message<?> result = interceptor.preSend(message, null);
        assertThat(result).isNotNull();
    }

    /**
     * Protects the contract that SEND frames to /app/game/{roomId}/ready with mismatched roomId are rejected.
     */
    @Test
    @DisplayName("should reject SEND frame to /app/game/{roomId}/ready with mismatched roomId")
    void givenMismatchedRoom_whenSendToAppGameReadyDestination_thenThrowAccessDeniedException() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setDestination("/app/game/other-room/ready");
        accessor.setUser(PRINCIPAL);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThatThrownBy(() -> interceptor.preSend(message, null))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("User not authorized for this room destination");
    }

    /**
     * Protects the contract that URL-encoded room IDs matching the principal roomId succeed.
     */
    @Test
    @DisplayName("should allow SEND frame with URL-encoded roomId matching principal")
    void givenUrlEncodedMatchingRoom_whenSend_thenAllow() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setDestination("/app/game/room%2D123/action");
        accessor.setUser(PRINCIPAL);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        Message<?> result = interceptor.preSend(message, null);
        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("should reject CONNECT, SUBSCRIBE, and SEND when token membershipId is stale")
    void givenStaleMembershipId_whenConnectSubscribeOrSend_thenThrowAccessDeniedException() {
        com.pokergame.service.RoomService roomService = org.mockito.Mockito.mock(com.pokergame.service.RoomService.class);
        com.pokergame.model.Room room = new com.pokergame.model.Room(ROOM_ID, "Room", PLAYER_NAME, 4, 10, 20, 500, null);
        String activeMemId = room.addPlayer(PLAYER_NAME);
        when(roomService.getRoom(ROOM_ID)).thenReturn(room);

        WebSocketAuthInterceptor interceptorWithRoom = new WebSocketAuthInterceptor(jwtService, roomService);
        PlayerPrincipal stalePrincipal = new PlayerPrincipal(PLAYER_NAME, ROOM_ID, "stale-mem-id");
        PlayerPrincipal activePrincipal = new PlayerPrincipal(PLAYER_NAME, ROOM_ID, activeMemId);

        // 1. CONNECT with stale membershipId
        StompHeaderAccessor connectAccessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        connectAccessor.setNativeHeader("Authorization", "Bearer " + VALID_TOKEN);
        connectAccessor.setLeaveMutable(true);
        Message<byte[]> connectMsg = MessageBuilder.createMessage(new byte[0], connectAccessor.getMessageHeaders());
        when(jwtService.isTokenValid(VALID_TOKEN)).thenReturn(true);
        when(jwtService.extractPrincipal(VALID_TOKEN)).thenReturn(stalePrincipal);

        assertThatThrownBy(() -> interceptorWithRoom.preSend(connectMsg, null))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Token membership is stale or invalid.");

        // 2. SUBSCRIBE with stale membershipId
        StompHeaderAccessor subAccessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        subAccessor.setDestination("/room/" + ROOM_ID);
        subAccessor.setUser(stalePrincipal);
        Message<byte[]> subMsg = MessageBuilder.createMessage(new byte[0], subAccessor.getMessageHeaders());

        assertThatThrownBy(() -> interceptorWithRoom.preSend(subMsg, null))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Token membership is stale or invalid.");

        // 3. SEND with stale membershipId
        StompHeaderAccessor sendAccessor = StompHeaderAccessor.create(StompCommand.SEND);
        sendAccessor.setDestination("/app/" + ROOM_ID + "/action");
        sendAccessor.setUser(stalePrincipal);
        Message<byte[]> sendMsg = MessageBuilder.createMessage(new byte[0], sendAccessor.getMessageHeaders());

        assertThatThrownBy(() -> interceptorWithRoom.preSend(sendMsg, null))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Token membership is stale or invalid.");

        // 4. SEND with active membershipId succeeds
        StompHeaderAccessor validSendAccessor = StompHeaderAccessor.create(StompCommand.SEND);
        validSendAccessor.setDestination("/app/" + ROOM_ID + "/action");
        validSendAccessor.setUser(activePrincipal);
        Message<byte[]> validSendMsg = MessageBuilder.createMessage(new byte[0], validSendAccessor.getMessageHeaders());

        assertThat(interceptorWithRoom.preSend(validSendMsg, null)).isNotNull();
    }

    private void unusedHelper() {
        accessor.setUser(PRINCIPAL);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        Message<?> result = interceptor.preSend(message, null);
        assertThat(result).isNotNull();
    }
}
