package com.pokergame.persistence.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.beans.factory.annotation.Value;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import com.pokergame.persistence.wal.EncryptedWalStore;
import com.pokergame.persistence.wal.EncryptionKeyring;
import com.pokergame.persistence.transaction.DurableMutationAspect;
import com.pokergame.persistence.snapshot.AggregateSnapshotMapper;
import com.pokergame.persistence.PersistenceRecovery;
import com.pokergame.config.WebSocketEventListener;
import com.pokergame.service.GameLifecycleService;
import com.pokergame.service.RoomCoordinator;
import com.pokergame.service.RoomService;
import org.springframework.context.ApplicationContext;

/**
 * Sets up the save/restore system for the poker game.
 * <p>
 * Think of this class as the "master switchboard" for autosaving. When enabled,
 * it wires up all the necessary parts—like encryption keys, data storage, and 
 * recovery tools—so that active poker games can be safely saved to disk and
 * restored if the server crashes. Storage and recovery are conditional; the room
 * serialization aspect remains active in memory-only mode for membership safety.
 * </p>
 */
@Configuration
@EnableAspectJAutoProxy
@EnableConfigurationProperties(PersistenceProperties.class)
public class PersistenceConfiguration {
    private static final Logger logger = LoggerFactory.getLogger(PersistenceConfiguration.class);

    private final PersistenceProperties properties;

    public PersistenceConfiguration(PersistenceProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void logStartupConfig() {
        logger.info("Persistence config: enabled={}, directory={}, compactAfterRecords={}, compactAfterBytes={}, keyCount={}, currentKeyId={}",
                properties.isEnabled(),
                properties.getDirectory(),
                properties.getCompactAfterRecords(),
                properties.getCompactAfterBytes(),
                properties.getKeys() != null ? properties.getKeys().size() : 0,
                properties.getCurrentKeyId());
    }
    /**
     * Loads and checks the encryption keys when the server starts.
     * We want to find out immediately if a key is broken, before any poker games begin.
     *
     * @param properties configuration for the encryption keys
     * @return the set of keys ready to encrypt and decrypt data
     * @throws PersistenceException if any key cannot be read
     */
    @Bean
    @ConditionalOnProperty(name = "poker.persistence.enabled", havingValue = "true")
    EncryptionKeyring persistenceKeyring(PersistenceProperties properties) {
        Map<String, SecretKey> keys = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : properties.getKeys().entrySet()) {
            try {
                // Decode each base64 key into a usable AES secret key
                keys.put(entry.getKey(), new SecretKeySpec(Base64.getDecoder().decode(entry.getValue()), "AES"));
            } catch (IllegalArgumentException e) {
                // Fail loud and early so the app doesn't start with bad keys
                throw new PersistenceException("Persistence key " + entry.getKey() + " is not valid Base64", e);
            }
        }
        return new EncryptionKeyring(properties.getCurrentKeyId(), keys);
    }

    /**
     * Creates the main storage vault where all saved poker games will be kept securely.
     *
     * @param properties settings like where to save the files on disk
     * @param keyring    the keys used to lock and unlock the saved data
     * @return the secure storage system
     */
    @Bean
    @ConditionalOnProperty(name = "poker.persistence.enabled", havingValue = "true")
    EncryptedWalStore encryptedWalStore(PersistenceProperties properties, EncryptionKeyring keyring) {
        return new EncryptedWalStore(properties.getDirectory(), keyring);
    }

    /**
     * Serializes room operations in every mode and saves accepted mutations when persistence is enabled.
     * Authentication checks and their reads/mutations use the same room lease even without a WAL.
     *
     * @param store                optional secure vault, absent when persistence is disabled
     * @param snapshotMapper       the tool used to convert active game data into a saveable format
     * @param roomService          helps look up game rooms
     * @param gameLifecycleService helps manage the state of active games
     * @param properties           settings for when to clean up old save files
     * @return the background autosaving mechanism
     */
    @Bean
    DurableMutationAspect durableMutationAspect(ObjectProvider<EncryptedWalStore> store, AggregateSnapshotMapper snapshotMapper,
            ObjectProvider<RoomService> roomService,
            ObjectProvider<GameLifecycleService> gameLifecycleService,
            PersistenceProperties properties,
            ObjectProvider<RoomCoordinator> roomCoordinatorProvider) {
        RoomCoordinator coordinator = roomCoordinatorProvider.getIfAvailable(RoomCoordinator::new);
        return new DurableMutationAspect(store.getIfAvailable(), snapshotMapper, roomService, gameLifecycleService, properties, coordinator);
    }

    /**
     * Manages the process of reloading saved games back into memory when the server starts up.
     *
     * @param store                   the secure vault holding the saved games
     * @param mapper                  the tool to convert saved data back into live games
     * @param roomService             where reloaded rooms will be registered
     * @param gameLifecycleService    where reloaded games will be registered
     * @param webSocketEventListener  handles player connections after a reload
     * @param applicationContext      tells the system when the server is fully ready
     * @param disconnectGracePeriodMs extra time given to players to reconnect after a server restart
     * @return the process that recovers games
     */
    @Bean
    @ConditionalOnProperty(name = "poker.persistence.enabled", havingValue = "true")
    PersistenceRecovery persistenceRecovery(EncryptedWalStore store, AggregateSnapshotMapper mapper,
            RoomService roomService,
            GameLifecycleService gameLifecycleService,
            WebSocketEventListener webSocketEventListener,
            ApplicationContext applicationContext,
            @Value("${poker.disconnect.grace-period-ms:120000}") long disconnectGracePeriodMs,
            java.time.Clock clock,
            ObjectProvider<RoomCoordinator> roomCoordinatorProvider) {
        RoomCoordinator coordinator = roomCoordinatorProvider.getIfAvailable(() ->
                roomService != null && roomService.getRoomCoordinator() != null
                        ? roomService.getRoomCoordinator()
                        : new RoomCoordinator());
        return new PersistenceRecovery(store, mapper, roomService, gameLifecycleService, webSocketEventListener,
                applicationContext, disconnectGracePeriodMs, clock, coordinator);
    }

    /**
     * Reports degraded persistence separately from gameplay availability.
     *
     * @param store          the secure vault
     * @param recovery       the process recovering games on startup
     * @return a health check indicating if persistence is healthy
     */
    @Bean("pokerPersistenceHealth")
    @ConditionalOnProperty(name = "poker.persistence.enabled", havingValue = "true")
    HealthIndicator pokerPersistenceHealth(EncryptedWalStore store, PersistenceRecovery recovery) {
        return () -> {
            boolean persistenceHealthy = store.isHealthy();
            String lastStorageError = store.lastError();
            Health.Builder builder = recovery.isComplete() ? Health.up() : Health.down();
            return builder.withDetail("recoveryComplete", recovery.isComplete())
                    .withDetail("persistenceHealthy", persistenceHealthy)
                    .withDetail("mode", !recovery.isComplete() ? "RECOVERING" : persistenceHealthy ? "DURABLE" : "MEMORY_ONLY")
                    .withDetail("unsavedRoomCount", store.unsavedRoomCount())
                    .withDetail("activeWalCount", store.walCount())
                    .withDetail("lastStorageError", lastStorageError == null ? "none" : lastStorageError)
                    .build();
        };
    }

    /**
     * Acts like a bouncer at the door, blocking regular internet traffic until all
     * games are fully reloaded. Health checks are allowed through.
     *
     * @param recovery       the process recovering games on startup
     * @return a filter that controls early web traffic
     */
    @Bean
    @ConditionalOnProperty(name = "poker.persistence.enabled", havingValue = "true")
    FilterRegistrationBean<OncePerRequestFilter> persistenceRecoveryTrafficGate(PersistenceRecovery recovery) {
        OncePerRequestFilter filter = new OncePerRequestFilter() {
            /**
             * Blocks players from accessing the game while old games are still being restored.
             *
             * @param request     the incoming request
             * @param response    what we send back to the user
             * @param filterChain the rest of the web filters
             * @throws ServletException if a web error occurs
             * @throws IOException      if we can't write the response
             */
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                    FilterChain filterChain) throws ServletException, IOException {
                if (!recovery.isComplete() && !request.getRequestURI().startsWith("/actuator/health")) {
                    response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Recovery is not complete");
                    return;
                }
                filterChain.doFilter(request, response);
            }
        };
        FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
