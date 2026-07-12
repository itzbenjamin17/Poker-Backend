package com.pokergame.persistence;

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

/**
 * Activates encrypted recovery as one coherent Spring subsystem.
 * <p>
 * Every bean is conditional on the same property so the legacy in-memory profile
 * cannot accidentally receive only part of the durability boundary.
 * </p>
 */
@Configuration
@EnableAspectJAutoProxy
@EnableConfigurationProperties(PersistenceProperties.class)
public class PersistenceConfiguration {
    /**
     * Decodes all configured keys during startup so malformed or incomplete key
     * rotation fails before the application accepts poker traffic.
     *
     * @param properties persistence key configuration
     * @return validated current-and-historical keyring
     * @throws PersistenceException if any key is malformed or unusable
     */
    @Bean
    @ConditionalOnProperty(name = "poker.persistence.enabled", havingValue = "true")
    EncryptionKeyring persistenceKeyring(PersistenceProperties properties) {
        Map<String, SecretKey> keys = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : properties.getKeys().entrySet()) {
            try {
                keys.put(entry.getKey(), new SecretKeySpec(Base64.getDecoder().decode(entry.getValue()), "AES"));
            } catch (IllegalArgumentException e) {
                throw new PersistenceException("Persistence key " + entry.getKey() + " is not valid Base64", e);
            }
        }
        return new EncryptionKeyring(properties.getCurrentKeyId(), keys);
    }

    /**
     * Creates the single store instance whose health state gates every subsequent
     * room mutation.
     *
     * @param properties persistence directory configuration
     * @param keyring    validated encryption keyring
     * @return encrypted per-room WAL store
     */
    @Bean
    @ConditionalOnProperty(name = "poker.persistence.enabled", havingValue = "true")
    EncryptedWalStore encryptedWalStore(PersistenceProperties properties, EncryptionKeyring keyring) {
        return new EncryptedWalStore(properties.getDirectory(), keyring);
    }

    /**
     * Installs durability at service mutation seams rather than inside domain
     * objects, keeping models independent of storage and Spring proxies.
     *
     * @param store                encrypted WAL store
     * @param snapshotMapper       explicit aggregate mapper
     * @param roomService          lazy room service provider
     * @param gameLifecycleService lazy game service provider
     * @param properties           compaction policy
     * @return mutation advice
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
     * Coordinates startup recovery before readiness is exposed and rebuilds runtime
     * timers that are intentionally absent from state images.
     *
     * @param store                   encrypted WAL store
     * @param mapper                  aggregate compatibility mapper
     * @param roomService             room registry
     * @param gameLifecycleService    game registry and scheduler owner
     * @param webSocketEventListener  reconnect cleanup scheduler
     * @param applicationContext      readiness event source
     * @param disconnectGracePeriodMs fresh grace granted after restart
     * @return startup recovery runner
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
     * Exposes one fail-closed health signal for both storage integrity and recovery
     * completion so orchestration never routes traffic to partially restored state.
     *
     * @param store    encrypted WAL store
     * @param recovery startup recovery coordinator
     * @return persistence health contributor
     */
    @Bean("pokerPersistenceHealth")
    @ConditionalOnProperty(name = "poker.persistence.enabled", havingValue = "true")
    HealthIndicator pokerPersistenceHealth(EncryptedWalStore store, PersistenceRecovery recovery) {
        return () -> {
            Health.Builder builder = store.isHealthy() && recovery.isComplete() ? Health.up() : Health.down();
            return builder.withDetail("recoveryComplete", recovery.isComplete())
                    .withDetail("activeWalCount", store.walCount())
                    .withDetail("lastStorageError", store.lastError() == null ? "none" : store.lastError())
                    .build();
        };
    }

    /**
     * Rejects application traffic until recovery finishes because the embedded web
     * server may start before {@link org.springframework.boot.ApplicationRunner}
     * execution completes. Health remains reachable for startup diagnostics.
     *
     * @param recovery startup recovery coordinator
     * @return highest-precedence servlet filter registration
     */
    @Bean
    @ConditionalOnProperty(name = "poker.persistence.enabled", havingValue = "true")
    FilterRegistrationBean<OncePerRequestFilter> persistenceRecoveryTrafficGate(PersistenceRecovery recovery) {
        OncePerRequestFilter filter = new OncePerRequestFilter() {
            /**
             * Keeps health probes available while preventing clients from observing
             * a partially populated room registry.
             *
             * @param request     current HTTP request
             * @param response    current HTTP response
             * @param filterChain remaining servlet filter chain
             * @throws ServletException if downstream filtering fails
             * @throws IOException      if the response cannot be written
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
