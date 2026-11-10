package com.pokergame.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.pokergame.dto.request.CreateRoomRequest;
import com.pokergame.dto.request.JoinRoomRequest;
import com.pokergame.integration.support.AbstractIntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.HttpClientErrorException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.pokergame.util.ConcurrentTestSupport.stopExecutor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Tests room lifecycle integration behavior. */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("Room lifecycle integration")
class RoomLifecycleIntegrationTest extends AbstractIntegrationTestSupport {

    /** Groups test scenarios for room lifecycle. */
    @Nested
    @DisplayName("room lifecycle")
    class RoomLifecycle {

        /**
         * Protects the contract that the system should create a room, join it, and fetch updated room details.
         */
        @Test
        @DisplayName("should create a room, join it, and fetch updated room details")
        void givenCreatedRoom_whenPlayerJoins_thenRoomInfoReflectsBothPlayers() throws Exception {
            String roomName = uniqueName("LifecycleRoom");
            JsonNode createData = createRoom(roomName, "HostAlpha", 6);
            String roomId = createData.path("roomId").asText();
            String hostToken = createData.path("token").asText();

            JsonNode joinData = joinRoom(roomName, "PlayerBeta");
            String roomInfoBody = restClient.get()
                    .uri("/api/room/" + roomId)
                    .header("Authorization", "Bearer " + hostToken)
                    .retrieve()
                    .body(String.class);

            JsonNode roomInfo = objectMapper.readTree(roomInfoBody);

            assertThat(joinData.path("roomId").asText()).isEqualTo(roomId);
            assertThat(roomInfo.path("roomName").asText()).isEqualTo(roomName);
            assertThat(roomInfo.path("currentPlayers").asInt()).isEqualTo(2);
            assertThat(roomInfo.path("canStartGame").asBoolean()).isTrue();
        }

        /**
         * Protects the contract that the system should keep a room open when a non-host player leaves.
         */
        @Test
        @DisplayName("should keep a room open when a non-host player leaves")
        void givenNonHostLeaves_whenLeaveRoom_thenRoomRemainsAvailable() throws Exception {
            String roomName = uniqueName("LeaveNonHostRoom");
            JsonNode createData = createRoom(roomName, "HostGamma", 6);
            String roomId = createData.path("roomId").asText();
            String hostToken = createData.path("token").asText();
            String playerToken = joinRoom(roomName, "PlayerDelta").path("token").asText();

            String leaveResponse = restClient.post()
                    .uri("/api/room/" + roomId + "/leave")
                    .header("Authorization", "Bearer " + playerToken)
                    .retrieve()
                    .body(String.class);

            JsonNode roomInfo = objectMapper.readTree(restClient.get()
                    .uri("/api/room/" + roomId)
                    .header("Authorization", "Bearer " + hostToken)
                    .retrieve()
                    .body(String.class));

            assertThat(leaveResponse).contains("Successfully left room");
            assertThat(roomInfo.path("currentPlayers").asInt()).isEqualTo(1);
            assertThat(roomInfo.path("hostName").asText()).isEqualTo("HostGamma");
        }

        /**
         * Protects the contract that the system should close the room when the host leaves.
         */
        @Test
        @DisplayName("should close the room when the host leaves")
        void givenHostLeaves_whenLeaveRoom_thenRoomIsClosed() throws Exception {
            JsonNode createData = createRoom(uniqueName("HostLeaveRoom"), "HostEpsilon", 6);
            String roomId = createData.path("roomId").asText();
            String hostToken = createData.path("token").asText();

            String leaveResponse = restClient.post()
                    .uri("/api/room/" + roomId + "/leave")
                    .header("Authorization", "Bearer " + hostToken)
                    .retrieve()
                    .body(String.class);

            HttpClientErrorException notFound = assertThrows(HttpClientErrorException.class, () -> restClient.get()
                    .uri("/api/room/" + roomId)
                    .header("Authorization", "Bearer " + hostToken)
                    .retrieve()
                    .body(String.class));

            assertThat(leaveResponse).contains("Successfully left room");
            assertThat(notFound.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        /**
         * Protects the contract that the system should return 404 when joining an unknown room.
         */
        @Test
        @DisplayName("should return 404 when joining an unknown room")
        void givenUnknownRoom_whenJoinRoom_thenReturnNotFound() {
            HttpClientErrorException exception = assertThrows(HttpClientErrorException.class, () -> restClient.post()
                    .uri("/api/room/join")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new JoinRoomRequest(uniqueName("UnknownRoom"), "PlayerZeta", null))
                    .retrieve()
                    .body(String.class));

            assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(exception.getResponseBodyAsString()).contains("Room not found");
        }
    }

    @Test
    @Tag("slow")
    @DisplayName("should allow only one successful create when the same room name is submitted concurrently")
    void givenConcurrentDuplicateCreates_whenCreateRoom_thenOnlyOneRequestSucceeds() throws Exception {
        int attempts = 8;
        String roomName = uniqueName("ConcurrentCreate");
        ExecutorService executor = Executors.newFixedThreadPool(attempts);
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> outcomes = new ArrayList<>();

        try {
            for (int index = 0; index < attempts; index++) {
                String host = "Host" + index;
                outcomes.add(executor.submit(() -> {
                    ready.countDown();
                    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                    try {
                        createRoom(roomName, host, 6);
                        return true;
                    } catch (HttpClientErrorException rejection) {
                        assertThat(rejection.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(rejection.getResponseBodyAsString()).contains("already taken");
                        return false;
                    }
                }));
            }

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            int successes = 0;
            for (Future<Boolean> outcome : outcomes) {
                if (outcome.get(10, TimeUnit.SECONDS)) {
                    successes++;
                }
            }
            assertThat(successes).isEqualTo(1);
        } finally {
            stopExecutor(executor);
        }
    }
}
