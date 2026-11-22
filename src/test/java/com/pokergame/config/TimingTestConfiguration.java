package com.pokergame.config;

import com.pokergame.testutil.ControllableTaskScheduler;
import com.pokergame.testutil.MutableClock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Replaces the system clock and the application's one-shot timers with controllable ones so timing tests
 * move time by hand. It is a top-level class, not nested in the test, because Spring ignores configuration
 * classes nested in a test class when running that class's {@code @Nested} tests.
 */
@TestConfiguration(proxyBeanMethods = false)
class TimingTestConfiguration {

    /** Reading the clock starts at, in UTC epoch milliseconds. */
    static final long START_EPOCH_MS = 1_900_000_000_000L;

    @Bean
    @Primary
    MutableClock timingTestClock() {
        return new MutableClock(START_EPOCH_MS);
    }

    /**
     * Swaps the application's own {@code taskScheduler} for one whose one-shot timers run only when a
     * test asks. Recurring timers keep running on the real pool.
     */
    @Bean
    static BeanPostProcessor controllableSchedulerPostProcessor(ObjectProvider<MutableClock> clock) {
        return ControllableTaskScheduler.replacingTaskScheduler(clock);
    }
}
