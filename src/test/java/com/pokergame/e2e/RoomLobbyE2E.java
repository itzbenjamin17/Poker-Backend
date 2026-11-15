package com.pokergame.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.pokergame.dto.request.JoinRoomRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * End-to-end HTTP tests for room creation, joining, leaving, host destruction, and concurrent room uniqueness.
 */
@Tag("e2e")
@DisplayName("Room lobby E2E")
class RoomLobbyE2E extends AbstractE2ESupport {

    @Nested
    @DisplayName("room lifecycle")
    class RoomLifecycle {

        @Test
        @DisplayName("should create a room, join it, and fetch updated room details")
        void givenCreatedRoom_whenPlayerJoins_thenRoomInfoReflectsBothPlayers() throws Exception {
            String roomName = uniqueName("LifecycleRoom");
            JsonNode createData = createRoom(roomName, "HostAlpha", 6);
            String roomId = createData.path("roomId").asText();
            String hostToken = createData.path("token").asText();

            JsonNode joinData = joinRoom(roomName, "PlayerBeta");
            JsonNode roomInfo = getRoomData(roomId, hostToken);

            assertThat(joinData.path("roomId").asText()).isEqualTo(roomId);
            assertThat(roomInfo.path("roomName").asText()).isEqualTo(roomName);
            assertThat(roomInfo.path("currentPlayers").asInt()).isEqualTo(2);
            assertThat(roomInfo.path("canStartGame").asBoolean()).isTrue();
        }

        @Test
        @DisplayName("should keep a room open when a non-host player leaves")
        void givenNonHostLeaves_whenLeaveRoom_thenRoomRemainsAvailable() throws Exception {
            String roomName = uniqueName("LeaveNonHostRoom");
            JsonNode createData = createRoom(roomName, "HostGamma", 6);
            String roomId = createData.path("roomId").asText();
            String hostToken = createData.path("token").asText();
            String playerToken = joinRoom(roomName, "PlayerDelta").path("token").asText();

            String leaveResponse = leaveRoom(roomId, playerToken);
            JsonNode roomInfo = getRoomData(roomId, hostToken);

            assertThat(leaveResponse).contains("Successfully left room");
            assertThat(roomInfo.path("currentPlayers").asInt()).isEqualTo(1);
            assertThat(roomInfo.path("hostName").asText()).isEqualTo("HostGamma");
        }

        @Test
        @DisplayName("should close the room when the host leaves")
        void givenHostLeaves_whenLeaveRoom_thenRoomIsClosed() throws Exception {
            JsonNode createData = createRoom(uniqueName("HostLeaveRoom"), "HostEpsilon", 6);
            String roomId = createData.path("roomId").asText();
            String hostToken = createData.path("token").asText();

            String leaveResponse = leaveRoom(roomId, hostToken);

            HttpClientErrorException notFound = assertThrows(HttpClientErrorException.class,
                    () -> getRoomData(roomId, hostToken));

            assertThat(leaveResponse).contains("Successfully left room");
            assertThat(notFound.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

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
    @DisplayName("should allow only one successful create when the same room name is submitted concurrently")
    void givenConcurrentDuplicateCreates_whenCreateRoom_thenOnlyOneRequestSucceeds() throws Exception {
        int attempts = 8;
        String roomName = uniqueName("ConcurrentCreate");
        CyclicBarrier barrier = new CyclicBarrier(attempts);
        List<Future<Boolean>> outcomes = new ArrayList<>();

        try (ExecutorService executor = Executors.newFixedThreadPool(attempts)) {
            for (int index = 0; index < attempts; index++) {
                String host = "Host" + index;
                outcomes.add(executor.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
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

            int successes = 0;
            for (Future<Boolean> outcome : outcomes) {
                if (outcome.get(10, TimeUnit.SECONDS)) {
                    successes++;
                }
            }
            assertThat(successes).isEqualTo(1);
        }
    }
}
