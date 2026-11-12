package com.cs203.healthwatch.warnings;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings for the AI line on per-audience warnings (CG-154), under app.warnings.ai-line. The Groq key, model and
 * timeout are shared with AI explanations (app.explanation).
 *
 * @param enabled   false skips the AI line entirely; drafts are still created
 * @param maxLength longest line kept; a longer reply is dropped, not cut short
 */
@ConfigurationProperties(prefix = "app.warnings.ai-line")
public record AiLineProperties(@DefaultValue("true") boolean enabled, @DefaultValue("400") int maxLength) {
}
