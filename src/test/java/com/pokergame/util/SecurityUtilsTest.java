package com.pokergame.util;

import com.pokergame.exception.UnauthorisedActionException;
import com.pokergame.security.PlayerPrincipal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.security.Principal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for {@link SecurityUtils}. */
class SecurityUtilsTest {

    private final PlayerPrincipal playerPrincipal = new PlayerPrincipal("Alice", "room-123");

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
    @DisplayName("getPlayerOrNull - returns null for null principal")
    void getPlayerOrNull_NullPrincipal() {
        PlayerPrincipal result = SecurityUtils.getPlayerOrNull(null);
        assertThat(result).isNull();
    }
}
