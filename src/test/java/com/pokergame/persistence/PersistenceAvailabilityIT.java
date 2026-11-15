package com.pokergame.persistence;

import com.pokergame.dto.request.CreateRoomRequest;
import com.pokergame.dto.request.JoinRoomRequest;
import com.pokergame.dto.request.PlayerActionRequest;
import com.pokergame.enums.PlayerAction;
import com.pokergame.model.Game;
import com.pokergame.model.Room;
import com.pokergame.persistence.config.PersistenceException;
import com.pokergame.persistence.config.PersistenceProperties;
import com.pokergame.persistence.snapshot.AggregateSnapshotMapper;
import com.pokergame.persistence.snapshot.RecoveredAggregate;
import com.pokergame.persistence.transaction.DurableMutationAspect;
import com.pokergame.persistence.wal.EncryptedWalStore;
import com.pokergame.persistence.wal.EncryptionKeyring;
import com.pokergame.persistence.wal.WalFaultPoint;
import com.pokergame.service.GameLifecycleService;
import com.pokergame.service.PlayerActionService;
import com.pokergame.util.RoomCoordinator;
import com.pokergame.service.RoomService;
import com.pokergame.util.LockLease;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.awaitility.Awaitility;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies graceful degradation to {@code MEMORY_ONLY} mode during a transient WAL storage outage
 * and automatic checkpoint restoration when storage recovers.
 */
@Tag("integration")
@DisplayName("Persistence availability integration tests")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@TestPropertySource(
        locations = "classpath:application-test.properties",
        properties = {
                "spring.main.allow-bean-definition-overriding=true",
                "poker.rate-limiting.enabled=false",
                "poker.lifecycle.game-end-display-delay-ms=60000",
                "poker.lifecycle.ready-countdown-ms=60000"
        })
@Import(PersistenceAvailabilityIT.FaultyStorageConfig.class)
class PersistenceAvailabilityIT {

    private static final AtomicBoolean UNAVAILABLE_STORAGE = new AtomicBoolean(false);

    @TempDir
    static Path walDirectory;

    @DynamicPropertySource
    static void persistenceProperties(DynamicPropertyRegistry registry) {
        String encodedKey = Base64.getEncoder().encodeToString(new byte[32]);
        registry.add("poker.persistence.enabled", () -> "true");
        registry.add("poker.persistence.directory", () -> walDirectory.toString().replace('\\', '/'));
        registry.add("poker.persistence.current-key-id", () -> "v1");
        registry.add("poker.persistence.keys.v1", () -> encodedKey);
    }

    @Autowired
    private RoomService roomService;

    @Autowired
    private GameLifecycleService gameLifecycleService;

    @Autowired
    private PlayerActionService playerActionService;

    @Autowired
    private RoomCoordinator roomCoordinator;

    @Autowired
    private EncryptedWalStore walStore;

    @Autowired
    private AggregateSnapshotMapper snapshotMapper;

    @Autowired
    private DurableMutationAspect durableMutationAspect;

    @Autowired
    @Qualifier("pokerPersistenceHealth")
    private HealthIndicator pokerPersistenceHealth;

    private final Set<String> trackedRoomIds = new HashSet<>();

    @AfterEach
    void cleanUp() {
        UNAVAILABLE_STORAGE.set(false);
        awaitPendingSavesFlushed();
        for (String roomId : List.copyOf(trackedRoomIds)) {
            try (LockLease _ = roomCoordinator.acquire(roomId)) {
                gameLifecycleService.evictGame(roomId);
                roomService.destroyRoom(roomId);
                walStore.delete(roomId);
            }
        }
        awaitPendingSavesFlushed();
        trackedRoomIds.clear();
    }

    private void awaitPendingSavesFlushed() {
        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            durableMutationAspect.retryPendingSaves();
            assertThat(walStore.isHealthy()).isTrue();
            assertThat(walStore.unsavedRoomCount()).isZero();
        });
    }

    @Test
    @DisplayName("Storage outage keeps gameplay running in MEMORY_ONLY mode and checkpoints latest state once recovered")
    void storageOutageContinuesGameplayAndCheckpointsLatestState() {
        String roomId = UUID.randomUUID().toString();
        trackedRoomIds.add(roomId);

        roomService.createRoom(roomId, new CreateRoomRequest("Outage Room", "Alice", 6, 5, 10, 1000, null));
        roomService.joinRoom(roomId, new JoinRoomRequest("Outage Room", "Bob", null));
        try (LockLease _ = roomCoordinator.acquire(roomId)) {
            Room room = roomService.getRoom(roomId);
            room.setPlayerConnected("Alice", true);
            room.setPlayerConnected("Bob", true);
        }
        gameLifecycleService.createGameFromRoom(roomId);

        Health initialHealth = pokerPersistenceHealth.health();
        assertThat(initialHealth).isNotNull();
        assertThat(initialHealth.getStatus().getCode()).isEqualTo("UP");
        assertThat(initialHealth.getDetails()).containsEntry("mode", "DURABLE")
                .containsEntry("persistenceHealthy", true);

        UNAVAILABLE_STORAGE.set(true);

        String actorName;
        long initialRevision;
        try (LockLease _ = roomCoordinator.acquire(roomId)) {
            Game game = gameLifecycleService.getGame(roomId);
            actorName = game.getCurrentPlayer().getName();
            initialRevision = game.getStateRevision();
        }

        playerActionService.processPlayerAction(roomId, new PlayerActionRequest(PlayerAction.CALL, null), actorName);

        assertThat(walStore.isHealthy()).isFalse();
        assertThat(walStore.unsavedRoomCount()).isEqualTo(1);
        Health degradedHealth = pokerPersistenceHealth.health();
        assertThat(degradedHealth).isNotNull();
        assertThat(degradedHealth.getStatus().getCode()).isEqualTo("UP");
        assertThat(degradedHealth.getDetails())
                .containsEntry("persistenceHealthy", false)
                .containsEntry("mode", "MEMORY_ONLY")
                .containsEntry("unsavedRoomCount", 1);

        long liveRevision;
        try (LockLease _ = roomCoordinator.acquire(roomId)) {
            Game game = gameLifecycleService.getGame(roomId);
            liveRevision = game.getStateRevision();
            assertThat(liveRevision).isGreaterThan(initialRevision);
        }

        UNAVAILABLE_STORAGE.set(false);
        awaitPendingSavesFlushed();
        byte[] recoveredBytes;
        try (LockLease _ = roomCoordinator.acquire(roomId)) {
            recoveredBytes = walStore.recoverLatest(roomId).orElseThrow();
        }
        RecoveredAggregate recovered = snapshotMapper.deserialize(recoveredBytes);
        assertThat(recovered.game().getStateRevision()).isEqualTo(liveRevision);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FaultyStorageConfig {
        @Bean
        @Primary
        EncryptedWalStore encryptedWalStore(PersistenceProperties properties, EncryptionKeyring keyring,
                RoomCoordinator roomCoordinator) {
            return new EncryptedWalStore(properties.getDirectory(), keyring, (point, roomId) -> {
                if (UNAVAILABLE_STORAGE.get()
                        && (point == WalFaultPoint.BEFORE_COMMIT_WRITE || point == WalFaultPoint.BEFORE_FLUSH)) {
                    throw new PersistenceException("injected availability outage");
                }
            }, roomCoordinator);
        }
    }
}
