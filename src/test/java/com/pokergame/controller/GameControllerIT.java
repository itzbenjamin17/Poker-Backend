package com.pokergame.controller;

import com.pokergame.dto.request.PlayerActionRequest;
import com.pokergame.dto.response.PrivatePlayerState;
import com.pokergame.dto.response.PublicGameStateResponse;
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
import com.pokergame.util.RoomCoordinator;
import com.pokergame.util.LockLease;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
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
import com.pokergame.security.EndpointRateLimitFilter;
import com.pokergame.security.PayloadSizeFilter;

/** Tests game controller behavior. */
@Tag("integration")
@WebMvcTest(GameController.class)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
class GameControllerIT {

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
    private RoomCoordinator roomCoordinator;

    @MockitoBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @MockitoBean
    private EndpointRateLimitFilter endpointRateLimitFilter;

    @MockitoBean
    private PayloadSizeFilter payloadSizeFilter;

    private final String gameId = "game-123";
    private final String playerName = "Player1";
    private PlayerPrincipal principal;
    private Authentication auth;

    /**
     * Initializes the test fixtures before each scenario.
     */
    @BeforeEach
    void setUp() {
        when(roomCoordinator.acquire(anyString())).thenReturn(LockLease.none());
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
        PublicGameStateResponse response = state(6, 100, GamePhase.PRE_FLOP, 10, List.of(), playerName, "p1-id", null);

        when(gameLifecycleService.gameExists(gameId)).thenReturn(true);
        when(gameLifecycleService.playerExistsInGame(gameId, playerName)).thenReturn(true);
        when(gameLifecycleService.getGame(gameId)).thenReturn(game);
        when(gameStateService.getPublicGameStateSnapshot(game)).thenReturn(response);

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
                .andExpect(jsonPath("$.detail").value("Game not found"));
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
                .andExpect(jsonPath("$.detail").value("Game not found"));
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
                .andExpect(jsonPath("$.detail").value("Game not found"));
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
                .andExpect(jsonPath("$.detail").value("You are no longer part of this game."));
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
                .andExpect(jsonPath("$.detail").value("You are no longer part of this game."));
    }

    /**
     * Verifies the expected response for POST /api/game/{gameId}/claim-win - Success.
     */
    @Test
    @DisplayName("POST /api/game/{gameId}/claim-win - Success")
    void claimWin_Success() throws Exception {
        PublicGameStateResponse endResponse = state(6, 0, GamePhase.SHOWDOWN, 0, List.of(), null, null,
                PublicGameStateResponse.GameEnd.won(playerName, 1000, true, "Winner"));
        when(gameLifecycleService.claimWin(gameId, playerName)).thenReturn(endResponse);

        mockMvc.perform(post("/api/game/{gameId}/claim-win", gameId)
                .principal(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gameEnd.winner").value(playerName))
                .andExpect(jsonPath("$.gameEnd.winnerChips").value(1000));

        verify(gameLifecycleService).claimWin(gameId, playerName);
    }

    @Test
    @DisplayName("POST /api/game/{gameId}/claim-win - Returns PublicGameStateResponse with valid terminal state and winner")
    void claimWin_ReturnsPublicGameStateWithValidTerminalStateAndWinner() throws Exception {
        PublicGameStateResponse.PlayerState playerState = new PublicGameStateResponse.PlayerState(
                "p-1", playerName, 1000, 0, "CONNECTED", false, false, false,
                false, false, null, List.of(), true, null, null, null, false);
        PublicGameStateResponse endResponse = state(6, 0, GamePhase.SHOWDOWN, 0, List.of(playerState), null, null,
                PublicGameStateResponse.GameEnd.won(playerName, 1000, true, "Winner"));
        when(gameLifecycleService.claimWin(gameId, playerName)).thenReturn(endResponse);

        mockMvc.perform(post("/api/game/{gameId}/claim-win", gameId)
                .principal(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gameEnd.winner").value(playerName))
                .andExpect(jsonPath("$.gameEnd.winnerChips").value(1000))
                .andExpect(jsonPath("$.gameEnd.isForfeit").value(true))
                .andExpect(jsonPath("$.maxPlayers").value(6))
                .andExpect(jsonPath("$.players[0].name").value(playerName))
                .andExpect(jsonPath("$.players[0].chips").value(1000))
                .andExpect(jsonPath("$.players[0].isWinner").value(true));

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
                .andExpect(jsonPath("$.detail").value("Token is not valid for this game."));

        verify(gameLifecycleService, never()).claimWin(anyString(), anyString());
    }

    @Test
    @DisplayName("GET /api/game/{gameId}/review - Success (200 OK)")
    void getGameReview_Success() throws Exception {
        PublicGameStateResponse endResponse = state(6, 0, GamePhase.SHOWDOWN, 0, List.of(), null, null,
                PublicGameStateResponse.GameEnd.won(playerName, 1000, false, "Winner"));
        when(gameLifecycleService.getGameReview(gameId, playerName)).thenReturn(endResponse);

        mockMvc.perform(get("/api/game/{gameId}/review", gameId)
                .principal(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gameEnd.winner").value(playerName))
                .andExpect(jsonPath("$.gameEnd.winnerChips").value(1000));

        verify(gameLifecycleService).getGameReview(gameId, playerName);
    }

    @Test
    @DisplayName("GET /api/game/{gameId}/review - Returns 200 with terminal PublicGameStateResponse after game is over")
    void getGameReview_AfterGameOver_Returns200WithTerminalPublicGameState() throws Exception {
        PublicGameStateResponse.PlayerState playerState = new PublicGameStateResponse.PlayerState(
                "p-1", playerName, 1000, 0, "CONNECTED", false, false, false,
                false, false, null, List.of(), true, null, null, null, false);
        PublicGameStateResponse endResponse = state(6, 0, GamePhase.SHOWDOWN, 0, List.of(playerState), null, null,
                PublicGameStateResponse.GameEnd.won(playerName, 1000, false, "Winner"));
        when(gameLifecycleService.getGameReview(gameId, playerName)).thenReturn(endResponse);

        mockMvc.perform(get("/api/game/{gameId}/review", gameId)
                .principal(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gameEnd.winner").value(playerName))
                .andExpect(jsonPath("$.gameEnd.winnerChips").value(1000))
                .andExpect(jsonPath("$.gameEnd.isForfeit").value(false))
                .andExpect(jsonPath("$.maxPlayers").value(6))
                .andExpect(jsonPath("$.players[0].name").value(playerName));

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
                .andExpect(jsonPath("$.detail").value("Game has not finished"));
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
                .thenThrow(new UnauthorisedActionException("You are not part of this game's game result."));

        mockMvc.perform(get("/api/game/{gameId}/review", gameId)
                .principal(auth))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("GET /api/game/{gameId}/review - Ignores spoofed query playerName and uses authenticated principal")
    void getGameReview_IgnoresQueryPlayerNameAndUsesAuthenticatedPrincipal() throws Exception {
        PublicGameStateResponse endResponse = state(6, 0, GamePhase.SHOWDOWN, 0, List.of(), null, null,
                PublicGameStateResponse.GameEnd.won(playerName, 1000, false, "Winner"));
        when(gameLifecycleService.getGameReview(gameId, playerName)).thenReturn(endResponse);

        mockMvc.perform(get("/api/game/{gameId}/review", gameId)
                .param("playerName", "different-player")
                .principal(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gameEnd.winner").value(playerName))
                .andExpect(jsonPath("$.gameEnd.winnerChips").value(1000));

        verify(gameLifecycleService).getGameReview(gameId, playerName);
        verify(gameLifecycleService, never()).getGameReview(gameId, "different-player");
    }

    @Test
    @DisplayName("GET /api/game/{gameId}/review - Forbidden when unauthenticated (403)")
    void getGameReview_Unauthenticated_Forbidden() throws Exception {
        mockMvc.perform(get("/api/game/{gameId}/review", gameId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.detail").value("Invalid authentication principal"));

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

    /**
     * Protects the contract that unready messages clear the authenticated player's ready state.
     */
    @Test
    @DisplayName("WS unmarkReady - Success")
    void unmarkReady_ShouldCallService() {
        gameController.unmarkReady(gameId, principal);

        verify(gameLifecycleService).unmarkPlayerReadyForNextHand(gameId, playerName);
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
                .andExpect(jsonPath("$.title").value("Unauthorized"))
                .andExpect(jsonPath("$.detail").value("Token membership is stale or invalid."));
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
                .andExpect(jsonPath("$.title").value("Unauthorized"))
                .andExpect(jsonPath("$.detail").value("Token membership is stale or invalid."));
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
                .andExpect(jsonPath("$.title").value("Unauthorized"));

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

    /**
     * Membership boundary: a secured endpoint must be rejected with 401 whenever the
     * active membership boundary refuses the token. The boundary (validatePlayerMembership,
     * ADR-0004) fails closed for a null, blank, stale (mismatched), or absent-authoritative
     * membership, so each of those values must be refused before any game state is read.
     * Blank is represented by whitespace; null is covered by the @NullSource case.
     */
    @ParameterizedTest(name = "membershipId=[{0}]")
    @NullSource
    @ValueSource(strings = {"", "   ", "stale-does-not-match", "no-active-membership"})
    @DisplayName("GET /api/game/{gameId}/state - Unauthorized (401) for null/blank/stale/missing membership")
    void getGameState_RejectedMembership_ShouldReturnUnauthorized401(String membershipId) throws Exception {
        PlayerPrincipal rejectedPrincipal = new PlayerPrincipal(playerName, gameId, membershipId);
        Authentication rejectedAuth =
                new PreAuthenticatedAuthenticationToken(rejectedPrincipal, "token", Collections.emptyList());

        doThrow(new AccessDeniedException("Token membership is stale or invalid."))
                .when(gameLifecycleService).validatePlayerMembership(gameId, playerName, membershipId);

        mockMvc.perform(get("/api/game/{gameId}/state", gameId)
                .principal(rejectedAuth))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.title").value("Unauthorized"))
                .andExpect(jsonPath("$.detail").value("Token membership is stale or invalid."));

        // The boundary runs before game state is read, so no snapshot is produced for a refused token.
        verify(gameStateService, never()).getPublicGameStateSnapshot(any());
    }

    @Test
    @DisplayName("GET /api/game/{gameId}/state - 200 when membership matches the active id")
    void getGameState_MatchingMembership_ShouldReturnOk() throws Exception {
        String activeMembershipId = "active-mid";
        PlayerPrincipal matchingPrincipal = new PlayerPrincipal(playerName, gameId, activeMembershipId);
        Authentication matchingAuth =
                new PreAuthenticatedAuthenticationToken(matchingPrincipal, "token", Collections.emptyList());

        Game game = mock(Game.class);
        PublicGameStateResponse response = state(6, 100, GamePhase.PRE_FLOP, 10, List.of(), playerName, "p1-id", null);
        // A matching membership passes the boundary (no throw), so the request proceeds.
        when(gameLifecycleService.gameExists(gameId)).thenReturn(true);
        when(gameLifecycleService.playerExistsInGame(gameId, playerName)).thenReturn(true);
        when(gameLifecycleService.getGame(gameId)).thenReturn(game);
        when(gameStateService.getPublicGameStateSnapshot(game)).thenReturn(response);

        mockMvc.perform(get("/api/game/{gameId}/state", gameId)
                .principal(matchingAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pot").value(100));

        verify(gameLifecycleService).validatePlayerMembership(gameId, playerName, activeMembershipId);
    }

    private static PublicGameStateResponse state(int maxPlayers, int pot, GamePhase phase, int currentBet,
            List<PublicGameStateResponse.PlayerState> players, String currentPlayerName, String currentPlayerId,
            PublicGameStateResponse.GameEnd gameEnd) {
        return new PublicGameStateResponse(
                maxPlayers, pot, Collections.emptyList(), 0, phase, currentBet,
                Collections.emptyList(), players, currentPlayerName, currentPlayerId,
                gameEnd != null && gameEnd.winner() != null ? List.of(gameEnd.winner()) : null,
                null, false, null, false, null, false, null,
                gameEnd != null, null, Collections.emptyList(), null, null, 1L, ReadyGateState.CLOSED,
                0L, 1L, null, null, gameEnd);
    }
}

