package com.pokergame.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.pokergame.dto.response.ErrorResponse;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

@ControllerAdvice
public class ControllerExceptionHandler {
    private static final Logger logger = LoggerFactory.getLogger(ControllerExceptionHandler.class);

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFoundException(ResourceNotFoundException ex) {
        logger.warn("Resource not found: {}", ex.getMessage());
        ErrorResponse error = new ErrorResponse(HttpStatus.NOT_FOUND.value(), "Not Found", ex.getMessage());
        // returns 404 Not Found
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ErrorResponse> handleBadRequestException(BadRequestException ex) {
        logger.warn("Bad request: {}", ex.getMessage());
        ErrorResponse error = new ErrorResponse(HttpStatus.BAD_REQUEST.value(), "Bad Request", ex.getMessage());
        // returns 400 Bad Request
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    @ExceptionHandler(UnauthorisedActionException.class)
    public ResponseEntity<ErrorResponse> handleUnauthorizedActionException(UnauthorisedActionException ex) {
        logger.warn("Unauthorized action: {}", ex.getMessage());
        ErrorResponse error = new ErrorResponse(HttpStatus.FORBIDDEN.value(), "Forbidden", ex.getMessage());
        // returns 403 Forbidden
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error);
    }

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<ErrorResponse> handleSecurityException(SecurityException ex) {
        logger.warn("Security exception: {}", ex.getMessage());
        ErrorResponse error = new ErrorResponse(HttpStatus.FORBIDDEN.value(), "Forbidden", ex.getMessage());
        // returns 403 Forbidden
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentNotValidException(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(DefaultMessageSourceResolvable::getDefaultMessage)
                .orElse("Invalid request payload");

        logger.warn("Validation error: {}", message);
        ErrorResponse error = new ErrorResponse(HttpStatus.BAD_REQUEST.value(), "Bad Request", message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(Exception ex) {
        logger.error("Unexpected error", ex);
        ErrorResponse error = new ErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR.value(), "Internal Server Error",
                "An unexpected error occurred");
        // returns 500 Internal Server Error
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }

}
