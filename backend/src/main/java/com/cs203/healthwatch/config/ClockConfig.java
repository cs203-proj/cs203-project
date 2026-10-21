package com.cs203.healthwatch.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {

    /** Injectable clock so time-dependent logic (e.g. confidence staleness) can be tested. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
