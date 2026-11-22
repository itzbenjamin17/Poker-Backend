package com.pokergame.advice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pokergame.dto.request.CreateRoomRequest;
import com.pokergame.exception.BadRequestException;
import com.pokergame.exception.ConflictException;
import com.pokergame.exception.ResourceNotFoundException;
import com.pokergame.exception.TooManyRequestsException;
import com.pokergame.exception.UnauthorisedActionException;
import jakarta.validation.Valid;
import java.lang.reflect.Method;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Unit tests for {@link ControllerExceptionHandler} using standalone {@link MockMvc} dispatch.
 */
@Tag("unit")
@DisplayName("ControllerExceptionHandler")
class ControllerExceptionHandlerTest {

    private final ControllerExceptionHandler handler = new ControllerExceptionHandler();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(handler)
                .build();
    }

    @Test
    @DisplayName("BadRequestException maps to 400 with its message")
    void badRequest_Returns400() throws Exception {
        expectProblemDetail(mockMvc.perform(get("/probe/bad-request")),
                HttpStatus.BAD_REQUEST, "Invalid blind configuration");
    }

    @Test
    @DisplayName("ResourceNotFoundException maps to 404 with its message")
    void resourceNotFound_Returns404() throws Exception {
        expectProblemDetail(mockMvc.perform(get("/probe/not-found")),
                HttpStatus.NOT_FOUND, "Room not found with ID: room-abc-123");
    }

    @Test
    @DisplayName("ConflictException maps to 409 with its message")
    void conflict_Returns409() throws Exception {
        expectProblemDetail(mockMvc.perform(get("/probe/conflict")),
                HttpStatus.CONFLICT, "Game is still in progress");
    }

    @Test
    @DisplayName("TooManyRequestsException maps to 429 without Retry-After header when wait is non-positive")
    void tooManyRequests_Returns429() throws Exception {
        expectProblemDetail(mockMvc.perform(get("/probe/too-many-requests")),
                HttpStatus.TOO_MANY_REQUESTS, "Slow down")
                .andExpect(header().doesNotExist("Retry-After"));
    }

    @Test
    @DisplayName("TooManyRequestsException with a known wait sends a Retry-After header")
    void tooManyRequestsWithWait_Returns429WithRetryAfter() throws Exception {
        expectProblemDetail(mockMvc.perform(get("/probe/too-many-requests-with-wait")),
                HttpStatus.TOO_MANY_REQUESTS, "Wait a bit")
                .andExpect(header().string("Retry-After", "42"));
    }

    @Test
    @DisplayName("AccessDeniedException maps to 401 Unauthorized with its message")
    void accessDenied_Returns401() throws Exception {
        expectProblemDetail(mockMvc.perform(get("/probe/access-denied")),
                HttpStatus.UNAUTHORIZED, "Unauthorized: Session is no longer active for this room.");
    }

    @Test
    @DisplayName("UnauthorisedActionException maps to 403 with its message")
    void unauthorisedAction_Returns403() throws Exception {
        expectProblemDetail(mockMvc.perform(get("/probe/unauthorised")),
                HttpStatus.FORBIDDEN, "Action not permitted");
    }

    @Test
    @DisplayName("SecurityException maps to 403 with a generic message and hides internal detail")
    void securityException_Returns403() throws Exception {
        expectProblemDetail(mockMvc.perform(get("/probe/security")),
                HttpStatus.FORBIDDEN, "Access denied")
                .andExpect(content().string(not(containsString("internal policy"))));
    }

    @Test
    @DisplayName("IllegalArgumentException maps to 400 with a generic message and hides internal detail")
    void illegalArgument_Returns400() throws Exception {
        expectProblemDetail(mockMvc.perform(get("/probe/illegal-argument")),
                HttpStatus.BAD_REQUEST, "Invalid request")
                .andExpect(content().string(not(containsString("delayMs"))));
    }

    @Test
    @DisplayName("MethodArgumentNotValidException with field errors maps to 400 with the field message")
    void methodArgumentNotValidWithFieldError_Returns400() throws Exception {
        String invalidBody = """
                {
                  "roomName": "",
                  "playerName": "Alice",
                  "maxPlayers": 6,
                  "smallBlind": 10,
                  "bigBlind": 20,
                  "buyIn": 1000
                }
                """;
        expectProblemDetail(mockMvc.perform(post("/probe/validated")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidBody)),
                HttpStatus.BAD_REQUEST, "Room name is required");
    }

    @Test
    @DisplayName("MethodArgumentNotValidException without field errors falls back to default message")
    void methodArgumentNotValidWithoutFieldErrors_ReturnsDefaultMessage() throws Exception {
        Method method = ProbeController.class.getDeclaredMethod("validated", CreateRoomRequest.class);
        MethodParameter parameter = new MethodParameter(method, 0);
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "createRoomRequest");
        MethodArgumentNotValidException exception = new MethodArgumentNotValidException(parameter, bindingResult);

        ResponseEntity<ProblemDetail> response = handler.handleMethodArgumentNotValidException(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(response.getBody().getTitle()).isEqualTo(HttpStatus.BAD_REQUEST.getReasonPhrase());
        assertThat(response.getBody().getDetail()).isEqualTo("Invalid request payload");
    }

    @Test
    @DisplayName("Malformed JSON maps to 400 with a stable message")
    void malformedJson_Returns400() throws Exception {
        expectProblemDetail(mockMvc.perform(post("/probe/validated")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{bad")),
                HttpStatus.BAD_REQUEST, "Malformed JSON request payload");
    }

    @Test
    @DisplayName("Missing required request parameter maps to 400 naming the parameter")
    void missingRequestParameter_Returns400() throws Exception {
        expectProblemDetail(mockMvc.perform(get("/probe/requires-param")),
                HttpStatus.BAD_REQUEST, "Required parameter 'playerId' is missing");
    }

    @Test
    @DisplayName("Wrong HTTP method maps to 405 with a stable message")
    void wrongHttpMethod_Returns405() throws Exception {
        expectProblemDetail(mockMvc.perform(post("/probe/get-only")),
                HttpStatus.METHOD_NOT_ALLOWED, "HTTP method not supported for this endpoint");
    }

    @Test
    @DisplayName("Unsupported Content-Type maps to 415 with a stable message")
    void unsupportedMediaType_Returns415() throws Exception {
        expectProblemDetail(mockMvc.perform(post("/probe/consumes-json")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("plain")),
                HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Content-Type not supported");
    }

    @Test
    @DisplayName("Unacceptable Accept header maps to 406 with an empty body")
    void notAcceptableMediaType_Returns406WithEmptyBody() throws Exception {
        mockMvc.perform(get("/probe/produces-text").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotAcceptable())
                .andExpect(content().string(""));
    }

    @Test
    @DisplayName("NoResourceFoundException maps to 404 Not Found with an empty body")
    void noResourceFound_Returns404WithEmptyBody() throws Exception {
        mockMvc.perform(get("/probe/no-resource"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));
    }

    @Test
    @DisplayName("Unexpected exception maps to 500 and hides secret-bearing detail")
    void unexpectedException_Returns500WithoutLeakingDetail() throws Exception {
        expectProblemDetail(mockMvc.perform(get("/probe/boom")),
                HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred")
                .andExpect(content().string(not(containsString("hunter2"))));
    }

    @Test
    @DisplayName("Internal IllegalStateException maps to 500 and hides internal invariant detail")
    void illegalStateException_Returns500WithoutLeakingDetail() throws Exception {
        expectProblemDetail(mockMvc.perform(get("/probe/illegal-state")),
                HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred")
                .andExpect(content().string(not(containsString("internal invariant secret"))));
    }

    private static ResultActions expectProblemDetail(
            ResultActions actions, HttpStatus status, String detail) throws Exception {
        return actions
                .andExpect(status().is(status.value()))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(status.value()))
                .andExpect(jsonPath("$.title").value(status.getReasonPhrase()))
                .andExpect(jsonPath("$.detail").value(detail));
    }

    @RestController
    static class ProbeController {

        @GetMapping("/probe/bad-request")
        void badRequest() {
            throw new BadRequestException("Invalid blind configuration");
        }

        @GetMapping("/probe/not-found")
        void notFound() {
            throw new ResourceNotFoundException("Room not found with ID: room-abc-123");
        }

        @GetMapping("/probe/conflict")
        void conflict() {
            throw new ConflictException("Game is still in progress");
        }

        @GetMapping("/probe/too-many-requests")
        void tooManyRequests() {
            throw new TooManyRequestsException("Slow down");
        }

        @GetMapping("/probe/too-many-requests-with-wait")
        void tooManyRequestsWithWait() {
            throw new TooManyRequestsException("Wait a bit", 42);
        }

        @GetMapping("/probe/access-denied")
        void accessDenied() {
            throw new AccessDeniedException("Unauthorized: Session is no longer active for this room.");
        }

        @GetMapping("/probe/unauthorised")
        void unauthorised() {
            throw new UnauthorisedActionException("Action not permitted");
        }

        @GetMapping("/probe/security")
        void security() {
            throw new SecurityException("internal policy failure");
        }

        @GetMapping("/probe/illegal-argument")
        void illegalArgument() {
            throw new IllegalArgumentException("delayMs must be >= 0: -5");
        }

        @PostMapping(value = "/probe/validated", consumes = MediaType.APPLICATION_JSON_VALUE)
        void validated(@Valid @RequestBody CreateRoomRequest request) {
        }

        @GetMapping("/probe/requires-param")
        void requiresParam(@RequestParam String playerId) {
        }

        @GetMapping("/probe/get-only")
        void getOnly() {
        }

        @PostMapping(value = "/probe/consumes-json", consumes = MediaType.APPLICATION_JSON_VALUE)
        void consumesJson() {
        }

        @GetMapping(value = "/probe/produces-text", produces = MediaType.TEXT_PLAIN_VALUE)
        String producesText() {
            return "ok";
        }

        @GetMapping("/probe/no-resource")
        void noResource() throws NoResourceFoundException {
            throw new NoResourceFoundException(HttpMethod.GET, "/wp-login.php", "wp-login.php");
        }

        @GetMapping("/probe/boom")
        void boom() {
            throw new RuntimeException("database password is hunter2");
        }

        @GetMapping("/probe/illegal-state")
        void illegalState() {
            throw new IllegalStateException("internal invariant secret");
        }
    }
}
