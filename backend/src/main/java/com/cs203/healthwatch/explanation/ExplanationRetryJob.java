package com.cs203.healthwatch.explanation;

import com.cs203.healthwatch.events.EventStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * CG-152: tries again to explain events whose first AI call FAILED (rate limit, timeout, bad reply), while the event
 * is still NEW. Each run takes at most {@code batch-size} rows, oldest event first, and never throws. It does nothing
 * when explanations are disabled or no API key is set. After a 429 it stops the run and waits (Retry-After, else
 * one interval) by skipping later runs, so no thread ever sleeps.
 *
 * ponytail: assumes one app instance; two instances could retry the same row at once. Add a row lock or
 * "for update skip locked" if the backend is ever scaled out.
 */
@Slf4j
@Component
public class ExplanationRetryJob {

    private final EventExplanationRepository explanations;
    private final EventExplanationService service;
    private final ExplanationProperties props;
    private final Clock clock;

    /** No retries before this moment (set after a 429). */
    private volatile Instant pausedUntil = Instant.MIN;

    @Autowired
    public ExplanationRetryJob(EventExplanationRepository explanations, EventExplanationService service,
                               ExplanationProperties props) {
        this(explanations, service, props, Clock.systemUTC());
    }

    ExplanationRetryJob(EventExplanationRepository explanations, EventExplanationService service,
                        ExplanationProperties props, Clock clock) {
        this.explanations = explanations;
        this.service = service;
        this.props = props;
        this.clock = clock;
    }

    // initialDelay = interval: give the app (and the live path) time to settle after startup
    @Scheduled(fixedDelayString = "${app.explanation.retry.interval}",
            initialDelayString = "${app.explanation.retry.interval}")
    public void run() {
        try {
            int tried = retryBatch();
            if (tried > 0) {
                log.info("Explanation retry run finished: {} tried", tried);
            }
        } catch (Exception e) {
            log.error("Explanation retry run failed: {}", e.getMessage(), e);
        }
    }

    /** @return how many explanations were tried (AI called or context rebuild attempted) */
    int retryBatch() {
        if (!props.enabled()) {
            return 0;
        }
        if (!props.hasApiKey()) {
            log.debug("Explanation retry skipped: GROQ_API_KEY is not set");
            return 0;
        }
        if (clock.instant().isBefore(pausedUntil)) {
            log.debug("Explanation retry paused until {} (rate limited)", pausedUntil);
            return 0;
        }

        ExplanationProperties.Retry retry = props.retry();
        List<EventExplanation> batch = explanations.findRetryable(ExplanationStatus.FAILED, EventStatus.NEW,
                retry.maxAttempts(), PageRequest.of(0, retry.batchSize()));

        int tried = 0;
        for (EventExplanation failed : batch) {
            Optional<EventExplanationService.Attempt> attempt = service.retry(failed);
            if (attempt.isEmpty()) {
                continue; // skipped: event gone or no longer NEW
            }
            tried++;
            if (attempt.get().rateLimited()) {
                Duration wait = attempt.get().retryAfter() != null ? attempt.get().retryAfter() : retry.interval();
                pausedUntil = clock.instant().plus(wait);
                log.warn("Groq rate limit hit; pausing explanation retries for {}", wait);
                break;
            }
        }
        return tried;
    }
}
