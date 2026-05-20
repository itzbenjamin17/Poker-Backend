package com.pokergame.integration;

import com.pokergame.dto.request.CreateRoomRequest;
import com.pokergame.dto.request.JoinRoomRequest;
import com.pokergame.integration.support.AbstractIntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.HttpClientErrorException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class InputSanitizationIntegrationTest extends AbstractIntegrationTestSupport {

    @Test
    @DisplayName("should return 400 when room name contains HTML tags")
    void givenHtmlInRoomName_whenCreateRoom_thenReturn400() throws Exception {
        CreateRoomRequest request = new CreateRoomRequest(
                "<script>alert('xss')</script>Room",
                "Player1", 6, 10, 20, 1000, null);

        HttpClientErrorException exception = assertThrows(HttpClientErrorException.class, () ->
            restClient.post()
                    .uri("/api/room/create")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .toBodilessEntity());

        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exception.getResponseBodyAsString()).contains("Input contains illegal HTML tags.");
    }

    @Test
    @DisplayName("should return 400 when player name contains HTML tags")
    void givenHtmlInPlayerName_whenCreateRoom_thenReturn400() throws Exception {
        CreateRoomRequest request = new CreateRoomRequest(
                "ValidRoom",
                "<b>Player</b>", 6, 10, 20, 1000, null);

        HttpClientErrorException exception = assertThrows(HttpClientErrorException.class, () ->
            restClient.post()
                    .uri("/api/room/create")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .toBodilessEntity());

        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("should return 400 when joining with HTML tags in player name")
    void givenHtmlInPlayerName_whenJoinRoom_thenReturn400() throws Exception {
        String roomName = uniqueName("JoinSanitize");
        createRoom(roomName, "Host", 6);

        JoinRoomRequest request = new JoinRoomRequest(roomName, "<i>Attacker</i>", null);

        HttpClientErrorException exception = assertThrows(HttpClientErrorException.class, () ->
            restClient.post()
                    .uri("/api/room/join")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .toBodilessEntity());

        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
