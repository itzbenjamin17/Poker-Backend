package com.pokergame.persistence.config;

import com.pokergame.persistence.PersistenceRecovery;
import com.pokergame.persistence.RoomAggregates;
import com.pokergame.persistence.RoomRecoveryHandler;
import com.pokergame.persistence.snapshot.RoomSnapshotMapper;
import com.pokergame.persistence.transaction.DurableMutationAspect;
import com.pokergame.persistence.wal.EncryptedWalStore;
import com.pokergame.persistence.wal.EncryptionKeyring;
import com.pokergame.util.RoomCoordinator;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.core.Ordered;

/**
 * Wires up the save/restore system for the poker game.
 * <p>
 * When persistence is enabled it creates the encryption keys, the encrypted save store and the startup
 * recovery, so active poker games can be saved to disk and restored if the server crashes. Storage and
 * recovery are conditional; the room serialization aspect stays active in memory-only mode for membership safety.
 * </p>
 * <p>
 * While saved games are being reloaded, the server is kept from serving players in three ways, because each
 * one covers a different caller:
 * </p>
 * <ul>
 *   <li>readiness events: {@link PersistenceRecovery} publishes "refusing traffic" while it runs and
 *       "accepting traffic" when it finishes, which tells orchestrators such as Kubernetes when to route requests;</li>
 *   <li>the {@link RecoveryTrafficGate} filter: the web server is already listening during recovery, so the
 *       filter turns away requests that arrive anyway;</li>
 *   <li>the {@link PersistenceHealthIndicator}: the health endpoint reports DOWN until recovery has finished,
 *       for monitoring that does not look at readiness events.</li>
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(PersistenceProperties.class)
@EnableAspectJAutoProxy(proxyTargetClass = true, exposeProxy = true)
public class PersistenceConfiguration {
    private static final Logger logger = LoggerFactory.getLogger(PersistenceConfiguration.class);

    private final PersistenceProperties properties;

    /**
     * Creates the configuration.
     *
     * @param properties the persistence settings
     */
    public PersistenceConfiguration(PersistenceProperties properties) {
        this.properties = properties;
    }

    /** Logs the persistence settings at startup (never the keys themselves). */
    @PostConstruct
    public void logStartupConfig() {
        logger.info("Persistence config: enabled={}, directory={}, compactAfterRecords={}, compactAfterBytes={}, keyCount={}, currentKeyId={}",
                properties.enabled(),
                properties.directory(),
                properties.compactAfterRecords(),
                properties.compactAfterBytes(),
                properties.keys().size(),
                properties.currentKeyId());
    }

    /**
     * Loads and checks the encryption keys when the server starts.
     * We want to find out immediately if a key is broken, before any poker games begin.
     *
     * @return the set of keys ready to encrypt and decrypt data
     * @throws com.pokergame.persistence.PersistenceException if any key cannot be read
     */
    @Bean
    @ConditionalOnProperty(name = "poker.persistence.enabled", havingValue = "true")
    EncryptionKeyring persistenceKeyring() {
        return EncryptionKeyring.fromBase64(properties.currentKeyId(), properties.keys());
    }

    /**
     * Creates the encrypted store where all saved poker games are kept.
     *
     * @param keyring         the keys used to lock and unlock the saved data
     * @param roomCoordinator the per-room lock shared with the rest of the application
     * @return the encrypted save store
     */
    @Bean
    @ConditionalOnProperty(name = "poker.persistence.enabled", havingValue = "true")
    EncryptedWalStore encryptedWalStore(EncryptionKeyring keyring, RoomCoordinator roomCoordinator) {
        return new EncryptedWalStore(properties.directory(), keyring, roomCoordinator);
    }

    /**
     * Serializes room operations in every mode and saves accepted mutations when persistence is enabled.
     * Authentication checks and their reads/mutations use the same room lease even without a WAL.
     *
     * @param store           the encrypted save store, absent when persistence is disabled
     * @param snapshotMapper  converts live game data to and from a saveable format
     * @param aggregates      the live rooms and games
     * @param roomCoordinator the per-room lock shared with the rest of the application
     * @return the aspect that saves each accepted change
     */
    @Bean
    DurableMutationAspect durableMutationAspect(ObjectProvider<EncryptedWalStore> store,
            RoomSnapshotMapper snapshotMapper, RoomAggregates aggregates, RoomCoordinator roomCoordinator) {
        return new DurableMutationAspect(store.getIfAvailable(), snapshotMapper, aggregates, properties,
                roomCoordinator);
    }

    /**
     * Reloads saved games back into memory when the server starts up.
     *
     * @param store              the encrypted save store
     * @param mapper             converts saved data back into live games
     * @param recoveryHandler    applies restart rules to each recovered room
     * @param applicationContext used to publish the readiness events
     * @param roomCoordinator    the per-room lock shared with the rest of the application
     * @return the startup recovery
     */
    @Bean
    @ConditionalOnProperty(name = "poker.persistence.enabled", havingValue = "true")
    PersistenceRecovery persistenceRecovery(EncryptedWalStore store, RoomSnapshotMapper mapper,
            RoomRecoveryHandler recoveryHandler,
            ApplicationContext applicationContext,
            RoomCoordinator roomCoordinator) {
        return new PersistenceRecovery(store, mapper, recoveryHandler, applicationContext, roomCoordinator);
    }

    /**
     * Reports degraded persistence separately from gameplay availability.
     *
     * @param store    the encrypted save store
     * @param recovery the startup recovery
     * @return a health check showing whether recovery has finished and saving is working
     */
    @Bean("pokerPersistenceHealth")
    @ConditionalOnProperty(name = "poker.persistence.enabled", havingValue = "true")
    HealthIndicator pokerPersistenceHealth(EncryptedWalStore store, PersistenceRecovery recovery) {
        return new PersistenceHealthIndicator(store, recovery);
    }

    /**
     * Registers the filter that blocks regular web traffic until all games are reloaded.
     * It runs before every other filter.
     *
     * @param recovery the startup recovery
     * @return the filter registration
     */
    @Bean
    @ConditionalOnProperty(name = "poker.persistence.enabled", havingValue = "true")
    FilterRegistrationBean<RecoveryTrafficGate> persistenceRecoveryTrafficGate(PersistenceRecovery recovery) {
        FilterRegistrationBean<RecoveryTrafficGate> registration =
                new FilterRegistrationBean<>(new RecoveryTrafficGate(recovery));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
