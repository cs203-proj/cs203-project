package com.cs203.healthwatch.ingestion.pm;

import com.cs203.healthwatch.common.exception.BadRequestException;
import com.cs203.healthwatch.dto.BackfillResponse;
import com.cs203.healthwatch.ingestion.FetchClient;
import com.cs203.healthwatch.ingestion.IngestResult;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.time.Duration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.beans.factory.annotation.Value;

import lombok.RequiredArgsConstructor;

/**
 * Loads past PM2.5 readings so baselines have history for every configured region (CG-136 sub-task). Each day is
 * requested with data.gov.sg's {@code ?date=YYYY-MM-DD} filter, following {@code paginationToken} until the day is
 * complete, and stored through the same path as live pulls. Rerunning a range is safe: rows already stored are
 * skipped by the (source_id, region, observed_at) unique constraint.
 */
@Service
public class PmBackfillService {
    private static final Logger log = LoggerFactory.getLogger(PmBackfillService.class);

    public static final int MAX_DAYS = 31;
    static final int MAX_PAGES_PER_DAY = 20;
    /** data.gov.sg dates its readings in Singapore time, so "today" is the SGT date. */
    private static final ZoneId SINGAPORE = ZoneId.of("Asia/Singapore");

    private final FetchClient fetchClient;
    private final ObjectMapper objectMapper;
    private final PmSourceProperties sourceProperties;
    private final PmIngestionService ingestion;
    private final Clock clock;
    /** Pause between days, so rapid requests don't hit data.gov.sg's rate limit. */
    private final Duration delayBetweenDays;

    public PmBackfillService(FetchClient fetchClient, ObjectMapper objectMapper,
                             PmSourceProperties sourceProperties, PmIngestionService ingestion, Clock clock,
                             @Value("${ingestion.backfill.delay-between-days:PT2S}") Duration delayBetweenDays) {
        this.fetchClient = fetchClient;
        this.objectMapper = objectMapper;
        this.sourceProperties = sourceProperties;
        this.ingestion = ingestion;
        this.clock = clock;
        this.delayBetweenDays = delayBetweenDays;
    }

    /** Backfills every day from {@code from} to {@code to}, both inclusive, as SGT dates. */
    public BackfillResponse backfill(LocalDate from, LocalDate to) {
        validate(from, to);

        IngestResult total = IngestResult.EMPTY;
        int pages = 0;
        List<LocalDate> failedDays = new ArrayList<>();

        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            DayResult result = backfillDay(day);
            total = total.plus(result.ingested());
            pages += result.pages();
            if (!result.complete()) {
                failedDays.add(day);
            }
            if (day.isBefore(to)) {
                pause(delayBetweenDays);   // no point waiting after the last day
            }
        }

        log.info("backfill {}..{} done: {} pages, {} saved, {} duplicates, {} malformed, {} failed days",
                from, to, pages, total.saved(), total.duplicates(), total.malformed(), failedDays.size());
        return new BackfillResponse(from, to, (int) ChronoUnit.DAYS.between(from, to) + 1, pages,
                total.saved(), total.malformed(), total.duplicates(), total.failed(), failedDays);
    }

    private DayResult backfillDay(LocalDate day) {
        IngestResult total = IngestResult.EMPTY;
        String token = null;
        int pages = 0;
        do {
            String json = fetchClient.fetch(urlFor(day, token));
            if (json == null) {
                log.error("backfill: fetch failed for {} (page {}), skipping the rest of that day", day, pages + 1);
                return new DayResult(total, pages, false);
            }
            JsonNode root;
            try {
                root = objectMapper.readTree(json);
            } catch (JsonProcessingException e) {
                log.error("backfill: response for {} is not JSON: {}", day, e.getMessage());
                return new DayResult(total, pages, false);
            }
            total = total.plus(ingestion.ingest(root));
            pages++;
            token = nextToken(root);
        } while (token != null && pages < MAX_PAGES_PER_DAY);

        if (token != null) {
            log.warn("backfill: stopped {} after {} pages; more pages were reported", day, pages);
            return new DayResult(total, pages, false);
        }
        return new DayResult(total, pages, true);
    }

    private String urlFor(LocalDate day, String token) {
        UriComponentsBuilder url = UriComponentsBuilder.fromUriString(sourceProperties.url())
                .queryParam("date", day.toString());
        if (token != null) {
            url.queryParam("paginationToken", token);
        }
        // Not encoded here: FetchClient's RestClient encodes the URL once when it sends it
        return url.build().toUriString();
    }

    private static String nextToken(JsonNode root) {
        JsonNode token = root.path("data").path("paginationToken");
        return token.isTextual() && !token.asText().isBlank() ? token.asText() : null;
    }

    private static void pause(Duration duration) {
        if (duration.isZero()) {
            return;
        }
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();   // keep the interrupt flag so the caller can see it
        }
    }

    private void validate(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw new BadRequestException("from and to are both required (YYYY-MM-DD)");
        }
        if (from.isAfter(to)) {
            throw new BadRequestException("from must be on or before to");
        }
        if (to.isAfter(LocalDate.now(clock.withZone(SINGAPORE)))) {
            throw new BadRequestException("to cannot be in the future");
        }
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        if (days > MAX_DAYS) {
            throw new BadRequestException("At most " + MAX_DAYS + " days per request (got " + days
                    + "); split the range into several calls");
        }
    }

    private record DayResult(IngestResult ingested, int pages, boolean complete) {}
}
