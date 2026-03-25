package com.pokergame.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pokergame.dto.request.CreateRoomRequest;
import com.pokergame.dto.request.JoinRoomRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RoomLifecycleIntegrationTest {

    @LocalServerPort
    private int port;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private RestClient restClient;

    @BeforeEach
    void setUp() {
        restClient = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    @Test
    @DisplayName("Room lifecycle: create -> join -> fetch room info")
    void roomLifecycle_createJoinFetchInfo_shouldSucceed() throws Exception {
        String roomName = uniqueName("LifecycleRoom");

        JsonNode createData = createRoom(roomName, "HostAlpha", 6);
        String roomId = createData.path("roomId").asText();
        String hostToken = createData.path("token").asText();

        JsonNode joinData = joinRoom(roomName, "PlayerBeta");
        assertEquals(roomId, joinData.path("roomId").asText());
        assertEquals("PlayerBeta", joinData.path("playerName").asText());

        String roomInfoBody = restClient.get()
                .uri("/api/room/" + roomId)
                .header("Authorization", "Bearer " + hostToken)
                .retrieve()
                .body(String.class);

        assertNotNull(roomInfoBody);
        JsonNode roomInfo = objectMapper.readTree(roomInfoBody);

        assertEquals(roomId, roomInfo.path("roomId").asText());
        assertEquals(roomName, roomInfo.path("roomName").asText());
        assertEquals(2, roomInfo.path("currentPlayers").asInt());
        assertTrue(roomInfo.path("canStartGame").asBoolean());
    }

    @Test
    @DisplayName("Non-host leave keeps room available")
    void leaveRoom_nonHost_shouldKeepRoomOpen() throws Exception {
        String roomName = uniqueName("LeaveNonHostRoom");

        JsonNode createData = createRoom(roomName, "HostGamma", 6);
        String roomId = createData.path("roomId").asText();
        String hostToken = createData.path("token").asText();

        JsonNode joinData = joinRoom(roomName, "PlayerDelta");
        String playerToken = joinData.path("token").asText();

        String leaveResponse = restClient.post()
                .uri("/api/room/" + roomId + "/leave")
                .header("Authorization", "Bearer " + playerToken)
                .retrieve()
                .body(String.class);

        assertNotNull(leaveResponse);
        assertTrue(leaveResponse.contains("Successfully left room"));

        String roomInfoBody = restClient.get()
                .uri("/api/room/" + roomId)
                .header("Authorization", "Bearer " + hostToken)
                .retrieve()
                .body(String.class);

        JsonNode roomInfo = objectMapper.readTree(roomInfoBody);
        assertEquals(1, roomInfo.path("currentPlayers").asInt());
        assertEquals("HostGamma", roomInfo.path("hostName").asText());
    }

    @Test
    @DisplayName("Host leave closes room")
    void leaveRoom_host_shouldCloseRoom() throws Exception {
        String roomName = uniqueName("HostLeaveRoom");

        JsonNode createData = createRoom(roomName, "HostEpsilon", 6);
        String roomId = createData.path("roomId").asText();
        String hostToken = createData.path("token").asText();

        String leaveResponse = restClient.post()
                .uri("/api/room/" + roomId + "/leave")
                .header("Authorization", "Bearer " + hostToken)
                .retrieve()
                .body(String.class);

        assertNotNull(leaveResponse);
        assertTrue(leaveResponse.contains("Successfully left room"));

        HttpClientErrorException notFound = assertThrows(HttpClientErrorException.class, () -> restClient.get()
                .uri("/api/room/" + roomId)
                .header("Authorization", "Bearer " + hostToken)
                .retrieve()
                .body(String.class));

        assertEquals(HttpStatus.NOT_FOUND, notFound.getStatusCode());
    }

    @Test
    @DisplayName("Join unknown room returns 404")
    void joinRoom_unknownRoom_shouldReturn404() {
        JoinRoomRequest joinRequest = new JoinRoomRequest(uniqueName("UnknownRoom"), "PlayerZeta", null);

        HttpClientErrorException ex = assertThrows(HttpClientErrorException.class, () -> restClient.post()
                .uri("/api/room/join")
                .contentType(MediaType.APPLICATION_JSON)
                .body(joinRequest)
                .retrieve()
                .body(String.class));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        assertTrue(ex.getResponseBodyAsString().contains("Room not found"));
    }

    @Test
    @DisplayName("Concurrent create with same room name allows only one success")
    void createRoom_concurrentDuplicateName_shouldAllowSingleSuccess() throws InterruptedException {
        int attempts = 8;
        String roomName = uniqueName("ConcurrentCreate");
        ExecutorService executor = Executors.newFixedThreadPool(attempts);
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(attempts);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < attempts; i++) {
            int idx = i;
            executor.submit(() -> {
                try {
                    CreateRoomRequest request = new CreateRoomRequest(roomName, "Host" + idx, 6, 10, 20, 1000, null);
                    ready.countDown();
                    if (!start.await(3, TimeUnit.SECONDS)) {
                        return;
                    }

                    restClient.post()
                            .uri("/api/room/create")
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(request)
                            .retrieve()
                            .body(String.class);
                    successCount.incrementAndGet();
                } catch (HttpClientErrorException ex) {
                    assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
                    assertTrue(ex.getResponseBodyAsString().contains("already taken"));
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        assertTrue(ready.await(3, TimeUnit.SECONDS));
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        executor.shutdown();

        assertEquals(1, successCount.get(), "Only one room creation should succeed for identical names");
    }

    private JsonNode createRoom(String roomName, String hostName, int maxPlayers) throws Exception {
        CreateRoomRequest request = new CreateRoomRequest(roomName, hostName, maxPlayers, 10, 20, 1000, null);

        String body = restClient.post()
                .uri("/api/room/create")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(String.class);

        assertNotNull(body);
        JsonNode json = objectMapper.readTree(body);
        assertEquals("Room created successfully", json.path("message").asText());

        JsonNode data = json.path("data");
        assertFalse(data.path("roomId").asText().isBlank());
        assertFalse(data.path("token").asText().isBlank());
        assertEquals(hostName, data.path("playerName").asText());
        return data;
    }

    private JsonNode joinRoom(String roomName, String playerName) throws Exception {
        JoinRoomRequest request = new JoinRoomRequest(roomName, playerName, null);

        String body = restClient.post()
                .uri("/api/room/join")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(String.class);

        assertNotNull(body);
        JsonNode json = objectMapper.readTree(body);
        assertEquals("Successfully joined room", json.path("message").asText());

        JsonNode data = json.path("data");
        assertFalse(data.path("roomId").asText().isBlank());
        assertFalse(data.path("token").asText().isBlank());
        assertEquals(playerName, data.path("playerName").asText());
        return data;
    }

    private String uniqueName(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
