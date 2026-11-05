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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Filter to reject requests with excessively large payloads to prevent DoS attacks.
 *
 * <p>Two layers of enforcement are applied. A declared {@code Content-Length} larger than the
 * limit is rejected before the body is read (fast path). When the declared length is unknown
 * ({@code -1}, as with chunked transfer encoding) the request stream is wrapped so that the byte
 * count is enforced while the body is consumed downstream; crossing the limit aborts the request
 * with HTTP 413 instead of streaming an unbounded body.
 */
@Component
public class PayloadSizeFilter extends OncePerRequestFilter {
    private static final Logger logger = LoggerFactory.getLogger(PayloadSizeFilter.class);
    private static final long MAX_PAYLOAD_SIZE = 10_240; // 10KB

    @Value("${poker.security.trust-proxy:false}")
    private boolean trustProxy;

    private final LogThrottler logThrottler = new LogThrottler(5000L);

    /**
     * Rejects payloads larger than 10 KiB, whether the length is declared up front or only
     * discovered while the body is streamed (unknown-length / chunked requests).
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

        // For unknown-length bodies (Content-Length == -1, e.g. chunked transfer encoding) the
        // declared-length fast path above cannot help, so enforce the bound while the body is read.
        HttpServletRequest boundedRequest = new BoundedRequest(request);
        try {
            filterChain.doFilter(boundedRequest, response);
        } catch (PayloadTooLargeException ex) {
            // The wrapped stream aborted downstream reading once the limit was crossed. If the
            // response has not been committed yet, surface the documented 413 contract to the client.
            if (!response.isCommitted()) {
                response.reset();
                rejectOversized(request, response, ex.getBytesRead());
            }
        }
    }

    /**
     * Writes the documented HTTP 413 rejection response and logs the event (throttled per client).
     *
     * @param request current HTTP request, used for client IP and path resolution
     * @param response current HTTP response to write the rejection to
     * @param size observed payload size in bytes (declared or counted), for the log line
     * @throws IOException if the rejection response cannot be written
     */
    private void rejectOversized(HttpServletRequest request, HttpServletResponse response, long size)
            throws IOException {
        String clientIp = SecurityUtils.getClientIp(request, trustProxy);
        String path = request.getRequestURI();
        String key = clientIp + ":" + path;
        logThrottler.throttle(key, () -> logger.warn(
                "Payload size limit exceeded (413): method={}, path={}, clientIp={}, size={} bytes, limit={} bytes",
                request.getMethod(), path, clientIp, size, MAX_PAYLOAD_SIZE));
        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        response.setContentType("application/json");
        response.getWriter().write("{\"message\": \"Request payload too large. Maximum allowed is 10KB.\"}");
    }

    /**
     * Signals that more than {@link #MAX_PAYLOAD_SIZE} bytes were read from the request body.
     */
    private static final class PayloadTooLargeException extends IOException {
        private final long bytesRead;

        PayloadTooLargeException(long bytesRead) {
            this.bytesRead = bytesRead;
        }

        long getBytesRead() {
            return bytesRead;
        }
    }

    /**
     * Request wrapper that exposes a byte-counting input stream enforcing the payload bound.
     */
    private static final class BoundedRequest extends HttpServletRequestWrapper {
        private BoundedServletInputStream inputStream;

        BoundedRequest(HttpServletRequest request) {
            super(request);
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (inputStream == null) {
                inputStream = new BoundedServletInputStream(super.getInputStream());
            }
            return inputStream;
        }
    }

    /**
     * Counting {@link ServletInputStream} that throws once the cumulative byte count exceeds the
     * payload limit, so unbounded (chunked / unknown-length) bodies cannot stream past the bound.
     */
    private static final class BoundedServletInputStream extends ServletInputStream {
        private final ServletInputStream delegate;
        private long bytesRead;

        BoundedServletInputStream(ServletInputStream delegate) {
            this.delegate = delegate;
        }

        private int countAndCheck(int value) throws IOException {
            if (value >= 0) {
                bytesRead++;
                if (bytesRead > MAX_PAYLOAD_SIZE) {
                    throw new PayloadTooLargeException(bytesRead);
                }
            }
            return value;
        }

        @Override
        public int read() throws IOException {
            return countAndCheck(delegate.read());
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = delegate.read(b, off, len);
            if (n > 0) {
                bytesRead += n;
                if (bytesRead > MAX_PAYLOAD_SIZE) {
                    throw new PayloadTooLargeException(bytesRead);
                }
            }
            return n;
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            delegate.setReadListener(readListener);
        }

        @Override
        public int available() throws IOException {
            return delegate.available();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
