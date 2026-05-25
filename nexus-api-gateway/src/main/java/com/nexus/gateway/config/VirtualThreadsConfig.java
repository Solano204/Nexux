package com.nexus.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.Executor;

/**
 * Virtual Threads Configuration — Java 21 Project Loom.
 *
 * The API Gateway is primarily reactive (Netty + Project Reactor).
 * Virtual Threads complement the reactive model for:
 *
 *   ┌─────────────────────────────────────────────────────────────┐
 *   │ USE CASE                    │ MECHANISM                     │
 *   ├─────────────────────────────┼───────────────────────────────┤
 *   │ @Scheduled tasks            │ TaskScheduler with VT factory │
 *   │   - JWKS cache refresh      │                               │
 *   │   - JWT key ring cleanup    │                               │
 *   ├─────────────────────────────┼───────────────────────────────┤
 *   │ @Async methods              │ SimpleAsyncTaskExecutor       │
 *   │   - Async metric publishing │ with setVirtualThreads(true)  │
 *   │   - Async audit log writes  │                               │
 *   ├─────────────────────────────┼───────────────────────────────┤
 *   │ Blocking I/O bridging       │ Schedulers.fromExecutor(vte)  │
 *   │   - Legacy SDK calls        │ in Reactor chains             │
 *   │   - Synchronous JWT parsing │                               │
 *   └─────────────────────────────┴───────────────────────────────┘
 *
 * Virtual Thread characteristics vs Platform Threads:
 *   Platform thread: ~1MB stack, OS-scheduled, ~10K limit per JVM
 *   Virtual thread:  ~2KB stack, JVM-scheduled, millions possible
 *                    Automatically unmounted from carrier thread
 *                    during any blocking I/O operation
 *
 * Why NOT replace Netty's event loop with Virtual Threads:
 *   Netty's non-blocking NIO event loop is already optimal for
 *   network I/O. Virtual Threads add value for BLOCKING operations
 *   that cannot be made reactive — not for replacing a reactive runtime.
 *   The gateway's hot path (request routing) stays on Netty event loops.
 *
 * Spring Boot 3.2+ auto-configuration:
 *   Spring Boot 3.2+ automatically enables Virtual Threads for
 *   Tomcat/Jetty embedded servers when spring.threads.virtual.enabled=true.
 *   Since this gateway uses Netty (not Tomcat), that property does NOT apply.
 *   We configure Virtual Threads explicitly for async/scheduled tasks only.
 */
@Configuration
@EnableAsync
@EnableScheduling
public class VirtualThreadsConfig {

    private static final String GATEWAY_VT_PREFIX = "nexus-gateway-vt-";
    private static final String SCHEDULER_VT_PREFIX = "nexus-gateway-sched-";

    /**
     * Primary async task executor using Virtual Threads.
     *
     * Named "applicationTaskExecutor" so Spring Boot's
     * @Async infrastructure picks this up automatically
     * for all @Async method invocations.
     *
     * SimpleAsyncTaskExecutor with setVirtualThreads(true) is the
     * Spring-idiomatic way to create a Virtual Thread executor.
     * It creates a new Virtual Thread per task (no pool needed —
     * Virtual Threads are cheap enough to create on demand).
     *
     * Used for:
     *   @Async void publishMetric(...)
     *   @Async void sendAlertNotification(...)
     */
    @Bean(name = "applicationTaskExecutor")
    public AsyncTaskExecutor applicationTaskExecutor() {
        SimpleAsyncTaskExecutor executor =
                new SimpleAsyncTaskExecutor(GATEWAY_VT_PREFIX);
        // Enable Virtual Threads (Java 21+)
        // Falls back to platform threads on Java < 21 automatically
        executor.setVirtualThreads(true);
        return executor;
    }

    /**
     * Task scheduler for @Scheduled methods, using Virtual Threads.
     *
     * Used by:
     *   JwksCache.scheduledRefresh()        — every 60 min
     *   Any future scheduled cleanup tasks
     *
     * ThreadPoolTaskScheduler with a Virtual Thread factory:
     *   - Pool size 2: one for JWKS refresh, one spare
     *   - Thread.ofVirtual().name(...).factory(): Java 21 factory API
     *
     * Why ThreadPoolTaskScheduler (not SimpleAsync):
     *   @Scheduled requires a TaskScheduler bean specifically.
     *   SimpleAsyncTaskExecutor does not implement TaskScheduler.
     */
    @Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        // Small pool — scheduled gateway tasks are infrequent and quick
        scheduler.setPoolSize(2);
        // Create Virtual Threads for scheduled task execution
        scheduler.setThreadFactory(
                Thread.ofVirtual()
                        .name(SCHEDULER_VT_PREFIX, 0)
                        .factory());
        scheduler.setThreadNamePrefix(SCHEDULER_VT_PREFIX);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(10);
        scheduler.initialize();
        return scheduler;
    }

    /**
     * Raw Executor bean for use with Reactor's Schedulers.fromExecutor().
     *
     * Usage in reactive chains for bridging blocking code:
     * <pre>
     *   Mono.fromCallable(() -> blockingJwtParse(token))
     *       .subscribeOn(Schedulers.fromExecutor(virtualThreadExecutor));
     * </pre>
     *
     * This creates a Virtual Thread per Reactor subscription,
     * so blocking calls don't pin Netty event loop threads.
     *
     * Named "virtualThreadExecutor" to avoid ambiguity with
     * applicationTaskExecutor when autowiring by type.
     */
    @Bean(name = "virtualThreadExecutor")
    public Executor virtualThreadExecutor() {
        // Executors.newVirtualThreadPerTaskExecutor() — Java 21 API
        // Creates an unbounded executor that spawns a new Virtual Thread
        // per submitted task. The JVM manages carrier thread scheduling.
        return java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
    }
}