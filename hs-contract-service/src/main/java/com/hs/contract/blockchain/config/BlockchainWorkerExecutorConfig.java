package com.hs.contract.blockchain.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@ConditionalOnProperty(name = "homespace.blockchain.enabled", havingValue = "true")
public class BlockchainWorkerExecutorConfig {
    @Bean("blockchainProofExecutor")
    public ThreadPoolTaskExecutor blockchainProofExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("blockchain-proof-");
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(0);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }
}
