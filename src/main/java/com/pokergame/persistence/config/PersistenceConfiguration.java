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
 * Spring configuration for per-room mutation locking and encrypted disk persistence.
 * <p>
 * {@link DurableMutationAspect} is always active so concurrent operations on the same room
 * run one at a time under {@link RoomCoordinator}, even when disk persistence is disabled.
 * </p>
 * <p>
 * When {@code poker.persistence.enabled=true}, this also wires up the encryption keyring,
 * encrypted WAL store, startup recovery, health indicator, and {@link RecoveryTrafficGate}
 * (which returns 503 to regular requests until saved rooms have finished loading).
 * </p>
 */
@Configuration
@EnableConfigurationProperties(PersistenceProperties.class)
@EnableAspectJAutoProxy(proxyTargetClass = true)
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
     * Locks each {@code @DurableMutation} call per room and saves accepted changes when disk
     * persistence is enabled. Stays active with persistence disabled so concurrent operations
     * on the same room still execute one at a time.
     *
     * @param store           the encrypted save store, or absent when disk persistence is disabled
     * @param snapshotMapper  converts live room and game data to and from the saved format
     * @param aggregates      reads, replaces, and removes live rooms and games
     * @param roomCoordinator the per-room lock shared with the rest of the application
     * @return the aspect that locks and (when enabled) persists room mutations
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
