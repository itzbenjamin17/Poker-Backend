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
}
