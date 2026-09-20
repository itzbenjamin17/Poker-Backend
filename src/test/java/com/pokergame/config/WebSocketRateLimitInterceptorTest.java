package com.pokergame.config;

import com.pokergame.security.PlayerPrincipal;
import com.pokergame.security.RateLimitService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link WebSocketRateLimitInterceptor}.
 */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
@DisplayName("WebSocketRateLimitInterceptor")
class WebSocketRateLimitInterceptorTest {

    @Mock
    private RateLimitService rateLimitService;

    @InjectMocks
    private WebSocketRateLimitInterceptor interceptor;

    private static final String USERNAME = "Alice";
    private static final PlayerPrincipal PRINCIPAL = new PlayerPrincipal(USERNAME, "room-123");

    @Test
    @DisplayName("should consume rate limit token on STOMP SEND command and allow message when under limit")
    void givenSendCommandUnderLimit_whenPreSend_thenConsumesTokenAndAllowsMessage() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setUser(PRINCIPAL);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        when(rateLimitService.tryConsumeWs(PRINCIPAL.getName())).thenReturn(true);

        Message<?> result = interceptor.preSend(message, null);

        verify(rateLimitService).tryConsumeWs(PRINCIPAL.getName());
        assertThat(result).isSameAs(message);
    }

    @Test
    @DisplayName("should throw MessageDeliveryException on STOMP SEND command when rate limit is exceeded")
    void givenSendCommandOverLimit_whenPreSend_thenThrowsMessageDeliveryException() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setUser(PRINCIPAL);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        when(rateLimitService.tryConsumeWs(PRINCIPAL.getName())).thenReturn(false);

        assertThatThrownBy(() -> interceptor.preSend(message, null))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("Message rate limit exceeded");

        verify(rateLimitService).tryConsumeWs(PRINCIPAL.getName());
    }

    @Test
    @DisplayName("should not consume rate limit tokens for non-SEND commands (e.g. CONNECT)")
    void givenConnectCommand_whenPreSend_thenDoesNotConsumeToken() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setUser(PRINCIPAL);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        Message<?> result = interceptor.preSend(message, null);

        verify(rateLimitService, never()).tryConsumeWs(anyString());
        assertThat(result).isSameAs(message);
    }

    @Test
    @DisplayName("should not consume rate limit tokens for non-SEND commands (e.g. SUBSCRIBE)")
    void givenSubscribeCommand_whenPreSend_thenDoesNotConsumeToken() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setUser(PRINCIPAL);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        Message<?> result = interceptor.preSend(message, null);

        verify(rateLimitService, never()).tryConsumeWs(anyString());
        assertThat(result).isSameAs(message);
    }

    @Test
    @DisplayName("should allow SEND command without consuming tokens if user is null")
    void givenSendCommandWithoutUser_whenPreSend_thenAllowsWithoutConsuming() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        Message<?> result = interceptor.preSend(message, null);

        verify(rateLimitService, never()).tryConsumeWs(anyString());
        assertThat(result).isSameAs(message);
    }
}
