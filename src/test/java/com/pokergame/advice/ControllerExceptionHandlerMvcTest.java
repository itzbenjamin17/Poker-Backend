package com.pokergame.advice;

import com.pokergame.exception.TooManyRequestsException;
import com.pokergame.exception.UnauthorisedActionException;
import com.pokergame.security.EndpointRateLimitFilter;
import com.pokergame.security.JwtAuthenticationFilter;
import com.pokergame.security.PayloadSizeFilter;
import com.pokergame.service.GameStateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Wire-level coverage proving {@link ControllerExceptionHandler}'s {@code @ExceptionHandler}
 * mappings are actually registered and keep their precedence through the Spring MVC dispatch
 * (report finding 9). Each case drives a real request against a probe controller and asserts the
 * HTTP status and body the client receives; the test would fail if a handler were removed,
 * retargeted to a different type, or lost precedence to the generic {@code Exception} fallback.
 * The economical type-by-type cases stay in {@code ControllerExceptionHandlerTest}.
 */
@WebMvcTest(ControllerExceptionHandlerMvcTest.ProbeController.class)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@TestPropertySource(locations = "classpath:application-test.properties")
@Import({ControllerExceptionHandler.class, ControllerExceptionHandlerMvcTest.ProbeController.class})
@Tag("unit")
@DisplayName("ControllerExceptionHandler MVC boundary")
class ControllerExceptionHandlerMvcTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    /** Required by the WebSocket exception advice that the controller slice context also loads. */
    @MockitoBean
    private GameStateService gameStateService;

    // The security filters are @Components the slice would otherwise wire with their real
    // dependencies; mocking them keeps the context focused on exception-handler resolution.
    @MockitoBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @MockitoBean
    private EndpointRateLimitFilter endpointRateLimitFilter;

    @MockitoBean
    private PayloadSizeFilter payloadSizeFilter;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // webAppContextSetup registers the imported @ControllerAdvice so the assertions exercise
        // real handler resolution and precedence rather than a hand-wired advice instance.
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    @Test
    @DisplayName("TooManyRequestsException maps to 429 with its message")
    void tooManyRequests_Returns429() throws Exception {
        mockMvc.perform(get("/probe/too-many-requests"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.error").value("Too Many Requests"))
                .andExpect(jsonPath("$.message").value("Slow down"));
    }

    @Test
    @DisplayName("SecurityException maps to 403 with its message")
    void securityException_Returns403() throws Exception {
        mockMvc.perform(get("/probe/security"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("Forbidden"))
                .andExpect(jsonPath("$.message").value("Not allowed here"));
    }

    @Test
    @DisplayName("UnauthorisedActionException maps to 403 with its message")
    void unauthorisedAction_Returns403() throws Exception {
        mockMvc.perform(get("/probe/unauthorised"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("Forbidden"))
                .andExpect(jsonPath("$.message").value("Action not permitted"));
    }

    @Test
    @DisplayName("IllegalArgumentException maps to 400 with its message and does not fall through to 500")
    void illegalArgument_Returns400() throws Exception {
        mockMvc.perform(get("/probe/illegal-argument"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("Bad argument"));
    }

    @Test
    @DisplayName("Missing required request parameter maps to 400 naming the parameter")
    void missingRequestParameter_Returns400() throws Exception {
        mockMvc.perform(get("/probe/requires-param"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Required parameter 'playerId' is missing"));
    }

    @Test
    @DisplayName("Wrong HTTP method maps to 405 with a stable message")
    void wrongHttpMethod_Returns405() throws Exception {
        mockMvc.perform(post("/probe/get-only"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.message").value("HTTP method not supported for this endpoint"));
    }

    @Test
    @DisplayName("Unsupported Content-Type maps to 415 with a stable message")
    void unsupportedMediaType_Returns415() throws Exception {
        mockMvc.perform(post("/probe/consumes-json")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("plain"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.status").value(415))
                .andExpect(jsonPath("$.message").value("Content-Type not supported"));
    }

    @Test
    @DisplayName("Unacceptable Accept header maps to 406")
    void notAcceptableMediaType_Returns406() throws Exception {
        // The client rejected JSON, so content negotiation cannot render the ErrorResponse body as
        // JSON; the wire status still proves the @ExceptionHandler is registered and resolved.
        mockMvc.perform(get("/probe/produces-json").accept(MediaType.APPLICATION_XML))
                .andExpect(status().isNotAcceptable());
    }

    @Test
    @DisplayName("Unexpected exception maps to 500 and hides secret-bearing detail")
    void unexpectedException_Returns500WithoutLeakingDetail() throws Exception {
        mockMvc.perform(get("/probe/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.error").value("Internal Server Error"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("hunter2"))));
    }

    /** Minimal probe endpoints that raise each representative exception at the MVC boundary. */
    @RestController
    static class ProbeController {

        @GetMapping("/probe/too-many-requests")
        void tooManyRequests() {
            throw new TooManyRequestsException("Slow down");
        }

        @GetMapping("/probe/security")
        void security() {
            throw new SecurityException("Not allowed here");
        }

        @GetMapping("/probe/unauthorised")
        void unauthorised() {
            throw new UnauthorisedActionException("Action not permitted");
        }

        @GetMapping("/probe/illegal-argument")
        void illegalArgument() {
            throw new IllegalArgumentException("Bad argument");
        }

        @GetMapping("/probe/requires-param")
        void requiresParam(@RequestParam String playerId) {
            // Never reached: a missing playerId is rejected before the body runs.
        }

        @GetMapping("/probe/get-only")
        void getOnly() {
            // POSTing here triggers HttpRequestMethodNotSupportedException.
        }

        @PostMapping(value = "/probe/consumes-json", consumes = MediaType.APPLICATION_JSON_VALUE)
        void consumesJson() {
            // A non-JSON Content-Type triggers HttpMediaTypeNotSupportedException.
        }

        @GetMapping(value = "/probe/produces-json", produces = MediaType.APPLICATION_JSON_VALUE)
        String producesJson() {
            // An incompatible Accept header triggers HttpMediaTypeNotAcceptableException.
            return "{}";
        }

        @GetMapping("/probe/boom")
        void boom() {
            throw new RuntimeException("database password is hunter2");
        }
    }
}
