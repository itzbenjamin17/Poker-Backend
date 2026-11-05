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

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;

import java.io.ByteArrayInputStream;
import java.io.IOException;

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
    @DisplayName("should allow unknown-length (Content-Length -1) body under the limit, reading its full body")
    void givenUnknownLengthBodyUnderLimit_whenDoFilter_thenAllowAndConsume() throws Exception {
        byte[] body = new byte[512];
        UnknownLengthRequest request = new UnknownLengthRequest(body);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(200);
        // The downstream request the chain received must be the wrapped (bounded) request, and
        // its body must be fully readable up to the limit.
        HttpServletRequest downstream = (HttpServletRequest) filterChain.getRequest();
        assertThat(downstream).isNotNull();
        assertThat(downstream.getInputStream().readAllBytes()).hasSize(body.length);
    }

    @Test
    @DisplayName("should reject unknown-length (Content-Length -1) body over the limit with HTTP 413")
    void givenUnknownLengthBodyOverLimit_whenBodyRead_thenRejectWith413() throws Exception {
        byte[] body = new byte[11_000];
        UnknownLengthRequest request = new UnknownLengthRequest(body);
        MockHttpServletResponse response = new MockHttpServletResponse();
        // The chain drains the body (as Jackson body binding would), triggering the bound.
        MockFilterChain filterChain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res)
                    throws IOException, jakarta.servlet.ServletException {
                ((HttpServletRequest) req).getInputStream().readAllBytes();
                super.doFilter(req, res);
            }
        };

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("payload too large");
    }

    /**
     * Request whose declared content length is unknown ({@code -1}, as with chunked transfer
     * encoding) but whose input stream still yields a real body, so the filter's counting path
     * is exercised rather than the declared-length fast path.
     */
    private static final class UnknownLengthRequest extends MockHttpServletRequest {
        private final byte[] body;

        UnknownLengthRequest(byte[] body) {
            super("POST", "/api/room/create");
            this.body = body;
        }

        @Override
        public long getContentLengthLong() {
            return -1L;
        }

        @Override
        public int getContentLength() {
            return -1;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream source = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return source.read();
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    return source.read(b, off, len);
                }

                @Override
                public boolean isFinished() {
                    return source.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    // not used in blocking test reads
                }
            };
        }
    }
}
