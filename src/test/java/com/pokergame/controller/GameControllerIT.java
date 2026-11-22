package com.pokergame.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import com.pokergame.security.PlayerPrincipal;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Tests the game REST endpoints and STOMP handlers through the web layer with the real room lock. */
@Tag("integration")
class GameControllerIT extends AbstractControllerSliceIT {
    private static final String GAME_ID = "game-123";
    private static final String PLAYER = "Player1";
    private static final String MEMBERSHIP_ID = "mem-1";
    private static final String STALE_MEMBERSHIP_ID = "stale-mid";
    private static final String STALE_MEMBERSHIP_MESSAGE = "Token membership is stale or invalid.";
    private static final String WRONG_GAME_MESSAGE = "Token is not valid for this game.";

    /** Every secured HTTP endpoint that binds the token to the game and validates membership. */
    private enum GameEndpoint {
        STATE("GET /api/game/{gameId}/state") {
            @Override
            MockHttpServletRequestBuilder request(String gameId) {
                return get("/api/game/{gameId}/state", gameId);
            }
        },
        PRIVATE_STATE("GET /api/game/{gameId}/private-state") {
            @Override
            MockHttpServletRequestBuilder request(String gameId) {
                return get("/api/game/{gameId}/private-state", gameId);
            }
        },
        REVIEW("GET /api/game/{gameId}/review") {
            @Override
            MockHttpServletRequestBuilder request(String gameId) {
                return get("/api/game/{gameId}/review", gameId);
            }
        },
        CLAIM_WIN("POST /api/game/{gameId}/claim-win") {
            @Override
            MockHttpServletRequestBuilder request(String gameId) {
                return post("/api/game/{gameId}/claim-win", gameId);
            }
        };

        private final String label;

        GameEndpoint(String label) {
            this.label = label;
        }

        abstract MockHttpServletRequestBuilder request(String gameId);

        @Override
        public String toString() {
            return label;
        }
    }

    /** The STOMP message handlers, invoked directly with a principal as the message broker would supply. */
    private enum StompHandler {
        PERFORM_ACTION {
            @Override
            void invoke(GameController controller, String gameId, PlayerPrincipal principal) {
                controller.performAction(gameId, new PlayerActionRequest(PlayerAction.CALL, 0), principal);
            }
        },
        MARK_READY {
            @Override
            void invoke(GameController controller, String gameId, PlayerPrincipal principal) {
                controller.markReady(gameId, principal);
            }
        },
        UNMARK_READY {
            @Override
            void invoke(GameController controller, String gameId, PlayerPrincipal principal) {
                controller.unmarkReady(gameId, principal);
            }
        };

        abstract void invoke(GameController controller, String gameId, PlayerPrincipal principal);
    }

    @Autowired
    private GameController gameController;

    private final Authentication auth = authenticated(PLAYER, GAME_ID, MEMBERSHIP_ID);
    private final AtomicBoolean lockHeldDuringService = new AtomicBoolean();

    // ---------------------------------------------------------------------------------------------
    // Public and private state
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("GET /api/game/{gameId}/state - Returns the public snapshot for a player in the live game")
    void getGameState_PlayerInLiveGame_ReturnsPublicSnapshot() throws Exception {
        Game game = stubLiveGameWithPlayer();
        when(gameStateService.getPublicGameStateSnapshot(game)).thenAnswer(recordingLockHeld(
                GAME_ID, lockHeldDuringService, state(GamePhase.PRE_FLOP, 100, List.of(), null)));

        mockMvc.perform(get("/api/game/{gameId}/state", GAME_ID).principal(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pot").value(100))
                .andExpect(jsonPath("$.phase").value("PRE_FLOP"));

        verify(gameLifecycleService).validatePlayerMembership(GAME_ID, PLAYER, MEMBERSHIP_ID);
        assertThat(lockHeldDuringService).isTrue();
    }

    @Test
    @DisplayName("GET /api/game/{gameId}/private-state - Returns the caller's own private snapshot")
    void getPrivateState_PlayerInLiveGame_ReturnsPrivateSnapshot() throws Exception {
        Game game = stubLiveGameWithPlayer();
        PrivatePlayerState privateState = new PrivatePlayerState("p1-id", 1L, Collections.emptyList());
        when(gameStateService.getPrivatePlayerStateSnapshot(game, PLAYER)).thenAnswer(
                recordingLockHeld(GAME_ID, lockHeldDuringService, privateState));

        mockMvc.perform(get("/api/game/{gameId}/private-state", GAME_ID).principal(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.playerId").value("p1-id"))
                .andExpect(jsonPath("$.handNumber").value(1));

        verify(gameLifecycleService).validatePlayerMembership(GAME_ID, PLAYER, MEMBERSHIP_ID);
        assertThat(lockHeldDuringService).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = GameEndpoint.class, names = {"STATE", "PRIVATE_STATE"})
    @DisplayName("Live game state endpoints - Unknown game returns 404")
    void liveGameEndpoint_UnknownGame_ReturnsNotFound(GameEndpoint endpoint) throws Exception {
        when(gameLifecycleService.gameExists(GAME_ID)).thenReturn(false);

        mockMvc.perform(endpoint.request(GAME_ID).principal(auth))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Game not found"));

        verifyNoInteractions(gameStateService);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = GameEndpoint.class, names = {"STATE", "PRIVATE_STATE"})
    @DisplayName("Live game state endpoints - Game that cannot be loaded returns 404")
    void liveGameEndpoint_GameCannotBeLoaded_ReturnsNotFound(GameEndpoint endpoint) throws Exception {
        when(gameLifecycleService.gameExists(GAME_ID)).thenReturn(true);
        when(gameLifecycleService.playerExistsInGame(GAME_ID, PLAYER)).thenReturn(true);
        when(gameLifecycleService.getGame(GAME_ID)).thenReturn(null);

        mockMvc.perform(endpoint.request(GAME_ID).principal(auth))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Game not found"));

        verifyNoInteractions(gameStateService);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = GameEndpoint.class, names = {"STATE", "PRIVATE_STATE"})
    @DisplayName("Live game state endpoints - Finished game returns 404")
    void liveGameEndpoint_FinishedGame_ReturnsNotFound(GameEndpoint endpoint) throws Exception {
        Game game = stubLiveGameWithPlayer();
        when(game.isGameOver()).thenReturn(true);

        mockMvc.perform(endpoint.request(GAME_ID).principal(auth))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Game not found"));

        verifyNoInteractions(gameStateService);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = GameEndpoint.class, names = {"STATE", "PRIVATE_STATE"})
    @DisplayName("Live game state endpoints - Player no longer in the game returns 403")
    void liveGameEndpoint_PlayerNoLongerInGame_ReturnsForbidden(GameEndpoint endpoint) throws Exception {
        when(gameLifecycleService.gameExists(GAME_ID)).thenReturn(true);
        when(gameLifecycleService.playerExistsInGame(GAME_ID, PLAYER)).thenReturn(false);

        mockMvc.perform(endpoint.request(GAME_ID).principal(auth))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("You are no longer part of this game."));

        verifyNoInteractions(gameStateService);
    }

    // ---------------------------------------------------------------------------------------------
    // Claim win and post-game review
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("POST /api/game/{gameId}/claim-win - Returns the final game state with the forfeit winner")
    void claimWin_RemainingPlayer_ReturnsFinalGameStateWithWinner() throws Exception {
        when(gameLifecycleService.claimWin(GAME_ID, PLAYER)).thenAnswer(
                recordingLockHeld(GAME_ID, lockHeldDuringService, finalGameState(true)));

        mockMvc.perform(post("/api/game/{gameId}/claim-win", GAME_ID).principal(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gameEnd.winner").value(PLAYER))
                .andExpect(jsonPath("$.gameEnd.winnerChips").value(1000))
                .andExpect(jsonPath("$.gameEnd.isForfeit").value(true))
                .andExpect(jsonPath("$.maxPlayers").value(6))
                .andExpect(jsonPath("$.players[0].name").value(PLAYER))
                .andExpect(jsonPath("$.players[0].chips").value(1000))
                .andExpect(jsonPath("$.players[0].isWinner").value(true));

        verify(gameLifecycleService).validatePlayerMembership(GAME_ID, PLAYER, MEMBERSHIP_ID);
        assertThat(lockHeldDuringService).isTrue();
    }

    @Test
    @DisplayName("GET /api/game/{gameId}/review - Finished game returns the final game state")
    void getGameReview_FinishedGame_ReturnsFinalGameStateWithWinner() throws Exception {
        when(gameLifecycleService.getGameReview(GAME_ID, PLAYER)).thenAnswer(
                recordingLockHeld(GAME_ID, lockHeldDuringService, finalGameState(false)));

        mockMvc.perform(get("/api/game/{gameId}/review", GAME_ID).principal(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gameEnd.winner").value(PLAYER))
                .andExpect(jsonPath("$.gameEnd.winnerChips").value(1000))
                .andExpect(jsonPath("$.gameEnd.isForfeit").value(false))
                .andExpect(jsonPath("$.maxPlayers").value(6))
                .andExpect(jsonPath("$.players[0].name").value(PLAYER));

        verify(gameLifecycleService).validatePlayerMembership(GAME_ID, PLAYER, MEMBERSHIP_ID);
        assertThat(lockHeldDuringService).isTrue();
    }

    @Test
    @DisplayName("GET /api/game/{gameId}/review - Conflict when game is not finished (409)")
    void getGameReview_GameNotFinished_ReturnsConflict() throws Exception {
        when(gameLifecycleService.getGameReview(GAME_ID, PLAYER))
                .thenThrow(new ConflictException("Game has not finished"));

        mockMvc.perform(get("/api/game/{gameId}/review", GAME_ID).principal(auth))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail").value("Game has not finished"));
    }

    @Test
    @DisplayName("GET /api/game/{gameId}/review - Not found when absent or expired (404)")
    void getGameReview_AbsentOrExpiredGame_ReturnsNotFound() throws Exception {
        when(gameLifecycleService.getGameReview(GAME_ID, PLAYER))
                .thenThrow(new ResourceNotFoundException("Game not found"));

        mockMvc.perform(get("/api/game/{gameId}/review", GAME_ID).principal(auth))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Game not found"));
    }

    @Test
    @DisplayName("GET /api/game/{gameId}/review - Forbidden when player is not in the finished-game roster (403)")
    void getGameReview_PlayerNotInFinishedGameRoster_ReturnsForbidden() throws Exception {
        when(gameLifecycleService.getGameReview(GAME_ID, PLAYER))
                .thenThrow(new UnauthorisedActionException("You are not part of this game's game result."));

        mockMvc.perform(get("/api/game/{gameId}/review", GAME_ID).principal(auth))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.detail").value("You are not part of this game's game result."));
    }

    @Test
    @DisplayName("GET /api/game/{gameId}/review - Ignores spoofed query playerName and uses authenticated principal")
    void getGameReview_SpoofedQueryPlayerName_UsesAuthenticatedPrincipal() throws Exception {
        // Only the authenticated player's name is stubbed: any other lookup returns no body.
        when(gameLifecycleService.getGameReview(GAME_ID, PLAYER)).thenReturn(finalGameState(false));

        mockMvc.perform(get("/api/game/{gameId}/review", GAME_ID)
                        .param("playerName", "different-player")
                        .principal(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gameEnd.winner").value(PLAYER))
                .andExpect(jsonPath("$.gameEnd.winnerChips").value(1000));
    }

    // ---------------------------------------------------------------------------------------------
    // Token binding and membership across every secured HTTP endpoint
    // ---------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @EnumSource(GameEndpoint.class)
    @DisplayName("Secured game endpoints - Token issued for another game returns 403 before any service call")
    void securedEndpoint_TokenForAnotherGame_ReturnsForbiddenBeforeAnyServiceCall(GameEndpoint endpoint)
            throws Exception {
        mockMvc.perform(endpoint.request(GAME_ID).principal(authenticated(PLAYER, "other-room", MEMBERSHIP_ID)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.detail").value(WRONG_GAME_MESSAGE));

        verifyNoInteractions(gameLifecycleService, gameStateService);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(GameEndpoint.class)
    @DisplayName("Secured game endpoints - Stale membership token returns 401 before any game is read")
    void securedEndpoint_StaleMembershipToken_ReturnsUnauthorizedBeforeAnyGameRead(GameEndpoint endpoint)
            throws Exception {
        doThrow(new AccessDeniedException(STALE_MEMBERSHIP_MESSAGE))
                .when(gameLifecycleService).validatePlayerMembership(GAME_ID, PLAYER, STALE_MEMBERSHIP_ID);

        mockMvc.perform(endpoint.request(GAME_ID).principal(authenticated(PLAYER, GAME_ID, STALE_MEMBERSHIP_ID)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.title").value("Unauthorized"))
                .andExpect(jsonPath("$.detail").value(STALE_MEMBERSHIP_MESSAGE));

        // The membership guard is the only call: no game lookup, review, claim, or snapshot happened.
        verify(gameLifecycleService).validatePlayerMembership(GAME_ID, PLAYER, STALE_MEMBERSHIP_ID);
        verifyNoMoreInteractions(gameLifecycleService);
        verifyNoInteractions(gameStateService);
    }

    // ---------------------------------------------------------------------------------------------
    // STOMP handlers
    // ---------------------------------------------------------------------------------------------

    /** The STOMP handlers are not reachable through MockMvc, so they are called directly. */
    @Nested
    @DisplayName("Direct STOMP handler guards and delegation")
    class DirectStompHandlers {
        private final PlayerPrincipal principal = new PlayerPrincipal(PLAYER, GAME_ID, MEMBERSHIP_ID);

        @Test
        @DisplayName("performAction - Valid member's action is delegated under the room lock")
        void performAction_ValidMember_DelegatesUnderRoomLock() {
            PlayerActionRequest request = new PlayerActionRequest(PlayerAction.CALL, 0);
            doAnswer(recordingLockHeld(GAME_ID, lockHeldDuringService, null))
                    .when(playerActionService).processPlayerAction(GAME_ID, request, PLAYER);

            gameController.performAction(GAME_ID, request, principal);

            verify(gameLifecycleService).validatePlayerMembership(GAME_ID, PLAYER, MEMBERSHIP_ID);
            verify(playerActionService).processPlayerAction(GAME_ID, request, PLAYER);
            assertThat(lockHeldDuringService).isTrue();
        }

        @Test
        @DisplayName("markReady - Valid member is marked ready under the room lock")
        void markReady_ValidMember_DelegatesUnderRoomLock() {
            doAnswer(recordingLockHeld(GAME_ID, lockHeldDuringService, null))
                    .when(gameLifecycleService).markPlayerReadyForNextHand(GAME_ID, PLAYER);

            gameController.markReady(GAME_ID, principal);

            verify(gameLifecycleService).validatePlayerMembership(GAME_ID, PLAYER, MEMBERSHIP_ID);
            verify(gameLifecycleService).markPlayerReadyForNextHand(GAME_ID, PLAYER);
            assertThat(lockHeldDuringService).isTrue();
        }

        @Test
        @DisplayName("unmarkReady - Valid member is unmarked under the room lock")
        void unmarkReady_ValidMember_DelegatesUnderRoomLock() {
            doAnswer(recordingLockHeld(GAME_ID, lockHeldDuringService, null))
                    .when(gameLifecycleService).unmarkPlayerReadyForNextHand(GAME_ID, PLAYER);

            gameController.unmarkReady(GAME_ID, principal);

            verify(gameLifecycleService).validatePlayerMembership(GAME_ID, PLAYER, MEMBERSHIP_ID);
            verify(gameLifecycleService).unmarkPlayerReadyForNextHand(GAME_ID, PLAYER);
            assertThat(lockHeldDuringService).isTrue();
        }

        @ParameterizedTest
        @EnumSource(StompHandler.class)
        @DisplayName("Token issued for another game is rejected before any service call")
        void handler_TokenForAnotherGame_ThrowsForbiddenBeforeAnyServiceCall(StompHandler handler) {
            PlayerPrincipal otherGame = new PlayerPrincipal(PLAYER, "other-room", MEMBERSHIP_ID);

            assertThatThrownBy(() -> handler.invoke(gameController, GAME_ID, otherGame))
                    .isInstanceOf(UnauthorisedActionException.class)
                    .hasMessage(WRONG_GAME_MESSAGE);

            verifyNoInteractions(gameLifecycleService, playerActionService);
        }

        @ParameterizedTest
        @EnumSource(StompHandler.class)
        @DisplayName("Stale membership token is rejected before any game work")
        void handler_StaleMembershipToken_ThrowsAccessDeniedBeforeAnyGameWork(StompHandler handler) {
            PlayerPrincipal stale = new PlayerPrincipal(PLAYER, GAME_ID, STALE_MEMBERSHIP_ID);
            doThrow(new AccessDeniedException(STALE_MEMBERSHIP_MESSAGE))
                    .when(gameLifecycleService).validatePlayerMembership(GAME_ID, PLAYER, STALE_MEMBERSHIP_ID);

            assertThatThrownBy(() -> handler.invoke(gameController, GAME_ID, stale))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessage(STALE_MEMBERSHIP_MESSAGE);

            verify(gameLifecycleService).validatePlayerMembership(GAME_ID, PLAYER, STALE_MEMBERSHIP_ID);
            verifyNoMoreInteractions(gameLifecycleService);
            verifyNoInteractions(playerActionService);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------------------------------

    /** Stubs a live game that {@link #PLAYER} still belongs to and returns it. */
    private Game stubLiveGameWithPlayer() {
        Game game = mock(Game.class);
        when(gameLifecycleService.gameExists(GAME_ID)).thenReturn(true);
        when(gameLifecycleService.playerExistsInGame(GAME_ID, PLAYER)).thenReturn(true);
        when(gameLifecycleService.getGame(GAME_ID)).thenReturn(game);
        return game;
    }

    /** Builds the finished-game response whose roster holds {@link #PLAYER} as the 1,000-chip winner. */
    private static PublicGameStateResponse finalGameState(boolean forfeit) {
        PublicGameStateResponse.PlayerState winner = new PublicGameStateResponse.PlayerState(
                "p-1", PLAYER, 1000, 0, "CONNECTED", false, false, false,
                false, false, null, List.of(), true, null, null, null, false);
        return state(GamePhase.SHOWDOWN, 0, List.of(winner),
                PublicGameStateResponse.GameEnd.won(PLAYER, 1000, forfeit, "Winner"));
    }

    private static PublicGameStateResponse state(GamePhase phase, int pot,
            List<PublicGameStateResponse.PlayerState> players, PublicGameStateResponse.GameEnd gameEnd) {
        return new PublicGameStateResponse(
                6, pot, Collections.emptyList(), 0, phase, 0,
                Collections.emptyList(), players, null, null,
                gameEnd != null && gameEnd.winner() != null ? List.of(gameEnd.winner()) : null,
                null, false, null, false, null, false, null,
                gameEnd != null, null, Collections.emptyList(), null, null, 1L, ReadyGateState.CLOSED,
                0L, 1L, null, null, gameEnd);
    }
}
