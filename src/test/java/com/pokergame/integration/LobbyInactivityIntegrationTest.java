package com.pokergame.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.pokergame.dto.request.JoinRoomRequest;
import com.pokergame.integration.support.AbstractIntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.HttpClientErrorException;
import java.time.Clock;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class LobbyInactivityIntegrationTest extends AbstractIntegrationTestSupport {
    @MockitoBean Clock clock;

    private static final long T0 = 1_900_000_000_000L;
    private static final long THIRTY_MINUTES_MS = 30 * 60_000L;

    @Test
    void creationPublishesThirtyMinuteDeadlineAndServerTime() throws Exception {
        when(clock.millis()).thenReturn(T0);
        JsonNode created = createRoom(uniqueName("IdleLobby"), "Host", 6);
        JsonNode room = getRoom(created.path("roomId").asText(), created.path("token").asText());
        assertThat(room.path("inactivityDeadlineEpochMs").asLong()).isEqualTo(T0 + THIRTY_MINUTES_MS);
        assertThat(room.path("serverTimeEpochMs").asLong()).isEqualTo(T0);
    }

    @Test
    void acceptedJoinResetsDeadline() throws Exception {
        when(clock.millis()).thenReturn(T0);
        String roomName = uniqueName("JoinReset");
        JsonNode created = createRoom(roomName, "Host", 6);
        String roomId = created.path("roomId").asText();
        String hostToken = created.path("token").asText();

        long joinTime = T0 + 10 * 60_000L;
        when(clock.millis()).thenReturn(joinTime);
        joinRoom(roomName, "Guest");

        JsonNode room = getRoom(roomId, hostToken);
        assertThat(room.path("inactivityDeadlineEpochMs").asLong()).isEqualTo(joinTime + THIRTY_MINUTES_MS);
        assertThat(room.path("serverTimeEpochMs").asLong()).isEqualTo(joinTime);
    }

    @Test
    void acceptedPlayerLeaveResetsDeadline() throws Exception {
        when(clock.millis()).thenReturn(T0);
        String roomName = uniqueName("LeaveReset");
        JsonNode created = createRoom(roomName, "Host", 6);
        String roomId = created.path("roomId").asText();
        String hostToken = created.path("token").asText();

        long joinTime = T0 + 5 * 60_000L;
        when(clock.millis()).thenReturn(joinTime);
        JsonNode guest = joinRoom(roomName, "Guest");
        String guestToken = guest.path("token").asText();

        long leaveTime = T0 + 15 * 60_000L;
        when(clock.millis()).thenReturn(leaveTime);
        leaveRoom(roomId, guestToken);

        JsonNode room = getRoom(roomId, hostToken);
        assertThat(room.path("inactivityDeadlineEpochMs").asLong()).isEqualTo(leaveTime + THIRTY_MINUTES_MS);
        assertThat(room.path("serverTimeEpochMs").asLong()).isEqualTo(leaveTime);
    }

    @Test
    void hostLeaveClosesLobbyImmediately() throws Exception {
        when(clock.millis()).thenReturn(T0);
        String roomName = uniqueName("HostLeave");
        JsonNode created = createRoom(roomName, "Host", 6);
        String roomId = created.path("roomId").asText();
        String hostToken = created.path("token").asText();

        leaveRoom(roomId, hostToken);

        assertThatThrownBy(() -> getRoom(roomId, hostToken))
                .isInstanceOf(HttpClientErrorException.NotFound.class);
    }

    @Test
    void failedJoinDoesNotResetDeadline() throws Exception {
        when(clock.millis()).thenReturn(T0);
        String roomName = uniqueName("FailedJoin");
        JsonNode created = createRoom(roomName, "Host", 6);
        String roomId = created.path("roomId").asText();
        String hostToken = created.path("token").asText();

        long attemptTime = T0 + 10 * 60_000L;
        when(clock.millis()).thenReturn(attemptTime);

        // Attempting to join with the name "Host" which is already taken
        assertThatThrownBy(() -> joinRoom(roomName, "Host"))
                .isInstanceOf(HttpClientErrorException.BadRequest.class);

        JsonNode room = getRoom(roomId, hostToken);
        // Deadline remains based on original creation time
        assertThat(room.path("inactivityDeadlineEpochMs").asLong()).isEqualTo(T0 + THIRTY_MINUTES_MS);
    }

    @Test
    void exactExpiryClosesLobbyAndReturnsNotFound() throws Exception {
        when(clock.millis()).thenReturn(T0);
        JsonNode created = createRoom(uniqueName("ExpireLobby"), "Host", 6);
        String roomId = created.path("roomId").asText();
        String hostToken = created.path("token").asText();

        // Advance clock to past the 30-minute deadline
        when(clock.millis()).thenReturn(T0 + THIRTY_MINUTES_MS + 1L);

        assertThatThrownBy(() -> getRoom(roomId, hostToken))
                .isInstanceOf(HttpClientErrorException.NotFound.class);
    }

    @Test
    void delayedSchedulerDoesNotReviveExpiredLobbyOnJoin() throws Exception {
        when(clock.millis()).thenReturn(T0);
        String roomName = uniqueName("LateJoinRevive");
        JsonNode created = createRoom(roomName, "Host", 6);
        String roomId = created.path("roomId").asText();
        String hostToken = created.path("token").asText();

        // Time advanced beyond deadline
        when(clock.millis()).thenReturn(T0 + THIRTY_MINUTES_MS + 5_000L);

        // Join should be rejected and room should remain closed
        assertThatThrownBy(() -> joinRoom(roomName, "LateGuest"))
                .isInstanceOf(HttpClientErrorException.class);

        assertThatThrownBy(() -> getRoom(roomId, hostToken))
                .isInstanceOf(HttpClientErrorException.NotFound.class);
    }

    @Test
    void delayedSchedulerDoesNotReviveExpiredLobbyOnStart() throws Exception {
        when(clock.millis()).thenReturn(T0);
        String roomName = uniqueName("LateStartRevive");
        JsonNode created = createRoom(roomName, "Host", 6);
        String roomId = created.path("roomId").asText();
        String hostToken = created.path("token").asText();

        long joinTime = T0 + 60_000L;
        when(clock.millis()).thenReturn(joinTime);
        joinRoom(roomName, "Guest");

        long deadline = joinTime + THIRTY_MINUTES_MS;
        // Advance beyond deadline
        when(clock.millis()).thenReturn(deadline + 5_000L);

        assertThatThrownBy(() -> startGame(roomId, hostToken))
                .isInstanceOf(HttpClientErrorException.class);

        assertThatThrownBy(() -> getRoom(roomId, hostToken))
                .isInstanceOf(HttpClientErrorException.NotFound.class);
    }

    @Test
    void gameStartRetiresLobbyInactivityDeadline() throws Exception {
        when(clock.millis()).thenReturn(T0);
        String roomName = uniqueName("StartRetiresLobby");
        JsonNode created = createRoom(roomName, "Host", 6);
        String roomId = created.path("roomId").asText();
        String hostToken = created.path("token").asText();

        joinRoom(roomName, "Guest");
        when(clock.millis()).thenReturn(T0 + 2 * 60_000L);
        startGame(roomId, hostToken);

        // Advance past the 30-minute mark from creation
        when(clock.millis()).thenReturn(T0 + THIRTY_MINUTES_MS + 60_000L);

        // The running game is NOT destroyed by the lobby inactivity timeout, and has active-game inactivity deadline
        JsonNode room = getRoom(roomId, hostToken);
        assertThat(room.path("gameStarted").asBoolean()).isTrue();
        assertThat(room.path("inactivityDeadlineEpochMs").asLong()).isEqualTo(T0 + 2 * 60_000L + 15 * 60_000L);
    }

    private JsonNode getRoom(String roomId, String token) throws Exception {
        String body = restClient.get()
                .uri("/api/room/" + roomId)
                .header("Authorization", "Bearer " + token)
                .retrieve().body(String.class);
        return objectMapper.readTree(body);
    }

    private void leaveRoom(String roomId, String token) {
        restClient.post()
                .uri("/api/room/" + roomId + "/leave")
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .toBodilessEntity();
    }
}
