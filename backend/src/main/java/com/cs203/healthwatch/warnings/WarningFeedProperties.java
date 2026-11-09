package com.cs203.healthwatch.warnings;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings for the end-user warning feed (CG-22), under app.warnings.feed.
 *
 * @param activeWindow how long after approval a warning counts as active. Expiry (CG-31) is descoped, so this window
 *                     is the only thing that retires a warning from the feed. Default 24 hours.
 */
@ConfigurationProperties(prefix = "app.warnings.feed")
public record WarningFeedProperties(@DefaultValue("24h") Duration activeWindow) {
}
