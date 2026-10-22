package com.cs203.healthwatch.ingestion.pm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cs203.healthwatch.common.exception.BadRequestException;
import com.cs203.healthwatch.dto.BackfillResponse;
import com.cs203.healthwatch.ingestion.FetchClient;
import com.cs203.healthwatch.ingestion.config.BackfillProperties;
import com.cs203.healthwatch.ingestion.pm.PmSourceProperties.Region;
import com.cs203.healthwatch.ingestion.readings.Reading;
import com.cs203.healthwatch.ingestion.readings.ReadingRepository;
import com.cs203.healthwatch.ingestion.sources.DataSourceRegistry;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Backfill over data.gov.sg's date filter. The fetch is mocked per URL, the repository is mocked, and the real
 * ingestion service and parser run in between, so historical rows go through the same path as live ones.
 */
@ExtendWith(MockitoExtension.class)
class PmBackfillServiceTest {

    private static final String URL = "https://example.test/pm25";
    private static final UUID SOURCE_ID = UUID.fromString("5f1e2d3c-4b5a-4968-8776-655443322110");
    private static final int MAX_DAYS = 31;
    private static final int MAX_PAGES_PER_DAY = 20;
    /** No pause between days, so the tests run instantly. */
    private static final BackfillProperties PROPS = new BackfillProperties(Duration.ZERO, MAX_DAYS, MAX_PAGES_PER_DAY);
    /** "Today" for every test: 15 Oct 2026, 12:00 SGT. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-15T04:00:00Z"), ZoneId.of("Asia/Singapore"));

    @Mock private FetchClient fetchClient;
    @Mock private ReadingRepository repo;
    @Mock private DataSourceRegistry registry;

    private PmBackfillService backfill;

    @BeforeEach
    void setUp() {
        backfill = backfillWith(CLOCK);
    }

    private PmBackfillService backfillWith(Clock clock) {
        ObjectMapper mapper = new ObjectMapper();
        PmSourceProperties props = new PmSourceProperties("datagovsg_pm25", URL, "air_quality",
                List.of(new Region("central"), new Region("west")));
        PmIngestionService ingestion =
                new PmIngestionService(fetchClient, new PmParser(), repo, mapper, props, registry);
        return new PmBackfillService(fetchClient, mapper, props, ingestion, PROPS, clock);
    }

    /** One page for a day: two hourly items with central and west values, optionally pointing to a next page. */
    private static String page(String day, int firstHour, String nextToken) {
        String token = nextToken == null ? "" : ",\"paginationToken\":\"" + nextToken + "\"";
        return """
                {"code":0,"data":{"items":[
                  {"timestamp":"%sT%02d:00:00+08:00","readings":{"pm25_one_hourly":{"central":10,"west":20}}},
                  {"timestamp":"%sT%02d:00:00+08:00","readings":{"pm25_one_hourly":{"central":11,"west":21}}}
                ]%s}}
                """.formatted(day, firstHour, day, firstHour + 1, token);
    }

    private void dayReturns(String day, String body) {
        when(fetchClient.fetch(URL + "?date=" + day)).thenReturn(body);
    }

    private void sourceResolves() {
        when(registry.resolve("datagovsg_pm25", "air_quality")).thenReturn(SOURCE_ID);
    }

    // ------------------------------------------------------------------ happy path

    @Test
    void fetchesEachDayWithTheDateFilter_andStoresEveryRegion() {
        sourceResolves();
        dayReturns("2026-09-01", page("2026-09-01", 0, null));
        dayReturns("2026-09-02", page("2026-09-02", 0, null));

        BackfillResponse result = backfill.backfill(LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-02"));

        // 2 days x 2 items x 2 regions
        assertThat(result.days()).isEqualTo(2);
        assertThat(result.pages()).isEqualTo(2);
        assertThat(result.saved()).isEqualTo(8);
        assertThat(result.failedDays()).isEmpty();
        ArgumentCaptor<Reading> saved = ArgumentCaptor.forClass(Reading.class);
        verify(repo, times(8)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(Reading::getSourceId).containsOnly(SOURCE_ID);
        assertThat(saved.getAllValues()).extracting(Reading::getRegion).containsOnly("central", "west");
    }

    @Test
    void followsPaginationTokenUntilTheLastPage() {
        sourceResolves();
        dayReturns("2026-09-01", page("2026-09-01", 0, "abc123"));
        when(fetchClient.fetch(URL + "?date=2026-09-01&paginationToken=abc123"))
                .thenReturn(page("2026-09-01", 2, null));

        BackfillResponse result = backfill.backfill(LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-01"));

        assertThat(result.pages()).isEqualTo(2);
        assertThat(result.saved()).isEqualTo(8); // 2 pages x 2 items x 2 regions
        assertThat(result.failedDays()).isEmpty();
    }

    @Test
    void endlessPaginationIsCapped_andTheDayIsReportedAsIncomplete() {
        sourceResolves();
        when(fetchClient.fetch(anyString())).thenReturn(page("2026-09-01", 0, "again"));

        BackfillResponse result = backfill.backfill(LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-01"));

        assertThat(result.pages()).isEqualTo(MAX_PAGES_PER_DAY);
        assertThat(result.failedDays()).containsExactly(LocalDate.parse("2026-09-01"));
    }

    // ------------------------------------------------------------------ failures

    @Test
    void aFailedDayIsReported_andTheOtherDaysStillRun() {
        sourceResolves();
        dayReturns("2026-09-01", page("2026-09-01", 0, null));
        dayReturns("2026-09-02", null); // fetch gave up after retries
        dayReturns("2026-09-03", page("2026-09-03", 0, null));

        BackfillResponse result = backfill.backfill(LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-03"));

        assertThat(result.failedDays()).containsExactly(LocalDate.parse("2026-09-02"));
        assertThat(result.saved()).isEqualTo(8); // the two good days
    }

    @Test
    void nonJsonResponseMarksTheDayFailed() {
        dayReturns("2026-09-01", "<html>502 Bad Gateway</html>");

        BackfillResponse result = backfill.backfill(LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-01"));

        assertThat(result.failedDays()).containsExactly(LocalDate.parse("2026-09-01"));
        verifyNoInteractions(repo);
    }

    @Test
    void unusableResponseForADay_isStoredOnceAsMalformed() {
        sourceResolves();
        dayReturns("2026-09-01", """
                {"code":1,"errorMsg":"no data for date"}
                """);

        BackfillResponse result = backfill.backfill(LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-01"));

        assertThat(result.saved()).isEqualTo(1);
        assertThat(result.malformed()).isEqualTo(1);
        verify(repo, times(1)).save(any(Reading.class));
    }

    // ------------------------------------------------------------------ reruns

    @Test
    void rerunningARange_countsExistingRowsAsDuplicates() {
        sourceResolves();
        dayReturns("2026-09-01", page("2026-09-01", 0, null));
        when(repo.save(any(Reading.class))).thenThrow(new DataIntegrityViolationException("duplicate key"));

        BackfillResponse result = backfill.backfill(LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-01"));

        assertThat(result.saved()).isZero();
        assertThat(result.duplicates()).isEqualTo(4);
        assertThat(result.failedDays()).isEmpty();
    }

    // ------------------------------------------------------------------ validation

    @Test
    void fromAfterToIsRejected() {
        assertThatThrownBy(() -> backfill.backfill(LocalDate.parse("2026-09-05"), LocalDate.parse("2026-09-01")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("from must be on or before to");
        verifyNoInteractions(fetchClient);
    }

    @Test
    void futureDateIsRejected() {
        assertThatThrownBy(() -> backfill.backfill(LocalDate.parse("2026-10-15"), LocalDate.parse("2026-10-16")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("future");
        verifyNoInteractions(fetchClient);
    }

    @Test
    void todayIsAllowed() {
        sourceResolves();
        dayReturns("2026-10-15", page("2026-10-15", 0, null));

        assertThat(backfill.backfill(LocalDate.parse("2026-10-15"), LocalDate.parse("2026-10-15")).failedDays())
                .isEmpty();
    }

    @Test
    void todayIsTheSingaporeDate_evenWhenTheAppClockIsUtc() {
        // The shared Clock bean is UTC. At 04:00 SGT on 15 Oct it is still 14 Oct in UTC,
        // but 15 Oct is already "today" for data.gov.sg.
        Clock utcClock = Clock.fixed(Instant.parse("2026-10-14T20:00:00Z"), ZoneId.of("UTC"));
        PmBackfillService utcBackfill = backfillWith(utcClock);
        sourceResolves();
        dayReturns("2026-10-15", page("2026-10-15", 0, null));

        assertThat(utcBackfill.backfill(LocalDate.parse("2026-10-15"), LocalDate.parse("2026-10-15")).failedDays())
                .isEmpty();
    }

    @Test
    void rangeLongerThanTheMaximumIsRejected() {
        LocalDate from = LocalDate.parse("2026-08-01");
        LocalDate to = from.plusDays(MAX_DAYS); // MAX_DAYS + 1 days inclusive

        assertThatThrownBy(() -> backfill.backfill(from, to))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("At most " + MAX_DAYS + " days");
        verify(fetchClient, never()).fetch(anyString());
    }

    @Test
    void exactlyTheMaximumRangeIsAllowed() {
        when(fetchClient.fetch(anyString())).thenReturn("""
                {"code":0,"data":{"items":[]}}
                """);
        sourceResolves();
        LocalDate from = LocalDate.parse("2026-08-01");

        BackfillResponse result = backfill.backfill(from, from.plusDays(MAX_DAYS - 1));

        assertThat(result.days()).isEqualTo(MAX_DAYS);
        verify(fetchClient, times(MAX_DAYS)).fetch(anyString());
    }
}
