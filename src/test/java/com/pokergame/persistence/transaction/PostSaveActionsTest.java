package com.pokergame.persistence.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** Unit tests for {@link PostSaveActions} queueing, execution, and discard semantics. */
@Tag("unit")
class PostSaveActionsTest {

    @AfterEach
    void clearThreadLocal() {
        PostSaveActions.clear();
    }

    @Test
    void runsImmediatelyAndInOrderWhenNoSaveContextIsActive() {
        List<String> executed = new ArrayList<>();

        PostSaveActions.runAfterSave(() -> executed.add("first"));
        assertThat(executed).containsExactly("first");

        PostSaveActions.runAfterSave(() -> executed.add("second"));
        assertThat(executed).containsExactly("first", "second");
        assertThat(PostSaveActions.currentRoomId()).isNull();
    }

    @Test
    void logsFailureWithStackTraceAndReturnsNormallyWhenNoSaveContextIsActive() {
        assertLogsErrorWithStackTrace(
                () -> PostSaveActions.runAfterSave(() -> {
                    throw new IllegalStateException("broker down");
                }),
                IllegalStateException.class,
                "broker down",
                "IllegalStateException");
        assertThat(PostSaveActions.currentRoomId()).isNull();
    }

    @Test
    void executeAllLogsFailureWithStackTrace() {
        PostSaveActions.begin("room-1");
        PostSaveActions.runAfterSave(() -> {
            throw new IllegalStateException("broker down");
        });

        assertLogsErrorWithStackTrace(
                PostSaveActions::executeAll,
                IllegalStateException.class,
                "broker down",
                "room-1", "IllegalStateException");
    }

    @Test
    void queuesUntilExecuteAllAndContinuesAfterActionFailure() {
        List<String> executed = new ArrayList<>();
        PostSaveActions.begin("room-1");
        assertThat(PostSaveActions.currentRoomId()).isEqualTo("room-1");

        PostSaveActions.runAfterSave(() -> executed.add("first"));
        PostSaveActions.runAfterSave(() -> {
            throw new IllegalStateException("broker down");
        });
        PostSaveActions.runAfterSave(() -> executed.add("third"));

        assertThat(executed).isEmpty();

        PostSaveActions.executeAll();

        assertThat(executed).containsExactly("first", "third");
        assertThat(PostSaveActions.currentRoomId()).isNull();
    }

    @Test
    void clearDiscardsQueuedActionsWithoutRunningThem() {
        List<String> executed = new ArrayList<>();
        PostSaveActions.begin("room-1");
        PostSaveActions.runAfterSave(() -> executed.add("discarded"));

        PostSaveActions.clear();
        PostSaveActions.executeAll();

        assertThat(PostSaveActions.currentRoomId()).isNull();
        assertThat(executed).isEmpty();
    }

    @Test
    void rejectsNullAction() {
        assertThatThrownBy(() -> PostSaveActions.runAfterSave(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("action must not be null");
    }

    @Test
    void rejectsNullActionWithoutDisturbingActiveQueue() {
        List<String> executed = new ArrayList<>();
        PostSaveActions.begin("room-1");
        PostSaveActions.runAfterSave(() -> executed.add("kept"));

        assertThatThrownBy(() -> PostSaveActions.runAfterSave(null)).isInstanceOf(NullPointerException.class);
        PostSaveActions.executeAll();

        assertThat(executed).containsExactly("kept");
    }

    @Test
    void executeAllWithoutActiveContextDoesNothing() {
        assertThatCode(PostSaveActions::executeAll).doesNotThrowAnyException();

        assertThat(PostSaveActions.currentRoomId()).isNull();
    }

    @Test
    void actionQueuedDuringExecuteAllRunsImmediatelyBecauseContextIsAlreadyClosed() {
        List<String> executed = new ArrayList<>();
        PostSaveActions.begin("room-1");
        PostSaveActions.runAfterSave(() -> {
            executed.add("outer");
            PostSaveActions.runAfterSave(() -> executed.add("nested"));
            executed.add("outer-after-nested-queue");
        });

        PostSaveActions.executeAll();

        assertThat(executed).containsExactly("outer", "nested", "outer-after-nested-queue");
        assertThat(PostSaveActions.currentRoomId()).isNull();
    }

    private static void assertLogsErrorWithStackTrace(
            Runnable action,
            Class<? extends Throwable> expectedException,
            String expectedExceptionMessage,
            String... expectedLogSubstrings) {
        Logger logbackLogger = (Logger) LoggerFactory.getLogger(PostSaveActions.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logbackLogger.addAppender(appender);
        try {
            action.run();

            assertThat(appender.list)
                    .singleElement()
                    .satisfies(event -> {
                        assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                        assertThat(event.getFormattedMessage()).contains(expectedLogSubstrings);
                        assertThat(event.getThrowableProxy()).isNotNull();
                        assertThat(event.getThrowableProxy().getClassName()).isEqualTo(expectedException.getName());
                        assertThat(event.getThrowableProxy().getMessage()).isEqualTo(expectedExceptionMessage);
                    });
        } finally {
            logbackLogger.detachAppender(appender);
            appender.stop();
        }
    }
}
