package com.cs203.healthwatch.ingestion.pm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

import com.cs203.healthwatch.ingestion.FetchClient;
import com.cs203.healthwatch.ingestion.pm.PmSourceProperties.Region;
import com.cs203.healthwatch.ingestion.readings.Reading;
import com.cs203.healthwatch.ingestion.readings.ReadingRepository;
import com.cs203.healthwatch.ingestion.sources.DataSourceRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Ingestion flow for the data.gov.sg PM2.5 feed (CG-9, CG-136): one fetch per cycle, one data source for the feed,
 * one set of readings per configured region. The repository and registry are mocked; the parser is real unless a
 * test needs it to fail.
 */
@ExtendWith(MockitoExtension.class)
class PmIngestionServiceImplTest {

    private static final String URL = "https://example.test/pm25";
    private static final String SOURCE_NAME = "datagovsg_pm25";
    private static final String SOURCE_TYPE = "air_quality";
    private static final UUID SOURCE_ID = UUID.fromString("5f1e2d3c-4b5a-4968-8776-655443322110");

    /** Two hourly items, each with a value for every region the API reports. */
    private static final String ALL_REGIONS_RESPONSE = """
            {"code":0,"data":{"items":[
              {"timestamp":"2026-09-27T09:00:00+08:00",
               "readings":{"pm25_one_hourly":{"central":10,"north":20,"south":30,"east":40,"west":50}}},
              {"timestamp":"2026-09-27T10:00:00+08:00",
               "readings":{"pm25_one_hourly":{"central":11,"north":21,"south":31,"east":41,"west":51}}}
            ]}}
            """;

    @Mock private FetchClient fetchClient;
    @Mock private ReadingRepository repo;
    @Mock private DataSourceRegistry registry;

    private PmIngestionServiceImpl service(PmParser parser, String... regions) {
        List<Region> regionList = Arrays.stream(regions).map(Region::new).toList();
        PmSourceProperties props = new PmSourceProperties(SOURCE_NAME, URL, SOURCE_TYPE, regionList);
        return new PmIngestionServiceImpl(fetchClient, parser, repo, new ObjectMapper(), props, registry);
    }

    private PmIngestionServiceImpl service(String... regions) {
        return service(new PmParser(), regions);
    }

    private void feedReturns(String body) {
        when(fetchClient.fetch(URL)).thenReturn(body);
    }

    private void sourceResolves() {
        when(registry.resolve(SOURCE_NAME, SOURCE_TYPE)).thenReturn(SOURCE_ID);
    }

    /** Every reading passed to repo.save, in order, including ones whose save threw. */
    private List<Reading> savedReadings(int expectedCount) {
        ArgumentCaptor<Reading> captor = ArgumentCaptor.forClass(Reading.class);
        verify(repo, times(expectedCount)).save(captor.capture());
        return captor.getAllValues();
    }

    // ------------------------------------------------------------------ pull failures

    @Test
    void fetchReturnsNothing_savesNothing_andNeverTouchesTheSourceTable() {
        feedReturns(null);

        service("central").ingestLatest();

        verifyNoInteractions(repo, registry);
    }

    @Test
    void bodyIsNotJson_savesNothing_andDoesNotThrow() {
        feedReturns("<html>502 Bad Gateway</html>");

        service("central").ingestLatest();

        verifyNoInteractions(repo, registry);
    }

    // ------------------------------------------------------------------ multiple regions (CG-136)

    @Test
    void everyConfiguredRegionIsIngested_fromOneFetch() {
        feedReturns(ALL_REGIONS_RESPONSE);
        sourceResolves();

        service("central", "west").ingestLatest();

        List<Reading> saved = savedReadings(4); // 2 items x 2 regions
        assertThat(saved).extracting(Reading::getRegion)
                .containsExactlyInAnyOrder("central", "central", "west", "west");
        assertThat(saved).filteredOn(r -> r.getRegion().equals("west"))
                .extracting(Reading::getValue).containsExactlyInAnyOrder(50.0, 51.0);
        assertThat(saved).allSatisfy(r -> assertThat(r.isMalformed()).isFalse());
        verify(fetchClient, times(1)).fetch(URL);
    }

    @Test
    void allRegionsShareOneSourceId_resolvedOncePerCycle() {
        feedReturns(ALL_REGIONS_RESPONSE);
        sourceResolves();

        service("central", "north", "south", "east", "west").ingestLatest();

        assertThat(savedReadings(10)).extracting(Reading::getSourceId).containsOnly(SOURCE_ID);
        verify(registry, times(1)).resolve(SOURCE_NAME, SOURCE_TYPE);
    }

    @Test
    void savedReadingsCarryIngestedAt() {
        feedReturns(ALL_REGIONS_RESPONSE);
        sourceResolves();

        service("central").ingestLatest();

        assertThat(savedReadings(2)).allSatisfy(r -> assertThat(r.getIngestedAt()).isNotNull());
    }

    @Test
    void regionsInTheResponseButNotConfigured_areIgnored() {
        feedReturns(ALL_REGIONS_RESPONSE);
        sourceResolves();

        service("central").ingestLatest();

        assertThat(savedReadings(2)).extracting(Reading::getRegion).containsOnly("central");
    }

    @Test
    void configuredRegionMissingFromResponse_isStoredAsMalformed_andOtherRegionsAreUnaffected() {
        feedReturns("""
                {"code":0,"data":{"items":[
                  {"timestamp":"2026-09-27T09:00:00+08:00","readings":{"pm25_one_hourly":{"central":10}}}
                ]}}
                """);
        sourceResolves();

        service("central", "west").ingestLatest();

        List<Reading> saved = savedReadings(2);
        Reading central = saved.stream().filter(r -> r.getRegion().equals("central")).findFirst().orElseThrow();
        Reading west = saved.stream().filter(r -> r.getRegion().equals("west")).findFirst().orElseThrow();
        assertThat(central.isMalformed()).isFalse();
        assertThat(central.getValue()).isEqualTo(10.0);
        assertThat(west.isMalformed()).isTrue();
        assertThat(west.getRawPayload()).isNotBlank();
    }

    @Test
    void oneRegionFailing_doesNotStopTheOtherRegions() {
        PmParser parser = spy(new PmParser());
        lenient().doThrow(new IllegalStateException("unexpected payload"))
                 .when(parser).parseAll(any(), eq("north"), any());
        feedReturns(ALL_REGIONS_RESPONSE);
        sourceResolves();

        service(parser, "central", "north", "west").ingestLatest();

        assertThat(savedReadings(4)).extracting(Reading::getRegion)
                .containsExactlyInAnyOrder("central", "central", "west", "west");
    }

    // ------------------------------------------------------------------ unusable responses

    @Test
    void nonZeroCode_storesExactlyOneMalformedRow_notOnePerRegion() {
        feedReturns("""
                {"code":1,"errorMsg":"upstream unavailable","data":{"items":[]}}
                """);
        sourceResolves();

        service("central", "north", "south", "east", "west").ingestLatest();

        Reading row = savedReadings(1).get(0);
        assertThat(row.isMalformed()).isTrue();
        assertThat(row.getRegion()).isEqualTo(PmParser.ALL_REGIONS);
        assertThat(row.getSourceId()).isEqualTo(SOURCE_ID);
        assertThat(row.getRawPayload()).contains("upstream unavailable");
        assertThat(row.getIngestedAt()).isNotNull();
    }

    @Test
    void changedResponseShape_storesExactlyOneMalformedRow() {
        feedReturns("""
                {"code":0,"data":{"items":"oops"}}
                """);
        sourceResolves();

        service("central", "west").ingestLatest();

        Reading row = savedReadings(1).get(0);
        assertThat(row.isMalformed()).isTrue();
        assertThat(row.getRegion()).isEqualTo(PmParser.ALL_REGIONS);
    }

    @Test
    void emptyItems_savesNothing() {
        feedReturns("""
                {"code":0,"data":{"items":[]}}
                """);
        sourceResolves();

        service("central", "west").ingestLatest();

        verifyNoInteractions(repo);
    }

    // ------------------------------------------------------------------ save failures

    @Test
    void duplicateReading_isSkipped_andTheRestAreStillSaved() {
        feedReturns(ALL_REGIONS_RESPONSE);
        sourceResolves();
        when(repo.save(any(Reading.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"))
                .thenAnswer(inv -> inv.getArgument(0));

        service("central", "west").ingestLatest();

        savedReadings(4);
    }

    @Test
    void unexpectedSaveFailure_doesNotStopTheBatch() {
        feedReturns(ALL_REGIONS_RESPONSE);
        sourceResolves();
        when(repo.save(any(Reading.class)))
                .thenAnswer(inv -> inv.getArgument(0))
                .thenThrow(new IllegalStateException("db hiccup"))
                .thenAnswer(inv -> inv.getArgument(0));

        service("central", "west").ingestLatest();

        savedReadings(4);
    }
}
