package com.pokergame.persistence;

import com.pokergame.config.WebSocketEventListener;
import com.pokergame.model.Game;
import com.pokergame.model.Player;
import com.pokergame.service.GameLifecycleService;
import com.pokergame.service.RoomService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Rebuilds every durable aggregate and its runtime-only work before the process is
 * considered ready.
 * <p>
 * Recovery is global rather than room-lazy because clients may immediately query
 * the lobby list, and exposing a partially restored registry would make durable
 * rooms appear deleted.
 * </p>
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class PersistenceRecovery implements ApplicationRunner {
    private final EncryptedWalStore store;
    private final AggregateSnapshotMapper mapper;
    private final RoomService roomService;
    private final GameLifecycleService gameLifecycleService;
    private final WebSocketEventListener webSocketEventListener;
    private final ApplicationContext applicationContext;
    private final long disconnectGracePeriodMs;
    private volatile boolean complete;

    /**
     * Creates the coordinator with both state registries and the runtime schedulers
     * that must be rebuilt from persisted deadlines.
     *
     * @param store                   encrypted WAL store
     * @param mapper                  explicit state-image mapper
     * @param roomService             authoritative room registry
     * @param gameLifecycleService    authoritative game registry and timer owner
     * @param webSocketEventListener  reconnect cleanup scheduler
     * @param applicationContext      source for readiness state changes
     * @param disconnectGracePeriodMs fresh grace granted because socket sessions do not survive restart
     */
    public PersistenceRecovery(EncryptedWalStore store, AggregateSnapshotMapper mapper, RoomService roomService,
            GameLifecycleService gameLifecycleService, WebSocketEventListener webSocketEventListener,
            ApplicationContext applicationContext, long disconnectGracePeriodMs) {
        this.store = store;
        this.mapper = mapper;
        this.roomService = roomService;
        this.gameLifecycleService = gameLifecycleService;
        this.webSocketEventListener = webSocketEventListener;
        this.applicationContext = applicationContext;
        this.disconnectGracePeriodMs = disconnectGracePeriodMs;
    }

    /**
     * Restores all committed images, removes durable tombstones, and reconstructs
     * runtime timers before accepting traffic.
     * <p>
     * Every prior socket is treated as disconnected because session identifiers are
     * process-local. The later of the stored deadline and a fresh grace period avoids
     * evicting players merely because the server restarted.
     * </p>
     *
     * @param args application startup arguments; recovery behavior is configuration-driven
     * @throws PersistenceException if any WAL or state image cannot be trusted
     */
    @Override
    public void run(ApplicationArguments args) {
        AvailabilityChangeEvent.publish(applicationContext, ReadinessState.REFUSING_TRAFFIC);
        Map<String, byte[]> images = store.recoverAll();
        List<RecoveredAggregate> recoveredAggregates = new ArrayList<>();

        for (Map.Entry<String, byte[]> entry : images.entrySet()) {
            RecoveredAggregate aggregate = mapper.deserialize(entry.getValue());
            if (aggregate.deleted()) {
                store.delete(entry.getKey());
                continue;
            }
            if (!entry.getKey().equals(aggregate.room().getRoomId())) {
                throw new PersistenceException("Snapshot room identity does not match WAL file identity");
            }
            roomService.restoreRoom(aggregate.room(), aggregate.currentHost());
            if (aggregate.game() != null) {
                gameLifecycleService.restoreGame(aggregate.game());
            }
            recoveredAggregates.add(aggregate);
        }

        long freshDeadline = System.currentTimeMillis() + disconnectGracePeriodMs;
        for (RecoveredAggregate aggregate : recoveredAggregates) {
            Game game = aggregate.game();
            for (String playerName : aggregate.room().getPlayers()) {
                Player gamePlayer = game == null ? null : game.getPlayers().stream()
                        .filter(player -> player.getName().equals(playerName))
                        .findFirst()
                        .orElse(null);
                long storedDeadline = gamePlayer == null || gamePlayer.getDisconnectDeadlineEpochMs() == null
                        ? 0
                        : gamePlayer.getDisconnectDeadlineEpochMs();
                long deadline = Math.max(freshDeadline, storedDeadline);
                if (gamePlayer != null) {
                    gamePlayer.setDisconnected(true);
                    gamePlayer.setDisconnectDeadlineEpochMs(deadline);
                }
                webSocketEventListener.scheduleRecoveredDisconnect(
                        aggregate.room().getRoomId(), playerName, deadline);
            }
            if (game != null) {
                gameLifecycleService.resumeRecoveredTimers(game.getGameId());
            }
        }
        complete = true;
        AvailabilityChangeEvent.publish(applicationContext, ReadinessState.ACCEPTING_TRAFFIC);
    }

    /**
     * Reports completion separately from storage health because a healthy directory
     * is still not ready while aggregate reconstruction is in progress.
     *
     * @return {@code true} only after all aggregates and timers are restored
     */
    public boolean isComplete() {
        return complete;
    }
}
