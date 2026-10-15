package com.cs203.healthwatch.ingestion.pm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cs203.healthwatch.ingestion.FetchClient;
import com.cs203.healthwatch.ingestion.config.IngestionHttpProperties;
import com.cs203.healthwatch.ingestion.pm.PmSourceProperties.Region;
import com.cs203.healthwatch.ingestion.readings.Reading;
import com.cs203.healthwatch.ingestion.readings.ReadingRepository;
import com.cs203.healthwatch.ingestion.sources.DataSourceRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.ArgumentCaptor;
import org.springframework.web.client.RestClient;

/**
 * Smoke test against the real data.gov.sg PM2.5 API: real fetch, real parser, real ingestion flow, but the
 * repository and source registry are mocks, so nothing is written to any database.
 *
 * Skipped unless LIVE_TESTS=true, so CI and normal test runs never call the internet:
 *   LIVE_TESTS=true ./mvnw test -Dtest=PmLiveFetchTest
 */
@EnabledIfEnvironmentVariable(named = "LIVE_TESTS", matches = "true")
class PmLiveFetchTest {

    private static final String URL = "https://api-open.data.gov.sg/v2/real-time/api/pm25";
    private static final List<String> REGIONS = List.of("central", "north", "south", "east", "west");
    private static final UUID SOURCE_ID = UUID.randomUUID();

    @Test
    void liveFeedProducesValidReadingsForEveryRegion() {
        IngestionHttpProperties http = new IngestionHttpProperties(
                Duration.ofSeconds(5), Duration.ofSeconds(10), 3, Duration.ofSeconds(2));
        FetchClient fetchClient = new FetchClient(RestClient.create(), http);

        ReadingRepository repo = mock(ReadingRepository.class);
        DataSourceRegistry registry = mock(DataSourceRegistry.class);
        when(registry.resolve("datagovsg_pm25", "air_quality")).thenReturn(SOURCE_ID);

        PmSourceProperties props = new PmSourceProperties("datagovsg_pm25", URL, "air_quality",
                REGIONS.stream().map(Region::new).toList());
        PmIngestionServiceImpl service = new PmIngestionServiceImpl(
                fetchClient, new PmParser(), repo, new ObjectMapper(), props, registry);

        service.ingestLatest();

        ArgumentCaptor<Reading> captor = ArgumentCaptor.forClass(Reading.class);
        verify(repo, atLeastOnce()).save(captor.capture());
        List<Reading> readings = captor.getAllValues();

        // Print what came back, so a failure is easy to diagnose
        readings.forEach(r -> System.out.printf("%-8s %-30s %6s malformed=%s%n",
                r.getRegion(), r.getObservedAt(), r.getValue(), r.isMalformed()));

        assertThat(readings).as("the API answered with a usable response, not one malformed ALL row")
                .noneMatch(r -> PmParser.ALL_REGIONS.equals(r.getRegion()));
        assertThat(readings).extracting(Reading::getSourceId).containsOnly(SOURCE_ID);

        for (String region : REGIONS) {
            List<Reading> forRegion = readings.stream().filter(r -> region.equals(r.getRegion())).toList();
            assertThat(forRegion).as("readings for %s", region).isNotEmpty();
            assertThat(forRegion).as("at least one valid reading for %s", region)
                    .anySatisfy(r -> {
                        assertThat(r.isMalformed()).isFalse();
                        assertThat(r.getValue()).isBetween(0.0, 1000.0);
                        assertThat(r.getObservedAt()).isAfter(OffsetDateTime.now().minusDays(2));
                    });
        }
    }
}
