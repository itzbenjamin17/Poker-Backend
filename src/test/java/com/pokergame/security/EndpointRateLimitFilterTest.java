package com.pokergame.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

/**
 * Unit tests for {@link EndpointRateLimitFilter}.
 */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
@DisplayName("EndpointRateLimitFilter")
class EndpointRateLimitFilterTest {

    @Mock
    private RateLimitService rateLimitService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("should use remote address when trustProxy is false")
    void givenTrustProxyFalse_whenDoFilter_thenUseRemoteAddress() throws Exception {
        EndpointRateLimitFilter filter = new EndpointRateLimitFilter(rateLimitService, objectMapper, false);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/room/create");
        request.setRemoteAddr("192.168.1.100");
        request.addHeader("X-Forwarded-For", "203.0.113.195");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        when(rateLimitService.consumeRest("192.168.1.100:/api/room/create"))
                .thenReturn(RateLimitService.Decision.ALLOWED);

        filter.doFilterInternal(request, response, filterChain);

        verify(rateLimitService).consumeRest("192.168.1.100:/api/room/create");
        assertThat(filterChain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("should extract first IP from X-Forwarded-For when trustProxy is true")
    void givenTrustProxyTrue_whenDoFilter_thenExtractForwardedIp() throws Exception {
        EndpointRateLimitFilter filter = new EndpointRateLimitFilter(rateLimitService, objectMapper, true);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/room/join");
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Forwarded-For", "203.0.113.195, 198.51.100.17");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        when(rateLimitService.consumeRest("203.0.113.195:/api/room/join"))
                .thenReturn(RateLimitService.Decision.ALLOWED);

        filter.doFilterInternal(request, response, filterChain);

        verify(rateLimitService).consumeRest("203.0.113.195:/api/room/join");
        assertThat(filterChain.getRequest()).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/room/create", "/api/room/join"})
    @DisplayName("should reject with HTTP 429 ProblemDetail and Retry-After header when rate limit is exceeded")
    void givenRateLimitExceeded_whenDoFilter_thenReturns429ProblemDetail(String path) throws Exception {
        EndpointRateLimitFilter filter = new EndpointRateLimitFilter(rateLimitService, objectMapper, false);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setRemoteAddr("10.1.1.1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        when(rateLimitService.consumeRest("10.1.1.1:" + path))
                .thenReturn(RateLimitService.Decision.rejected(42));

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("42");
        assertThat(response.getContentType()).contains("application/problem+json");
        assertThat(response.getContentAsString())
                .contains("\"status\":429")
                .contains("\"title\":\"Too Many Requests\"")
                .contains("\"detail\":\"Too many requests. Please try again in 42 seconds.\"");
        assertThat(filterChain.getRequest()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/room/create", "/api/room/join"})
    @DisplayName("should pass CORS preflight requests through without consuming rate limit tokens")
    void givenCorsPreflight_whenDoFilter_thenPassThroughWithoutRateLimiting(String path) throws Exception {
        EndpointRateLimitFilter filter = new EndpointRateLimitFilter(rateLimitService, objectMapper, false);

        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", path);
        request.setRemoteAddr("10.1.1.2");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        filter.doFilterInternal(request, response, filterChain);

        verify(rateLimitService, never()).consumeRest(anyString());
        assertThat(filterChain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("should permit other endpoints without consuming rate limit tokens")
    void givenUnrestrictedEndpoint_whenDoFilter_thenPassThroughWithoutRateLimiting() throws Exception {
        EndpointRateLimitFilter filter = new EndpointRateLimitFilter(rateLimitService, objectMapper, false);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        request.setRemoteAddr("10.1.1.3");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        filter.doFilterInternal(request, response, filterChain);

        verify(rateLimitService, never()).consumeRest(anyString());
        assertThat(filterChain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }
}
