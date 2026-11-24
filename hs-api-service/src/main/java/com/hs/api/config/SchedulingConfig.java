package com.hs.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Default pool for plain @Scheduled jobs (4 threads: Hikari default is 10 connections, shared with HTTP and the nearby pool). NearbyWorker's own "nearbyScheduler" (pool 2) is a TaskScheduler bean,
 * which makes Spring Boot's auto TaskScheduler back off; without this bean every unqualified job would share it.
 * The bean name "taskScheduler" is what ScheduledAnnotationBeanPostProcessor falls back to when several exist.
 */
@Configuration
class SchedulingConfig {
    @Bean(name = "taskScheduler")
    ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("scheduling-");
        return scheduler;
    }
}
