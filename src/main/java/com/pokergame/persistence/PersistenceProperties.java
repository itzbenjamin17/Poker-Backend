package com.pokergame.persistence;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

@ConfigurationProperties(prefix = "poker.persistence")
public class PersistenceProperties {
    private boolean enabled;
    private Path directory = Path.of("data", "poker-wal");
    private String currentKeyId;
    private Map<String, String> keys = new LinkedHashMap<>();
    private int compactAfterRecords = 1000;
    private long compactAfterBytes = 16L * 1024 * 1024;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Path getDirectory() { return directory; }
    public void setDirectory(Path directory) { this.directory = directory; }
    public String getCurrentKeyId() { return currentKeyId; }
    public void setCurrentKeyId(String currentKeyId) { this.currentKeyId = currentKeyId; }
    public Map<String, String> getKeys() { return keys; }
    public void setKeys(Map<String, String> keys) { this.keys = keys; }
    public int getCompactAfterRecords() { return compactAfterRecords; }
    public void setCompactAfterRecords(int compactAfterRecords) { this.compactAfterRecords = compactAfterRecords; }
    public long getCompactAfterBytes() { return compactAfterBytes; }
    public void setCompactAfterBytes(long compactAfterBytes) { this.compactAfterBytes = compactAfterBytes; }
}
