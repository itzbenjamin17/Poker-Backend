package com.pokergame.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

/**
 * Unit tests for {@link PayloadSizeFilter}.
 */
@Tag("unit")
@DisplayName("PayloadSizeFilter")
class PayloadSizeFilterTest {

    private PayloadSizeFilter filter;

    @BeforeEach
    void setUp() {
        filter = new PayloadSizeFilter(new ObjectMapper(), false);
    }

    @Test
    @DisplayName("should allow request when Content-Length is equal to max limit (10,240 bytes)")
    void givenExactMaxContentLength_whenDoFilter_thenAllowRequest() throws Exception {
        byte[] body = new byte[10_240];
        Arrays.fill(body, (byte) 'a');
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/room/create");
        request.setContent(body);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        filter.doFilterInternal(request, response, filterChain);

        HttpServletRequest downstream = (HttpServletRequest) filterChain.getRequest();
        assertThat(downstream).isNotNull();
        assertThat(downstream.getInputStream().readAllBytes()).isEqualTo(body);
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
    @DisplayName("should reject request with HTTP 413 when declared Content-Length exceeds 10,240 bytes")
    void givenOversizedContentLength_whenDoFilter_thenRejectWith413() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/room/create");
        request.setContent(new byte[10_241]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        filter.doFilterInternal(request, response, filterChain);

        assert413ProblemDetail(response);
        assertThat(filterChain.getRequest()).isNull();
    }

    @Test
    @DisplayName("should allow unknown-length (Content-Length -1) body of exact 10,240 bytes and forward exact bytes")
    void givenUnknownLengthBodyAtExactLimit_whenDoFilter_thenAllowAndForwardExactBytes() throws Exception {
        byte[] body = new byte[10_240];
        Arrays.fill(body, (byte) 'x');
        StreamedRequest request = new StreamedRequest(-1L, body);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(200);
        HttpServletRequest downstream = (HttpServletRequest) filterChain.getRequest();
        assertThat(downstream).isNotNull();
        assertThat(downstream.getContentLengthLong()).isEqualTo(10_240L);
        assertThat(downstream.getInputStream().readAllBytes()).isEqualTo(body);
    }

    @Test
    @DisplayName("should reject unknown-length (Content-Length -1) body of 10,241 bytes with HTTP 413 before filter chain")
    void givenUnknownLengthBodyAt10241Bytes_whenDoFilter_thenRejectWith413BeforeChain() throws Exception {
        byte[] body = new byte[10_241];
        StreamedRequest request = new StreamedRequest(-1L, body);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        filter.doFilterInternal(request, response, filterChain);

        assert413ProblemDetail(response);
        assertThat(filterChain.getRequest()).isNull();
    }

    @Test
    @DisplayName("should reject request when declared Content-Length is small (100) but stream yields 10,241 bytes")
    void givenLyingSmallContentLengthWithOversizedStream_whenDoFilter_thenRejectWith413() throws Exception {
        byte[] body = new byte[10_241];
        StreamedRequest request = new StreamedRequest(100L, body);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        filter.doFilterInternal(request, response, filterChain);

        assert413ProblemDetail(response);
        assertThat(filterChain.getRequest()).isNull();
    }

    @Test
    @DisplayName("should pass GET request without body through untouched without reading input stream")
    void givenGetRequestWithoutBody_whenDoFilter_thenPassThroughWithoutReadingStream() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ws") {
            @Override
            public ServletInputStream getInputStream() {
                throw new AssertionError("GET without body must not read input stream");
            }
        };
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        filter.doFilterInternal(request, response, filterChain);

        assertThat(filterChain.getRequest()).isSameAs(request);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    private static void assert413ProblemDetail(MockHttpServletResponse response) throws Exception {
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentType()).contains("application/problem+json");
        assertThat(response.getContentAsString())
                .contains("\"status\":413")
                .contains("Request payload too large. Maximum allowed is 10KB.");
    }

    private static final class StreamedRequest extends MockHttpServletRequest {
        private final long declaredLength;
        private final byte[] body;

        StreamedRequest(long declaredLength, byte[] body) {
            super("POST", "/api/room/create");
            this.declaredLength = declaredLength;
            this.body = body;
        }

        @Override
        public long getContentLengthLong() {
            return declaredLength;
        }

        @Override
        public int getContentLength() {
            return (int) declaredLength;
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
                }
            };
        }
    }
}
