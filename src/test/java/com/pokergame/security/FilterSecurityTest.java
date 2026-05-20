package com.pokergame.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class FilterSecurityTest {

    @Mock
    private RateLimitService rateLimitService;

    @Mock
    private FilterChain filterChain;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @InjectMocks
    private EndpointRateLimitFilter rateLimitFilter;

    @InjectMocks
    private PayloadSizeFilter payloadSizeFilter;

    @Test
    @DisplayName("should use remote address when trust-proxy is false")
    void givenTrustProxyFalse_whenGetIp_thenUseRemoteAddr() throws Exception {
        ReflectionTestUtils.setField(rateLimitFilter, "trustProxy", false);
        when(request.getRequestURI()).thenReturn("/api/room/create");
        when(request.getRemoteAddr()).thenReturn("1.2.3.4");
        when(rateLimitService.tryConsumeRest(anyString())).thenReturn(true);

        rateLimitFilter.doFilterInternal(request, response, filterChain);

        verify(rateLimitService).tryConsumeRest("1.2.3.4:/api/room/create");
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("should use X-Forwarded-For when trust-proxy is true")
    void givenTrustProxyTrue_whenGetIp_thenUseXForwardedFor() throws Exception {
        ReflectionTestUtils.setField(rateLimitFilter, "trustProxy", true);
        when(request.getRequestURI()).thenReturn("/api/room/create");
        when(request.getHeader("X-Forwarded-For")).thenReturn("5.6.7.8, 10.0.0.1");
        when(rateLimitService.tryConsumeRest(anyString())).thenReturn(true);

        rateLimitFilter.doFilterInternal(request, response, filterChain);

        verify(rateLimitService).tryConsumeRest("5.6.7.8:/api/room/create");
    }

    @Test
    @DisplayName("should use separate buckets for different endpoints")
    void givenDifferentPaths_whenRateLimited_thenUseDifferentKeys() throws Exception {
        ReflectionTestUtils.setField(rateLimitFilter, "trustProxy", false);
        when(request.getRemoteAddr()).thenReturn("1.2.3.4");
        when(rateLimitService.tryConsumeRest(anyString())).thenReturn(true);

        // Path 1
        when(request.getRequestURI()).thenReturn("/api/room/create");
        rateLimitFilter.doFilterInternal(request, response, filterChain);
        verify(rateLimitService).tryConsumeRest("1.2.3.4:/api/room/create");

        // Path 2
        when(request.getRequestURI()).thenReturn("/api/room/join");
        rateLimitFilter.doFilterInternal(request, response, filterChain);
        verify(rateLimitService).tryConsumeRest("1.2.3.4:/api/room/join");
    }

    @Test
    @DisplayName("should block request when rate limit exceeded")
    void givenRateLimitExceeded_whenDoFilter_thenSend429() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/room/create");
        when(request.getRemoteAddr()).thenReturn("1.2.3.4");
        when(rateLimitService.tryConsumeRest(anyString())).thenReturn(false);
        
        StringWriter stringWriter = new StringWriter();
        PrintWriter printWriter = new PrintWriter(stringWriter);
        when(response.getWriter()).thenReturn(printWriter);

        rateLimitFilter.doFilterInternal(request, response, filterChain);

        verify(response).setStatus(429);
        assertThat(stringWriter.toString()).contains("Too many requests");
        verify(filterChain, never()).doFilter(request, response);
    }

    @Test
    @DisplayName("should allow small payload")
    void givenSmallPayload_whenDoFilter_thenAllow() throws Exception {
        when(request.getContentLengthLong()).thenReturn(500L);

        payloadSizeFilter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("should reject large payload")
    void givenLargePayload_whenDoFilter_thenSend413() throws Exception {
        when(request.getContentLengthLong()).thenReturn(20000L);
        
        StringWriter stringWriter = new StringWriter();
        PrintWriter printWriter = new PrintWriter(stringWriter);
        when(response.getWriter()).thenReturn(printWriter);

        payloadSizeFilter.doFilterInternal(request, response, filterChain);

        verify(response).setStatus(413);
        assertThat(stringWriter.toString()).contains("payload too large");
        verify(filterChain, never()).doFilter(request, response);
    }

    @Test
    @DisplayName("should allow chunked transfer (Content-Length: -1) with current simple filter")
    void givenChunkedTransfer_whenDoFilter_thenAllowByBypassing() throws Exception {
        when(request.getContentLengthLong()).thenReturn(-1L);

        payloadSizeFilter.doFilterInternal(request, response, filterChain);

        // Documenting that -1 bypasses the Content-Length check in this simple implementation
        verify(filterChain).doFilter(request, response);
    }
}
