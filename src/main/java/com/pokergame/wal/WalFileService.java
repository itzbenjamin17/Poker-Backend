package com.pokergame.wal;

import tools.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.stream.Stream;

@Service
public class WalFileService {
    private static final Logger logger = LoggerFactory.getLogger(WalFileService.class);
    private final String walDir;
    private final JsonMapper jsonMapper;

    public WalFileService(JsonMapper jsonMapper, @Value("${poker.wal.dir:data/wal}") String walDir) {
        this.jsonMapper = jsonMapper;
        this.walDir = walDir;
        try {
            Files.createDirectories(Paths.get(this.walDir));
        } catch (IOException e) {
            throw new RuntimeException("Could not create WAL directory", e);
        }
    }

    public void appendEvent(String roomId, WalEvent event) {
        Path filePath = getWalFilePath(roomId);
        try {
            String jsonLine = jsonMapper.writeValueAsString(event) + System.lineSeparator();
            Files.writeString(filePath, jsonLine, StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.DSYNC);
        } catch (IOException e) {
            logger.error("Failed to write to WAL for room: {}", roomId, e);
            throw new RuntimeException("Failed to write WAL", e);
        }
    }

    public void deleteWal(String roomId) {
        Path filePath = getWalFilePath(roomId);
        try {
            Files.deleteIfExists(filePath);
        } catch (IOException e) {
            logger.error("Failed to delete WAL for room: {}", roomId, e);
        }
    }

    public Stream<Path> getAllWalFiles() throws IOException {
        return Files.list(Paths.get(walDir))
                .filter(p -> p.toString().endsWith(".jsonl"));
    }

    public Path getWalFilePath(String roomId) {
        return Paths.get(walDir, "wal_" + roomId + ".jsonl");
    }
}
