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

    public boolean isComplete() {
        return complete;
    }
}
