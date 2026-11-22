package com.pokergame.testutil;

import com.pokergame.config.MdcTaskDecorator;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * A {@link ThreadPoolTaskScheduler} that keeps one-shot {@code schedule(Runnable, Instant)} timers in a
 * {@link DeterministicTaskScheduler}, so tests run them on the calling thread against a {@link MutableClock}.
 * <p>
 * It stays a {@code ThreadPoolTaskScheduler} because {@code @EnableScheduling} registers fixed-rate and
 * fixed-delay tasks on the application scheduler, which the deterministic fake does not support; those
 * recurring tasks keep running on the real pool.
 * </p>
 */
public final class ControllableTaskScheduler extends ThreadPoolTaskScheduler {

    private final DeterministicTaskScheduler oneShotTasks;

    /**
     * Creates a scheduler whose one-shot timers become due according to {@code clock}.
     *
     * @param clock clock that decides when a timer is due
     */
    public ControllableTaskScheduler(MutableClock clock) {
        this.oneShotTasks = new DeterministicTaskScheduler(clock);
    }

    /**
     * Creates a post-processor that replaces the application's {@code taskScheduler} bean with a
     * {@code ControllableTaskScheduler} that has the same pool size, thread prefix and MDC decorator.
     * Declare it as a {@code static @Bean} method on a {@code @TestConfiguration}.
     *
     * @param clock provider of the test's {@link MutableClock} bean
     * @return post-processor that swaps in the controllable scheduler
     */
    public static BeanPostProcessor replacingTaskScheduler(ObjectProvider<MutableClock> clock) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (!"taskScheduler".equals(beanName) || !(bean instanceof ThreadPoolTaskScheduler original)) {
                    return bean;
                }
                original.shutdown();
                ControllableTaskScheduler scheduler = new ControllableTaskScheduler(clock.getObject());
                scheduler.setPoolSize(5);
                scheduler.setThreadNamePrefix("TaskScheduler-");
                scheduler.setTaskDecorator(new MdcTaskDecorator());
                scheduler.initialize();
                return scheduler;
            }
        };
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable task, Instant startTime) {
        return oneShotTasks.schedule(task, startTime);
    }

    /**
     * Runs every pending one-shot timer due at or before {@code now}.
     *
     * @param now instant to compare each timer's start time against
     * @return how many timers ran
     */
    public int triggerDueTasks(Instant now) {
        return oneShotTasks.runDueTasks(now);
    }

    /** Forgets every one-shot timer, as a stopped process would. */
    public void clearTrackedTasks() {
        oneShotTasks.clear();
    }

    /**
     * Returns the start time of every one-shot timer that has neither run nor been cancelled.
     *
     * @return pending start times
     */
    public List<Instant> pendingStartTimes() {
        return oneShotTasks.pendingTasks().stream()
                .map(DeterministicTaskScheduler.ScheduledTask::startTime)
                .toList();
    }
}
