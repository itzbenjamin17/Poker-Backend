package com.pokergame.integration;

import com.pokergame.PokerApplication;
import com.pokergame.controller.GameController;
import com.pokergame.controller.RoomController;
import com.pokergame.dto.request.CreateRoomRequest;
import com.pokergame.dto.request.JoinRoomRequest;
import com.pokergame.persistence.transaction.DurableMutationAspect;
import com.pokergame.security.PlayerPrincipal;
import com.pokergame.service.GameLifecycleService;
import com.pokergame.service.RoomService;
import com.pokergame.util.LockLease;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.access.AccessDeniedException;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Exercises authenticated controller requests queued across membership replacement. */
class MembershipAtomicityIntegrationTest {
    @TempDir
    Path directory;

    /** A queued old token must never receive a replacement member's hole cards. */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void queuedPrivateReadRejectsReplacementMembership(boolean persistenceEnabled) throws Exception {
        try (ConfigurableApplicationContext context = startApplication(persistenceEnabled);
             var executor = Executors.newSingleThreadExecutor()) {
            RoomService rooms = context.getBean(RoomService.class);
            GameLifecycleService games = context.getBean(GameLifecycleService.class);
            GameController controller = context.getBean(GameController.class);
            String roomId = rooms.createRoom(new CreateRoomRequest("Atomic room", "Host", 6, 5, 10, 1000, null));
            rooms.joinRoom(new JoinRoomRequest("Atomic room", "Alice", null));
            PlayerPrincipal retired = new PlayerPrincipal("Alice", roomId, rooms.getRoom(roomId).getMembershipId("Alice"));
            DurableMutationAspect locks = context.getBean(DurableMutationAspect.class);
            Future<Throwable> request;
            try (LockLease ignored = locks.acquireRoomLock(roomId)) {
                request = executor.submit(() -> {
                    try {
                        controller.getPrivateState(roomId, retired);
                        return null;
                    } catch (Throwable failure) {
                        return failure;
                    }
                });
                await().atMost(Duration.ofSeconds(5)).until(() -> locks.getLockRegistry().reservationCount(roomId) == 2);
                games.departParticipants(roomId, List.of("Alice"));
                rooms.joinRoom(new JoinRoomRequest("Atomic room", "Alice", null));
                games.createGameFromRoom(roomId);
            }
            assertThat(request.get(5, TimeUnit.SECONDS)).isInstanceOf(AccessDeniedException.class);
            PlayerPrincipal replacement = new PlayerPrincipal("Alice", roomId, rooms.getRoom(roomId).getMembershipId("Alice"));
            assertThat(controller.getPrivateState(roomId, replacement).getBody().holeCards()).hasSize(2);
        }
    }

    /** Neither room nor game leave may remove a membership that replaced a queued caller. */
    @ParameterizedTest
    @CsvSource({"true,true", "true,false", "false,true", "false,false"})
    void queuedDepartureRejectsReplacementMembership(boolean persistenceEnabled, boolean roomEndpoint) throws Exception {
        try (ConfigurableApplicationContext context = startApplication(persistenceEnabled);
             var executor = Executors.newSingleThreadExecutor()) {
            RoomService rooms = context.getBean(RoomService.class);
            GameLifecycleService games = context.getBean(GameLifecycleService.class);
            GameController gameController = context.getBean(GameController.class);
            RoomController roomController = context.getBean(RoomController.class);
            String roomId = rooms.createRoom(new CreateRoomRequest("Departure room", "Host", 6, 5, 10, 1000, null));
            rooms.joinRoom(new JoinRoomRequest("Departure room", "Alice", null));
            PlayerPrincipal retired = new PlayerPrincipal("Alice", roomId, rooms.getRoom(roomId).getMembershipId("Alice"));
            DurableMutationAspect locks = context.getBean(DurableMutationAspect.class);
            Future<Throwable> request;
            String replacementMembership;
            try (LockLease ignored = locks.acquireRoomLock(roomId)) {
                request = executor.submit(() -> {
                    try {
                        if (roomEndpoint) roomController.leaveRoom(roomId, retired);
                        else gameController.leaveGame(roomId, retired);
                        return null;
                    } catch (Throwable failure) {
                        return failure;
                    }
                });
                await().atMost(Duration.ofSeconds(5)).until(() -> locks.getLockRegistry().reservationCount(roomId) == 2);
                games.departParticipants(roomId, List.of("Alice"));
                rooms.joinRoom(new JoinRoomRequest("Departure room", "Alice", null));
                replacementMembership = rooms.getRoom(roomId).getMembershipId("Alice");
            }
            assertThat(request.get(5, TimeUnit.SECONDS)).isInstanceOf(AccessDeniedException.class);
            assertThat(rooms.getRoom(roomId).getMembershipId("Alice")).isEqualTo(replacementMembership);
            PlayerPrincipal replacement = new PlayerPrincipal("Alice", roomId, replacementMembership);
            if (roomEndpoint) roomController.leaveRoom(roomId, replacement);
            else gameController.leaveGame(roomId, replacement);
            assertThat(rooms.getRoom(roomId).hasPlayer("Alice")).isFalse();
        }
    }

    /** Starts the real service/controller graph with an isolated optional WAL. */
    private ConfigurableApplicationContext startApplication(boolean persistenceEnabled) {
        return new SpringApplicationBuilder(PokerApplication.class)
                .profiles("test").web(WebApplicationType.NONE)
                .run("--poker.persistence.enabled=" + persistenceEnabled,
                        "--poker.persistence.directory=" + directory.toString().replace('\\', '/'),
                        "--poker.persistence.current-key-id=current",
                        "--poker.persistence.keys.current=" + Base64.getEncoder().encodeToString(new byte[32]));
    }
}
