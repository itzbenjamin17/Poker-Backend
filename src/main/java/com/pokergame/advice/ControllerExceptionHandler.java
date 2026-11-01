package com.pokergame.advice;

import com.pokergame.dto.response.ErrorResponse;
import com.pokergame.exception.BadRequestException;
import com.pokergame.exception.ConflictException;
import com.pokergame.exception.ResourceNotFoundException;
import com.pokergame.exception.TooManyRequestsException;
import com.pokergame.exception.UnauthorisedActionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Global REST exception handler mapping domain, security, and Spring Web exceptions
 * to standardized {@link ErrorResponse} payloads.
 *
 * <p>Logging Strategy:
 * <ul>
 *   <li>{@code WARN}: Domain rule breaches, validation failures, and authorization denials.</li>
 *   <li>{@code DEBUG}: Protocol errors and scanner probes (e.g. static resource scans, unsupported methods).</li>
 *   <li>{@code ERROR}: Unexpected internal server exceptions.</li>
 * </ul>
 *
 * <p>Note: WebSocket/STOMP errors are handled separately by {@link WebSocketExceptionHandler}.
 */
@ControllerAdvice
public class ControllerExceptionHandler {
    private static final Logger logger = LoggerFactory.getLogger(ControllerExceptionHandler.class);

    /**
     * Maps domain request failures and poker rule violations to HTTP 400.
     *
     * @param ex invalid-request failure
     * @return structured bad-request response
     */
    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ErrorResponse> handleBadRequestException(BadRequestException ex) {
        logger.warn("Bad request: {}", ex.getMessage());
        return errorResponse(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    /**
     * Maps missing resources to HTTP 404.
     *
     * @param ex missing-resource failure
     * @return structured not-found response
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFoundException(ResourceNotFoundException ex) {
        logger.warn("Resource not found: {}", ex.getMessage());
        return errorResponse(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    /**
     * Maps resource state conflicts to HTTP 409 (e.g. requesting review for an ongoing game).
     *
     * @param ex conflict failure
     * @return structured conflict response
     */
    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ErrorResponse> handleConflictException(ConflictException ex) {
        logger.warn("Conflict: {}", ex.getMessage());
        return errorResponse(HttpStatus.CONFLICT, ex.getMessage());
    }

    /**
     * Maps application rate-limit failures to HTTP 429.
     *
     * @param ex rate-limit failure
     * @return structured too-many-requests response
     */
    @ExceptionHandler(TooManyRequestsException.class)
    public ResponseEntity<ErrorResponse> handleTooManyRequestsException(TooManyRequestsException ex) {
        logger.warn("Rate limit exceeded: {}", ex.getMessage());
        return errorResponse(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage());
    }

    /**
     * Maps access-denied security failures (including stale or mismatched session tokens)
     * to HTTP 401 Unauthorized.
     *
     * @param ex access-denied failure
     * @return structured unauthorized response
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDeniedException(AccessDeniedException ex) {
        logger.warn("Access denied: {}", ex.getMessage());
        return errorResponse(HttpStatus.UNAUTHORIZED, ex.getMessage());
    }

    /**
     * Maps forbidden poker actions to HTTP 403.
     *
     * @param ex authorization failure
     * @return structured forbidden response
     */
    @ExceptionHandler(UnauthorisedActionException.class)
    public ResponseEntity<ErrorResponse> handleUnauthorizedActionException(UnauthorisedActionException ex) {
        logger.warn("Unauthorized action: {}", ex.getMessage());
        return errorResponse(HttpStatus.FORBIDDEN, ex.getMessage());
    }

    /**
     * Maps general security failures to HTTP 403 while preserving the exception's
     * client-facing message.
     *
     * @param ex security failure
     * @return structured forbidden response
     */
    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<ErrorResponse> handleSecurityException(SecurityException ex) {
        logger.warn("Security exception: {}", ex.getMessage());
        return errorResponse(HttpStatus.FORBIDDEN, ex.getMessage());
    }

    /**
     * Maps invalid arguments raised outside the domain hierarchy to HTTP 400.
     *
     * @param ex invalid-argument failure
     * @return structured bad-request response
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgumentException(IllegalArgumentException ex) {
        logger.warn("Illegal argument: {}", ex.getMessage());
        return errorResponse(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    /**
     * Returns the first Bean Validation message for an invalid request body.
     *
     * @param ex request validation failure
     * @return structured bad-request response
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentNotValidException(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(DefaultMessageSourceResolvable::getDefaultMessage)
                .orElse("Invalid request payload");

        logger.warn("Validation error: {}", message);
        return errorResponse(HttpStatus.BAD_REQUEST, message);
    }

    /**
     * Maps malformed JSON to a stable client-facing HTTP 400 response.
     *
     * @param ex request-body decoding failure
     * @return structured bad-request response
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleHttpMessageNotReadableException(HttpMessageNotReadableException ex) {
        logger.warn("Malformed JSON request: {}", ex.getMessage());
        return errorResponse(HttpStatus.BAD_REQUEST, "Malformed JSON request payload");
    }

    /**
     * Maps a missing required query/form parameter to HTTP 400.
     *
     * @param ex missing-parameter failure
     * @return structured bad-request response
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingServletRequestParameterException(MissingServletRequestParameterException ex) {
        logger.warn("Missing request parameter: {}", ex.getMessage());
        return errorResponse(HttpStatus.BAD_REQUEST, "Required parameter '" + ex.getParameterName() + "' is missing");
    }

    /**
     * Maps wrong HTTP method to HTTP 405. Logged at DEBUG to avoid polluting logs
     * with scanner probes.
     *
     * @param ex method-not-allowed failure
     * @return structured method-not-allowed response
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupportedException(HttpRequestMethodNotSupportedException ex) {
        logger.debug("Method not allowed: {}", ex.getMessage());
        return errorResponse(HttpStatus.METHOD_NOT_ALLOWED, "HTTP method not supported for this endpoint");
    }

    /**
     * Maps an unsupported request Content-Type to HTTP 415. Logged at DEBUG.
     *
     * @param ex unsupported-media-type failure
     * @return structured unsupported-media-type response
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMediaTypeNotSupportedException(HttpMediaTypeNotSupportedException ex) {
        logger.debug("Unsupported media type: {}", ex.getMessage());
        return errorResponse(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Content-Type not supported");
    }

    /**
     * Maps an unacceptable Accept header to HTTP 406. Logged at DEBUG.
     *
     * @param ex not-acceptable failure
     * @return structured not-acceptable response
     */
    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ErrorResponse> handleMediaTypeNotAcceptableException(HttpMediaTypeNotAcceptableException ex) {
        logger.debug("Not acceptable media type: {}", ex.getMessage());
        return errorResponse(HttpStatus.NOT_ACCEPTABLE, "Requested media type is not supported");
    }

    /**
     * Handles requests for static resources that do not exist (e.g. automated
     * vulnerability scanner probes). Logged at DEBUG to avoid polluting logs.
     *
     * @param ex missing-static-resource failure raised by Spring MVC
     * @return 404 Not Found with no body
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Void> handleNoResourceFoundException(NoResourceFoundException ex) {
        logger.debug("No static resource found: {}", ex.getMessage());
        return ResponseEntity.notFound().build();
    }

    /**
     * Hides unexpected implementation details behind a generic HTTP 500 response.
     *
     * @param ex unexpected application failure
     * @return structured internal-server-error response
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(Exception ex) {
        logger.error("Unexpected error", ex);
        return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
    }

    private ResponseEntity<ErrorResponse> errorResponse(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(new ErrorResponse(status.value(), status.getReasonPhrase(), message));
    }
}
