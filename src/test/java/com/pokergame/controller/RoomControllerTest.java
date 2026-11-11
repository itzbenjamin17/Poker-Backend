package com.pokergame.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pokergame.dto.internal.PlayerJoinInfo;
import com.pokergame.dto.internal.RoomEntryResult;
import com.pokergame.dto.request.CreateRoomRequest;
import com.pokergame.dto.request.JoinRoomRequest;
import com.pokergame.dto.response.RoomDataResponse;
import com.pokergame.security.JwtAuthenticationFilter;
import com.pokergame.security.JwtService;
import com.pokergame.security.PayloadSizeFilter;
import com.pokergame.security.PlayerPrincipal;
import com.pokergame.service.GameLifecycleService;
import com.pokergame.service.RoomService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.pokergame.security.EndpointRateLimitFilter;
import com.pokergame.security.RateLimitService;
import com.pokergame.service.GameStateService;
import com.pokergame.service.RoomCoordinator;
import com.pokergame.util.LockLease;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import com.pokergame.model.Room;

/** Tests room controller behavior. */
@WebMvcTest(RoomController.class)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@TestPropertySource(locations = "classpath:application-test.properties")
@Import({
        RateLimitService.class,
        EndpointRateLimitFilter.class,
        PayloadSizeFilter.class
})
class RoomControllerTest {

    /** Unsupported password bytes produce client validation errors on both public commands. */
    @Test
    void oversizedUnicodePasswordsReturnBadRequestBeforeServiceInvocation() throws Exception {
        String password = "æ¼¢".repeat(25);
        mockMvc.perform(post("/api/room/create").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateRoomRequest(
                                "Room", "Host", 4, 5, 10, 100, password))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/room/join").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new JoinRoomRequest("Room", "Guest", password))))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(roomService);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private PayloadSizeFilter payloadSizeFilter;

    @Autowired
    private EndpointRateLimitFilter endpointRateLimitFilter;

    @Autowired
    private RateLimitService rateLimitService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean
    private RoomService roomService;

    @MockitoBean
    private GameLifecycleService gameLifecycleService;

    /** Required by the WebSocket exception advice that the controller slice loads. */
    @MockitoBean
    private GameStateService gameStateService;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockitoBean
    private RoomCoordinator roomCoordinator;

    /**
     * Initializes the test fixtures before each scenario.
     */
    @BeforeEach
    void setup() {
        Mockito.when(roomCoordinator.acquire(ArgumentMatchers.anyString()))
                .thenReturn(LockLease.none());
        Mockito.when(roomCoordinator.coordinate(ArgumentMatchers.anyString(), ArgumentMatchers.<java.util.function.Supplier<?>>any()))
                .thenAnswer(invocation -> ((java.util.function.Supplier<?>) invocation.getArgument(1)).get());
        Mockito.doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(1)).run();
            return null;
        }).when(roomCoordinator).coordinate(ArgumentMatchers.anyString(), ArgumentMatchers.any(Runnable.class));

        // Reset rate limit buckets to ensure a fresh start for each test
        rateLimitService.reset();

        // We need to manually add the filters because @WebMvcTest 
        // doesn't include custom filters by default even if they are @Components.
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilter(payloadSizeFilter)
                .addFilter(endpointRateLimitFilter)
                .build();
    }

    /**
     * Verifies the expected response for POST /api/room/create - Success.
     */
    @Test
    @DisplayName("POST /api/room/create - Success")
    void createRoom_ShouldReturnTokenResponse() throws Exception {
        CreateRoomRequest request = new CreateRoomRequest("Room1", "Player1", 6, 10, 20, 1000, null);
        String roomId = "room-123";
        when(roomService.createRoomForEntry(any(CreateRoomRequest.class)))
                .thenReturn(new RoomEntryResult(roomId, "Player1"));
        when(jwtService.generateToken("Player1", roomId)).thenReturn("mock-token");

        mockMvc.perform(post("/api/room/create")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Room created successfully"))
                .andExpect(jsonPath("$.data.token").value("mock-token"))
                .andExpect(jsonPath("$.data.roomId").value(roomId))
                .andExpect(jsonPath("$.data.playerName").value("Player1"));

        verify(roomService).createRoomForEntry(any(CreateRoomRequest.class));
        verify(jwtService).generateToken("Player1", roomId);
    }

    /**
     * Verifies the expected response for POST /api/room/join - Success.
     */
    @Test
    @DisplayName("POST /api/room/join - Success")
    void joinRoom_ShouldReturnTokenResponse() throws Exception {
        JoinRoomRequest request = new JoinRoomRequest("Room1", "Player2", null);
        String roomId = "room-123";
        when(roomService.joinRoomForEntry(any(JoinRoomRequest.class)))
                .thenReturn(new RoomEntryResult(roomId, "Player2"));
        when(jwtService.generateToken("Player2", roomId)).thenReturn("mock-token");

        mockMvc.perform(post("/api/room/join")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Successfully joined room"))
                .andExpect(jsonPath("$.data.token").value("mock-token"))
                .andExpect(jsonPath("$.data.roomId").value(roomId))
                .andExpect(jsonPath("$.data.playerName").value("Player2"));

        verify(roomService).joinRoomForEntry(any(JoinRoomRequest.class));
        verify(jwtService).generateToken("Player2", roomId);
    }

    /**
     * Verifies the expected response for POST /api/room/{roomId}/leave - Success (No Game Active).
     */
    @Test
    @DisplayName("POST /api/room/{roomId}/leave - Success (No Game Active)")
    void leaveRoom_NoGameActive_ShouldReturnSuccess() throws Exception {
        String roomId = "room-123";
        String playerName = "Player1";
        PlayerPrincipal principal = new PlayerPrincipal(playerName, roomId);
        Authentication auth = new PreAuthenticatedAuthenticationToken(principal, "token", Collections.emptyList());

        mockMvc.perform(post("/api/room/{roomId}/leave", roomId)
                .principal(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Successfully left room"));

        verify(gameLifecycleService).departParticipants(roomId, List.of(playerName));
    }

    /**
     * Verifies the expected response for POST /api/room/{roomId}/leave - Success (Game Active, Player in Game).
     */
    @Test
    @DisplayName("POST /api/room/{roomId}/leave - Success (Game Active, Player in Game)")
    void leaveRoom_GameActive_ShouldLeaveBoth() throws Exception {
        String roomId = "room-123";
        String playerName = "Player1";
        PlayerPrincipal principal = new PlayerPrincipal(playerName, roomId);
        Authentication auth = new PreAuthenticatedAuthenticationToken(principal, "token", Collections.emptyList());

        mockMvc.perform(post("/api/room/{roomId}/leave", roomId)
                .principal(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Successfully left room"));

        verify(gameLifecycleService).departParticipants(roomId, List.of(playerName));
    }

    /**
     * Verifies the expected response for POST /api/room/{roomId}/leave - Success (Game Active, Player NOT in Game).
     */
    @Test
    @DisplayName("POST /api/room/{roomId}/leave - Success (Game Active, Player NOT in Game)")
    void leaveRoom_GameActive_PlayerNotInGame() throws Exception {
        String roomId = "room-123";
        String playerName = "Player1";
        PlayerPrincipal principal = new PlayerPrincipal(playerName, roomId);
        Authentication auth = new PreAuthenticatedAuthenticationToken(principal, "token", Collections.emptyList());

        mockMvc.perform(post("/api/room/{roomId}/leave", roomId)
                .principal(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Successfully left room"));

        verify(gameLifecycleService).departParticipants(roomId, List.of(playerName));
    }

    /**
     * Verifies the expected response for GET /api/room/{roomId} - Success.
     */
    @Test
    @DisplayName("GET /api/room/{roomId} - Success")
    void getRoomInfo_ShouldReturnRoomData() throws Exception {
        String roomId = "room-123";
        String playerName = "Player1";
        PlayerPrincipal principal = new PlayerPrincipal(playerName, roomId);
        Authentication auth = new PreAuthenticatedAuthenticationToken(principal, "token", Collections.emptyList());

        Room room = new Room(roomId, "Room1", playerName, 6, 10, 20, 1000, null);
        room.addPlayer(playerName);
        when(roomService.getRoom(roomId)).thenReturn(room);

        PlayerJoinInfo pInfo = new PlayerJoinInfo(playerName, true, "2026-05-17T12:00:00");
        RoomDataResponse roomData = new RoomDataResponse(roomId, "Room1", 6, 1000, 10, 20, LocalDateTime.now(), playerName, List.of(pInfo), 1, true, false);
        when(roomService.getRoomData(roomId)).thenReturn(roomData);

        mockMvc.perform(get("/api/room/{roomId}", roomId)
                .principal(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roomId").value(roomId))
                .andExpect(jsonPath("$.roomName").value("Room1"))
                .andExpect(jsonPath("$.hostName").value(playerName));
    }

    /**
     * Verifies the expected response for GET /api/room/{roomId} - Not Found.
     */
    @Test
    @DisplayName("GET /api/room/{roomId} - Not Found")
    void getRoomInfo_NotFound_ShouldReturn404() throws Exception {
        String roomId = "unknown";
        PlayerPrincipal principal = new PlayerPrincipal("Player1", roomId);
        Authentication auth = new PreAuthenticatedAuthenticationToken(principal, "token", Collections.emptyList());
        when(roomService.getRoom(roomId)).thenReturn(null);

        mockMvc.perform(get("/api/room/{roomId}", roomId)
                .principal(auth))
                .andExpect(status().isNotFound());
    }

    /**
     * Verifies that GET /api/room/{roomId} rejects tokens issued for another room with 403 before resource lookup.
     */
    @Test
    @DisplayName("GET /api/room/{roomId} - Cross-Room Token (403)")
    void getRoomInfo_CrossRoomToken_ShouldReturn403() throws Exception {
        String roomId = "room-123";
        PlayerPrincipal principal = new PlayerPrincipal("Player1", "different-room-456");
        Authentication auth = new PreAuthenticatedAuthenticationToken(principal, "token", Collections.emptyList());

        mockMvc.perform(get("/api/room/{roomId}", roomId)
                .principal(auth))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Forbidden"))
                .andExpect(jsonPath("$.message").value("Token is not valid for this room."));

        verify(roomService, never()).getRoom(anyString());
        verify(roomService, never()).getRoomData(anyString());
    }

    /**
     * Verifies that GET /api/room/{roomId} rejects authenticated non-members with 403.
     */
    @Test
    @DisplayName("GET /api/room/{roomId} - Non-Member (403)")
    void getRoomInfo_NonMember_ShouldReturn403() throws Exception {
        String roomId = "room-123";
        PlayerPrincipal principal = new PlayerPrincipal("NonMemberPlayer", roomId);
        Authentication auth = new PreAuthenticatedAuthenticationToken(principal, "token", Collections.emptyList());

        Room room = new Room(roomId, "Room1", "HostPlayer", 6, 10, 20, 1000, null);
        room.addPlayer("HostPlayer");
        when(roomService.getRoom(roomId)).thenReturn(room);

        mockMvc.perform(get("/api/room/{roomId}", roomId)
                .principal(auth))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Forbidden"))
                .andExpect(jsonPath("$.message").value("You are not a member of this room."));

        verify(roomService, never()).getRoomData(anyString());
    }

    /**
     * Verifies the expected response for POST /api/room/create - Validation Error (Oversized Name).
     */
    @Test
    @DisplayName("POST /api/room/create - Validation Error (Oversized Name)")
    void createRoom_ValidationError_OversizedName() throws Exception {
        CreateRoomRequest request = new CreateRoomRequest("A".repeat(51), "Player1", 6, 10, 20, 1000, null);

        mockMvc.perform(post("/api/room/create")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Room name must be 50 characters or less"));
    }

    /**
     * Verifies the expected response for POST /api/room/create - Validation Error (Control Characters).
     */
    @Test
    @DisplayName("POST /api/room/create - Validation Error (Control Characters)")
    void createRoom_ValidationError_ControlChars() throws Exception {
        CreateRoomRequest request = new CreateRoomRequest("Room" + (char) 7, "Player1", 6, 10, 20, 1000, null);

        mockMvc.perform(post("/api/room/create")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Room name cannot contain control characters"));
    }

    /**
     * Verifies the expected response for POST /api/room/create - Validation Error (Format Characters in Room Name).
     */
    @Test
    @DisplayName("POST /api/room/create - Validation Error (Format Characters in Room Name)")
    void createRoom_ValidationError_FormatCharsRoomName() throws Exception {
        CreateRoomRequest request = new CreateRoomRequest("Room\u200B1", "Player1", 6, 10, 20, 1000, null);

        mockMvc.perform(post("/api/room/create")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    /**
     * Verifies the expected response for POST /api/room/create - Validation Error (Format Characters in Player Name).
     */
    @Test
    @DisplayName("POST /api/room/create - Validation Error (Format Characters in Player Name)")
    void createRoom_ValidationError_FormatCharsPlayerName() throws Exception {
        CreateRoomRequest request = new CreateRoomRequest("Room1", "Player\u200D1", 6, 10, 20, 1000, null);

        mockMvc.perform(post("/api/room/create")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    /**
     * Verifies the expected response for POST /api/room/create - Validation Error (HTML Tags in Room Name).
     */
    @Test
    @DisplayName("POST /api/room/create - Validation Error (HTML Tags in Room Name)")
    void createRoom_ValidationError_HtmlTagsRoomName() throws Exception {
        CreateRoomRequest request = new CreateRoomRequest("<b>Room</b>", "Player1", 6, 10, 20, 1000, null);

        mockMvc.perform(post("/api/room/create")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    /**
     * Verifies the expected response for POST /api/room/create - Validation Error (HTML Tags in Player Name).
     */
    @Test
    @DisplayName("POST /api/room/create - Validation Error (HTML Tags in Player Name)")
    void createRoom_ValidationError_HtmlTagsPlayerName() throws Exception {
        CreateRoomRequest request = new CreateRoomRequest("Room1", "<script>alert(1)</script>", 6, 10, 20, 1000, null);

        mockMvc.perform(post("/api/room/create")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    /**
     * Verifies the expected response for POST /api/room/create - Whitespace-only password treated as absent.
     */
    @Test
    @DisplayName("POST /api/room/create - Whitespace-only password treated as absent")
    void createRoom_WhitespaceOnlyPassword_TreatedAsAbsent() throws Exception {
        ArgumentCaptor<CreateRoomRequest> captor = ArgumentCaptor.forClass(CreateRoomRequest.class);
        when(roomService.createRoomForEntry(any(CreateRoomRequest.class)))
                .thenReturn(new RoomEntryResult("room-123", "Player1"));
        when(jwtService.generateToken("Player1", "room-123")).thenReturn("mock-token");

        mockMvc.perform(post("/api/room/create")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"roomName\":\"Room1\",\"playerName\":\"Player1\",\"maxPlayers\":6,\"smallBlind\":10,\"bigBlind\":20,\"buyIn\":1000,\"password\":\"   \"}"))
                .andExpect(status().isOk());

        verify(roomService).createRoomForEntry(captor.capture());
        assertThat(captor.getValue().getPassword()).isNull();
        assertThat(captor.getValue().hasPassword()).isFalse();
    }

    /**
     * Verifies the expected response for POST /api/room/create - Password preserves spaces.
     */
    @Test
    @DisplayName("POST /api/room/create - Password preserves spaces")
    void createRoom_PasswordPreservesSpaces() throws Exception {
        ArgumentCaptor<CreateRoomRequest> captor = ArgumentCaptor.forClass(CreateRoomRequest.class);
        when(roomService.createRoomForEntry(any(CreateRoomRequest.class)))
                .thenReturn(new RoomEntryResult("room-123", "Player1"));
        when(jwtService.generateToken("Player1", "room-123")).thenReturn("mock-token");

        mockMvc.perform(post("/api/room/create")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"roomName\":\"Room1\",\"playerName\":\"Player1\",\"maxPlayers\":6,\"smallBlind\":10,\"bigBlind\":20,\"buyIn\":1000,\"password\":\"  secret123  \"}"))
                .andExpect(status().isOk());

        verify(roomService).createRoomForEntry(captor.capture());
        assertThat(captor.getValue().getPassword()).isEqualTo("  secret123  ");
        assertThat(captor.getValue().hasPassword()).isTrue();
    }

    /**
     * Verifies the expected response for POST /api/room/create - Validation Error (Invalid Numeric Range).
     */
    @Test
    @DisplayName("POST /api/room/create - Validation Error (Invalid Numeric Range)")
    void createRoom_ValidationError_InvalidRange() throws Exception {
        CreateRoomRequest request = new CreateRoomRequest("Room1", "Player1", 1, 10, 20, 1000, null);

        mockMvc.perform(post("/api/room/create")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Minimum 2 players required"));
    }

    /**
     * Verifies the expected response for POST /api/room/join - Validation Error (Oversized Player Name).
     */
    @Test
    @DisplayName("POST /api/room/join - Validation Error (Oversized Player Name)")
    void joinRoom_ValidationError_OversizedPlayerName() throws Exception {
        JoinRoomRequest request = new JoinRoomRequest("Room1", "A".repeat(31), null);

        mockMvc.perform(post("/api/room/join")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Player name must be 30 characters or less"));
    }

    /**
     * Verifies the expected response for POST /api/room/join - Validation Error (Format Characters in Room Name).
     */
    @Test
    @DisplayName("POST /api/room/join - Validation Error (Format Characters in Room Name)")
    void joinRoom_ValidationError_FormatCharsRoomName() throws Exception {
        JoinRoomRequest request = new JoinRoomRequest("Room\u200B1", "Player1", null);

        mockMvc.perform(post("/api/room/join")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    /**
     * Verifies the expected response for POST /api/room/join - Validation Error (Format Characters in Player Name).
     */
    @Test
    @DisplayName("POST /api/room/join - Validation Error (Format Characters in Player Name)")
    void joinRoom_ValidationError_FormatCharsPlayerName() throws Exception {
        JoinRoomRequest request = new JoinRoomRequest("Room1", "Player\u200D1", null);

        mockMvc.perform(post("/api/room/join")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    /**
     * Verifies the expected response for POST /api/room/join - Validation Error (HTML Tags in Room Name).
     */
    @Test
    @DisplayName("POST /api/room/join - Validation Error (HTML Tags in Room Name)")
    void joinRoom_ValidationError_HtmlTagsRoomName() throws Exception {
        JoinRoomRequest request = new JoinRoomRequest("<b>Room</b>", "Player1", null);

        mockMvc.perform(post("/api/room/join")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    /**
     * Verifies the expected response for POST /api/room/join - Validation Error (HTML Tags in Player Name).
     */
    @Test
    @DisplayName("POST /api/room/join - Validation Error (HTML Tags in Player Name)")
    void joinRoom_ValidationError_HtmlTagsPlayerName() throws Exception {
        JoinRoomRequest request = new JoinRoomRequest("Room1", "<i>Player</i>", null);

        mockMvc.perform(post("/api/room/join")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    /**
     * Verifies the expected response for POST /api/room/join - Whitespace-only password treated as null.
     */
    @Test
    @DisplayName("POST /api/room/join - Whitespace-only password treated as null")
    void joinRoom_WhitespaceOnlyPassword_TreatedAsNull() throws Exception {
        ArgumentCaptor<JoinRoomRequest> captor = ArgumentCaptor.forClass(JoinRoomRequest.class);
        when(roomService.joinRoomForEntry(any(JoinRoomRequest.class)))
                .thenReturn(new RoomEntryResult("room-123", "Player2"));
        when(jwtService.generateToken("Player2", "room-123")).thenReturn("mock-token");

        mockMvc.perform(post("/api/room/join")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"roomName\":\"Room1\",\"playerName\":\"Player2\",\"password\":\"   \"}"))
                .andExpect(status().isOk());

        verify(roomService).joinRoomForEntry(captor.capture());
        assertThat(captor.getValue().password()).isNull();
    }

    /**
     * Verifies the expected response for POST /api/room/join - Password preserves spaces.
     */
    @Test
    @DisplayName("POST /api/room/join - Password preserves spaces")
    void joinRoom_PasswordPreservesSpaces() throws Exception {
        ArgumentCaptor<JoinRoomRequest> captor = ArgumentCaptor.forClass(JoinRoomRequest.class);
        when(roomService.joinRoomForEntry(any(JoinRoomRequest.class)))
                .thenReturn(new RoomEntryResult("room-123", "Player2"));
        when(jwtService.generateToken("Player2", "room-123")).thenReturn("mock-token");

        mockMvc.perform(post("/api/room/join")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"roomName\":\"Room1\",\"playerName\":\"Player2\",\"password\":\"  secret123  \"}"))
                .andExpect(status().isOk());

        verify(roomService).joinRoomForEntry(captor.capture());
        assertThat(captor.getValue().password()).isEqualTo("  secret123  ");
    }

    /**
     * Verifies the expected response for POST /api/room/create - Malformed JSON.
     */
    @Test
    @DisplayName("POST /api/room/create - Malformed JSON")
    void createRoom_MalformedJson() throws Exception {
        mockMvc.perform(post("/api/room/create")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ \"roomName\": \"Room1\", \"maxPlayers\": \"not-a-number\" }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed JSON request payload"));
    }

    /**
     * Verifies the expected response for POST /api/room/create - Payload Too Large.
     */
    @Test
    @DisplayName("POST /api/room/create - Payload Too Large")
    void createRoom_PayloadTooLarge() throws Exception {
        // Since we enabled the filter in SecurityConfig, we need to ensure the test MockMvc uses it.
        // However, standard @WebMvcTest doesn't include custom filters from the context unless explicitly configured.
        // But the PayloadSizeFilter is a @Component and OncePerRequestFilter.
        
        // Create a large body (> 10KB)
        String largeBody = "{ \"roomName\": \"" + "A".repeat(11000) + "\", \"playerName\": \"P\" }";

        mockMvc.perform(post("/api/room/create")
                .contentType(MediaType.APPLICATION_JSON)
                .content(largeBody))
                .andExpect(status().isContentTooLarge())
                .andExpect(jsonPath("$.message").value("Request payload too large. Maximum allowed is 10KB."));
    }

    /**
     * Verifies the expected response for POST /api/room/create - Rate Limit Exceeded.
     */
    @Test
    @DisplayName("POST /api/room/create - Rate Limit Exceeded")
    void createRoom_RateLimitExceeded() throws Exception {
        ReflectionTestUtils.setField(rateLimitService, "enabled", true);

        CreateRoomRequest request = new CreateRoomRequest("Room", "Player1", 6, 10, 20, 1000, null);

        // Consume the limit (5 per 15 minutes). We don't need to simulate failures
        // because the rate limit properly applies to successful requests too.
        when(roomService.createRoomForEntry(any()))
                .thenReturn(new RoomEntryResult("room-123", "Player1"));

        // Consume the limit (5 per 15 minutes)
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/room/create")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk());
        }

        // The 6th attempt should fail with 429
        mockMvc.perform(post("/api/room/create")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value("Too many requests. Please try again in 15 minutes."));
    }

    /**
     * Verifies the expected response for POST /api/room/{roomId}/start-game - Success (Host).
     */
    @Test
    @DisplayName("POST /api/room/{roomId}/start-game - Success (Host)")
    void startGame_Host_ShouldReturnGameId() throws Exception {
        String roomId = "room-123";
        String playerName = "HostPlayer";
        PlayerPrincipal principal = new PlayerPrincipal(playerName, roomId);
        Authentication auth = new PreAuthenticatedAuthenticationToken(principal, "token", Collections.emptyList());
        when(roomService.isRoomHost(roomId, playerName)).thenReturn(true);
        when(gameLifecycleService.createGameFromRoom(roomId)).thenReturn("game-123");

        mockMvc.perform(post("/api/room/{roomId}/start-game", roomId)
                .principal(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Game started successfully"))
                .andExpect(jsonPath("$.data").value("game-123"));

        verify(gameLifecycleService).createGameFromRoom(roomId);
    }

    /**
     * Verifies the expected response for POST /api/room/{roomId}/start-game - Forbidden (Non-Host).
     */
    @Test
    @DisplayName("POST /api/room/{roomId}/start-game - Forbidden (Non-Host)")
    void startGame_NonHost_ShouldReturn403() throws Exception {
        String roomId = "room-123";
        String playerName = "GuestPlayer";
        PlayerPrincipal principal = new PlayerPrincipal(playerName, roomId);
        Authentication auth = new PreAuthenticatedAuthenticationToken(principal, "token", Collections.emptyList());
        when(roomService.isRoomHost(roomId, playerName)).thenReturn(false);

        mockMvc.perform(post("/api/room/{roomId}/start-game", roomId)
                .principal(auth))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Forbidden"))
                .andExpect(jsonPath("$.message").value("Only the room host can start the game. Please ask the host to start."));

        verify(gameLifecycleService, never()).createGameFromRoom(anyString());
    }

    @Test
    @DisplayName("POST /api/room/create - Keeps admitted membershipId after host replacement")
    void createRoom_WithActiveMembership_ShouldIssueTokenWithMembershipId() throws Exception {
        CreateRoomRequest request = new CreateRoomRequest("RoomMem", "HostAlice", 6, 10, 20, 1000, null);
        String roomId = "room-mem-123";
        Room room = new Room(roomId, "RoomMem", "HostAlice", 6, 10, 20, 1000, null);
        String expectedMembershipId = room.addPlayer("HostAlice");

        when(roomService.createRoomForEntry(any(CreateRoomRequest.class)))
                .thenReturn(new RoomEntryResult(roomId, "HostAlice", expectedMembershipId));
        room.removePlayer("HostAlice");
        room.addPlayer("HostAlice");
        when(roomService.getRoom(roomId)).thenReturn(room);
        when(jwtService.generateToken("HostAlice", roomId, expectedMembershipId)).thenReturn("token-with-mid");

        mockMvc.perform(post("/api/room/create")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").value("token-with-mid"))
                .andExpect(jsonPath("$.data.roomId").value(roomId))
                .andExpect(jsonPath("$.data.playerName").value("HostAlice"));

        verify(jwtService).generateToken("HostAlice", roomId, expectedMembershipId);
    }

    @Test
    @DisplayName("POST /api/room/join - Keeps admitted membershipId after player replacement")
    void joinRoom_WithActiveMembership_ShouldIssueTokenWithMembershipId() throws Exception {
        JoinRoomRequest request = new JoinRoomRequest("RoomMem", "Bob", null);
        String roomId = "room-mem-456";
        Room room = new Room(roomId, "RoomMem", "HostAlice", 6, 10, 20, 1000, null);
        room.addPlayer("HostAlice");
        String expectedMembershipId = room.addPlayer("Bob");

        when(roomService.joinRoomForEntry(any(JoinRoomRequest.class)))
                .thenReturn(new RoomEntryResult(roomId, "Bob", expectedMembershipId));
        room.removePlayer("Bob");
        room.addPlayer("Bob");
        when(roomService.getRoom(roomId)).thenReturn(room);
        when(jwtService.generateToken("Bob", roomId, expectedMembershipId)).thenReturn("token-with-mid-bob");

        mockMvc.perform(post("/api/room/join")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").value("token-with-mid-bob"))
                .andExpect(jsonPath("$.data.roomId").value(roomId))
                .andExpect(jsonPath("$.data.playerName").value("Bob"));

        verify(jwtService).generateToken("Bob", roomId, expectedMembershipId);
    }

    @Test
    @DisplayName("GET /api/room/{roomId} - Unauthorized when membership token is stale (401)")
    void getRoomInfo_StaleMembershipToken_ShouldReturnUnauthorized401() throws Exception {
        String roomId = "room-123";
        String playerName = "Player1";
        PlayerPrincipal principal = new PlayerPrincipal(playerName, roomId, "stale-mid");
        Authentication auth = new PreAuthenticatedAuthenticationToken(principal, "token", Collections.emptyList());

        doThrow(new AccessDeniedException("Token membership is stale or invalid."))
                .when(gameLifecycleService).validatePlayerMembership(roomId, playerName, "stale-mid");

        mockMvc.perform(get("/api/room/{roomId}", roomId)
                .principal(auth))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.message").value("Token membership is stale or invalid."));

        verify(roomService, never()).getRoomData(anyString());
    }
}
