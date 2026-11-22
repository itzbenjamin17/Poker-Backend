package com.pokergame.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.pokergame.util.MdcKeys;
import jakarta.servlet.FilterChain;
import java.util.concurrent.atomic.AtomicReference;
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
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.ObjectMapper;

/**
 * Unit tests for {@link JwtAuthenticationFilter}.
 */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
@DisplayName("JwtAuthenticationFilter")
class JwtAuthenticationFilterTest {

    @Mock
    private JwtService jwtService;

    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        filter = new JwtAuthenticationFilter(jwtService, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        MDC.clear();
    }

    @Test
    @DisplayName("should allow anonymous pass-through when Authorization header is missing")
    void givenMissingToken_whenDoFilter_thenPassesThroughWithoutTouchingSecurityContext() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        filter.doFilterInternal(request, response, filterChain);

        assertThat(filterChain.getRequest()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(jwtService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Basic dXNlcjpwYXNz", "Bearer"})
    @DisplayName("should pass through non-Bearer Authorization headers without validating JWT")
    void givenNonBearerHeader_whenDoFilter_thenPassesThroughWithoutTouchingJwtService(String headerValue)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", headerValue);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        filter.doFilterInternal(request, response, filterChain);

        assertThat(filterChain.getRequest()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(jwtService);
    }

    @Test
    @DisplayName("should populate SecurityContext and MDC during filterChain and clear MDC afterward")
    void givenValidToken_whenDoFilter_thenPopulatesSecurityContextAndMdcDuringChainAndClearsMdcAfter()
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer valid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        PlayerPrincipal principal = new PlayerPrincipal("Alice", "room-123", "mem-1");
        when(jwtService.isTokenValid("valid-token")).thenReturn(true);
        when(jwtService.extractPrincipal("valid-token")).thenReturn(principal);

        AtomicReference<String> observedRoomId = new AtomicReference<>();
        AtomicReference<String> observedPlayerName = new AtomicReference<>();
        FilterChain chain = (req, res) -> {
            observedRoomId.set(MDC.get(MdcKeys.ROOM_ID));
            observedPlayerName.set(MDC.get(MdcKeys.PLAYER_NAME));
        };

        filter.doFilterInternal(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isEqualTo(principal);
        assertThat(observedRoomId.get()).isEqualTo("room-123");
        assertThat(observedPlayerName.get()).isEqualTo("Alice");
        assertThat(MDC.get(MdcKeys.ROOM_ID)).isNull();
        assertThat(MDC.get(MdcKeys.PLAYER_NAME)).isNull();
    }

    @Test
    @DisplayName("should clear MDC even when downstream filter chain throws an exception")
    void givenDownstreamException_whenDoFilter_thenClearsMdcInFinally() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer valid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        PlayerPrincipal principal = new PlayerPrincipal("Alice", "room-123", "mem-1");
        when(jwtService.isTokenValid("valid-token")).thenReturn(true);
        when(jwtService.extractPrincipal("valid-token")).thenReturn(principal);

        RuntimeException failure = new IllegalStateException("downstream failure");
        FilterChain failingChain = (req, res) -> {
            assertThat(MDC.get(MdcKeys.ROOM_ID)).isEqualTo("room-123");
            assertThat(MDC.get(MdcKeys.PLAYER_NAME)).isEqualTo("Alice");
            throw failure;
        };

        assertThatThrownBy(() -> filter.doFilterInternal(request, response, failingChain))
                .isSameAs(failure);

        assertThat(MDC.get(MdcKeys.ROOM_ID)).isNull();
        assertThat(MDC.get(MdcKeys.PLAYER_NAME)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Bearer expired-or-malformed-token", "Bearer "})
    @DisplayName("should set 401 and halt chain when Authorization header contains empty or invalid Bearer token")
    void givenInvalidOrEmptyBearerToken_whenDoFilter_thenSets401AndHaltsChain(String authHeader) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", authHeader);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        String token = authHeader.substring(7);
        when(jwtService.isTokenValid(token)).thenReturn(false);

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).contains("application/problem+json");
        assertThat(response.getContentAsString())
                .contains("\"status\":401")
                .contains("\"title\":\"Unauthorized\"")
                .contains("\"detail\":\"Session expired or invalid token\"");
        assertThat(filterChain.getRequest()).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
