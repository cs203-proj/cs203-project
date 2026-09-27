package com.cs203.healthwatch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@ConfigurationPropertiesScan
@SpringBootApplication
public class HealthWatchApplication {

    public static void main(String[] args) {
        SpringApplication.run(HealthWatchApplication.class, args);
    }
}
