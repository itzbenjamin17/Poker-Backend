package com.pokergame.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.JsonNode;

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
        void givenCreatedRoom_whenPlayerJoins_thenRoomInfoReflectsBothPlayers() {
            String roomName = uniqueName("LifecycleRoom");
            JsonNode createData = createRoom(roomName, "HostAlpha", 6);
            String roomId = createData.path("roomId").asString();
            String hostToken = createData.path("token").asString();

            JsonNode joinData = joinRoom(roomName, "PlayerBeta");
            JsonNode roomInfo = getRoomData(roomId, hostToken);

            assertThat(joinData.path("roomId").asString()).isEqualTo(roomId);
            assertThat(roomInfo.path("roomName").asString()).isEqualTo(roomName);
            assertThat(roomInfo.path("players")).hasSize(2);
            assertThat(roomInfo.path("canStartGame").asBoolean()).isTrue();
        }

        @Test
        @DisplayName("should keep a room open when a non-host player leaves")
        void givenNonHostLeaves_whenLeaveRoom_thenRoomRemainsAvailable() {
            String roomName = uniqueName("LeaveNonHostRoom");
            JsonNode createData = createRoom(roomName, "HostGamma", 6);
            String roomId = createData.path("roomId").asString();
            String hostToken = createData.path("token").asString();
            String playerToken = joinRoom(roomName, "PlayerDelta").path("token").asString();

            int leaveStatus = leaveRoom(roomId, playerToken);
            JsonNode roomInfo = getRoomData(roomId, hostToken);

            assertThat(leaveStatus).isEqualTo(204);
            assertThat(roomInfo.path("players")).hasSize(1);
            assertThat(roomInfo.path("players").get(0).path("name").asString()).isEqualTo("HostGamma");
            assertThat(roomInfo.path("players").get(0).path("isHost").asBoolean()).isTrue();
        }

        @Test
        @DisplayName("should close the room when the host leaves")
        void givenHostLeaves_whenLeaveRoom_thenRoomIsClosed() {
            JsonNode createData = createRoom(uniqueName("HostLeaveRoom"), "HostEpsilon", 6);
            String roomId = createData.path("roomId").asString();
            String hostToken = createData.path("token").asString();

            int leaveStatus = leaveRoom(roomId, hostToken);

            HttpClientErrorException notFound = expectRejection(() -> getRoomData(roomId, hostToken));
            assertThat(leaveStatus).isEqualTo(204);
            assertThat(notFound.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @Test
    @DisplayName("should allow only one successful create when the same room name is submitted concurrently")
    void givenConcurrentDuplicateCreates_whenCreateRoom_thenOnlyOneRequestSucceeds() throws Exception {
        int attempts = 8;
        String roomName = uniqueName("ConcurrentCreate");
        CyclicBarrier barrier = new CyclicBarrier(attempts);
        List<Future<Boolean>> outcomes = new ArrayList<>();

        ExecutorService executor = Executors.newFixedThreadPool(attempts);
        try {
            for (int index = 0; index < attempts; index++) {
                String host = "Host" + index;
                outcomes.add(executor.submit(() -> {
                    barrier.await(DEFAULT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                    try {
                        createRoom(roomName, host, 6);
                        return true;
                    } catch (HttpClientErrorException rejection) {
                        assertProblem(rejection, HttpStatus.BAD_REQUEST,
                                "Room name '" + roomName + "' is already taken. Please choose a different name.");
                        return false;
                    }
                }));
            }

            int successes = 0;
            for (Future<Boolean> outcome : outcomes) {
                if (outcome.get(DEFAULT_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                    successes++;
                }
            }
            assertThat(successes).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }
}
