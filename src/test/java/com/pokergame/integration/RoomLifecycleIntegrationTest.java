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
import org.springframework.web.client.HttpClientErrorException;

import java.time.Duration;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("Room lifecycle integration")
class RoomLifecycleIntegrationTest extends AbstractIntegrationTestSupport {

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
    void givenConcurrentDuplicateCreates_whenCreateRoom_thenOnlyOneRequestSucceeds() throws InterruptedException {
        int attempts = 8;
        String roomName = uniqueName("ConcurrentCreate");
        ExecutorService executor = Executors.newFixedThreadPool(attempts);
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(attempts);
        AtomicInteger successCount = new AtomicInteger();
        Queue<Throwable> unexpectedFailures = new ConcurrentLinkedQueue<>();

        for (int index = 0; index < attempts; index++) {
            int currentIndex = index;
            executor.submit(() -> {
                try {
                    ready.countDown();
                    assertThat(start.await(3, TimeUnit.SECONDS)).isTrue();

                    restClient.post()
                            .uri("/api/room/create")
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(new CreateRoomRequest(roomName, "Host" + currentIndex, 6, 10, 20, 1000, null))
                            .retrieve()
                            .body(String.class);
                    successCount.incrementAndGet();
                } catch (HttpClientErrorException exception) {
                    assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.getResponseBodyAsString()).contains("already taken");
                } catch (Throwable throwable) {
                    unexpectedFailures.add(throwable);
                } finally {
                    done.countDown();
                }
            });
        }

        assertThat(ready.await(3, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        assertThat(done.await(Duration.ofSeconds(10).toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        executor.shutdownNow();

        assertThat(unexpectedFailures).isEmpty();
        assertThat(successCount).hasValue(1);
    }
}
