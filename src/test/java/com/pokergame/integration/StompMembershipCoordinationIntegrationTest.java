package com.pokergame.integration;

import com.pokergame.PokerApplication;
import com.pokergame.config.WebSocketAuthInterceptor;
import com.pokergame.dto.request.CreateRoomRequest;
import com.pokergame.dto.request.JoinRoomRequest;
import com.pokergame.security.PlayerPrincipal;
import com.pokergame.service.GameLifecycleService;
import com.pokergame.service.RoomCoordinator;
import com.pokergame.service.RoomService;
import com.pokergame.util.LockLease;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.pokergame.util.ConcurrentTestSupport.stopExecutor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Exercises STOMP authorization queued behind replacement of an active membership. */
class StompMembershipCoordinationIntegrationTest {
    @TempDir
    Path directory;

    /** A stale subscription must validate the membership that exists after its room lease is granted. */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void queuedSubscriptionRejectsReplacementMembership(boolean persistenceEnabled) throws Exception {
        try (ConfigurableApplicationContext context = startApplication(persistenceEnabled)) {
            var executor = Executors.newSingleThreadExecutor();
            try {
                RoomService rooms = context.getBean(RoomService.class);
                GameLifecycleService games = context.getBean(GameLifecycleService.class);
                RoomCoordinator coordinator = context.getBean(RoomCoordinator.class);
                WebSocketAuthInterceptor interceptor = context.getBean(WebSocketAuthInterceptor.class);
                String roomId = rooms.createRoom(new CreateRoomRequest("STOMP race", "Host", 6, 5, 10, 1000, null));
                rooms.joinRoom(new JoinRoomRequest("STOMP race", "Alice", null));
                Future<Throwable> request;
                String replacementMembership;
                try (LockLease _ = coordinator.acquire(roomId)) {
                    String retiredMembership = rooms.getRoom(roomId).getMembershipId("Alice");
                    Message<byte[]> subscription = subscription(new PlayerPrincipal("Alice", roomId, retiredMembership));
                    CountDownLatch entered = new CountDownLatch(1);
                    request = executor.submit(() -> {
                        entered.countDown();
                        try {
                            interceptor.preSend(subscription, null);
                            return null;
                        } catch (Throwable failure) {
                            return failure;
                        }
                    });
                    assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                    await().atMost(Duration.ofSeconds(5)).until(() -> request.isDone()
                            || coordinator.getLockRegistry().reservationCount(roomId) >= 2);
                    assertThat(request.isDone()).as("subscription waits for the active room operation").isFalse();
                    games.departParticipants(roomId, List.of("Alice"));
                    rooms.joinRoom(new JoinRoomRequest("STOMP race", "Alice", null));
                    replacementMembership = rooms.getRoom(roomId).getMembershipId("Alice");
                    assertThat(replacementMembership).isNotEqualTo(retiredMembership);
                }
                assertThat(request.get(5, TimeUnit.SECONDS)).isInstanceOf(AccessDeniedException.class);
                assertThat(interceptor.preSend(subscription(new PlayerPrincipal("Alice", roomId, replacementMembership)), null))
                        .isNotNull();
                coordinator.coordinate(roomId, () ->
                        assertThat(rooms.getRoom(roomId).getMembershipId("Alice")).isEqualTo(replacementMembership));
            } finally {
                stopExecutor(executor);
            }
        }
    }

    /** Builds the same inbound subscription frame inspected by the configured channel interceptor. */
    private Message<byte[]> subscription(PlayerPrincipal principal) {
        StompHeaderAccessor headers = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        headers.setDestination("/room/" + principal.roomId());
        headers.setUser(principal);
        return MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
    }

    /** Starts real service wiring with an isolated optional WAL. */
    private ConfigurableApplicationContext startApplication(boolean persistenceEnabled) {
        return new SpringApplicationBuilder(PokerApplication.class)
                .profiles("test").web(WebApplicationType.NONE)
                .run("--poker.persistence.enabled=" + persistenceEnabled,
                        "--poker.persistence.directory=" + directory.toString().replace('\\', '/'),
                        "--poker.persistence.current-key-id=current",
                        "--poker.persistence.keys.current=" + Base64.getEncoder().encodeToString(new byte[32]));
    }
}
