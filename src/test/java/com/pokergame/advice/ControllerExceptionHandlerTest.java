package com.pokergame.advice;

import com.pokergame.exception.ResourceNotFoundException;
import com.pokergame.exception.TooManyRequestsException;

import com.pokergame.dto.response.ErrorResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ControllerExceptionHandler}.
 */
@Tag("unit")
@DisplayName("ControllerExceptionHandler")
class ControllerExceptionHandlerTest {

    private final ControllerExceptionHandler handler = new ControllerExceptionHandler();

    @Test
    @DisplayName("should map HttpMessageNotReadableException to 400 Bad Request with stable message")
    void givenHttpMessageNotReadableException_whenHandle_thenReturnBadRequest() {
        HttpMessageNotReadableException ex = mock(HttpMessageNotReadableException.class);
        when(ex.getMessage()).thenReturn("JSON parse error: Unexpected character");

        ResponseEntity<ErrorResponse> response = handler.handleHttpMessageNotReadableException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(400);
        assertThat(response.getBody().error()).isEqualTo("Bad Request");
        assertThat(response.getBody().message()).isEqualTo("Malformed JSON request payload");
    }

    @Test
    @DisplayName("should map MethodArgumentNotValidException with field errors to 400 Bad Request with field message")
    void givenMethodArgumentNotValidExceptionWithFieldErrors_whenHandle_thenReturnBadRequestWithFieldMessage() {
        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
        BindingResult bindingResult = mock(BindingResult.class);
        FieldError fieldError = new FieldError("createRoomRequest", "roomName", "Room name is required");

        when(bindingResult.getFieldErrors()).thenReturn(List.of(fieldError));
        when(ex.getBindingResult()).thenReturn(bindingResult);

        ResponseEntity<ErrorResponse> response = handler.handleMethodArgumentNotValidException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(400);
        assertThat(response.getBody().error()).isEqualTo("Bad Request");
        assertThat(response.getBody().message()).isEqualTo("Room name is required");
    }

    @Test
    @DisplayName("should map MethodArgumentNotValidException with empty field errors to 400 Bad Request with default message")
    void givenMethodArgumentNotValidExceptionWithoutFieldErrors_whenHandle_thenReturnBadRequestWithDefaultMessage() {
        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
        BindingResult bindingResult = mock(BindingResult.class);

        when(bindingResult.getFieldErrors()).thenReturn(Collections.emptyList());
        when(ex.getBindingResult()).thenReturn(bindingResult);

        ResponseEntity<ErrorResponse> response = handler.handleMethodArgumentNotValidException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(400);
        assertThat(response.getBody().error()).isEqualTo("Bad Request");
        assertThat(response.getBody().message()).isEqualTo("Invalid request payload");
    }

    @Test
    @DisplayName("should map ResourceNotFoundException to 404 Not Found")
    void givenResourceNotFoundException_whenHandle_thenReturnNotFound() {
        ResourceNotFoundException ex = new ResourceNotFoundException("Room not found with ID: room-abc-123");

        ResponseEntity<ErrorResponse> response = handler.handleNotFoundException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(404);
        assertThat(response.getBody().error()).isEqualTo("Not Found");
        assertThat(response.getBody().message()).isEqualTo("Room not found with ID: room-abc-123");
    }

    @Test
    @DisplayName("should map TooManyRequestsException to 429 Too Many Requests with exception message")
    void givenTooManyRequestsException_whenHandle_thenReturnTooManyRequests() {
        ResponseEntity<ErrorResponse> response = handler.handleTooManyRequestsException(
                new TooManyRequestsException("Slow down"));

        assertErrorResponse(response, HttpStatus.TOO_MANY_REQUESTS, "Slow down");
    }

    @Test
    @DisplayName("should map SecurityException to 403 Forbidden with exception message")
    void givenSecurityException_whenHandle_thenReturnForbidden() {
        ResponseEntity<ErrorResponse> response = handler.handleSecurityException(
                new SecurityException("Not allowed here"));

        assertErrorResponse(response, HttpStatus.FORBIDDEN, "Not allowed here");
    }

    @Test
    @DisplayName("should map IllegalArgumentException to 400 Bad Request with exception message")
    void givenIllegalArgumentException_whenHandle_thenReturnBadRequest() {
        ResponseEntity<ErrorResponse> response = handler.handleIllegalArgumentException(
                new IllegalArgumentException("Bad argument"));

        assertErrorResponse(response, HttpStatus.BAD_REQUEST, "Bad argument");
    }

    @Test
    @DisplayName("should map MissingServletRequestParameterException to 400 Bad Request naming the parameter")
    void givenMissingServletRequestParameterException_whenHandle_thenReturnBadRequestNamingParameter() {
        ResponseEntity<ErrorResponse> response = handler.handleMissingServletRequestParameterException(
                new MissingServletRequestParameterException("playerId", "String"));

        assertErrorResponse(response, HttpStatus.BAD_REQUEST, "Required parameter 'playerId' is missing");
    }

    @Test
    @DisplayName("should map HttpRequestMethodNotSupportedException to 405 Method Not Allowed with stable message")
    void givenHttpRequestMethodNotSupportedException_whenHandle_thenReturnMethodNotAllowed() {
        ResponseEntity<ErrorResponse> response = handler.handleMethodNotSupportedException(
                new HttpRequestMethodNotSupportedException("DELETE"));

        assertErrorResponse(response, HttpStatus.METHOD_NOT_ALLOWED, "HTTP method not supported for this endpoint");
    }

    @Test
    @DisplayName("should map HttpMediaTypeNotSupportedException to 415 Unsupported Media Type with stable message")
    void givenHttpMediaTypeNotSupportedException_whenHandle_thenReturnUnsupportedMediaType() {
        ResponseEntity<ErrorResponse> response = handler.handleMediaTypeNotSupportedException(
                new HttpMediaTypeNotSupportedException("Content-Type 'text/plain' is not supported"));

        assertErrorResponse(response, HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Content-Type not supported");
    }

    @Test
    @DisplayName("should map HttpMediaTypeNotAcceptableException to 406 Not Acceptable with stable message")
    void givenHttpMediaTypeNotAcceptableException_whenHandle_thenReturnNotAcceptable() {
        ResponseEntity<ErrorResponse> response = handler.handleMediaTypeNotAcceptableException(
                new HttpMediaTypeNotAcceptableException("Could not find acceptable representation"));

        assertErrorResponse(response, HttpStatus.NOT_ACCEPTABLE, "Requested media type is not supported");
    }

    @Test
    @DisplayName("should map NoResourceFoundException to 404 Not Found with no body")
    void givenNoResourceFoundException_whenHandle_thenReturnNotFoundWithoutBody() {
        ResponseEntity<Void> response = handler.handleNoResourceFoundException(
                new NoResourceFoundException(HttpMethod.GET, "/wp-login.php", "wp-login.php"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNull();
    }

    @Test
    @DisplayName("should map unexpected Exception to 500 Internal Server Error without leaking details")
    void givenUnexpectedException_whenHandle_thenReturnInternalServerErrorWithGenericMessage() {
        ResponseEntity<ErrorResponse> response = handler.handleException(
                new RuntimeException("database password is hunter2"));

        assertErrorResponse(response, HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
    }

    private static void assertErrorResponse(ResponseEntity<ErrorResponse> response, HttpStatus status, String message) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(status.value());
        assertThat(response.getBody().error()).isEqualTo(status.getReasonPhrase());
        assertThat(response.getBody().message()).isEqualTo(message);
    }
}