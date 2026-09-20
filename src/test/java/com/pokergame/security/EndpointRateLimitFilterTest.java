package com.pokergame.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EndpointRateLimitFilter}.
 */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
@DisplayName("EndpointRateLimitFilter")
class EndpointRateLimitFilterTest {

    @Mock
    private RateLimitService rateLimitService;

    @InjectMocks
    private EndpointRateLimitFilter filter;

    @Test
    @DisplayName("should use remote address when trustProxy is false")
    void givenTrustProxyFalse_whenDoFilter_thenUseRemoteAddress() throws Exception {
        ReflectionTestUtils.setField(filter, "trustProxy", false);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/room/create");
        request.setRemoteAddr("192.168.1.100");
        request.addHeader("X-Forwarded-For", "203.0.113.195");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        when(rateLimitService.tryConsumeRest("192.168.1.100:/api/room/create")).thenReturn(true);

        filter.doFilterInternal(request, response, filterChain);

        verify(rateLimitService).tryConsumeRest("192.168.1.100:/api/room/create");
        assertThat(filterChain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("should extract first IP from X-Forwarded-For when trustProxy is true")
    void givenTrustProxyTrue_whenDoFilter_thenExtractForwardedIp() throws Exception {
        ReflectionTestUtils.setField(filter, "trustProxy", true);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/room/join");
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Forwarded-For", "203.0.113.195, 198.51.100.17");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        when(rateLimitService.tryConsumeRest("203.0.113.195:/api/room/join")).thenReturn(true);

        filter.doFilterInternal(request, response, filterChain);

        verify(rateLimitService).tryConsumeRest("203.0.113.195:/api/room/join");
        assertThat(filterChain.getRequest()).isNotNull();
    }

    @Test
    @DisplayName("should reject with HTTP 429 when rate limit is exceeded on /api/room/create")
    void givenRateLimitExceededOnCreate_whenDoFilter_thenReturns429() throws Exception {
        ReflectionTestUtils.setField(filter, "trustProxy", false);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/room/create");
        request.setRemoteAddr("10.1.1.1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        when(rateLimitService.tryConsumeRest("10.1.1.1:/api/room/create")).thenReturn(false);

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getContentAsString()).contains("Too many requests");
        assertThat(filterChain.getRequest()).isNull();
    }

    @Test
    @DisplayName("should reject with HTTP 429 when rate limit is exceeded on /api/room/join")
    void givenRateLimitExceededOnJoin_whenDoFilter_thenReturns429() throws Exception {
        ReflectionTestUtils.setField(filter, "trustProxy", false);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/room/join");
        request.setRemoteAddr("10.1.1.2");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        when(rateLimitService.tryConsumeRest("10.1.1.2:/api/room/join")).thenReturn(false);

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getContentAsString()).contains("Too many requests");
        assertThat(filterChain.getRequest()).isNull();
    }

    @Test
    @DisplayName("should permit other endpoints without consuming rate limit tokens")
    void givenUnrestrictedEndpoint_whenDoFilter_thenPassThroughWithoutRateLimiting() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        request.setRemoteAddr("10.1.1.3");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        filter.doFilterInternal(request, response, filterChain);

        verify(rateLimitService, never()).tryConsumeRest(anyString());
        assertThat(filterChain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }
}
