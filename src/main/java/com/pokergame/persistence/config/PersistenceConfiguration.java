package com.pokergame.persistence.config;

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

/**
 * Sets up the save/restore system for the poker game.
 * <p>
 * Think of this class as the "master switchboard" for autosaving. When enabled,
 * it wires up all the necessary parts—like encryption keys, data storage, and 
 * recovery tools—so that active poker games can be safely saved to disk and
 * restored if the server crashes. All parts turn on or off together based on
 * a single setting.
 * </p>
 */
@Configuration
@EnableAspectJAutoProxy
@EnableConfigurationProperties(PersistenceProperties.class)
public class PersistenceConfiguration {
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
     * Sets up the "Autosave trigger" that automatically saves the game whenever players take actions.
     * It works silently in the background, so the core poker logic doesn't even know it's being saved.
     *
     * @param store                the secure vault where data is written
     * @param snapshotMapper       the tool used to convert active game data into a saveable format
     * @param roomService          helps look up game rooms
     * @param gameLifecycleService helps manage the state of active games
     * @param properties           settings for when to clean up old save files
     * @return the background autosaving mechanism
     */
    @Bean
    @ConditionalOnProperty(name = "poker.persistence.enabled", havingValue = "true")
    DurableMutationAspect durableMutationAspect(EncryptedWalStore store, AggregateSnapshotMapper snapshotMapper,
            ObjectProvider<com.pokergame.service.RoomService> roomService,
            ObjectProvider<com.pokergame.service.GameLifecycleService> gameLifecycleService,
            PersistenceProperties properties) {
        return new DurableMutationAspect(store, snapshotMapper, roomService, gameLifecycleService, properties);
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
            com.pokergame.service.RoomService roomService,
            com.pokergame.service.GameLifecycleService gameLifecycleService,
            com.pokergame.config.WebSocketEventListener webSocketEventListener,
            org.springframework.context.ApplicationContext applicationContext,
            @Value("${poker.disconnect.grace-period-ms:120000}") long disconnectGracePeriodMs) {
        return new PersistenceRecovery(store, mapper, roomService, gameLifecycleService, webSocketEventListener,
                applicationContext, disconnectGracePeriodMs);
    }

    /**
     * Reports on the health of the save system. If something is wrong, or if games
     * are still loading, it tells the load balancer not to send players here.
     *
     * @param store    the secure vault
     * @param recovery the process recovering games on startup
     * @return a health check indicating if it's safe to play
     */
    @Bean("pokerPersistenceHealth")
    @ConditionalOnProperty(name = "poker.persistence.enabled", havingValue = "true")
    HealthIndicator pokerPersistenceHealth(EncryptedWalStore store, PersistenceRecovery recovery) {
        return () -> {
            // Only report "up" if storage is working fine and all games have finished reloading
            Health.Builder builder = store.isHealthy() && recovery.isComplete() ? Health.up() : Health.down();
            return builder.withDetail("recoveryComplete", recovery.isComplete())
                    .withDetail("activeWalCount", store.walCount())
                    .withDetail("lastStorageError", store.lastError() == null ? "none" : store.lastError())
                    .build();
        };
    }

    /**
     * Acts like a bouncer at the door, blocking regular internet traffic until all
     * games are fully reloaded. Health checks are allowed through.
     *
     * @param recovery the process recovering games on startup
     * @return a filter that controls early web traffic
     */
    @Bean
    @ConditionalOnProperty(name = "poker.persistence.enabled", havingValue = "true")
    FilterRegistrationBean<OncePerRequestFilter> persistenceRecoveryTrafficGate(PersistenceRecovery recovery) {
        OncePerRequestFilter filter = new OncePerRequestFilter() {
            /**
             * Blocks players from accessing the game if old games are still being restored.
             * Health checks pass through so we know the server is alive.
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
                // If recovery is still running, block everything except health checks
                if (!recovery.isComplete() && !request.getRequestURI().startsWith("/actuator/health")) {
                    response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Recovery is not complete");
                    return;
                }
                // Allow the request to proceed normally
                filterChain.doFilter(request, response);
            }
        };
        FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
