package com.pokergame.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableAsync
public class AsyncConfiguration {

    @Bean(name = "gameExecutor")
    public Executor gameExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        // Number of permanent threads that always exist
        executor.setCorePoolSize(5);

        // Maximum threads that can be created
        executor.setMaxPoolSize(20);

        // How many tasks can wait in queue when all threads busy
        // If queue full + max threads reached → rejection policy
        executor.setQueueCapacity(100);

        // Name in debug logs
        executor.setThreadNamePrefix("GameExecutor-");

        // If the task queue is full, the thread that submitted the task will run it instead
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        // Wait for tasks to complete before shutting down the executor
        executor.setWaitForTasksToCompleteOnShutdown(true);

        // Maximum seconds to wait for tasks during shutdown
        executor.setAwaitTerminationSeconds(120);

        executor.initialize();

        return executor;
    }

    @Bean(name = "taskScheduler")
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        // Number of threads for scheduled tasks (e.g., game timers)
        scheduler.setPoolSize(5);
        scheduler.setThreadNamePrefix("TaskScheduler-");
        scheduler.initialize();
        return scheduler;
    }
}