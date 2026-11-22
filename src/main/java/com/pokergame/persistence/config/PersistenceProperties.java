package com.pokergame.persistence.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.nio.file.Path;
import java.util.Map;

/**
 * Settings for the game's autosave system, read from the {@code poker.persistence} properties.
 *
 * @param enabled             whether games are saved to disk; keep it off for quick local testing
 * @param directory           the folder where saved poker games are stored
 * @param currentKeyId        the ID of the encryption key used to save new data
 * @param keys                all encryption keys as Base64 text, by ID; old keys are kept so older saves can still be read
 * @param compactAfterRecords how many saved records a room's save file may hold before it is compacted
 * @param compactAfterBytes   how large a room's save file may grow, in bytes, before it is compacted
 * @param retryIntervalMs     how long to wait, in milliseconds, between attempts to re-save rooms whose last save
 *                            failed; also the delay before the first retry
 */
@ConfigurationProperties(prefix = "poker.persistence")
public record PersistenceProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("data/poker-wal") Path directory,
        String currentKeyId,
        @DefaultValue Map<String, String> keys,
        @DefaultValue("1000") int compactAfterRecords,
        @DefaultValue("16777216") long compactAfterBytes,
        @DefaultValue("5000") long retryIntervalMs) {
}
