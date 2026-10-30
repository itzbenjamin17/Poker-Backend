package com.pokergame.controller;

import com.pokergame.dto.request.PlayerActionRequest;
import com.pokergame.dto.response.GameEndResponse;
import com.pokergame.dto.response.PrivatePlayerState;
import com.pokergame.dto.response.PublicGameStateResponse;
import com.pokergame.dto.response.PublicPlayerState;
import com.pokergame.enums.GamePhase;
import com.pokergame.enums.PlayerAction;
import com.pokergame.enums.ReadyGateState;
import com.pokergame.exception.ConflictException;
import com.pokergame.exception.ResourceNotFoundException;
import com.pokergame.exception.UnauthorisedActionException;
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
import java.util.List;

import org.springframework.security.access.AccessDeniedException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
     * Verifies that GET /api/game/{gameId}/state returns 404 when the game has already finished.
     */
    @Test
    @DisplayName("GET /api/game/{gameId}/state - Game Over (404)")
    void getGameState_GameOver_ShouldReturn404() throws Exception {
        Game game = mock(Game.class);
        when(game.isGameOver()).thenReturn(true);
        when(gameLifecycleService.gameExists(gameId)).thenReturn(true);
        when(gameLifecycleService.playerExistsInGame(gameId, playerName)).thenReturn(true);
        when(gameLifecycleService.getGame(gameId)).thenReturn(game);

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
        PrivatePlayerState response = new PrivatePlayerState("p1-id", 1L, Collections.emptyList());

        when(gameLifecycleService.gameExists(gameId)).thenReturn(true);
        when(gameLifecycleService.playerExistsInGame(gameId, playerName)).thenReturn(true);
        when(gameLifecycleService.getGame(gameId)).thenReturn(game);
        when(gameStateService.getPrivatePlayerStateSnapshot(game, playerName)).thenReturn(response);

        mockMvc.perform(get("/api/game/{gameId}/private-state", gameId)
                .principal(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.playerId").value("p1-id"))
                .andExpect(jsonPath("$.handNumber").value(1));
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

        verify(gameLifecycleService).departParticipants(gameId, List.of(playerName));
    }

    /**
     * Verifies the expected response for POST /api/game/{gameId}/claim-win - Success.
     */
    @Test
    @DisplayName("POST /api/game/{gameId}/claim-win - Success")
    void claimWin_Success() throws Exception {
        GameEndResponse endResponse = new GameEndResponse("GAME_END", gameId, playerName, 1000, true, "Winner", null);
        when(gameLifecycleService.claimWin(gameId, playerName)).thenReturn(endResponse);

        mockMvc.perform(post("/api/game/{gameId}/claim-win", gameId)
                .principal(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Win claimed successfully"))
                .andExpect(jsonPath("$.data.winner").value(playerName))
                .andExpect(jsonPath("$.data.winnerChips").value(1000));

        verify(gameLifecycleService).claimWin(gameId, playerName);
    }

    @Test
    @DisplayName("POST /api/game/{gameId}/claim-win - Returns ApiResponse<GameEndResponse> with valid terminal state and winner")
    void claimWin_ReturnsApiResponseWithValidTerminalStateAndWinner() throws Exception {
        PublicPlayerState playerState = new PublicPlayerState("p-1", playerName, 1000, 0, "CONNECTED", false, false, false,
                false, false, null, List.of(), true, null, null, null, false);
        PublicGameStateResponse finalState = new PublicGameStateResponse(
                6, 0, List.of(), 0, GamePhase.SHOWDOWN, 0, List.of(),
                List.of(playerState),
                null, null, List.of(playerName), null, false, null, false, null, false, null,
                true, null, List.of(), null, null, 1, ReadyGateState.CLOSED, System.currentTimeMillis(), 1);
        GameEndResponse endResponse = new GameEndResponse("GAME_END", gameId, playerName, 1000, true, "Winner", finalState);
        when(gameLifecycleService.claimWin(gameId, playerName)).thenReturn(endResponse);

        mockMvc.perform(post("/api/game/{gameId}/claim-win", gameId)
                .principal(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Win claimed successfully"))
                .andExpect(jsonPath("$.data.type").value("GAME_END"))
                .andExpect(jsonPath("$.data.winner").value(playerName))
                .andExpect(jsonPath("$.data.winnerChips").value(1000))
                .andExpect(jsonPath("$.data.isForfeit").value(true))
                .andExpect(jsonPath("$.data.finalState.maxPlayers").value(6))
                .andExpect(jsonPath("$.data.finalState.players[0].name").value(playerName))
                .andExpect(jsonPath("$.data.finalState.players[0].chips").value(1000))
                .andExpect(jsonPath("$.data.finalState.players[0].isWinner").value(true));

        verify(gameLifecycleService).claimWin(gameId, playerName);
    }

    @Test
    @DisplayName("POST /api/game/{gameId}/claim-win - Forbidden when principal token room does not match")
    void claimWin_WrongRoomId_ThrowsForbidden() throws Exception {
        PlayerPrincipal wrongPrincipal = new PlayerPrincipal(playerName, "other-room");
        Authentication wrongAuth = new PreAuthenticatedAuthenticationToken(wrongPrincipal, "token", Collections.emptyList());

        mockMvc.perform(post("/api/game/{gameId}/claim-win", gameId)
                .principal(wrongAuth))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").value("Token is not valid for this game."));

        verify(gameLifecycleService, never()).claimWin(anyString(), anyString());
    }

    @Test
    @DisplayName("GET /api/game/{gameId}/review - Success (200 OK)")
    void getGameReview_Success() throws Exception {
        GameEndResponse endResponse = new GameEndResponse("GAME_END", gameId, playerName, 1000, false, "Winner", null);
        when(gameLifecycleService.getGameReview(gameId, playerName)).thenReturn(endResponse);

        mockMvc.perform(get("/api/game/{gameId}/review", gameId)
                .principal(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.winner").value(playerName))
                .andExpect(jsonPath("$.winnerChips").value(1000))
                .andExpect(jsonPath("$.type").value("GAME_END"));

        verify(gameLifecycleService).getGameReview(gameId, playerName);
    }

    @Test
    @DisplayName("GET /api/game/{gameId}/review - Returns 200 with terminal GameEndResponse after game is over")
    void getGameReview_AfterGameOver_Returns200WithTerminalGameEndResponse() throws Exception {
        PublicPlayerState playerState = new PublicPlayerState("p-1", playerName, 1000, 0, "CONNECTED", false, false, false,
                false, false, null, List.of(), true, null, null, null, false);
        PublicGameStateResponse finalState = new PublicGameStateResponse(
                6, 0, List.of(), 0, GamePhase.SHOWDOWN, 0, List.of(),
                List.of(playerState),
                null, null, List.of(playerName), null, false, null, false, null, false, null,
                true, null, List.of(), null, null, 1, ReadyGateState.CLOSED, System.currentTimeMillis(), 1);
        GameEndResponse endResponse = new GameEndResponse("GAME_END", gameId, playerName, 1000, false, "Winner", finalState);
        when(gameLifecycleService.getGameReview(gameId, playerName)).thenReturn(endResponse);

        mockMvc.perform(get("/api/game/{gameId}/review", gameId)
                .principal(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("GAME_END"))
                .andExpect(jsonPath("$.winner").value(playerName))
                .andExpect(jsonPath("$.winnerChips").value(1000))
                .andExpect(jsonPath("$.isForfeit").value(false))
                .andExpect(jsonPath("$.finalState.maxPlayers").value(6))
                .andExpect(jsonPath("$.finalState.players[0].name").value(playerName));

        verify(gameLifecycleService).getGameReview(gameId, playerName);
    }

    @Test
    @DisplayName("GET /api/game/{gameId}/review - Conflict when game is not finished (409)")
    void getGameReview_GameNotFinished() throws Exception {
        when(gameLifecycleService.getGameReview(gameId, playerName))
                .thenThrow(new ConflictException("Game has not finished"));

        mockMvc.perform(get("/api/game/{gameId}/review", gameId)
                .principal(auth))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value("Game has not finished"));
    }

    @Test
    @DisplayName("GET /api/game/{gameId}/review - Not Found when absent or expired (404)")
    void getGameReview_NotFound() throws Exception {
        when(gameLifecycleService.getGameReview(gameId, playerName))
                .thenThrow(new ResourceNotFoundException("Game not found"));

        mockMvc.perform(get("/api/game/{gameId}/review", gameId)
                .principal(auth))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("GET /api/game/{gameId}/review - Forbidden when token room does not match (403)")
    void getGameReview_WrongTokenRoom() throws Exception {
        PlayerPrincipal wrongPrincipal = new PlayerPrincipal(playerName, "other-room");
        Authentication wrongAuth = new PreAuthenticatedAuthenticationToken(wrongPrincipal, "token", Collections.emptyList());

        mockMvc.perform(get("/api/game/{gameId}/review", gameId)
                .principal(wrongAuth))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("GET /api/game/{gameId}/review - Forbidden when player not in terminal roster (403)")
    void getGameReview_NotInTerminalRoster() throws Exception {
        when(gameLifecycleService.getGameReview(gameId, playerName))
                .thenThrow(new UnauthorisedActionException("You are not part of this game's terminal result."));

        mockMvc.perform(get("/api/game/{gameId}/review", gameId)
                .principal(auth))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("GET /api/game/{gameId}/review - Success with matching query player name (200 OK)")
    void getGameReview_MatchingQueryPlayerName_Success() throws Exception {
        GameEndResponse endResponse = new GameEndResponse("GAME_END", gameId, playerName, 1000, false, "Winner", null);
        when(gameLifecycleService.getGameReview(gameId, playerName)).thenReturn(endResponse);

        mockMvc.perform(get("/api/game/{gameId}/review", gameId)
                .param("playerName", playerName)
                .principal(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.winner").value(playerName))
                .andExpect(jsonPath("$.winnerChips").value(1000))
                .andExpect(jsonPath("$.type").value("GAME_END"));

        verify(gameLifecycleService).getGameReview(gameId, playerName);
    }

    @Test
    @DisplayName("GET /api/game/{gameId}/review - Forbidden when query player name mismatches principal (403)")
    void getGameReview_QueryPlayerNameMismatch_Forbidden() throws Exception {
        mockMvc.perform(get("/api/game/{gameId}/review", gameId)
                .param("playerName", "different-player")
                .principal(auth))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").value("Query player name does not match authenticated player."));

        verify(gameLifecycleService, never()).getGameReview(anyString(), anyString());
    }

    @Test
    @DisplayName("GET /api/game/{gameId}/review - Forbidden when unauthenticated (403)")
    void getGameReview_Unauthenticated_Forbidden() throws Exception {
        mockMvc.perform(get("/api/game/{gameId}/review", gameId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").value("Invalid authentication principal"));

        verify(gameLifecycleService, never()).getGameReview(anyString(), anyString());
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

    @Test
    @DisplayName("GET /api/game/{gameId}/state - Unauthorized when membership token is stale (401)")
    void getGameState_StaleMembershipToken_ShouldReturnUnauthorized401() throws Exception {
        PlayerPrincipal stalePrincipal = new PlayerPrincipal(playerName, gameId, "stale-mid-1");
        Authentication staleAuth = new PreAuthenticatedAuthenticationToken(stalePrincipal, "token", Collections.emptyList());

        doThrow(new AccessDeniedException("Token membership is stale or invalid."))
                .when(gameLifecycleService).validatePlayerMembership(gameId, playerName, "stale-mid-1");

        mockMvc.perform(get("/api/game/{gameId}/state", gameId)
                .principal(staleAuth))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.message").value("Token membership is stale or invalid."));
    }

    @Test
    @DisplayName("GET /api/game/{gameId}/private-state - Unauthorized when membership token is stale (401)")
    void getPrivateState_StaleMembershipToken_ShouldReturnUnauthorized401() throws Exception {
        PlayerPrincipal stalePrincipal = new PlayerPrincipal(playerName, gameId, "stale-mid-2");
        Authentication staleAuth = new PreAuthenticatedAuthenticationToken(stalePrincipal, "token", Collections.emptyList());

        doThrow(new AccessDeniedException("Token membership is stale or invalid."))
                .when(gameLifecycleService).validatePlayerMembership(gameId, playerName, "stale-mid-2");

        mockMvc.perform(get("/api/game/{gameId}/private-state", gameId)
                .principal(staleAuth))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.message").value("Token membership is stale or invalid."));
    }

    @Test
    @DisplayName("POST /api/game/{gameId}/leave - Unauthorized when membership token is stale (401)")
    void leaveGame_StaleMembershipToken_ShouldReturnUnauthorized401() throws Exception {
        PlayerPrincipal stalePrincipal = new PlayerPrincipal(playerName, gameId, "stale-mid-3");
        Authentication staleAuth = new PreAuthenticatedAuthenticationToken(stalePrincipal, "token", Collections.emptyList());

        doThrow(new AccessDeniedException("Token membership is stale or invalid."))
                .when(gameLifecycleService).validatePlayerMembership(gameId, playerName, "stale-mid-3");

        mockMvc.perform(post("/api/game/{gameId}/leave", gameId)
                .principal(staleAuth))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"));

        verify(gameLifecycleService, never()).departParticipants(anyString(), anyList());
    }

    @Test
    @DisplayName("POST /api/game/{gameId}/claim-win - Unauthorized when membership token is stale (401)")
    void claimWin_StaleMembershipToken_ShouldReturnUnauthorized401() throws Exception {
        PlayerPrincipal stalePrincipal = new PlayerPrincipal(playerName, gameId, "stale-mid-4");
        Authentication staleAuth = new PreAuthenticatedAuthenticationToken(stalePrincipal, "token", Collections.emptyList());

        doThrow(new AccessDeniedException("Token membership is stale or invalid."))
                .when(gameLifecycleService).validatePlayerMembership(gameId, playerName, "stale-mid-4");

        mockMvc.perform(post("/api/game/{gameId}/claim-win", gameId)
                .principal(staleAuth))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"));

        verify(gameLifecycleService, never()).claimWin(anyString(), anyString());
    }

    @Test
    @DisplayName("WS performAction - Throws AccessDeniedException when membership token is stale")
    void performAction_StaleMembershipToken_ShouldThrowAccessDenied() {
        PlayerPrincipal stalePrincipal = new PlayerPrincipal(playerName, gameId, "stale-mid-ws");
        PlayerActionRequest request = new PlayerActionRequest(PlayerAction.CALL, 0);

        doThrow(new AccessDeniedException("Token membership is stale or invalid."))
                .when(gameLifecycleService).validatePlayerMembership(gameId, playerName, "stale-mid-ws");

        assertThatThrownBy(() -> gameController.performAction(gameId, request, stalePrincipal))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Token membership is stale or invalid.");

        verify(playerActionService, never()).processPlayerAction(anyString(), any(), anyString());
    }
}

