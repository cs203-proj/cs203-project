package com.cs203.healthwatch.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/** Turns on @Async, used to write AI lines on warnings (CG-154) in the background after a confirm commits. */
@Configuration
@EnableAsync
public class AsyncConfig {
}
