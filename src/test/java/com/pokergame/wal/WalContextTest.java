package com.pokergame.wal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
@DisplayName("WalContext")
class WalContextTest {

    @AfterEach
    void tearDown() {
        WalContext.setGlobalReplayMode(false);
        WalContext.clear();
    }

    @Nested
    @DisplayName("global replay mode")
    class GlobalReplayMode {

        @Test
        @DisplayName("defaults to not replaying")
        void defaultsToNotReplaying() {
            assertThat(WalContext.isReplaying()).isFalse();
        }

        @Test
        @DisplayName("returns true when global replay mode is enabled")
        void returnsTrueWhenGlobalEnabled() {
            WalContext.setGlobalReplayMode(true);
            assertThat(WalContext.isReplaying()).isTrue();
        }

        @Test
        @DisplayName("returns false after global replay mode is disabled")
        void returnsFalseAfterGlobalDisabled() {
            WalContext.setGlobalReplayMode(true);
            WalContext.setGlobalReplayMode(false);
            assertThat(WalContext.isReplaying()).isFalse();
        }
    }

    @Nested
    @DisplayName("thread-local replay mode")
    class ThreadLocalReplayMode {

        @Test
        @DisplayName("returns true when thread-local replay mode is set")
        void returnsTrueWhenThreadLocalSet() {
            WalContext.setThreadReplayMode(true);
            assertThat(WalContext.isReplaying()).isTrue();
        }

        @Test
        @DisplayName("returns false after thread-local replay mode is cleared")
        void returnsFalseAfterThreadLocalCleared() {
            WalContext.setThreadReplayMode(true);
            WalContext.clear();
            assertThat(WalContext.isReplaying()).isFalse();
        }

        @Test
        @DisplayName("thread-local does not leak to other threads")
        void threadLocalDoesNotLeak() throws InterruptedException {
            WalContext.setThreadReplayMode(true);

            boolean[] otherThreadResult = {true};
            Thread thread = new Thread(() -> otherThreadResult[0] = WalContext.isReplaying());
            thread.start();
            thread.join();

            assertThat(otherThreadResult[0]).isFalse();
        }
    }

    @Nested
    @DisplayName("combined modes")
    class CombinedModes {

        @Test
        @DisplayName("isReplaying returns true if either mode is active")
        void eitherModeTriggersReplaying() {
            // Only global
            WalContext.setGlobalReplayMode(true);
            assertThat(WalContext.isReplaying()).isTrue();
            WalContext.setGlobalReplayMode(false);

            // Only thread-local
            WalContext.setThreadReplayMode(true);
            assertThat(WalContext.isReplaying()).isTrue();
        }

        @Test
        @DisplayName("isReplaying returns true when both modes are active")
        void bothModesActive() {
            WalContext.setGlobalReplayMode(true);
            WalContext.setThreadReplayMode(true);
            assertThat(WalContext.isReplaying()).isTrue();
        }
    }
}
