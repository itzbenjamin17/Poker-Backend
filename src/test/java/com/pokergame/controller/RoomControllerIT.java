package com.pokergame.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pokergame.dto.request.CreateRoomRequest;
import com.pokergame.dto.request.JoinRoomRequest;
import com.pokergame.dto.response.RoomDataResponse;
import com.pokergame.exception.InvalidRoomPasswordException;
import com.pokergame.exception.ResourceNotFoundException;
import com.pokergame.model.Room;
import com.pokergame.security.RateLimitService;
import com.pokergame.service.RoomService;
import com.pokergame.util.RoomPasswordPolicy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Tests the room REST endpoints through the web layer with mocked services and the real room lock. */
@Tag("integration")
class RoomControllerIT extends AbstractControllerSliceIT {
    private static final String ROOM_ID = "room-123";
    private static final String HOST = "HostPlayer";
    private static final String PLAYER = "Player1";
    private static final String STALE_MEMBERSHIP_MESSAGE = "Token membership is stale or invalid.";
    /** 25 CJK characters are 75 UTF-8 bytes: within the 50-character limit but over BCrypt's 72-byte limit. */
    private static final String OVERSIZED_UNICODE_PASSWORD = "\u6F22".repeat(25);

    private final AtomicBoolean lockHeldDuringService = new AtomicBoolean();

    /** The secured room endpoints that bind the token to the room and validate membership. */
    private enum SecuredRoomEndpoint {
        LEAVE("POST /api/room/{roomId}/leave") {
            @Override
            MockHttpServletRequestBuilder request(String roomId) {
                return post("/api/room/{roomId}/leave", roomId);
            }
        },
        START_GAME("POST /api/room/{roomId}/start-game") {
            @Override
            MockHttpServletRequestBuilder request(String roomId) {
                return post("/api/room/{roomId}/start-game", roomId);
            }
        },
        ROOM_INFO("GET /api/room/{roomId}") {
            @Override
            MockHttpServletRequestBuilder request(String roomId) {
                return get("/api/room/{roomId}", roomId);
            }
        };

        private final String label;

        SecuredRoomEndpoint(String label) {
            this.label = label;
        }

        abstract MockHttpServletRequestBuilder request(String roomId);

        @Override
        public String toString() {
            return label;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Create and join
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("POST /api/room/create - Issues a token for the canonical name and admitted membership")
    void createRoom_ValidRequest_ReturnsTokenForCanonicalNameAndMembership() throws Exception {
        CreateRoomRequest request = new CreateRoomRequest("Room1", "  Player1  ", 6, 10, 20, 1000, null);
        String membershipId = newRoom().addPlayer(PLAYER);
        when(roomService.createRoom(anyString(), any(CreateRoomRequest.class)))
                .thenReturn(new RoomService.EntryResult(ROOM_ID, PLAYER, membershipId));
        when(jwtService.generateToken(PLAYER, ROOM_ID, membershipId)).thenReturn("mock-token");

        mockMvc.perform(post("/api/room/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("mock-token"))
                .andExpect(jsonPath("$.roomId").value(ROOM_ID))
                .andExpect(jsonPath("$.playerName").value(PLAYER));

        ArgumentCaptor<CreateRoomRequest> passed = ArgumentCaptor.forClass(CreateRoomRequest.class);
        verify(roomService).createRoom(anyString(), passed.capture());
        assertThat(passed.getValue()).isEqualTo(request);
        verify(roomService, never()).getRoom(anyString());
    }

    @Test
    @DisplayName("POST /api/room/join - Issues a token for the canonical name and admitted membership")
    void joinRoom_ValidRequest_ReturnsTokenForCanonicalNameAndMembership() throws Exception {
        JoinRoomRequest request = new JoinRoomRequest("Room1", "  Player2  ", null);
        Room room = newRoom();
        room.addPlayer(HOST);
        String membershipId = room.addPlayer("Player2");
        when(roomService.resolveRoomIdByName("Room1")).thenReturn(ROOM_ID);
        when(roomService.joinRoom(eq(ROOM_ID), any(JoinRoomRequest.class))).thenAnswer(
                recordingLockHeld(ROOM_ID, lockHeldDuringService,
                        new RoomService.EntryResult(ROOM_ID, "Player2", membershipId)));
        when(jwtService.generateToken("Player2", ROOM_ID, membershipId)).thenReturn("mock-token");

        mockMvc.perform(post("/api/room/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("mock-token"))
                .andExpect(jsonPath("$.roomId").value(ROOM_ID))
                .andExpect(jsonPath("$.playerName").value("Player2"));

        ArgumentCaptor<JoinRoomRequest> passed = ArgumentCaptor.forClass(JoinRoomRequest.class);
        verify(roomService).joinRoom(eq(ROOM_ID), passed.capture());
        assertThat(passed.getValue()).isEqualTo(request);
        assertThat(lockHeldDuringService).isTrue();
        verify(roomService, never()).getRoom(anyString());
    }

    @Test
    @DisplayName("POST /api/room/join - Unknown room name returns 404 without joining")
    void joinRoom_UnknownRoomName_ReturnsNotFound() throws Exception {
        when(roomService.resolveRoomIdByName("Nowhere")).thenThrow(new ResourceNotFoundException("Room not found"));

        mockMvc.perform(post("/api/room/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new JoinRoomRequest("Nowhere", PLAYER, null))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Room not found"));

        verify(roomService, never()).joinRoom(anyString(), any(JoinRoomRequest.class));
        verifyNoInteractions(jwtService);
    }

    @Test
    @DisplayName("POST /api/room/join - Closes expired lobby before join and returns 400")
    void joinRoom_ExpiredLobby_ClosesBeforeJoinAndReturnsBadRequest() throws Exception {
        when(roomService.resolveRoomIdByName("Room1")).thenReturn(ROOM_ID);
        when(roomService.closeLobbyIfExpired(ROOM_ID)).thenReturn(true);

        mockMvc.perform(post("/api/room/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new JoinRoomRequest("Room1", "Player2", null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Room has expired due to inactivity."));

        verify(roomService, never()).joinRoom(anyString(), any(JoinRoomRequest.class));
        verifyNoInteractions(jwtService);
    }

    /** Blank passwords mean "no password"; every other password reaches the service exactly as typed. */
    private static Stream<Arguments> passwordNormalisationCases() {
        return Stream.of(
                Arguments.of("   ", null),
                Arguments.of("  secret123  ", "  secret123  "));
    }

    @ParameterizedTest(name = "password [{0}] reaches the service as [{1}]")
    @MethodSource("passwordNormalisationCases")
    @DisplayName("POST /api/room/create - Blank password is dropped and other passwords keep their spaces")
    void createRoom_Password_NormalisedBeforeReachingService(String sentPassword, String expectedPassword)
            throws Exception {
        when(roomService.createRoom(anyString(), any(CreateRoomRequest.class)))
                .thenReturn(new RoomService.EntryResult(ROOM_ID, PLAYER, "mem-1"));
        when(jwtService.generateToken(PLAYER, ROOM_ID, "mem-1")).thenReturn("mock-token");

        mockMvc.perform(post("/api/room/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "roomName", "Room1", "playerName", PLAYER, "maxPlayers", 6,
                                "smallBlind", 10, "bigBlind", 20, "buyIn", 1000, "password", sentPassword))))
                .andExpect(status().isOk());

        ArgumentCaptor<CreateRoomRequest> passed = ArgumentCaptor.forClass(CreateRoomRequest.class);
        verify(roomService).createRoom(anyString(), passed.capture());
        assertThat(passed.getValue().password()).isEqualTo(expectedPassword);
        assertThat(passed.getValue().hasPassword()).isEqualTo(expectedPassword != null);
    }

    @ParameterizedTest(name = "password [{0}] reaches the service as [{1}]")
    @MethodSource("passwordNormalisationCases")
    @DisplayName("POST /api/room/join - Blank password is dropped and other passwords keep their spaces")
    void joinRoom_Password_NormalisedBeforeReachingService(String sentPassword, String expectedPassword)
            throws Exception {
        when(roomService.resolveRoomIdByName("Room1")).thenReturn(ROOM_ID);
        when(roomService.joinRoom(eq(ROOM_ID), any(JoinRoomRequest.class)))
                .thenReturn(new RoomService.EntryResult(ROOM_ID, "Player2", "mem-2"));
        when(jwtService.generateToken("Player2", ROOM_ID, "mem-2")).thenReturn("mock-token");

        mockMvc.perform(post("/api/room/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "roomName", "Room1", "playerName", "Player2", "password", sentPassword))))
                .andExpect(status().isOk());

        ArgumentCaptor<JoinRoomRequest> passed = ArgumentCaptor.forClass(JoinRoomRequest.class);
        verify(roomService).joinRoom(eq(ROOM_ID), passed.capture());
        assertThat(passed.getValue().password()).isEqualTo(expectedPassword);
    }

    /**
     * Repeated wrong passwords from one IP lock that IP out of that room only, even with
     * a fresh player name each time; a correct password is then refused too until the wait ends.
     */
    @Test
    @DisplayName("POST /api/room/join - Repeated wrong passwords lock the room for that IP")
    void joinRoom_RepeatedWrongPasswords_LockRoomForThatIp() throws Exception {
        when(roomService.resolveRoomIdByName("Locked")).thenReturn("room-locked");
        when(roomService.resolveRoomIdByName("Other")).thenReturn("room-other");
        when(roomService.joinRoom(eq("room-locked"), any(JoinRoomRequest.class)))
                .thenThrow(new InvalidRoomPasswordException());
        when(roomService.joinRoom(eq("room-other"), any(JoinRoomRequest.class)))
                .thenReturn(new RoomService.EntryResult("room-other", "Guest", "mem-2"));

        for (int i = 0; i < RateLimitService.JOIN_PASSWORD_FAILURES; i++) {
            mockMvc.perform(post("/api/room/join").contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new JoinRoomRequest("Locked", "Bot" + i, "guess" + i))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value("Invalid password"));
        }

        mockMvc.perform(post("/api/room/join").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new JoinRoomRequest("Locked", "Fresh", "right"))))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", matchesPattern("[1-9]\\d*")))
                .andExpect(jsonPath("$.detail").value(startsWith("Too many requests. Please try again in ")));

        // The locked attempt is refused before the service sees the (correct) password.
        verify(roomService, times(RateLimitService.JOIN_PASSWORD_FAILURES))
                .joinRoom(eq("room-locked"), any(JoinRoomRequest.class));

        // The budget refills one attempt at a time: after one refill interval a single guess gets through
        // to the service (and fails), then the room is locked again.
        clock.advanceByMillis(RateLimitService.JOIN_PASSWORD_WINDOW.toMillis()
                / RateLimitService.JOIN_PASSWORD_FAILURES);
        mockMvc.perform(post("/api/room/join").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new JoinRoomRequest("Locked", "Retry", "guess"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Invalid password"));
        mockMvc.perform(post("/api/room/join").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new JoinRoomRequest("Locked", "Fresh", "right"))))
                .andExpect(status().isTooManyRequests());
        verify(roomService, times(RateLimitService.JOIN_PASSWORD_FAILURES + 1))
                .joinRoom(eq("room-locked"), any(JoinRoomRequest.class));

        mockMvc.perform(post("/api/room/join").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new JoinRoomRequest("Other", "Guest", null))))
                .andExpect(status().isOk());
    }

    // ---------------------------------------------------------------------------------------------
    // Request validation (rejected before any service is reached)
    // ---------------------------------------------------------------------------------------------

    private static Stream<Arguments> invalidCreateRequests() {
        return Stream.of(
                Arguments.of("oversized room name",
                        new CreateRoomRequest("A".repeat(51), PLAYER, 6, 10, 20, 1000, null),
                        "Room name must be 50 characters or less"),
                Arguments.of("control character in room name",
                        new CreateRoomRequest("Room" + (char) 7, PLAYER, 6, 10, 20, 1000, null),
                        "Room name cannot contain control characters"),
                Arguments.of("format character in room name",
                        new CreateRoomRequest("Room\u200B1", PLAYER, 6, 10, 20, 1000, null),
                        "Input contains illegal format characters."),
                Arguments.of("format character in player name",
                        new CreateRoomRequest("Room1", "Player\u200D1", 6, 10, 20, 1000, null),
                        "Input contains illegal format characters."),
                Arguments.of("HTML tags in room name",
                        new CreateRoomRequest("<b>Room</b>", PLAYER, 6, 10, 20, 1000, null),
                        "Input contains illegal HTML tags."),
                Arguments.of("HTML tags in player name",
                        new CreateRoomRequest("Room1", "<script>alert(1)</script>", 6, 10, 20, 1000, null),
                        "Input contains illegal HTML tags."),
                Arguments.of("single-seat room",
                        new CreateRoomRequest("Room1", PLAYER, 1, 10, 20, 1000, null),
                        "Minimum 2 players required"),
                Arguments.of("big blind below twice the small blind",
                        new CreateRoomRequest("Room1", PLAYER, 4, 10, 15, 100, null),
                        "Big blind must be at least twice the small blind."),
                Arguments.of("buy-in below the big blind",
                        new CreateRoomRequest("Room1", PLAYER, 4, 10, 50, 30, null),
                        "Buy-in must be at least the big blind amount."),
                Arguments.of("password over 72 UTF-8 bytes",
                        new CreateRoomRequest("Room1", PLAYER, 4, 5, 10, 100, OVERSIZED_UNICODE_PASSWORD),
                        RoomPasswordPolicy.BYTE_LIMIT_MESSAGE));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidCreateRequests")
    @DisplayName("POST /api/room/create - Invalid request returns 400 before the service is invoked")
    void createRoom_InvalidRequest_ReturnsBadRequestBeforeServiceInvocation(
            String ignoredCase, CreateRoomRequest request, String expectedDetail) throws Exception {
        mockMvc.perform(post("/api/room/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(expectedDetail));

        verifyNoInteractions(roomService, jwtService);
    }

    private static Stream<Arguments> invalidJoinRequests() {
        return Stream.of(
                Arguments.of("oversized player name",
                        new JoinRoomRequest("Room1", "A".repeat(31), null),
                        "Player name must be 30 characters or less"),
                Arguments.of("format character in room name",
                        new JoinRoomRequest("Room\u200B1", PLAYER, null),
                        "Input contains illegal format characters."),
                Arguments.of("format character in player name",
                        new JoinRoomRequest("Room1", "Player\u200D1", null),
                        "Input contains illegal format characters."),
                Arguments.of("HTML tags in room name",
                        new JoinRoomRequest("<b>Room</b>", PLAYER, null),
                        "Input contains illegal HTML tags."),
                Arguments.of("HTML tags in player name",
                        new JoinRoomRequest("Room1", "<i>Player</i>", null),
                        "Input contains illegal HTML tags."),
                Arguments.of("password over 72 UTF-8 bytes",
                        new JoinRoomRequest("Room1", PLAYER, OVERSIZED_UNICODE_PASSWORD),
                        RoomPasswordPolicy.BYTE_LIMIT_MESSAGE));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidJoinRequests")
    @DisplayName("POST /api/room/join - Invalid request returns 400 before the service is invoked")
    void joinRoom_InvalidRequest_ReturnsBadRequestBeforeServiceInvocation(
            String ignoredCase, JoinRoomRequest request, String expectedDetail) throws Exception {
        mockMvc.perform(post("/api/room/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(expectedDetail));

        verifyNoInteractions(roomService, jwtService);
    }

    @Test
    @DisplayName("POST /api/room/create - Malformed JSON returns 400 before the service is invoked")
    void createRoom_MalformedJson_ReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/room/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"roomName\": \"Room1\", \"maxPlayers\": \"not-a-number\" }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Malformed JSON request payload"));

        verifyNoInteractions(roomService);
    }

    // ---------------------------------------------------------------------------------------------
    // Leave
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("POST /api/room/{roomId}/leave - Valid member is removed and receives 204")
    void leaveRoom_ValidMember_RemovesPlayerAndReturnsNoContent() throws Exception {
        String membershipId = newRoom().addPlayer(PLAYER);
        doAnswer(recordingLockHeld(ROOM_ID, lockHeldDuringService, null))
                .when(gameLifecycleService).removePlayers(ROOM_ID, List.of(PLAYER));

        mockMvc.perform(post("/api/room/{roomId}/leave", ROOM_ID)
                        .principal(authenticated(PLAYER, ROOM_ID, membershipId)))
                .andExpect(status().isNoContent());

        verify(gameLifecycleService).validatePlayerMembership(ROOM_ID, PLAYER, membershipId);
        verify(gameLifecycleService).removePlayers(ROOM_ID, List.of(PLAYER));
        assertThat(lockHeldDuringService).isTrue();
    }

    @Test
    @DisplayName("POST /api/room/{roomId}/leave - Closes expired lobby and returns 404")
    void leaveRoom_ExpiredLobby_ClosesAndReturnsNotFound() throws Exception {
        when(roomService.closeLobbyIfExpired(ROOM_ID)).thenReturn(true);

        mockMvc.perform(post("/api/room/{roomId}/leave", ROOM_ID)
                        .principal(authenticated(PLAYER, ROOM_ID, "mem-1")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Room not found"));

        verifyNoInteractions(gameLifecycleService);
    }

    // ---------------------------------------------------------------------------------------------
    // Room info
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("GET /api/room/{roomId} - Member receives the room data")
    void getRoomInfo_Member_ReturnsRoomData() throws Exception {
        Room room = newRoom();
        String membershipId = room.addPlayer(HOST);
        when(roomService.getRoom(ROOM_ID)).thenReturn(room);
        RoomDataResponse roomData = new RoomDataResponse(
                ROOM_ID, "Room1", 6, 1000, 10, 20,
                List.of(new RoomDataResponse.RoomPlayer(HOST, true)), true, false, null, null, null);
        when(roomService.getRoomData(ROOM_ID)).thenAnswer(recordingLockHeld(ROOM_ID, lockHeldDuringService, roomData));

        mockMvc.perform(get("/api/room/{roomId}", ROOM_ID)
                        .principal(authenticated(HOST, ROOM_ID, membershipId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roomId").value(ROOM_ID))
                .andExpect(jsonPath("$.roomName").value("Room1"))
                .andExpect(jsonPath("$.players[0].name").value(HOST))
                .andExpect(jsonPath("$.players[0].isHost").value(true));

        verify(gameLifecycleService).validatePlayerMembership(ROOM_ID, HOST, membershipId);
        assertThat(lockHeldDuringService).isTrue();
    }

    @Test
    @DisplayName("GET /api/room/{roomId} - Unknown room returns 404")
    void getRoomInfo_UnknownRoom_ReturnsNotFound() throws Exception {
        when(roomService.getRoom("unknown")).thenReturn(null);

        mockMvc.perform(get("/api/room/{roomId}", "unknown")
                        .principal(authenticated(PLAYER, "unknown", "mem-1")))
                .andExpect(status().isNotFound());

        verify(roomService, never()).getRoomData(anyString());
    }

    @Test
    @DisplayName("GET /api/room/{roomId} - Closes expired lobby and returns 404")
    void getRoomInfo_ExpiredLobby_ClosesAndReturnsNotFound() throws Exception {
        when(roomService.closeLobbyIfExpired(ROOM_ID)).thenReturn(true);

        mockMvc.perform(get("/api/room/{roomId}", ROOM_ID)
                        .principal(authenticated(PLAYER, ROOM_ID, "mem-1")))
                .andExpect(status().isNotFound());

        verify(roomService, never()).getRoomData(anyString());
        verifyNoInteractions(gameLifecycleService);
    }

    @Test
    @DisplayName("GET /api/room/{roomId} - Authenticated non-member returns 403")
    void getRoomInfo_NonMember_ReturnsForbidden() throws Exception {
        Room room = newRoom();
        room.addPlayer(HOST);
        when(roomService.getRoom(ROOM_ID)).thenReturn(room);

        mockMvc.perform(get("/api/room/{roomId}", ROOM_ID)
                        .principal(authenticated("NonMemberPlayer", ROOM_ID, "mem-9")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Forbidden"))
                .andExpect(jsonPath("$.detail").value("You are not a member of this room."));

        verify(roomService, never()).getRoomData(anyString());
    }

    // ---------------------------------------------------------------------------------------------
    // Start game
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("POST /api/room/{roomId}/start-game - Host starts the game and receives 204")
    void startGame_Host_ReturnsNoContent() throws Exception {
        String membershipId = newRoom().addPlayer(HOST);
        when(roomService.isRoomHost(ROOM_ID, HOST)).thenReturn(true);
        when(gameLifecycleService.createGameFromRoom(ROOM_ID))
                .thenAnswer(recordingLockHeld(ROOM_ID, lockHeldDuringService, "game-123"));

        mockMvc.perform(post("/api/room/{roomId}/start-game", ROOM_ID)
                        .principal(authenticated(HOST, ROOM_ID, membershipId)))
                .andExpect(status().isNoContent());

        verify(gameLifecycleService).validatePlayerMembership(ROOM_ID, HOST, membershipId);
        verify(gameLifecycleService).createGameFromRoom(ROOM_ID);
        assertThat(lockHeldDuringService).isTrue();
    }

    @Test
    @DisplayName("POST /api/room/{roomId}/start-game - Closes expired lobby and returns 404")
    void startGame_ExpiredLobby_ClosesAndReturnsNotFound() throws Exception {
        when(roomService.closeLobbyIfExpired(ROOM_ID)).thenReturn(true);

        mockMvc.perform(post("/api/room/{roomId}/start-game", ROOM_ID)
                        .principal(authenticated(HOST, ROOM_ID, "mem-1")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Room not found"));

        verifyNoInteractions(gameLifecycleService);
    }

    @Test
    @DisplayName("POST /api/room/{roomId}/start-game - Non-host returns 403 and no game is created")
    void startGame_NonHost_ReturnsForbidden() throws Exception {
        when(roomService.isRoomHost(ROOM_ID, "GuestPlayer")).thenReturn(false);

        mockMvc.perform(post("/api/room/{roomId}/start-game", ROOM_ID)
                        .principal(authenticated("GuestPlayer", ROOM_ID, "mem-2")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Forbidden"))
                .andExpect(jsonPath("$.detail")
                        .value("Only the room host can start the game. Please ask the host to start."));

        verify(gameLifecycleService, never()).createGameFromRoom(anyString());
    }

    // ---------------------------------------------------------------------------------------------
    // Token binding and membership across the secured endpoints
    // ---------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @EnumSource(SecuredRoomEndpoint.class)
    @DisplayName("Secured room endpoints - Token issued for another room returns 403 before any service call")
    void securedEndpoint_TokenForAnotherRoom_ReturnsForbiddenBeforeAnyServiceCall(SecuredRoomEndpoint endpoint)
            throws Exception {
        mockMvc.perform(endpoint.request(ROOM_ID)
                        .principal(authenticated(PLAYER, "different-room-456", "mem-1")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Forbidden"))
                .andExpect(jsonPath("$.detail").value("Token is not valid for this room."));

        verifyNoInteractions(roomService, gameLifecycleService);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(SecuredRoomEndpoint.class)
    @DisplayName("Secured room endpoints - Stale membership token returns 401 and nothing else runs")
    void securedEndpoint_StaleMembershipToken_ReturnsUnauthorizedAndStopsBeforeWork(SecuredRoomEndpoint endpoint)
            throws Exception {
        doThrow(new AccessDeniedException(STALE_MEMBERSHIP_MESSAGE))
                .when(gameLifecycleService).validatePlayerMembership(ROOM_ID, PLAYER, "stale-mid");

        mockMvc.perform(endpoint.request(ROOM_ID)
                        .principal(authenticated(PLAYER, ROOM_ID, "stale-mid")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.title").value("Unauthorized"))
                .andExpect(jsonPath("$.detail").value(STALE_MEMBERSHIP_MESSAGE));

        // Only the expiry check and the membership guard ran: no removal, no game, no room data.
        verify(roomService).closeLobbyIfExpired(ROOM_ID);
        verifyNoMoreInteractions(roomService);
        verify(gameLifecycleService).validatePlayerMembership(ROOM_ID, PLAYER, "stale-mid");
        verifyNoMoreInteractions(gameLifecycleService);
    }

    private static Room newRoom() {
        return new Room(ROOM_ID, "Room1", HOST, 6, 10, 20, 1000, null, FIXED_CLOCK);
    }
}
