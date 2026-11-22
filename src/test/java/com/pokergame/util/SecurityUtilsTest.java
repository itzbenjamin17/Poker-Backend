package com.pokergame.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pokergame.exception.UnauthorisedActionException;
import com.pokergame.security.PlayerPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

/** Unit tests for {@link SecurityUtils}. */
@Tag("unit")
class SecurityUtilsTest {

    private final PlayerPrincipal playerPrincipal = new PlayerPrincipal("Alice", "room-123", "mem-1");

    @Test
    @DisplayName("getPlayer - returns PlayerPrincipal directly")
    void getPlayer_DirectPrincipal() {
        PlayerPrincipal result = SecurityUtils.getPlayer(playerPrincipal);
        assertThat(result).isSameAs(playerPrincipal);
    }

    @Test
    @DisplayName("getPlayer - extracts PlayerPrincipal from Authentication wrapper")
    void getPlayer_AuthenticationWrapper() {
        Authentication auth = new UsernamePasswordAuthenticationToken(playerPrincipal, null);
        PlayerPrincipal result = SecurityUtils.getPlayer(auth);
        assertThat(result).isSameAs(playerPrincipal);
    }

    @Test
    @DisplayName("getPlayer - throws UnauthorisedActionException for non-player principal")
    void getPlayer_InvalidPrincipal() {
        Principal nonPlayerPrincipal = () -> "unknown";
        assertThatThrownBy(() -> SecurityUtils.getPlayer(nonPlayerPrincipal))
                .isInstanceOf(UnauthorisedActionException.class)
                .hasMessage("Invalid authentication principal");
    }

    @Test
    @DisplayName("getPlayer - throws UnauthorisedActionException for Authentication wrapping non-PlayerPrincipal")
    void getPlayer_AuthenticationWithNonPlayerPrincipal() {
        Authentication anonymous = new UsernamePasswordAuthenticationToken("anonymousUser", null);
        assertThatThrownBy(() -> SecurityUtils.getPlayer(anonymous))
                .isInstanceOf(UnauthorisedActionException.class)
                .hasMessage("Invalid authentication principal");
    }

    @Test
    @DisplayName("getPlayer - throws UnauthorisedActionException for null principal")
    void getPlayer_NullPrincipal() {
        assertThatThrownBy(() -> SecurityUtils.getPlayer(null))
                .isInstanceOf(UnauthorisedActionException.class)
                .hasMessage("Invalid authentication principal");
    }

    @Test
    @DisplayName("getPlayerOrNull - returns PlayerPrincipal directly")
    void getPlayerOrNull_DirectPrincipal() {
        PlayerPrincipal result = SecurityUtils.getPlayerOrNull(playerPrincipal);
        assertThat(result).isSameAs(playerPrincipal);
    }

    @Test
    @DisplayName("getPlayerOrNull - extracts PlayerPrincipal from Authentication wrapper")
    void getPlayerOrNull_AuthenticationWrapper() {
        Authentication auth = new UsernamePasswordAuthenticationToken(playerPrincipal, null);
        PlayerPrincipal result = SecurityUtils.getPlayerOrNull(auth);
        assertThat(result).isSameAs(playerPrincipal);
    }

    @Test
    @DisplayName("getPlayerOrNull - returns null for non-player principal")
    void getPlayerOrNull_InvalidPrincipal() {
        Principal nonPlayerPrincipal = () -> "unknown";
        PlayerPrincipal result = SecurityUtils.getPlayerOrNull(nonPlayerPrincipal);
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("getPlayerOrNull - returns null for Authentication wrapping non-PlayerPrincipal")
    void getPlayerOrNull_AuthenticationWithNonPlayerPrincipal() {
        Authentication anonymous = new UsernamePasswordAuthenticationToken("anonymousUser", null);
        assertThat(SecurityUtils.getPlayerOrNull(anonymous)).isNull();
    }

    @Test
    @DisplayName("getPlayerOrNull - returns null for null principal")
    void getPlayerOrNull_NullPrincipal() {
        PlayerPrincipal result = SecurityUtils.getPlayerOrNull(null);
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("getClientIp - ignores X-Forwarded-For when trustProxy is false")
    void getClientIp_TrustProxyFalse_ReturnsRemoteAddr() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("192.168.1.50");

        assertThat(SecurityUtils.getClientIp(request, false)).isEqualTo("192.168.1.50");
    }

    @Test
    @DisplayName("getClientIp - extracts and trims first IP from single or multi-hop X-Forwarded-For when trustProxy is true")
    void getClientIp_TrustProxyTrue_ExtractsFirstForwardedIp() {
        HttpServletRequest singleHop = mock(HttpServletRequest.class);
        when(singleHop.getHeader("X-Forwarded-For")).thenReturn(" 203.0.113.195 ");
        assertThat(SecurityUtils.getClientIp(singleHop, true)).isEqualTo("203.0.113.195");

        HttpServletRequest multiHop = mock(HttpServletRequest.class);
        when(multiHop.getHeader("X-Forwarded-For")).thenReturn(" 203.0.113.195 , 70.41.3.18, 150.172.238.178");
        assertThat(SecurityUtils.getClientIp(multiHop, true)).isEqualTo("203.0.113.195");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", ",", " , ", ", 10.0.0.1"})
    @DisplayName("getClientIp - falls back to remoteAddr when X-Forwarded-For is missing, blank, or has an empty first entry")
    void getClientIp_TrustProxyTrueWithMissingOrMalformedHeader_FallsBackToRemoteAddr(String xfHeader) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Forwarded-For")).thenReturn(xfHeader);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");

        assertThat(SecurityUtils.getClientIp(request, true)).isEqualTo("127.0.0.1");
    }
}
