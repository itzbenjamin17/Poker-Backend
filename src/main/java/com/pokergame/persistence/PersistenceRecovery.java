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
import com.pokergame.persistence.wal.EncryptedWalStore;
import com.pokergame.persistence.snapshot.AggregateSnapshotMapper;
import com.pokergame.persistence.snapshot.RecoveredAggregate;
import com.pokergame.persistence.config.PersistenceException;

/**
 * Reloads all saved poker games and rooms back into memory when the server starts up.
 * <p>
 * We load everything all at once before letting players connect. If we only loaded
 * games when players asked for them, a player looking at the lobby might think
 * an ongoing game had disappeared just because it hadn't been loaded yet.
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
     * Sets up the recovery process with everything it needs to load saved games
     * and restart game timers (like the countdown for player turns).
     *
     * @param store                   where the encrypted game data is saved on disk
     * @param mapper                  converts raw bytes back into game and room objects
     * @param roomService             manages the active poker rooms
     * @param gameLifecycleService    manages the active games and their timers
     * @param webSocketEventListener  handles player connections and disconnections
     * @param applicationContext      tells the application when it's safe to start taking requests
     * @param disconnectGracePeriodMs extra time given to players to reconnect after a server restart
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
     * Loads all saved game data, cleans up deleted rooms, and restarts game timers
     * before letting any players connect.
     * <p>
     * Since the server just started, all players are currently disconnected. We give
     * them a grace period to reconnect so they don't lose their seats just because
     * the server restarted.
     * </p>
     *
     * @param args application startup arguments
     * @throws PersistenceException if the saved data is corrupted or doesn't match
     */
    @Override
    public void run(ApplicationArguments args) {
        // Stop the server from accepting incoming requests while we load data
        AvailabilityChangeEvent.publish(applicationContext, ReadinessState.REFUSING_TRAFFIC);
        
        // Fetch all the saved raw data from disk
        Map<String, byte[]> images = store.recoverAll();
        List<RecoveredAggregate> recoveredAggregates = new ArrayList<>();

        for (Map.Entry<String, byte[]> entry : images.entrySet()) {
            // Convert the raw bytes back into readable game and room data
            RecoveredAggregate aggregate = mapper.deserialize(entry.getValue());
            
            // If the room was marked for deletion before the crash, permanently delete it now
            if (aggregate.deleted()) {
                store.delete(entry.getKey());
                continue;
            }
            
            // Make sure the file name matches the actual room ID inside the file to prevent loading corrupt data
            if (!entry.getKey().equals(aggregate.room().getRoomId())) {
                throw new PersistenceException("Snapshot room identity does not match WAL file identity");
            }
            
            // Bring the room and game back to life in the server's memory
            roomService.restoreRoom(aggregate.room(), aggregate.currentHost());
            if (aggregate.game() != null) {
                gameLifecycleService.restoreGame(aggregate.game());
            }
            recoveredAggregates.add(aggregate);
        }

        // Calculate a new grace period deadline from the current time
        long freshDeadline = System.currentTimeMillis() + disconnectGracePeriodMs;
        
        for (RecoveredAggregate aggregate : recoveredAggregates) {
            Game game = aggregate.game();
            for (String playerName : aggregate.room().getPlayers()) {
                Player gamePlayer = game == null ? null : game.getPlayers().stream()
                        .filter(player -> player.getName().equals(playerName))
                        .findFirst()
                        .orElse(null);
                        
                // Find out if the player already had a disconnection deadline before the server restarted
                long storedDeadline = gamePlayer == null || gamePlayer.getDisconnectDeadlineEpochMs() == null
                        ? 0
                        : gamePlayer.getDisconnectDeadlineEpochMs();
                        
                // Give the player whichever deadline is further in the future: their old one or the new grace period
                long deadline = Math.max(freshDeadline, storedDeadline);
                if (gamePlayer != null) {
                    gamePlayer.setDisconnected(true);
                    gamePlayer.setDisconnectDeadlineEpochMs(deadline);
                }
                
                // Tell the system to kick the player if they don't reconnect by the deadline
                webSocketEventListener.scheduleRecoveredDisconnect(
                        aggregate.room().getRoomId(), playerName, deadline);
            }
            
            // Restart the game timers (like the countdown for player turns) so the game can continue
            if (game != null) {
                gameLifecycleService.resumeRecoveredTimers(game.getGameId());
            }
        }
        
        // Mark recovery as finished and let the server accept player connections again
        complete = true;
        AvailabilityChangeEvent.publish(applicationContext, ReadinessState.ACCEPTING_TRAFFIC);
    }

    /**
     * Tells us if the server has finished loading all the games from disk.
     * Even if the files on disk are fine, the server isn't ready until this is true.
     *
     * @return {@code true} only after all games and timers are fully loaded and ready
     */
    public boolean isComplete() {
        return complete;
    }
}
