package com.pokergame.wal;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
@DisplayName("WalReplayService")
class WalReplayServiceTest {

    @Mock
    private WalFileService walFileService;

    @Mock
    private ApplicationContext applicationContext;

    private JsonMapper jsonMapper;
    private WalReplayService walReplayService;

    @BeforeEach
    void setUp() {
        jsonMapper = JsonMapper.builder().build();
        walReplayService = new WalReplayService(walFileService, jsonMapper, applicationContext);
    }

    @AfterEach
    void tearDown() {
        WalContext.setGlobalReplayMode(false);
        WalContext.clear();
    }

    @Nested
    @DisplayName("replayAll")
    class ReplayAll {

        @Test
        @DisplayName("enables and disables global replay mode around replay")
        void togglesReplayMode() throws IOException {
            when(walFileService.getAllWalFiles()).thenReturn(Stream.empty());

            // Before replay
            assertThat(WalContext.isReplaying()).isFalse();

            walReplayService.replayAll();

            // After replay — should be back to false
            assertThat(WalContext.isReplaying()).isFalse();
        }

        @Test
        @DisplayName("disables replay mode even if an IOException is thrown")
        void disablesReplayOnException() throws IOException {
            when(walFileService.getAllWalFiles()).thenThrow(new IOException("Disk error"));

            walReplayService.replayAll();

            // Replay mode must be off even after error
            assertThat(WalContext.isReplaying()).isFalse();
        }

        @Test
        @DisplayName("replays events from a WAL file by invoking the correct service bean")
        void replaysEventsFromFile() throws Exception {
            // Create a temp file with a WAL event
            Path tempFile = Files.createTempFile("wal_test_", ".jsonl");
            try {
                // Build a WAL event that calls a method on a mock service
                JsonNode arg = jsonMapper.valueToTree("test-value");
                WalEvent event = new WalEvent(
                        "TestReplayTarget",
                        "receiveValue",
                        List.of("java.lang.String"),
                        List.of(arg),
                        null
                );
                Files.writeString(tempFile, jsonMapper.writeValueAsString(event) + "\n");

                when(walFileService.getAllWalFiles()).thenReturn(Stream.of(tempFile));

                TestReplayTarget target = new TestReplayTarget();
                when(applicationContext.getBean("testReplayTarget")).thenReturn(target);

                walReplayService.replayAll();

                assertThat(target.receivedValue).isEqualTo("test-value");
            } finally {
                Files.deleteIfExists(tempFile);
            }
        }

        @Test
        @DisplayName("skips corrupt JSON lines and continues replaying subsequent valid lines")
        void skipsCorruptLinesAndContinuesReplay() throws Exception {
            Path tempFile = Files.createTempFile("wal_corrupt_", ".jsonl");
            try {
                // Build a valid WAL event
                JsonNode arg = jsonMapper.valueToTree("valid-value");
                WalEvent validEvent = new WalEvent(
                        "TestReplayTarget",
                        "receiveValue",
                        List.of("java.lang.String"),
                        List.of(arg),
                        null
                );
                
                String validJson = jsonMapper.writeValueAsString(validEvent);
                String corruptJson = "{ corrupt json line... ";
                
                // Write: valid, corrupt, valid
                Files.writeString(tempFile, validJson + "\n" + corruptJson + "\n" + validJson + "\n");

                when(walFileService.getAllWalFiles()).thenReturn(Stream.of(tempFile));

                TestReplayTarget target = new TestReplayTarget();
                when(applicationContext.getBean("testReplayTarget")).thenReturn(target);

                walReplayService.replayAll();

                // It should have received the valid event twice, despite the corrupt line in the middle
                assertThat(target.receiveCount).isEqualTo(2);
                assertThat(target.receivedValue).isEqualTo("valid-value");
            } finally {
                Files.deleteIfExists(tempFile);
            }
        }

        @Test
        @DisplayName("handles empty WAL file gracefully")
        void handlesEmptyFile() throws Exception {
            Path tempFile = Files.createTempFile("wal_empty_", ".jsonl");
            try {
                // Empty file
                when(walFileService.getAllWalFiles()).thenReturn(Stream.of(tempFile));

                // Should not throw
                walReplayService.replayAll();
            } finally {
                Files.deleteIfExists(tempFile);
            }
        }

        @Test
        @DisplayName("skips events with unknown service beans")
        void skipsUnknownBeans() throws Exception {
            Path tempFile = Files.createTempFile("wal_unknown_", ".jsonl");
            try {
                JsonNode arg = jsonMapper.valueToTree("val");
                WalEvent event = new WalEvent(
                        "NonExistentService",
                        "someMethod",
                        List.of("java.lang.String"),
                        List.of(arg),
                        null
                );
                Files.writeString(tempFile, jsonMapper.writeValueAsString(event) + "\n");

                when(walFileService.getAllWalFiles()).thenReturn(Stream.of(tempFile));
                when(applicationContext.getBean("nonExistentService")).thenThrow(new org.springframework.beans.factory.NoSuchBeanDefinitionException("not found"));

                // Should not throw — just logs an error
                walReplayService.replayAll();
            } finally {
                Files.deleteIfExists(tempFile);
            }
        }

        @Test
        @DisplayName("replays multiple events in order")
        void replaysMultipleEventsInOrder() throws Exception {
            Path tempFile = Files.createTempFile("wal_multi_", ".jsonl");
            try {
                TestReplayTarget target = new TestReplayTarget();
                when(applicationContext.getBean("testReplayTarget")).thenReturn(target);

                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < 3; i++) {
                    JsonNode arg = jsonMapper.valueToTree("value-" + i);
                    WalEvent event = new WalEvent(
                            "TestReplayTarget",
                            "receiveValue",
                            List.of("java.lang.String"),
                            List.of(arg),
                            null
                    );
                    sb.append(jsonMapper.writeValueAsString(event)).append("\n");
                }
                Files.writeString(tempFile, sb.toString());
                when(walFileService.getAllWalFiles()).thenReturn(Stream.of(tempFile));

                walReplayService.replayAll();

                // Last call wins for receivedValue; callCount tracks total
                assertThat(target.callCount).isEqualTo(3);
                assertThat(target.receivedValue).isEqualTo("value-2");
            } finally {
                Files.deleteIfExists(tempFile);
            }
        }

        @Test
        @DisplayName("handles primitive parameter types during replay")
        void handlesPrimitiveTypes() throws Exception {
            Path tempFile = Files.createTempFile("wal_prim_", ".jsonl");
            try {
                TestReplayTarget target = new TestReplayTarget();
                when(applicationContext.getBean("testReplayTarget")).thenReturn(target);

                JsonNode arg1 = jsonMapper.valueToTree("room-1");
                JsonNode arg2 = jsonMapper.valueToTree(42);
                JsonNode arg3 = jsonMapper.valueToTree(true);
                WalEvent event = new WalEvent(
                        "TestReplayTarget",
                        "receiveWithPrimitives",
                        List.of("java.lang.String", "int", "boolean"),
                        List.of(arg1, arg2, arg3),
                        null
                );
                Files.writeString(tempFile, jsonMapper.writeValueAsString(event) + "\n");
                when(walFileService.getAllWalFiles()).thenReturn(Stream.of(tempFile));

                walReplayService.replayAll();

                assertThat(target.receivedValue).isEqualTo("room-1");
                assertThat(target.receivedInt).isEqualTo(42);
                assertThat(target.receivedBool).isTrue();
            } finally {
                Files.deleteIfExists(tempFile);
            }
        }
    }

    /**
     * A dummy target bean used to verify replay invocations via reflection.
     */
    public static class TestReplayTarget {
        String receivedValue;
        int callCount;
        int receiveCount;
        int receivedInt;
        boolean receivedBool;

        public void receiveValue(String value) {
            this.receivedValue = value;
            this.callCount++;
            this.receiveCount++;
        }

        public void receiveWithPrimitives(String value, int num, boolean flag) {
            this.receivedValue = value;
            this.receivedInt = num;
            this.receivedBool = flag;
            this.callCount++;
        }
    }
}
