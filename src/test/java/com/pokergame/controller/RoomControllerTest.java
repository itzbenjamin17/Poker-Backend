package com.pokergame.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pokergame.dto.internal.PlayerJoinInfo;
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
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(RoomController.class)
@AutoConfigureMockMvc(addFilters = false)
@org.springframework.context.annotation.Import({
        com.pokergame.security.RateLimitService.class,
        com.pokergame.security.EndpointRateLimitFilter.class,
        com.pokergame.security.PayloadSizeFilter.class
})
class RoomControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private PayloadSizeFilter payloadSizeFilter;

    @Autowired
    private com.pokergame.security.EndpointRateLimitFilter endpointRateLimitFilter;

    @Autowired
    private com.pokergame.security.RateLimitService rateLimitService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean
    private RoomService roomService;

    @MockitoBean
    private GameLifecycleService gameLifecycleService;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @BeforeEach
    void setup() {
        // Reset rate limit buckets to ensure a fresh start for each test
        rateLimitService.reset();

        // We need to manually add the filters because @WebMvcTest 
        // doesn't include custom filters by default even if they are @Components.
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilter(payloadSizeFilter)
                .addFilter(endpointRateLimitFilter)
                .build();
    }

    @Test
    @DisplayName("POST /api/room/create - Success")
    void createRoom_ShouldReturnTokenResponse() throws Exception {
        CreateRoomRequest request = new CreateRoomRequest("Room1", "Player1", 6, 10, 20, 1000, null);
        String roomId = "room-123";
        when(roomService.createRoom(any(CreateRoomRequest.class))).thenReturn(roomId);
        when(jwtService.generateToken("Player1", roomId)).thenReturn("mock-token");

        mockMvc.perform(post("/api/room/create")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Room created successfully"))
                .andExpect(jsonPath("$.data.token").value("mock-token"))
                .andExpect(jsonPath("$.data.roomId").value(roomId))
                .andExpect(jsonPath("$.data.playerName").value("Player1"));

        verify(roomService).createRoom(any(CreateRoomRequest.class));
        verify(jwtService).generateToken("Player1", roomId);
    }

    @Test
    @DisplayName("POST /api/room/join - Success")
    void joinRoom_ShouldReturnTokenResponse() throws Exception {
        JoinRoomRequest request = new JoinRoomRequest("Room1", "Player2", null);
        String roomId = "room-123";
        when(roomService.joinRoom(any(JoinRoomRequest.class))).thenReturn(roomId);
        when(jwtService.generateToken("Player2", roomId)).thenReturn("mock-token");

        mockMvc.perform(post("/api/room/join")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Successfully joined room"))
                .andExpect(jsonPath("$.data.token").value("mock-token"))
                .andExpect(jsonPath("$.data.roomId").value(roomId))
                .andExpect(jsonPath("$.data.playerName").value("Player2"));

        verify(roomService).joinRoom(any(JoinRoomRequest.class));
        verify(jwtService).generateToken("Player2", roomId);
    }

    @Test
    @DisplayName("POST /api/room/{roomId}/leave - Success (No Game Active)")
    void leaveRoom_NoGameActive_ShouldReturnSuccess() throws Exception {
        String roomId = "room-123";
        String playerName = "Player1";
        PlayerPrincipal principal = new PlayerPrincipal(playerName, roomId);
        Authentication auth = new PreAuthenticatedAuthenticationToken(principal, "token", Collections.emptyList());
        when(gameLifecycleService.gameExists(roomId)).thenReturn(false);

        mockMvc.perform(post("/api/room/{roomId}/leave", roomId)
                .principal(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Successfully left room"));

        verify(roomService).leaveRoom(eq(roomId), eq(playerName), eq(true));
        verify(gameLifecycleService, never()).leaveGame(anyString(), anyString());
    }

    @Test
    @DisplayName("POST /api/room/{roomId}/leave - Success (Game Active, Player in Game)")
    void leaveRoom_GameActive_ShouldLeaveBoth() throws Exception {
        String roomId = "room-123";
        String playerName = "Player1";
        PlayerPrincipal principal = new PlayerPrincipal(playerName, roomId);
        Authentication auth = new PreAuthenticatedAuthenticationToken(principal, "token", Collections.emptyList());
        when(gameLifecycleService.gameExists(roomId)).thenReturn(true);
        when(gameLifecycleService.playerExistsInGame(roomId, playerName)).thenReturn(true);

        mockMvc.perform(post("/api/room/{roomId}/leave", roomId)
                .principal(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Successfully left room"));

        verify(roomService).leaveRoom(eq(roomId), eq(playerName), eq(false));
        verify(gameLifecycleService).leaveGame(roomId, playerName);
    }

    @Test
    @DisplayName("POST /api/room/{roomId}/leave - Success (Game Active, Player NOT in Game)")
    void leaveRoom_GameActive_PlayerNotInGame() throws Exception {
        String roomId = "room-123";
        String playerName = "Player1";
        PlayerPrincipal principal = new PlayerPrincipal(playerName, roomId);
        Authentication auth = new PreAuthenticatedAuthenticationToken(principal, "token", Collections.emptyList());
        when(gameLifecycleService.gameExists(roomId)).thenReturn(true);
        when(gameLifecycleService.playerExistsInGame(roomId, playerName)).thenReturn(false);

        mockMvc.perform(post("/api/room/{roomId}/leave", roomId)
                .principal(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Successfully left room"));

        verify(roomService).leaveRoom(eq(roomId), eq(playerName), eq(false));
        verify(gameLifecycleService, never()).leaveGame(anyString(), anyString());
    }

    @Test
    @DisplayName("GET /api/room/{roomId} - Success")
    void getRoomInfo_ShouldReturnRoomData() throws Exception {
        String roomId = "room-123";
        PlayerJoinInfo pInfo = new PlayerJoinInfo("Player1", true, "2026-05-17T12:00:00");
        RoomDataResponse roomData = new RoomDataResponse(roomId, "Room1", 6, 1000, 10, 20, LocalDateTime.now(), "Player1", List.of(pInfo), 1, true, false);
        when(roomService.getRoomData(roomId)).thenReturn(roomData);

        mockMvc.perform(get("/api/room/{roomId}", roomId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roomId").value(roomId))
                .andExpect(jsonPath("$.roomName").value("Room1"))
                .andExpect(jsonPath("$.hostName").value("Player1"));
    }

    @Test
    @DisplayName("GET /api/room/{roomId} - Not Found")
    void getRoomInfo_NotFound_ShouldReturn404() throws Exception {
        String roomId = "unknown";
        when(roomService.getRoomData(roomId)).thenReturn(null);

        mockMvc.perform(get("/api/room/{roomId}", roomId))
                .andExpect(status().isNotFound());
    }

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

    @Test
    @DisplayName("POST /api/room/create - Malformed JSON")
    void createRoom_MalformedJson() throws Exception {
        mockMvc.perform(post("/api/room/create")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ \"roomName\": \"Room1\", \"maxPlayers\": \"not-a-number\" }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed JSON request payload"));
    }

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

    @Test
    @DisplayName("POST /api/room/create - Rate Limit Exceeded")
    void createRoom_RateLimitExceeded() throws Exception {
        org.springframework.test.util.ReflectionTestUtils.setField(rateLimitService, "enabled", true);

        CreateRoomRequest request = new CreateRoomRequest("Room", "Player1", 6, 10, 20, 1000, null);

        // Consume the limit (5 per 15 minutes). We don't need to simulate failures
        // because the rate limit properly applies to successful requests too.
        when(roomService.createRoom(any())).thenReturn("room-123");

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
}
