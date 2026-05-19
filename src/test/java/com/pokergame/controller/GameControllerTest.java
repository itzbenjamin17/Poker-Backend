package com.pokergame.controller;

import com.pokergame.dto.request.PlayerActionRequest;
import com.pokergame.dto.response.PrivatePlayerState;
import com.pokergame.dto.response.PublicGameStateResponse;
import com.pokergame.enums.GamePhase;
import com.pokergame.enums.PlayerAction;
import com.pokergame.enums.ResponseMessage;
import com.pokergame.model.Game;
import com.pokergame.security.JwtAuthenticationFilter;
import com.pokergame.service.GameLifecycleService;
import com.pokergame.service.GameStateService;
import com.pokergame.service.PlayerActionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.security.Principal;
import java.util.Collections;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(GameController.class)
@AutoConfigureMockMvc(addFilters = false)
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
    private Principal principal;

    @BeforeEach
    void setUp() {
        principal = mock(Principal.class);
        when(principal.getName()).thenReturn(playerName);
    }

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
                .principal(() -> playerName))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pot").value(100))
                .andExpect(jsonPath("$.phase").value("PRE_FLOP"));
    }

    @Test
    @DisplayName("GET /api/game/{gameId}/state - Game Not Found")
    void getGameState_NotFound() throws Exception {
        when(gameLifecycleService.gameExists(gameId)).thenReturn(false);

        mockMvc.perform(get("/api/game/{gameId}/state", gameId)
                .principal(() -> playerName))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Game not found"));
    }

    @Test
    @DisplayName("GET /api/game/{gameId}/state - Game Null (404)")
    void getGameState_GameNull() throws Exception {
        when(gameLifecycleService.gameExists(gameId)).thenReturn(true);
        when(gameLifecycleService.playerExistsInGame(gameId, playerName)).thenReturn(true);
        when(gameLifecycleService.getGame(gameId)).thenReturn(null);

        mockMvc.perform(get("/api/game/{gameId}/state", gameId)
                .principal(() -> playerName))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Game not found"));
    }

    @Test
    @DisplayName("GET /api/game/{gameId}/state - Not In Game (403)")
    void getGameState_NotInGame() throws Exception {
        when(gameLifecycleService.gameExists(gameId)).thenReturn(true);
        when(gameLifecycleService.playerExistsInGame(gameId, playerName)).thenReturn(false);

        mockMvc.perform(get("/api/game/{gameId}/state", gameId)
                .principal(() -> playerName))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("You are no longer part of this game."));
    }

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
                .principal(() -> playerName))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.playerId").value("p1-id"));
    }

    @Test
    @DisplayName("GET /api/game/{gameId}/private-state - Not In Game (403)")
    void getPrivateState_NotInGame() throws Exception {
        when(gameLifecycleService.gameExists(gameId)).thenReturn(true);
        when(gameLifecycleService.playerExistsInGame(gameId, playerName)).thenReturn(false);

        mockMvc.perform(get("/api/game/{gameId}/private-state", gameId)
                .principal(() -> playerName))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("You are no longer part of this game."));
    }

    @Test
    @DisplayName("POST /api/game/{gameId}/leave - Success")
    void leaveGame_Success() throws Exception {
        mockMvc.perform(post("/api/game/{gameId}/leave", gameId)
                .principal(() -> playerName))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Successfully left game"));

        verify(gameLifecycleService).leaveGame(gameId, playerName);
    }

    @Test
    @DisplayName("POST /api/game/{gameId}/claim-win - Success")
    void claimWin_Success() throws Exception {
        mockMvc.perform(post("/api/game/{gameId}/claim-win", gameId)
                .principal(() -> playerName))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Win claimed successfully"));

        verify(gameLifecycleService).claimWin(gameId, playerName);
    }


    @Test
    @DisplayName("WS performAction - Success")
    void performAction_ShouldCallService() {
        PlayerActionRequest request = new PlayerActionRequest(PlayerAction.CALL, 0);
        
        gameController.performAction(gameId, request, principal);

        verify(playerActionService).processPlayerAction(eq(gameId), eq(request), eq(playerName));
    }

    @Test
    @DisplayName("WS markReady - Success")
    void markReady_ShouldCallService() {
        gameController.markReady(gameId, principal);

        verify(gameLifecycleService).markPlayerReadyForNextHand(gameId, playerName);
    }

    // Exception Handler Test
    @Test
    @DisplayName("WS handleMessageException - Sanitizes Technical Errors")
    void handleMessageException_SanitizesJacksonErrors() {
        Exception technicalException = new org.springframework.messaging.converter.MessageConversionException("Cannot deserialize something technical");
        Message<String> message = MessageBuilder.withPayload("bad payload")
                .setHeader("simpDestination", "/app/" + gameId + "/action")
                .build();

        gameController.handleMessageException(technicalException, principal, message);

        verify(gameStateService).sendPrivatePlayerNotification(
                eq(gameId),
                eq(playerName),
                eq("Invalid action request format. Please try again with a valid amount."),
                eq(ResponseMessage.ACTION_ERROR)
        );
    }

    @Test
    @DisplayName("WS handleMessageException - Truncates Long Messages")
    void handleMessageException_TruncatesLongMessages() {
        String longMessage = "This is a very long error message that should definitely be truncated because it exceeds the eighty character limit that we have established in the controller logic.";
        Exception ex = new RuntimeException(longMessage);
        Message<String> message = MessageBuilder.withPayload("info")
                .setHeader("simpDestination", "/app/" + gameId + "/action")
                .build();

        gameController.handleMessageException(ex, principal, message);

        // Expectation: "This is a very long error message that should definitely be truncated because..."
        verify(gameStateService).sendPrivatePlayerNotification(
                eq(gameId),
                eq(playerName),
                argThat(msg -> msg.length() <= 80 && msg.endsWith("...")),
                eq(ResponseMessage.ACTION_ERROR)
        );
    }

    @Test
    @DisplayName("WS handleMessageException - Null Destination")
    void handleMessageException_NullDestination() {
        Exception ex = new RuntimeException("Test error");
        Message<String> message = MessageBuilder.withPayload("payload").build();

        gameController.handleMessageException(ex, principal, message);

        verify(gameStateService).sendPrivatePlayerNotification(
                eq("unknown"),
                eq(playerName),
                eq("Test error"),
                eq(ResponseMessage.ACTION_ERROR)
        );
    }

    @Test
    @DisplayName("WS handleMessageException - Short Destination")
    void handleMessageException_ShortDestination() {
        Exception ex = new RuntimeException("Test error");
        Message<String> message = MessageBuilder.withPayload("payload")
                .setHeader("simpDestination", "/app")
                .build();

        gameController.handleMessageException(ex, principal, message);

        verify(gameStateService).sendPrivatePlayerNotification(
                eq("unknown"),
                eq(playerName),
                eq("Test error"),
                eq(ResponseMessage.ACTION_ERROR)
        );
    }

    @Test
    @DisplayName("WS handleMessageException - Exact 80 Characters")
    void handleMessageException_Exact80Characters() {
        String exact80Message = "12345678901234567890123456789012345678901234567890123456789012345678901234567890";
        Exception ex = new RuntimeException(exact80Message);
        Message<String> message = MessageBuilder.withPayload("payload")
                .setHeader("simpDestination", "/app/" + gameId + "/action")
                .build();

        gameController.handleMessageException(ex, principal, message);

        verify(gameStateService).sendPrivatePlayerNotification(
                eq(gameId),
                eq(playerName),
                eq(exact80Message), // Should not be truncated
                eq(ResponseMessage.ACTION_ERROR)
        );
    }
}
