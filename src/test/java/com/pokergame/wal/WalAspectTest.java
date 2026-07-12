package com.pokergame.wal;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
@DisplayName("WalAspect")
class WalAspectTest {

    @Mock
    private WalFileService walFileService;

    @Mock
    private ProceedingJoinPoint joinPoint;

    @Mock
    private MethodSignature methodSignature;

    private JsonMapper jsonMapper;
    private WalAspect walAspect;

    @BeforeEach
    void setUp() {
        jsonMapper = JsonMapper.builder().build();
        walAspect = new WalAspect(walFileService, jsonMapper);
    }

    @AfterEach
    void tearDown() {
        WalContext.setGlobalReplayMode(false);
        WalContext.clear();
    }

    // ---- Helper methods to simulate annotated method calls ----

    /**
     * Dummy service class with annotated methods for reflection-based testing.
     */
    @SuppressWarnings("unused")
    static class DummyService {
        @WalLogged(roomId = "#gameId")
        public void writeAheadMethod(String gameId, String playerName) {
        }

        @WalLogged(roomId = "#result", writeAfter = true)
        public String writeBehindMethod(String roomName) {
            return "generated-id";
        }
    }

    private void setupJoinPoint(String methodName, Class<?>[] paramTypes, Object[] args, Object target)
            throws NoSuchMethodException {
        Method method = DummyService.class.getMethod(methodName, paramTypes);
        when(joinPoint.getSignature()).thenReturn(methodSignature);
        when(methodSignature.getMethod()).thenReturn(method);
        when(joinPoint.getArgs()).thenReturn(args);
        when(joinPoint.getTarget()).thenReturn(target);
    }

    @Nested
    @DisplayName("write-ahead (default) mode")
    class WriteAheadMode {

        @Test
        @DisplayName("logs to WAL BEFORE proceeding for write-ahead methods")
        void logsBeforeProceeding() throws Throwable {
            DummyService target = new DummyService();
            setupJoinPoint("writeAheadMethod", new Class[] { String.class, String.class },
                    new Object[] { "room-123", "Alice" }, target);
            when(joinPoint.proceed()).thenReturn(null);

            WalLogged annotation = DummyService.class.getMethod("writeAheadMethod", String.class, String.class)
                    .getAnnotation(WalLogged.class);

            walAspect.logToWal(joinPoint, annotation);

            // Verify WAL was written with correct roomId
            ArgumentCaptor<WalEvent> eventCaptor = ArgumentCaptor.forClass(WalEvent.class);
            verify(walFileService).appendEvent(eq("room-123"), eventCaptor.capture());

            WalEvent captured = eventCaptor.getValue();
            assertThat(captured.serviceName()).isEqualTo("DummyService");
            assertThat(captured.methodName()).isEqualTo("writeAheadMethod");
            assertThat(captured.parameterTypes()).containsExactly("java.lang.String", "java.lang.String");
            assertThat(captured.arguments()).hasSize(2);

            // Verify proceed was called
            verify(joinPoint).proceed();
        }

        @Test
        @DisplayName("does not proceed if WAL write fails")
        void doesNotProceedOnWalFailure() throws Throwable {
            DummyService target = new DummyService();
            setupJoinPoint("writeAheadMethod", new Class[] { String.class, String.class },
                    new Object[] { "room-fail", "Bob" }, target);

            org.mockito.Mockito.doThrow(new RuntimeException("Disk full"))
                    .when(walFileService).appendEvent(any(), any());

            WalLogged annotation = DummyService.class.getMethod("writeAheadMethod", String.class, String.class)
                    .getAnnotation(WalLogged.class);

            assertThatThrownBy(() -> walAspect.logToWal(joinPoint, annotation))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Disk full");

            // Verify proceed WAS called, since we changed to Write-Behind
            verify(joinPoint).proceed();
        }
    }

    @Nested
    @DisplayName("write-behind mode")
    class WriteBehindMode {

        @Test
        @DisplayName("logs to WAL AFTER proceeding for write-behind methods")
        void logsAfterProceeding() throws Throwable {
            DummyService target = new DummyService();
            setupJoinPoint("writeBehindMethod", new Class[] { String.class },
                    new Object[] { "My Room" }, target);
            when(joinPoint.proceed()).thenReturn("generated-room-id");

            WalLogged annotation = DummyService.class.getMethod("writeBehindMethod", String.class)
                    .getAnnotation(WalLogged.class);

            Object result = walAspect.logToWal(joinPoint, annotation);

            // Verify proceed was called and result returned
            assertThat(result).isEqualTo("generated-room-id");

            // Verify WAL was written with the result as roomId
            ArgumentCaptor<WalEvent> eventCaptor = ArgumentCaptor.forClass(WalEvent.class);
            verify(walFileService).appendEvent(eq("generated-room-id"), eventCaptor.capture());

            WalEvent captured = eventCaptor.getValue();
            assertThat(captured.serviceName()).isEqualTo("DummyService");
            assertThat(captured.methodName()).isEqualTo("writeBehindMethod");
        }
    }

    @Nested
    @DisplayName("replay mode bypass")
    class ReplayModeBypass {

        @Test
        @DisplayName("skips WAL logging during global replay")
        void skipsLoggingDuringGlobalReplay() throws Throwable {
            WalContext.setGlobalReplayMode(true);

            // During replay, the aspect only calls joinPoint.proceed() — no signature/args
            when(joinPoint.proceed()).thenReturn(null);

            WalLogged annotation = DummyService.class.getMethod("writeAheadMethod", String.class, String.class)
                    .getAnnotation(WalLogged.class);

            walAspect.logToWal(joinPoint, annotation);

            // Verify WAL was NOT written
            verify(walFileService, never()).appendEvent(any(), any());
            // Verify proceed WAS called
            verify(joinPoint).proceed();
        }

        @Test
        @DisplayName("skips WAL logging during thread-local replay")
        void skipsLoggingDuringThreadLocalReplay() throws Throwable {
            WalContext.setThreadReplayMode(true);

            // During replay, the aspect only calls joinPoint.proceed() — no signature/args
            when(joinPoint.proceed()).thenReturn(null);

            WalLogged annotation = DummyService.class.getMethod("writeAheadMethod", String.class, String.class)
                    .getAnnotation(WalLogged.class);

            walAspect.logToWal(joinPoint, annotation);

            verify(walFileService, never()).appendEvent(any(), any());
            verify(joinPoint).proceed();
        }
    }

    @Nested
    @DisplayName("argument serialization")
    class ArgumentSerialization {

        @Test
        @DisplayName("serializes method arguments as JsonNode values")
        void serializesArgumentsAsJson() throws Throwable {
            DummyService target = new DummyService();
            setupJoinPoint("writeAheadMethod", new Class[] { String.class, String.class },
                    new Object[] { "room-ser", "Eve" }, target);
            when(joinPoint.proceed()).thenReturn(null);

            WalLogged annotation = DummyService.class.getMethod("writeAheadMethod", String.class, String.class)
                    .getAnnotation(WalLogged.class);

            walAspect.logToWal(joinPoint, annotation);

            ArgumentCaptor<WalEvent> eventCaptor = ArgumentCaptor.forClass(WalEvent.class);
            verify(walFileService).appendEvent(eq("room-ser"), eventCaptor.capture());

            List<JsonNode> args = eventCaptor.getValue().arguments();
            assertThat(args.get(0).asString()).isEqualTo("room-ser");
            assertThat(args.get(1).asString()).isEqualTo("Eve");
        }
    }
}
