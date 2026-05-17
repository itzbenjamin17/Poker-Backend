package com.pokergame.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pokergame.dto.internal.PlayerJoinInfo;
import com.pokergame.dto.request.CreateRoomRequest;
import com.pokergame.dto.request.JoinRoomRequest;
import com.pokergame.dto.response.RoomDataResponse;
import com.pokergame.security.JwtAuthenticationFilter;
import com.pokergame.security.JwtService;
import com.pokergame.service.GameLifecycleService;
import com.pokergame.service.RoomService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(RoomController.class)
@AutoConfigureMockMvc(addFilters = false)
class RoomControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean
    private RoomService roomService;

    @MockitoBean
    private GameLifecycleService gameLifecycleService;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @Test
    @DisplayName("POST /api/room/create - Success")
    void createRoom_ShouldReturnTokenResponse() throws Exception {
        CreateRoomRequest request = new CreateRoomRequest("Room1", "Player1", 6, 10, 20, 1000, null);
        when(roomService.createRoom(any(CreateRoomRequest.class))).thenReturn("room-123");
        when(jwtService.generateToken("Player1")).thenReturn("mock-token");

        mockMvc.perform(post("/api/room/create")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Room created successfully"))
                .andExpect(jsonPath("$.data.token").value("mock-token"))
                .andExpect(jsonPath("$.data.roomId").value("room-123"))
                .andExpect(jsonPath("$.data.playerName").value("Player1"));

        verify(roomService).createRoom(any(CreateRoomRequest.class));
        verify(jwtService).generateToken("Player1");
    }

    @Test
    @DisplayName("POST /api/room/join - Success")
    void joinRoom_ShouldReturnTokenResponse() throws Exception {
        JoinRoomRequest request = new JoinRoomRequest("Room1", "Player2", null);
        when(roomService.joinRoom(any(JoinRoomRequest.class))).thenReturn("room-123");
        when(jwtService.generateToken("Player2")).thenReturn("mock-token");

        mockMvc.perform(post("/api/room/join")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Successfully joined room"))
                .andExpect(jsonPath("$.data.token").value("mock-token"))
                .andExpect(jsonPath("$.data.roomId").value("room-123"))
                .andExpect(jsonPath("$.data.playerName").value("Player2"));

        verify(roomService).joinRoom(any(JoinRoomRequest.class));
        verify(jwtService).generateToken("Player2");
    }

    @Test
    @DisplayName("POST /api/room/{roomId}/leave - Success (No Game Active)")
    void leaveRoom_NoGameActive_ShouldReturnSuccess() throws Exception {
        String roomId = "room-123";
        String playerName = "Player1";
        when(gameLifecycleService.gameExists(roomId)).thenReturn(false);

        mockMvc.perform(post("/api/room/{roomId}/leave", roomId)
                .principal(() -> playerName))
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
        when(gameLifecycleService.gameExists(roomId)).thenReturn(true);
        when(gameLifecycleService.playerExistsInGame(roomId, playerName)).thenReturn(true);

        mockMvc.perform(post("/api/room/{roomId}/leave", roomId)
                .principal(() -> playerName))
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
        when(gameLifecycleService.gameExists(roomId)).thenReturn(true);
        when(gameLifecycleService.playerExistsInGame(roomId, playerName)).thenReturn(false);

        mockMvc.perform(post("/api/room/{roomId}/leave", roomId)
                .principal(() -> playerName))
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
    @DisplayName("POST /api/room/{roomId}/start-game - Success (Host)")
    void startGame_Host_ShouldReturnGameId() throws Exception {
        String roomId = "room-123";
        String playerName = "HostPlayer";
        when(roomService.isRoomHost(roomId, playerName)).thenReturn(true);
        when(gameLifecycleService.createGameFromRoom(roomId)).thenReturn("game-123");

        mockMvc.perform(post("/api/room/{roomId}/start-game", roomId)
                .principal(() -> playerName))
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
        when(roomService.isRoomHost(roomId, playerName)).thenReturn(false);

        mockMvc.perform(post("/api/room/{roomId}/start-game", roomId)
                .principal(() -> playerName))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Forbidden"))
                .andExpect(jsonPath("$.message").value("Only the room host can start the game. Please ask the host to start."));

        verify(gameLifecycleService, never()).createGameFromRoom(anyString());
    }
}
