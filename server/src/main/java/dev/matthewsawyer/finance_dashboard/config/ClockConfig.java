package dev.matthewsawyer.finance_dashboard.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class ClockConfig {

    /** Decides which day a balance snapshot belongs to, in the server's time zone. */
    @Bean
    Clock clock() {
        return Clock.systemDefaultZone();
    }
}
