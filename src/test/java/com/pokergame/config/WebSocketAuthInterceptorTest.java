package com.pokergame.config;

import com.pokergame.security.JwtService;
import com.pokergame.security.PlayerPrincipal;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

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

    @BeforeEach
    void setUp() {
    }

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
}
