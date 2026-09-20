package com.pokergame.persistence.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Holds all the settings for the game's autosave system.
 * <p>
 * Think of this as the "Settings Menu" for saving data. It controls whether 
 * autosaving is on, where the files are stored on your computer, what encryption 
 * keys to use, and when to clean up old save data.
 * </p>
 */
@ConfigurationProperties(prefix = "poker.persistence")
public class PersistenceProperties {
    private boolean enabled;
    private Path directory = Path.of("data", "poker-wal");
    private String currentKeyId;
    private Map<String, String> keys = new LinkedHashMap<>();
    private int compactAfterRecords = 1000;
    private long compactAfterBytes = 16L * 1024 * 1024;

    /**
     * Checks if the autosave system is turned on.
     *
     * @return true if games are being saved, false otherwise
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Turns the autosave system on or off. Keep it off for quick local testing.
     *
     * @param enabled true to turn on autosaving
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Gets the folder where all saved poker games are stored.
     *
     * @return the save folder path
     */
    public Path getDirectory() {
        return directory;
    }

    /**
     * Sets the folder where games should be saved.
     *
     * @param directory the new save folder path
     */
    public void setDirectory(Path directory) {
        this.directory = directory;
    }

    /**
     * Gets the ID of the encryption key currently being used to save new data.
     *
     * @return the ID of the current key
     */
    public String getCurrentKeyId() {
        return currentKeyId;
    }

    /**
     * Sets which encryption key ID should be used to save new data.
     *
     * @param currentKeyId the ID of the new key to use
     */
    public void setCurrentKeyId(String currentKeyId) {
        this.currentKeyId = currentKeyId;
    }

    /**
     * Gets all the encryption keys. We keep old ones around to read older save files.
     *
     * @return all configured keys
     */
    public Map<String, String> getKeys() {
        return keys;
    }

    /**
     * Sets the encryption keys.
     *
     * @param keys the keys to use, mapped by their ID
     */
    public void setKeys(Map<String, String> keys) {
        this.keys = keys;
    }

    /**
     * Gets the number of actions that can happen before the system cleans up the save file.
     *
     * @return the maximum number of saved actions before cleanup
     */
    public int getCompactAfterRecords() {
        return compactAfterRecords;
    }

    /**
     * Sets how many actions to save before cleaning up the file to keep it small.
     *
     * @param compactAfterRecords the maximum number of actions
     */
    public void setCompactAfterRecords(int compactAfterRecords) {
        this.compactAfterRecords = compactAfterRecords;
    }

    /**
     * Gets the maximum file size allowed before the system cleans up the save file.
     *
     * @return the file size limit in bytes
     */
    public long getCompactAfterBytes() {
        return compactAfterBytes;
    }

    /**
     * Sets the file size limit for a save file. If it gets too big, the system will clean it up.
     *
     * @param compactAfterBytes the file size limit in bytes
     */
    public void setCompactAfterBytes(long compactAfterBytes) {
        this.compactAfterBytes = compactAfterBytes;
    }
}
