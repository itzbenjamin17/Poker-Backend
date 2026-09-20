package com.pokergame.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link PayloadSizeFilter}.
 */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
@DisplayName("PayloadSizeFilter")
class PayloadSizeFilterTest {

    @InjectMocks
    private PayloadSizeFilter filter;

    @Test
    @DisplayName("should allow request when Content-Length is equal to max limit (10,240 bytes)")
    void givenExactMaxContentLength_whenDoFilter_thenAllowRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/room/create");
        request.setContent(new byte[10_240]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        filter.doFilterInternal(request, response, filterChain);

        assertThat(filterChain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("should allow request when Content-Length is under max limit")
    void givenSmallContentLength_whenDoFilter_thenAllowRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/room/create");
        request.setContent(new byte[512]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        filter.doFilterInternal(request, response, filterChain);

        assertThat(filterChain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("should reject request with HTTP 413 when Content-Length exceeds 10,240 bytes")
    void givenOversizedContentLength_whenDoFilter_thenRejectWith413() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/room/create");
        request.setContent(new byte[10_241]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("payload too large");
        assertThat(filterChain.getRequest()).isNull();
    }

    @Test
    @DisplayName("should allow chunked transfer or request with no Content-Length (-1)")
    void givenChunkedTransferWithoutContentLength_whenDoFilter_thenAllowRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/room/create");
        // By default without setting content, getContentLengthLong() returns -1
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        filter.doFilterInternal(request, response, filterChain);

        assertThat(filterChain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }
}
