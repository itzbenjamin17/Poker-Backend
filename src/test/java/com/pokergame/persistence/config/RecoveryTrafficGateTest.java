package com.pokergame.persistence.config;

import com.pokergame.persistence.PersistenceRecovery;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Unit tests for {@link RecoveryTrafficGate}. */
@Tag("unit")
class RecoveryTrafficGateTest {

    private final PersistenceRecovery recovery = mock(PersistenceRecovery.class);
    private final FilterChain chain = mock(FilterChain.class);
    private final RecoveryTrafficGate gate = new RecoveryTrafficGate(recovery);

    @Test
    void regularRequestsAreRejectedWhileRecoveryIsRunning() throws Exception {
        when(recovery.isComplete()).thenReturn(false);
        MockHttpServletResponse response = new MockHttpServletResponse();

        gate.doFilter(new MockHttpServletRequest("GET", "/api/rooms"), response, chain);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getErrorMessage()).isEqualTo("Recovery is not complete");
        verifyNoInteractions(chain);
    }

    @Test
    void healthChecksPassWhileRecoveryIsRunning() throws Exception {
        when(recovery.isComplete()).thenReturn(false);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health/readiness");
        MockHttpServletResponse response = new MockHttpServletResponse();

        gate.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void everyRequestPassesOnceRecoveryIsComplete() throws Exception {
        when(recovery.isComplete()).thenReturn(true);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/rooms");
        MockHttpServletResponse response = new MockHttpServletResponse();

        gate.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }
}
