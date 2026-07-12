package com.pokergame.wal;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for WalFileService.
 * Uses a temp directory to avoid polluting the real WAL directory.
 */
@Tag("unit")
@DisplayName("WalFileService")
class WalFileServiceTest {

    @TempDir
    Path tempDir;

    private JsonMapper jsonMapper;
    private WalFileService walFileService;

    @BeforeEach
    void setUp() {
        jsonMapper = JsonMapper.builder().build();
        walFileService = new WalFileService(jsonMapper, tempDir.toString());
    }

    private WalEvent sampleEvent(String service, String method) {
        JsonNode arg = jsonMapper.valueToTree("test-arg");
        return new WalEvent(service, method, List.of("java.lang.String"), List.of(arg), null);
    }

    @Nested
    @DisplayName("appendEvent")
    class AppendEvent {

        @Test
        @DisplayName("creates a new WAL file and appends an event")
        void createsFileAndAppendsEvent() throws IOException {
            walFileService.appendEvent("room-1", sampleEvent("RoomService", "createRoom"));

            Path walFile = tempDir.resolve("wal_room-1.jsonl");
            assertThat(walFile).exists();

            List<String> lines = Files.readAllLines(walFile);
            assertThat(lines).hasSize(1);
            assertThat(lines.getFirst()).contains("RoomService");
            assertThat(lines.getFirst()).contains("createRoom");
        }

        @Test
        @DisplayName("appends multiple events to the same file")
        void appendsMultipleEvents() throws IOException {
            walFileService.appendEvent("room-2", sampleEvent("RoomService", "createRoom"));
            walFileService.appendEvent("room-2", sampleEvent("GameLifecycleService", "createGameFromRoom"));
            walFileService.appendEvent("room-2", sampleEvent("PlayerActionService", "processPlayerAction"));

            Path walFile = tempDir.resolve("wal_room-2.jsonl");
            List<String> lines = Files.readAllLines(walFile);
            assertThat(lines).hasSize(3);
        }

        @Test
        @DisplayName("events are valid JSON that can be deserialized")
        void eventsAreValidJson() throws IOException {
            WalEvent original = sampleEvent("TestService", "testMethod");
            walFileService.appendEvent("room-3", original);

            Path walFile = tempDir.resolve("wal_room-3.jsonl");
            String line = Files.readAllLines(walFile).getFirst();
            WalEvent deserialized = jsonMapper.readValue(line, WalEvent.class);

            assertThat(deserialized.serviceName()).isEqualTo("TestService");
            assertThat(deserialized.methodName()).isEqualTo("testMethod");
        }

        @Test
        @DisplayName("different rooms get separate WAL files")
        void differentRoomsSeparateFiles() throws IOException {
            walFileService.appendEvent("room-a", sampleEvent("S", "m1"));
            walFileService.appendEvent("room-b", sampleEvent("S", "m2"));

            assertThat(tempDir.resolve("wal_room-a.jsonl")).exists();
            assertThat(tempDir.resolve("wal_room-b.jsonl")).exists();

            List<String> linesA = Files.readAllLines(tempDir.resolve("wal_room-a.jsonl"));
            List<String> linesB = Files.readAllLines(tempDir.resolve("wal_room-b.jsonl"));
            assertThat(linesA).hasSize(1);
            assertThat(linesB).hasSize(1);
        }
    }

    @Nested
    @DisplayName("deleteWal")
    class DeleteWal {

        @Test
        @DisplayName("deletes an existing WAL file")
        void deletesExistingFile() throws IOException {
            walFileService.appendEvent("room-del", sampleEvent("S", "m"));
            Path walFile = tempDir.resolve("wal_room-del.jsonl");
            assertThat(walFile).exists();

            walFileService.deleteWal("room-del");
            assertThat(walFile).doesNotExist();
        }

        @Test
        @DisplayName("does not throw when deleting a non-existent WAL")
        void doesNotThrowForNonExistent() {
            // Should not throw
            walFileService.deleteWal("non-existent-room");
        }
    }

    @Nested
    @DisplayName("getAllWalFiles")
    class GetAllWalFiles {

        @Test
        @DisplayName("returns empty stream when no WAL files exist")
        void returnsEmptyWhenNoFiles() throws IOException {
            try (Stream<Path> files = walFileService.getAllWalFiles()) {
                assertThat(files.count()).isZero();
            }
        }

        @Test
        @DisplayName("returns only .jsonl files")
        void returnsOnlyJsonlFiles() throws IOException {
            walFileService.appendEvent("room-1", sampleEvent("S", "m"));
            walFileService.appendEvent("room-2", sampleEvent("S", "m"));
            // Create a non-jsonl file
            Files.writeString(tempDir.resolve("not-a-wal.txt"), "ignore me");

            try (Stream<Path> files = walFileService.getAllWalFiles()) {
                List<Path> fileList = files.toList();
                assertThat(fileList).hasSize(2);
                assertThat(fileList).allMatch(p -> p.toString().endsWith(".jsonl"));
            }
        }
    }

    @Nested
    @DisplayName("getWalFilePath")
    class GetWalFilePath {

        @Test
        @DisplayName("returns path with correct naming convention")
        void returnsCorrectPath() {
            Path path = walFileService.getWalFilePath("my-room-id");
            assertThat(path.getFileName().toString()).isEqualTo("wal_my-room-id.jsonl");
        }
    }
}
