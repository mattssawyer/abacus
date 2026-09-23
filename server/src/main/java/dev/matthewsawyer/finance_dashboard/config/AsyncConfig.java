package dev.matthewsawyer.finance_dashboard.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class AsyncConfig {

    /** Keeps webhook responses fast by moving Plaid syncs off the request thread. */
    @Bean("plaidSyncExecutor")
    ThreadPoolTaskExecutor plaidSyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("plaid-sync-");
        return executor;
    }

    /**
     * Runs plan part sorting jobs. One thread, so jobs never overlap and a later job always
     * stores its answers after an earlier one's.
     */
    @Bean("planPartJobExecutor")
    ThreadPoolTaskExecutor planPartJobExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("plan-part-job-");
        return executor;
    }

    /** Asks TypeSafe about several transactions at once while a sorting job runs. */
    @Bean("planPartClassifyExecutor")
    ThreadPoolTaskExecutor planPartClassifyExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(8);
        executor.setThreadNamePrefix("plan-part-classify-");
        return executor;
    }
}
