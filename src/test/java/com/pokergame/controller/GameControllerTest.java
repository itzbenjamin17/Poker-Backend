package com.pokergame.controller;

import com.pokergame.dto.request.PlayerActionRequest;
import com.pokergame.dto.response.PrivatePlayerState;
import com.pokergame.dto.response.PublicGameStateResponse;
import com.pokergame.enums.GamePhase;
import com.pokergame.enums.PlayerAction;
import com.pokergame.model.Game;
import com.pokergame.security.JwtAuthenticationFilter;
import com.pokergame.security.PlayerPrincipal;
import com.pokergame.service.GameLifecycleService;
import com.pokergame.service.GameStateService;
import com.pokergame.service.PlayerActionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Tests game controller behavior. */
@WebMvcTest(GameController.class)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@org.springframework.test.context.TestPropertySource(locations = "classpath:application-test.properties")
class GameControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private GameController gameController;

    @MockitoBean
    private GameLifecycleService gameLifecycleService;

    @MockitoBean
    private GameStateService gameStateService;

    @MockitoBean
    private PlayerActionService playerActionService;

    @MockitoBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @MockitoBean
    private com.pokergame.security.EndpointRateLimitFilter endpointRateLimitFilter;

    @MockitoBean
    private com.pokergame.security.PayloadSizeFilter payloadSizeFilter;

    private final String gameId = "game-123";
    private final String playerName = "Player1";
    private PlayerPrincipal principal;
    private Authentication auth;

    /**
     * Initializes the test fixtures before each scenario.
     */
    @BeforeEach
    void setUp() {
        principal = new PlayerPrincipal(playerName, gameId);
        auth = new PreAuthenticatedAuthenticationToken(principal, "token", Collections.emptyList());
    }

    /**
     * Verifies the expected response for GET /api/game/{gameId}/state - Success.
     */
    @Test
    @DisplayName("GET /api/game/{gameId}/state - Success")
    void getGameState_Success() throws Exception {
        Game game = mock(Game.class);
        PublicGameStateResponse response = new PublicGameStateResponse(
                6, 100, Collections.emptyList(), 0, GamePhase.PRE_FLOP, 10,
                Collections.emptyList(), Collections.emptyList(), playerName, "p1-id"
        );

        when(gameLifecycleService.gameExists(gameId)).thenReturn(true);
        when(gameLifecycleService.playerExistsInGame(gameId, playerName)).thenReturn(true);
        when(gameLifecycleService.getGame(gameId)).thenReturn(game);
        when(gameStateService.getPublicGameStateSnapshot(gameId, game)).thenReturn(response);

        mockMvc.perform(get("/api/game/{gameId}/state", gameId)
                .principal(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pot").value(100))
                .andExpect(jsonPath("$.phase").value("PRE_FLOP"));
    }

    /**
     * Verifies the expected response for GET /api/game/{gameId}/state - Game Not Found.
     */
    @Test
    @DisplayName("GET /api/game/{gameId}/state - Game Not Found")
    void getGameState_NotFound() throws Exception {
        when(gameLifecycleService.gameExists(gameId)).thenReturn(false);

        mockMvc.perform(get("/api/game/{gameId}/state", gameId)
                .principal(auth))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Game not found"));
    }

    /**
     * Verifies the expected response for GET /api/game/{gameId}/state - Game Null (404).
     */
    @Test
    @DisplayName("GET /api/game/{gameId}/state - Game Null (404)")
    void getGameState_GameNull() throws Exception {
        when(gameLifecycleService.gameExists(gameId)).thenReturn(true);
        when(gameLifecycleService.playerExistsInGame(gameId, playerName)).thenReturn(true);
        when(gameLifecycleService.getGame(gameId)).thenReturn(null);

        mockMvc.perform(get("/api/game/{gameId}/state", gameId)
                .principal(auth))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Game not found"));
    }

    /**
     * Verifies the expected response for GET /api/game/{gameId}/state - Not In Game (403).
     */
    @Test
    @DisplayName("GET /api/game/{gameId}/state - Not In Game (403)")
    void getGameState_NotInGame() throws Exception {
        when(gameLifecycleService.gameExists(gameId)).thenReturn(true);
        when(gameLifecycleService.playerExistsInGame(gameId, playerName)).thenReturn(false);

        mockMvc.perform(get("/api/game/{gameId}/state", gameId)
                .principal(auth))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("You are no longer part of this game."));
    }

    /**
     * Verifies the expected response for GET /api/game/{gameId}/private-state - Success.
     */
    @Test
    @DisplayName("GET /api/game/{gameId}/private-state - Success")
    void getPrivateState_Success() throws Exception {
        Game game = mock(Game.class);
        PrivatePlayerState response = new PrivatePlayerState("p1-id", Collections.emptyList());

        when(gameLifecycleService.gameExists(gameId)).thenReturn(true);
        when(gameLifecycleService.playerExistsInGame(gameId, playerName)).thenReturn(true);
        when(gameLifecycleService.getGame(gameId)).thenReturn(game);
        when(gameStateService.getPrivatePlayerStateSnapshot(game, playerName)).thenReturn(response);

        mockMvc.perform(get("/api/game/{gameId}/private-state", gameId)
                .principal(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.playerId").value("p1-id"));
    }

    /**
     * Verifies the expected response for GET /api/game/{gameId}/private-state - Not In Game (403).
     */
    @Test
    @DisplayName("GET /api/game/{gameId}/private-state - Not In Game (403)")
    void getPrivateState_NotInGame() throws Exception {
        when(gameLifecycleService.gameExists(gameId)).thenReturn(true);
        when(gameLifecycleService.playerExistsInGame(gameId, playerName)).thenReturn(false);

        mockMvc.perform(get("/api/game/{gameId}/private-state", gameId)
                .principal(auth))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("You are no longer part of this game."));
    }

    /**
     * Verifies the expected response for POST /api/game/{gameId}/leave - Success.
     */
    @Test
    @DisplayName("POST /api/game/{gameId}/leave - Success")
    void leaveGame_Success() throws Exception {
        mockMvc.perform(post("/api/game/{gameId}/leave", gameId)
                .principal(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Successfully left game"));

        verify(gameLifecycleService).leaveGame(gameId, playerName);
    }

    /**
     * Verifies the expected response for POST /api/game/{gameId}/claim-win - Success.
     */
    @Test
    @DisplayName("POST /api/game/{gameId}/claim-win - Success")
    void claimWin_Success() throws Exception {
        mockMvc.perform(post("/api/game/{gameId}/claim-win", gameId)
                .principal(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Win claimed successfully"));

        verify(gameLifecycleService).claimWin(gameId, playerName);
    }


    /**
     * Protects the contract that WebSocket actions delegate the authenticated
     * player identity and request to the action service.
     */
    @Test
    @DisplayName("WS performAction - Success")
    void performAction_ShouldCallService() {
        PlayerActionRequest request = new PlayerActionRequest(PlayerAction.CALL, 0);
        
        gameController.performAction(gameId, request, principal);

        verify(playerActionService).processPlayerAction(eq(gameId), eq(request), eq(playerName));
    }

    /**
     * Protects the contract that ready messages mark the authenticated player ready.
     */
    @Test
    @DisplayName("WS markReady - Success")
    void markReady_ShouldCallService() {
        gameController.markReady(gameId, principal);

        verify(gameLifecycleService).markPlayerReadyForNextHand(gameId, playerName);
    }
}

