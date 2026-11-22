package com.pokergame.security;

import com.pokergame.util.LogThrottler;
import com.pokergame.util.SecurityUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Filter to reject requests with excessively large payloads to prevent DoS attacks.
 *
 * <p>Two layers of enforcement are applied. A declared {@code Content-Length} larger than the
 * limit is rejected before the body is read (fast path). For requests that may carry a body
 * (including unknown-length / chunked transfer encoding), up to {@code MAX_PAYLOAD_SIZE + 1}
 * bytes are buffered upfront so oversized bodies are rejected with HTTP 413 before reaching
 * downstream filters or Spring MVC message converters.
 */
@Component
public class PayloadSizeFilter extends OncePerRequestFilter {
    private static final Logger logger = LoggerFactory.getLogger(PayloadSizeFilter.class);
    private static final int MAX_PAYLOAD_SIZE = 10_240; // 10KB

    private final ObjectMapper objectMapper;
    private final boolean trustProxy;

    private final LogThrottler logThrottler = new LogThrottler(5000L);

    /**
     * Creates the payload size filter with the JSON mapper and trusted-proxy setting.
     *
     * @param objectMapper object mapper for problem detail responses
     * @param trustProxy   whether to trust {@code X-Forwarded-For} headers for client IP logging
     */
    public PayloadSizeFilter(
            ObjectMapper objectMapper,
            @Value("${poker.security.trust-proxy:false}") boolean trustProxy) {
        this.objectMapper = objectMapper;
        this.trustProxy = trustProxy;
    }

    /**
     * Rejects payloads larger than 10 KiB, whether the length is declared up front or only
     * discovered when reading the request stream (unknown-length / chunked requests).
     *
     * @param request current HTTP request
     * @param response current HTTP response
     * @param filterChain remaining servlet filter chain
     * @throws ServletException if downstream filtering fails
     * @throws IOException if the rejection or downstream response cannot be written
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        long contentLength = request.getContentLengthLong();

        if (contentLength > MAX_PAYLOAD_SIZE) {
            rejectOversized(request, response, contentLength);
            return;
        }

        if (mayCarryBody(request, contentLength)) {
            byte[] body = request.getInputStream().readNBytes(MAX_PAYLOAD_SIZE + 1);
            if (body.length > MAX_PAYLOAD_SIZE) {
                rejectOversized(request, response, body.length);
                return;
            }
            filterChain.doFilter(new CachedBodyRequest(request, body), response);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private static boolean mayCarryBody(HttpServletRequest request, long contentLength) {
        if (request.getHeader("Transfer-Encoding") != null || contentLength > 0) {
            return true;
        }
        String method = request.getMethod();
        return !"GET".equalsIgnoreCase(method)
                && !"HEAD".equalsIgnoreCase(method)
                && !"OPTIONS".equalsIgnoreCase(method)
                && !"TRACE".equalsIgnoreCase(method);
    }

    private void rejectOversized(HttpServletRequest request, HttpServletResponse response, long size)
            throws IOException {
        String clientIp = SecurityUtils.getClientIp(request, trustProxy);
        String path = request.getRequestURI();
        String key = clientIp + ":" + path;
        logThrottler.throttle(key, () -> logger.warn(
                "Payload size limit exceeded (413): method={}, path={}, clientIp={}, size={} bytes, limit={} bytes",
                request.getMethod(), path, clientIp, size, MAX_PAYLOAD_SIZE));
        response.setStatus(HttpStatus.CONTENT_TOO_LARGE.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.CONTENT_TOO_LARGE,
                        "Request payload too large. Maximum allowed is 10KB.")));
    }

    private static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            return new CachedServletInputStream(body);
        }

        @Override
        public BufferedReader getReader() {
            String encoding = getCharacterEncoding();
            return new BufferedReader(new InputStreamReader(
                    new ByteArrayInputStream(body),
                    encoding != null ? java.nio.charset.Charset.forName(encoding) : StandardCharsets.UTF_8));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }

    private static final class CachedServletInputStream extends ServletInputStream {
        private final ByteArrayInputStream delegate;

        CachedServletInputStream(byte[] body) {
            this.delegate = new ByteArrayInputStream(body);
        }

        @Override
        public int read() {
            return delegate.read();
        }

        @Override
        public int read(byte[] b, int off, int len) {
            return delegate.read(b, off, len);
        }

        @Override
        public boolean isFinished() {
            return delegate.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
        }

        @Override
        public int available() {
            return delegate.available();
        }
    }
}
