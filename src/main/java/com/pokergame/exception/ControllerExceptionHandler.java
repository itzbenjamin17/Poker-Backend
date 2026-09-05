package com.pokergame.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.pokergame.dto.response.ErrorResponse;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Exception handler for all exceptions in the application.
 */

@ControllerAdvice
public class ControllerExceptionHandler {
    private static final Logger logger = LoggerFactory.getLogger(ControllerExceptionHandler.class);

    /**
     * Maps missing resources to HTTP 404.
     *
     * @param ex missing-resource failure
     * @return structured not-found response
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFoundException(ResourceNotFoundException ex) {
        logger.warn("Resource not found: {}", ex.getMessage());
        ErrorResponse error = new ErrorResponse(HttpStatus.NOT_FOUND.value(), "Not Found", ex.getMessage());
        // returns 404 Not Found
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    /**
     * Maps domain request failures to HTTP 400.
     *
     * @param ex invalid-request failure
     * @return structured bad-request response
     */
    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ErrorResponse> handleBadRequestException(BadRequestException ex) {
        logger.warn("Bad request: {}", ex.getMessage());
        ErrorResponse error = new ErrorResponse(HttpStatus.BAD_REQUEST.value(), "Bad Request", ex.getMessage());
        // returns 400 Bad Request
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
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
        ErrorResponse error = new ErrorResponse(HttpStatus.BAD_REQUEST.value(), "Bad Request", ex.getMessage());
        // returns 400 Bad Request
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
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
        ErrorResponse error = new ErrorResponse(HttpStatus.FORBIDDEN.value(), "Forbidden", ex.getMessage());
        // returns 403 Forbidden
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error);
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
        ErrorResponse error = new ErrorResponse(HttpStatus.FORBIDDEN.value(), "Forbidden", ex.getMessage());
        // returns 403 Forbidden
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error);
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
        ErrorResponse error = new ErrorResponse(HttpStatus.TOO_MANY_REQUESTS.value(), "Too Many Requests", ex.getMessage());
        // returns 429 Too Many Requests
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(error);
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
        ErrorResponse error = new ErrorResponse(HttpStatus.BAD_REQUEST.value(), "Bad Request", message);
        // returns 400 Bad Request
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
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
        ErrorResponse error = new ErrorResponse(HttpStatus.BAD_REQUEST.value(), "Bad Request", "Malformed JSON request payload");
        // returns 400 Bad Request
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    /**
     * Maps wrong HTTP method to HTTP 405.  Logged at DEBUG — scanners commonly
     * try arbitrary methods against every discovered path.
     *
     * @param ex method-not-allowed failure
     * @return structured method-not-allowed response
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupportedException(HttpRequestMethodNotSupportedException ex) {
        logger.debug("Method not allowed: {}", ex.getMessage());
        ErrorResponse error = new ErrorResponse(HttpStatus.METHOD_NOT_ALLOWED.value(), "Method Not Allowed",
                "HTTP method not supported for this endpoint");
        // returns 405 Method Not Allowed
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(error);
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
        ErrorResponse error = new ErrorResponse(HttpStatus.BAD_REQUEST.value(), "Bad Request",
                "Required parameter '" + ex.getParameterName() + "' is missing");
        // returns 400 Bad Request
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    /**
     * Maps an unsupported request Content-Type to HTTP 415.
     *
     * @param ex unsupported-media-type failure
     * @return structured unsupported-media-type response
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMediaTypeNotSupportedException(HttpMediaTypeNotSupportedException ex) {
        logger.debug("Unsupported media type: {}", ex.getMessage());
        ErrorResponse error = new ErrorResponse(HttpStatus.UNSUPPORTED_MEDIA_TYPE.value(), "Unsupported Media Type",
                "Content-Type not supported");
        // returns 415 Unsupported Media Type
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(error);
    }

    /**
     * Maps an unacceptable Accept header to HTTP 406.
     *
     * @param ex not-acceptable failure
     * @return structured not-acceptable response
     */
    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ErrorResponse> handleMediaTypeNotAcceptableException(HttpMediaTypeNotAcceptableException ex) {
        logger.debug("Not acceptable media type: {}", ex.getMessage());
        ErrorResponse error = new ErrorResponse(HttpStatus.NOT_ACCEPTABLE.value(), "Not Acceptable",
                "Requested media type is not supported");
        // returns 406 Not Acceptable
        return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE).body(error);
    }

    /**
     * Handles requests for static resources that do not exist (e.g. automated
     * vulnerability scanner probes).  Logged at DEBUG to avoid polluting ERROR
     * logs with expected 404s.
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
        ErrorResponse error = new ErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR.value(), "Internal Server Error",
                "An unexpected error occurred");
        // returns 500 Internal Server Error
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }

}
