package com.pokergame.advice;

import com.pokergame.exception.ResourceNotFoundException;

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
import org.springframework.web.bind.MethodArgumentNotValidException;
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

    // TooManyRequestsException (429), SecurityException (403), IllegalArgumentException (400),
    // MissingServletRequestParameterException (400), HttpRequestMethodNotSupportedException (405),
    // HttpMediaTypeNotSupportedException (415), and HttpMediaTypeNotAcceptableException (406) are
    // now asserted at the MVC boundary in ControllerExceptionHandlerMvcTest, which also proves the
    // @ExceptionHandler registration and precedence those direct calls could not.

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