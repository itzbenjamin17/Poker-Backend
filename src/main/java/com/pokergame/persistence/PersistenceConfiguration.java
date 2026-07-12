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

@Configuration
@EnableAspectJAutoProxy
@EnableConfigurationProperties(PersistenceProperties.class)
public class PersistenceConfiguration {
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

    @Bean
    @ConditionalOnProperty(name = "poker.persistence.enabled", havingValue = "true")
    EncryptedWalStore encryptedWalStore(PersistenceProperties properties, EncryptionKeyring keyring) {
        return new EncryptedWalStore(properties.getDirectory(), keyring);
    }

    @Bean
    @ConditionalOnProperty(name = "poker.persistence.enabled", havingValue = "true")
    DurableMutationAspect durableMutationAspect(EncryptedWalStore store, AggregateSnapshotMapper snapshotMapper,
            ObjectProvider<com.pokergame.service.RoomService> roomService,
            ObjectProvider<com.pokergame.service.GameLifecycleService> gameLifecycleService,
            PersistenceProperties properties) {
        return new DurableMutationAspect(store, snapshotMapper, roomService, gameLifecycleService, properties);
    }

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

    @Bean
    @ConditionalOnProperty(name = "poker.persistence.enabled", havingValue = "true")
    FilterRegistrationBean<OncePerRequestFilter> persistenceRecoveryTrafficGate(PersistenceRecovery recovery) {
        OncePerRequestFilter filter = new OncePerRequestFilter() {
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
