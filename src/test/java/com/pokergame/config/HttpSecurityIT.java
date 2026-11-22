package com.pokergame.config;

import static com.pokergame.testutil.TestRoomLocks.runWithRoomLock;
import static com.pokergame.testutil.TestRoomLocks.withRoomLock;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.pokergame.dto.request.CreateRoomRequest;
import com.pokergame.dto.request.JoinRoomRequest;
import com.pokergame.dto.response.TokenResponse;
import com.pokergame.persistence.RoomAggregates;
import com.pokergame.security.JwtService;
import com.pokergame.security.PlayerPrincipal;
import com.pokergame.security.RateLimitService;
import com.pokergame.service.RoomService;
import com.pokergame.util.RoomCoordinator;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Integration tests covering HTTP security, JWT authentication, CORS, input sanitization,
 * filter chain ordering, rate limiting, and concurrent admission boundaries.
 */
@Tag("integration")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "poker.rate-limiting.enabled=true")
@ActiveProfiles("test")
@DisplayName("HTTP security, rate-limit, and sanitization integration")
class HttpSecurityIT {

    private static final String ALLOWED_ORIGIN = "http://localhost:5173";
    private static final String DISALLOWED_ORIGIN = "http://malicious-site.com";
    private static final String UNAUTHENTICATED_DETAIL = "Session expired or invalid token";
    private static final AtomicLong UNIQUE_COUNTER = new AtomicLong();

    @LocalServerPort
    private int port;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private RateLimitService rateLimitService;

    @Autowired
    private RoomService roomService;

    @Autowired
    private RoomAggregates roomAggregates;

    @Autowired
    private RoomCoordinator roomCoordinator;

    @Autowired
    private ObjectMapper objectMapper;

    private final Set<String> trackedRoomIds = ConcurrentHashMap.newKeySet();
    private HttpClient httpClient;
    private RestClient restClient;

    @BeforeEach
    void setUp() {
        rateLimitService.reset();
        httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        restClient = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .requestFactory(new JdkClientHttpRequestFactory(httpClient))
                .build();
    }

    @AfterEach
    void tearDown() {
        for (String roomId : List.copyOf(trackedRoomIds)) {
            try {
                runWithRoomLock(roomCoordinator, roomId, () -> {
                    roomService.destroyRoom(roomId);
                    roomAggregates.remove(roomId);
                });
            } catch (RuntimeException ignored) {
                // Best-effort per-room cleanup so one failure does not leak later rooms.
            }
        }
        trackedRoomIds.clear();
        rateLimitService.reset();
        httpClient.close();
    }

    @Nested
    @DisplayName("Public and secured endpoints")
    class PublicAndProtectedEndpoints {

        static Stream<Arguments> protectedEndpoints() {
            return Stream.of(
                    arguments(HttpMethod.GET, "/api/room/{roomId}"),
                    arguments(HttpMethod.POST, "/api/room/{roomId}/leave"),
                    arguments(HttpMethod.POST, "/api/room/{roomId}/start-game"),
                    arguments(HttpMethod.GET, "/api/game/{roomId}/state"),
                    arguments(HttpMethod.GET, "/api/game/{roomId}/private-state"),
                    arguments(HttpMethod.GET, "/api/game/{roomId}/review"),
                    arguments(HttpMethod.POST, "/api/game/{roomId}/claim-win"));
        }

        @Test
        @DisplayName("anonymous create and join return bare TokenResponse payloads bound to the issued identity")
        void anonymousCreateAndJoinIssueJwt() {
            String roomName = uniqueName("PublicHttpRoom");

            TokenResponse created = createRoom(roomName, "NewPlayer", 6);
            assertThat(created.roomId()).isNotBlank();
            assertThat(created.playerName()).isEqualTo("NewPlayer");
            PlayerPrincipal createdPrincipal = jwtService.extractPrincipal(created.token());
            assertThat(createdPrincipal.playerName()).isEqualTo("NewPlayer");
            assertThat(createdPrincipal.roomId()).isEqualTo(created.roomId());

            TokenResponse joined = joinRoom(roomName, "JoiningPlayer");
            assertThat(joined.roomId()).isEqualTo(created.roomId());
            assertThat(joined.playerName()).isEqualTo("JoiningPlayer");
            PlayerPrincipal joinedPrincipal = jwtService.extractPrincipal(joined.token());
            assertThat(joinedPrincipal.playerName()).isEqualTo("JoiningPlayer");
            assertThat(joinedPrincipal.roomId()).isEqualTo(created.roomId());
            assertThat(joinedPrincipal.membershipId()).isNotEqualTo(createdPrincipal.membershipId());
        }

        @Test
        @DisplayName("missing or invalid bearer token returns 401 ProblemDetail JSON")
        void missingAndInvalidTokensReturn401ProblemDetail() {
            HttpReply missing = send(HttpMethod.GET, "/api/room/any-room-id", headers -> { }, null);
            assertProblem(missing, HttpStatus.UNAUTHORIZED, UNAUTHENTICATED_DETAIL);

            HttpReply invalid = send(HttpMethod.GET, "/api/room/any-room-id",
                    headers -> headers.setBearerAuth("invalid-or-expired-token"), null);
            assertProblem(invalid, HttpStatus.UNAUTHORIZED, UNAUTHENTICATED_DETAIL);
        }

        @Test
        @DisplayName("Authorization schemes other than Bearer are ignored and never authenticate")
        void nonBearerAuthorizationIsRejected() {
            TokenResponse host = createRoom(uniqueName("NonBearer"), "Host", 6);
            String uri = "/api/room/" + host.roomId();

            HttpReply basicCredentials = send(HttpMethod.GET, uri,
                    headers -> headers.set(HttpHeaders.AUTHORIZATION, "Basic dXNlcjpwYXNz"), null);
            assertProblem(basicCredentials, HttpStatus.UNAUTHORIZED, UNAUTHENTICATED_DETAIL);

            HttpReply validTokenUnderBasicScheme = send(HttpMethod.GET, uri,
                    headers -> headers.set(HttpHeaders.AUTHORIZATION, "Basic " + host.token()), null);
            assertProblem(validTokenUnderBasicScheme, HttpStatus.UNAUTHORIZED, UNAUTHENTICATED_DETAIL);

            HttpReply sameTokenAsBearer = send(HttpMethod.GET, uri,
                    headers -> headers.setBearerAuth(host.token()), null);
            assertThat(sameTokenAsBearer.status()).isEqualTo(HttpStatus.OK);
        }

        @ParameterizedTest(name = "{0} {1}")
        @MethodSource("protectedEndpoints")
        @DisplayName("protected room and game endpoints reject requests without a bearer token with 401")
        void missingTokensOnProtectedEndpointsReturn401(HttpMethod method, String uriTemplate) {
            TokenResponse host = createRoom(uniqueName("ProtectedEndpoints"), "TestHost", 6);

            HttpReply reply = send(method, uriTemplate.replace("{roomId}", host.roomId()), headers -> { }, null);

            assertProblem(reply, HttpStatus.UNAUTHORIZED, UNAUTHENTICATED_DETAIL);
        }

        @Test
        @DisplayName("only the health endpoint is reachable anonymously")
        void onlyActuatorHealthIsPublic() {
            HttpReply health = send(HttpMethod.GET, "/actuator/health", headers -> { }, null);
            assertThat(health.status()).isEqualTo(HttpStatus.OK);
            assertThat(objectMapper.readTree(health.body()).path("status").asString()).isEqualTo("UP");

            for (String path : new String[] {"/actuator/info", "/actuator/env"}) {
                HttpReply other = send(HttpMethod.GET, path, headers -> { }, null);
                assertProblem(other, HttpStatus.UNAUTHORIZED, UNAUTHENTICATED_DETAIL);
            }
        }

        @Test
        @DisplayName("leave room accepts valid membership JWT and rejects invalid JWT")
        void validAndInvalidTokensOnLeaveRoom() {
            String roomId = UUID.randomUUID().toString();
            roomService.createRoom(roomId, new CreateRoomRequest(
                    uniqueName("SecureLeave"), "TestHost", 6, 10, 20, 1000, null));
            trackedRoomIds.add(roomId);
            String membershipId = withRoomLock(roomCoordinator, roomId,
                    () -> roomService.getRoom(roomId).getMembershipId("TestHost"));
            String validToken = jwtService.generateToken("TestHost", roomId, membershipId);

            HttpReply invalid = send(HttpMethod.POST, "/api/room/" + roomId + "/leave",
                    headers -> headers.setBearerAuth("invalid-token-here"), null);
            assertProblem(invalid, HttpStatus.UNAUTHORIZED, UNAUTHENTICATED_DETAIL);
            assertThat(withRoomLock(roomCoordinator, roomId, () -> roomService.getRoom(roomId).hasPlayer("TestHost")))
                    .isTrue();

            HttpReply leave = send(HttpMethod.POST, "/api/room/" + roomId + "/leave",
                    headers -> headers.setBearerAuth(validToken), null);
            assertThat(leave.status()).isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(leave.body()).isEmpty();
        }

        @Test
        @DisplayName("start game rejects non-host token and succeeds for host with two players")
        void startGameAuthorization() {
            String roomName = uniqueName("AuthStartRoom");
            TokenResponse host = createRoom(roomName, "ActualHost", 6);
            TokenResponse nonHost = joinRoom(roomName, "NonHostPlayer");

            HttpReply nonHostRejected = send(HttpMethod.POST, "/api/room/" + host.roomId() + "/start-game",
                    headers -> headers.setBearerAuth(nonHost.token()), null);
            assertProblem(nonHostRejected, HttpStatus.FORBIDDEN, "Only the room host can start the game. Please ask the host to start.");
            assertThat(get("/api/room/" + host.roomId(), host.token()).path("gameStarted").asBoolean()).isFalse();

            HttpReply hostStart = send(HttpMethod.POST, "/api/room/" + host.roomId() + "/start-game",
                    headers -> headers.setBearerAuth(host.token()), null);
            assertThat(hostStart.status()).isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(get("/api/room/" + host.roomId(), host.token()).path("gameStarted").asBoolean()).isTrue();
        }

        @Test
        @DisplayName("same player name in room A cannot access room B")
        void playerInRoomACannotAccessRoomB() {
            TokenResponse aliceA = createRoom(uniqueName("RoomA"), "Alice", 6);
            TokenResponse aliceB = createRoom(uniqueName("RoomB"), "Alice", 6);

            HttpReply forbidden = send(HttpMethod.POST, "/api/room/" + aliceB.roomId() + "/leave",
                    headers -> headers.setBearerAuth(aliceA.token()), null);

            assertProblem(forbidden, HttpStatus.FORBIDDEN, "Token is not valid for this room.");
            assertThat(get("/api/room/" + aliceB.roomId(), aliceB.token()).path("players")).hasSize(1);
        }

        @Test
        @DisplayName("leaving and rejoining retires prior token on both room and game endpoints with 401")
        void leaveAndRejoinRetiresStaleTokenAcrossRoomAndGameEndpoints() {
            String roomName = uniqueName("StaleSessionRoom");
            TokenResponse host = createRoom(roomName, "HostPlayer", 6);
            String staleToken = joinRoom(roomName, "Alice").token();

            HttpReply leave = send(HttpMethod.POST, "/api/room/" + host.roomId() + "/leave",
                    headers -> headers.setBearerAuth(staleToken), null);
            assertThat(leave.status()).isEqualTo(HttpStatus.NO_CONTENT);

            String activeToken = joinRoom(roomName, "Alice").token();
            assertThat(activeToken).isNotEqualTo(staleToken);
            String staleMembershipDetail = "Token membership is stale or invalid.";

            HttpReply roomRejected = send(HttpMethod.GET, "/api/room/" + host.roomId(),
                    headers -> headers.setBearerAuth(staleToken), null);
            assertProblem(roomRejected, HttpStatus.UNAUTHORIZED, staleMembershipDetail);
            assertThat(get("/api/room/" + host.roomId(), activeToken).path("roomId").asString())
                    .isEqualTo(host.roomId());

            HttpReply start = send(HttpMethod.POST, "/api/room/" + host.roomId() + "/start-game",
                    headers -> headers.setBearerAuth(host.token()), null);
            assertThat(start.status()).isEqualTo(HttpStatus.NO_CONTENT);

            HttpReply gameRejected = send(HttpMethod.GET, "/api/game/" + host.roomId() + "/state",
                    headers -> headers.setBearerAuth(staleToken), null);
            assertProblem(gameRejected, HttpStatus.UNAUTHORIZED, staleMembershipDetail);
            assertThat(get("/api/game/" + host.roomId() + "/state", activeToken).path("players"))
                    .anyMatch(player -> "Alice".equals(player.path("name").asString()));
        }
    }

    @Nested
    @DisplayName("Input sanitization and lifecycle boundaries")
    class InputSanitizationAndValidation {

        @Test
        @DisplayName("HTML tags in room or player names return 400 ProblemDetail and create nothing")
        void htmlTagsReturn400() {
            String htmlDetail = "Input contains illegal HTML tags.";

            HttpReply htmlRoom = send(HttpMethod.POST, "/api/room/create", headers -> { },
                    new CreateRoomRequest("<script>alert('xss')</script>Room", "Player1", 6, 10, 20, 1000, null));
            assertProblem(htmlRoom, HttpStatus.BAD_REQUEST, htmlDetail);

            HttpReply htmlCreator = send(HttpMethod.POST, "/api/room/create", headers -> { },
                    new CreateRoomRequest("ValidRoom", "<b>Player</b>", 6, 10, 20, 1000, null));
            assertProblem(htmlCreator, HttpStatus.BAD_REQUEST, htmlDetail);

            String roomName = uniqueName("JoinSanitize");
            TokenResponse host = createRoom(roomName, "Host", 6);
            HttpReply htmlJoiner = send(HttpMethod.POST, "/api/room/join", headers -> { },
                    new JoinRoomRequest(roomName, "<i>Attacker</i>", null));
            assertProblem(htmlJoiner, HttpStatus.BAD_REQUEST, htmlDetail);

            assertThat(get("/api/room/" + host.roomId(), host.token()).path("players")).hasSize(1);
        }

        @Test
        @DisplayName("colon and whitespace in player names preserve identity safely between storage and JWT")
        void colonAndWhitespaceNamesSanitizedConsistently() {
            String spoofedName = "Alice:anything";
            TokenResponse colonData = createRoom(uniqueName("ColonRoom"), spoofedName, 6);
            assertThat(colonData.playerName()).isEqualTo(spoofedName);
            PlayerPrincipal colonPrincipal = jwtService.extractPrincipal(colonData.token());
            assertThat(colonPrincipal.playerName()).isEqualTo(spoofedName);
            assertThat(colonPrincipal.roomId()).isEqualTo(colonData.roomId());

            TokenResponse spaceData = createRoom(uniqueName("SpaceRoom"), "  Bob  ", 6);
            assertThat(spaceData.playerName()).isEqualTo("Bob");
            PlayerPrincipal spacePrincipal = jwtService.extractPrincipal(spaceData.token());
            assertThat(spacePrincipal.playerName()).isEqualTo("Bob");
        }

        @Test
        @DisplayName("late joins and duplicate start-game calls return 400 ProblemDetail")
        void joinAndDoubleStartAfterStartReturn400() {
            String roomName = uniqueName("StartedBoundary");
            TokenResponse host = createRoom(roomName, "Host", 6);
            joinRoom(roomName, "Player2");
            String startUri = "/api/room/" + host.roomId() + "/start-game";
            assertThat(send(HttpMethod.POST, startUri, headers -> headers.setBearerAuth(host.token()), null).status())
                    .isEqualTo(HttpStatus.NO_CONTENT);

            HttpReply lateJoin = send(HttpMethod.POST, "/api/room/join", headers -> { },
                    new JoinRoomRequest(roomName, "LateComer", null));
            assertProblem(lateJoin, HttpStatus.BAD_REQUEST, "Cannot join room. The game has already started.");

            HttpReply secondStart = send(HttpMethod.POST, startUri,
                    headers -> headers.setBearerAuth(host.token()), null);
            assertProblem(secondStart, HttpStatus.BAD_REQUEST, "Game has already started for this room.");
        }
    }

    @Nested
    @DisplayName("Filter chain ordering and rate limiting")
    class FilterChainAndRateLimiting {

        private static final int OVERSIZED_NAME_LENGTH = 11_000;

        @Test
        @DisplayName("oversized payload returns 413 ProblemDetail before rate limiting or JWT validation runs")
        void oversizedPayloadRejectedWith413BeforeRateLimitAndJwt() {
            String largeBody = "{\"roomName\":\"" + "A".repeat(OVERSIZED_NAME_LENGTH) + "\"}";

            // More oversized requests than the REST bucket holds, all with a malformed JWT: if the payload filter
            // ran after rate limiting or JWT validation, some would return 429 or 401 instead of 413.
            for (int i = 0; i <= RateLimitService.REST_CAPACITY; i++) {
                HttpReply tooLarge = send(HttpMethod.POST, "/api/room/create",
                        headers -> headers.setBearerAuth("malformed.invalid.token"), largeBody);
                assertProblem(tooLarge, HttpStatus.CONTENT_TOO_LARGE,
                        "Request payload too large. Maximum allowed is 10KB.");
            }

            // The /api/room/create bucket must still have full capacity afterwards.
            assertThat(createRoom(uniqueName("Post413Room"), "Host", 6).roomId()).isNotBlank();
        }

        @Test
        @DisplayName("chunked oversized body without Content-Length returns 413 ProblemDetail, not 400")
        void chunkedOversizedBodyReturns413() throws Exception {
            byte[] oversized = ("{\"roomName\":\"" + "A".repeat(OVERSIZED_NAME_LENGTH) + "\"}")
                    .getBytes(StandardCharsets.UTF_8);

            HttpResponse<String> response = sendChunkedCreate(oversized);

            assertThat(response.statusCode()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE.value());
            assertThat(response.headers().firstValue(HttpHeaders.CONTENT_TYPE).orElse(""))
                    .startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            JsonNode problem = objectMapper.readTree(response.body());
            assertThat(problem.path("status").asInt()).isEqualTo(413);
            assertThat(problem.path("title").asString()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE.getReasonPhrase());
            assertThat(problem.path("detail").asString()).isEqualTo("Request payload too large. Maximum allowed is 10KB.");
        }

        @Test
        @DisplayName("chunked body within the size limit is still delivered intact to the controller")
        void chunkedBodyWithinLimitCreatesRoom() throws Exception {
            String roomName = uniqueName("ChunkedRoom");
            byte[] body = objectMapper.writeValueAsBytes(
                    new CreateRoomRequest(roomName, "ChunkedHost", 6, 10, 20, 1000, null));

            HttpResponse<String> response = sendChunkedCreate(body);

            assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
            TokenResponse created = objectMapper.readValue(response.body(), TokenResponse.class);
            trackedRoomIds.add(created.roomId());
            assertThat(created.playerName()).isEqualTo("ChunkedHost");
            assertThat(get("/api/room/" + created.roomId(), created.token()).path("roomName").asString())
                    .isEqualTo(roomName);
        }

        @Test
        @DisplayName("rate limit rejection returns 429 ProblemDetail before JWT validation runs")
        void rateLimitExhaustionReturns429BeforeJwtValidation() {
            CreateRoomRequest invalid = new CreateRoomRequest("A".repeat(51), "Player", 6, 10, 20, 1000, null);

            for (int i = 0; i < RateLimitService.REST_CAPACITY; i++) {
                HttpReply validationError = send(HttpMethod.POST, "/api/room/create", headers -> { }, invalid);
                assertProblem(validationError, HttpStatus.BAD_REQUEST, "Room name must be 50 characters or less");
            }

            // The next request, with a malformed JWT, must fail with 429 from EndpointRateLimitFilter before
            // JwtAuthenticationFilter would have answered 401.
            HttpReply rateLimited = send(HttpMethod.POST, "/api/room/create",
                    headers -> headers.setBearerAuth("malformed.invalid.token"), invalid);

            String retryAfter = rateLimited.headers().getFirst(HttpHeaders.RETRY_AFTER);
            assertThat(retryAfter).isNotNull();
            assertProblem(rateLimited, HttpStatus.TOO_MANY_REQUESTS,
                    RateLimitService.tooManyRequestsMessage(Long.parseLong(retryAfter)));
        }

        private HttpResponse<String> sendChunkedCreate(byte[] body) throws Exception {
            HttpRequest.BodyPublisher chunked = HttpRequest.BodyPublishers.ofInputStream(
                    () -> new ByteArrayInputStream(body));
            // An unknown content length makes the JDK client send Transfer-Encoding: chunked.
            assertThat(chunked.contentLength()).isEqualTo(-1L);
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/room/create"))
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .POST(chunked)
                    .build();
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    @Nested
    @DisplayName("Cross-origin (CORS) policy")
    class CrossOriginPolicy {

        @Test
        @DisplayName("preflight from an allowed origin is answered without a token and advertises POST and the auth headers")
        void allowedOriginPreflightIsAccepted() {
            HttpReply preflight = send(HttpMethod.OPTIONS, "/api/room/create", headers -> {
                headers.set(HttpHeaders.ORIGIN, ALLOWED_ORIGIN);
                headers.set(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST");
                headers.set(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type");
            }, null);

            assertThat(preflight.status()).isEqualTo(HttpStatus.OK);
            assertThat(preflight.headers().getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isEqualTo(ALLOWED_ORIGIN);
            assertThat(preflight.headers().getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS)).contains("POST");
            assertThat(preflight.headers().getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS))
                    .containsIgnoringCase("authorization")
                    .containsIgnoringCase("content-type");
        }

        @Test
        @DisplayName("preflight requests do not use up the room creation rate limit")
        void preflightsDoNotConsumeTheRoomCreationAllowance() {
            for (int i = 0; i <= RateLimitService.REST_CAPACITY; i++) {
                HttpReply preflight = send(HttpMethod.OPTIONS, "/api/room/create", headers -> {
                    headers.set(HttpHeaders.ORIGIN, ALLOWED_ORIGIN);
                    headers.set(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST");
                }, null);
                assertThat(preflight.status()).isEqualTo(HttpStatus.OK);
            }

            TokenResponse created = createRoom(uniqueName("Preflight"), "Host", 6);

            assertThat(created.roomId()).isNotBlank();
        }

        @Test
        @DisplayName("preflight from a disallowed origin is rejected with no CORS grant headers")
        void disallowedOriginPreflightIsRejected() {
            HttpReply preflight = send(HttpMethod.OPTIONS, "/api/room/create", headers -> {
                headers.set(HttpHeaders.ORIGIN, DISALLOWED_ORIGIN);
                headers.set(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST");
            }, null);

            assertThat(preflight.status()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(preflight.headers().getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isNull();
        }

        @Test
        @DisplayName("preflight from an allowed origin for a method outside GET, POST, OPTIONS is rejected")
        void disallowedMethodPreflightIsRejected() {
            HttpReply preflight = send(HttpMethod.OPTIONS, "/api/room/create", headers -> {
                headers.set(HttpHeaders.ORIGIN, ALLOWED_ORIGIN);
                headers.set(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "DELETE");
            }, null);

            assertThat(preflight.status()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(preflight.headers().getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isNull();
        }

        @Test
        @DisplayName("actual cross-origin request from an allowed origin is granted and exposes the Authorization header")
        void allowedOriginActualRequestExposesAuthorizationHeader() {
            HttpReply created = send(HttpMethod.POST, "/api/room/create",
                    headers -> headers.set(HttpHeaders.ORIGIN, ALLOWED_ORIGIN),
                    new CreateRoomRequest(uniqueName("CorsRoom"), "CorsHost", 6, 10, 20, 1000, null));
            trackedRoomIds.add(objectMapper.readTree(created.body()).path("roomId").asString());

            assertThat(created.status()).isEqualTo(HttpStatus.OK);
            assertThat(created.headers().getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isEqualTo(ALLOWED_ORIGIN);
            assertThat(created.headers().getFirst(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS))
                    .containsIgnoringCase("Authorization");
        }

        @Test
        @DisplayName("actual cross-origin request from a disallowed origin is rejected and never reaches the controller")
        void disallowedOriginActualRequestIsRejected() {
            String roomName = uniqueName("CorsBlocked");

            HttpReply blocked = send(HttpMethod.POST, "/api/room/create",
                    headers -> headers.set(HttpHeaders.ORIGIN, DISALLOWED_ORIGIN),
                    new CreateRoomRequest(roomName, "Intruder", 6, 10, 20, 1000, null));

            assertThat(blocked.status()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(blocked.headers().getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isNull();
            assertProblem(send(HttpMethod.POST, "/api/room/join", headers -> { },
                    new JoinRoomRequest(roomName, "Prober", null)), HttpStatus.NOT_FOUND, "Room not found");
        }
    }

    @Nested
    @DisplayName("Concurrent admissions")
    class ConcurrentAdmissions {

        @Test
        @DisplayName("concurrent HTTP joins respect maxPlayers capacity without oversubscription")
        void concurrentJoinsRespectMaxPlayersCap() throws Exception {
            String roomName = uniqueName("ConcurrentCap");
            TokenResponse host = createRoom(roomName, "Host", 2);
            int attempts = 5; // Within the REST /api/room/join limit (RateLimitService.REST_CAPACITY per minute)
            CyclicBarrier ready = new CyclicBarrier(attempts);
            List<Future<String>> outcomes = new ArrayList<>();

            ExecutorService executor = Executors.newFixedThreadPool(attempts);
            try {
                for (int i = 0; i < attempts; i++) {
                    String player = "Player" + i;
                    outcomes.add(executor.submit(() -> {
                        ready.await(5, TimeUnit.SECONDS);
                        HttpReply reply = send(HttpMethod.POST, "/api/room/join", headers -> { },
                                new JoinRoomRequest(roomName, player, null));
                        if (reply.status().is2xxSuccessful()) {
                            return player;
                        }
                        assertProblem(reply, HttpStatus.BAD_REQUEST, "Room is full");
                        return null;
                    }));
                }

                List<String> admitted = new ArrayList<>();
                for (Future<String> outcome : outcomes) {
                    String player = outcome.get(10, TimeUnit.SECONDS);
                    if (player != null) {
                        admitted.add(player);
                    }
                }
                assertThat(admitted).hasSize(1);
                JsonNode room = get("/api/room/" + host.roomId(), host.token());
                assertThat(room.path("players")).hasSize(2);
                assertThat(room.path("players")).extracting(p -> p.path("name").asString())
                        .containsExactlyInAnyOrder("Host", admitted.getFirst());
            } finally {
                executor.shutdownNow();
            }
        }
    }

    private static String uniqueName(String prefix) {
        return prefix + "-" + UNIQUE_COUNTER.incrementAndGet();
    }

    private TokenResponse createRoom(String roomName, String hostName, int maxPlayers) {
        TokenResponse created = restClient.post()
                .uri("/api/room/create")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new CreateRoomRequest(roomName, hostName, maxPlayers, 10, 20, 1000, null))
                .retrieve()
                .body(TokenResponse.class);
        assertThat(created).isNotNull();
        trackedRoomIds.add(created.roomId());
        return created;
    }

    private TokenResponse joinRoom(String roomName, String playerName) {
        TokenResponse joined = restClient.post()
                .uri("/api/room/join")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new JoinRoomRequest(roomName, playerName, null))
                .retrieve()
                .body(TokenResponse.class);
        assertThat(joined).isNotNull();
        return joined;
    }

    // Authenticated GET that must succeed; returns the parsed JSON body.
    private JsonNode get(String uri, String token) {
        HttpReply reply = send(HttpMethod.GET, uri, headers -> headers.setBearerAuth(token), null);
        assertThat(reply.status()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(reply.body());
    }

    // Sends a request and returns the response without throwing on 4xx/5xx.
    private HttpReply send(HttpMethod method, String uri, Consumer<HttpHeaders> headers, Object body) {
        RestClient.RequestBodySpec request = restClient.method(method).uri(uri).headers(headers);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return request.exchange((ignored, response) -> new HttpReply(
                response.getStatusCode(),
                response.getHeaders(),
                Objects.requireNonNullElse(response.bodyTo(String.class), "")));
    }

    private void assertProblem(HttpReply reply, HttpStatus expectedStatus, String expectedDetail) {
        assertThat(reply.status()).isEqualTo(expectedStatus);
        assertThat(reply.headers().getFirst(HttpHeaders.CONTENT_TYPE))
                .startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        JsonNode problem = objectMapper.readTree(reply.body());
        assertThat(problem.path("status").asInt()).isEqualTo(expectedStatus.value());
        assertThat(problem.path("title").asString()).isEqualTo(expectedStatus.getReasonPhrase());
        assertThat(problem.path("detail").asString()).isEqualTo(expectedDetail);
    }

    private record HttpReply(HttpStatusCode status, HttpHeaders headers, String body) { }
}
