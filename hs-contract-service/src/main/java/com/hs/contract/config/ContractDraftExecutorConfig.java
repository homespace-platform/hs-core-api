package com.hs.contract.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class ContractDraftExecutorConfig {
    @Bean("contractDraftExecutor")
    public ThreadPoolTaskExecutor contractDraftExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("contract-draft-");
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(64);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }
}
