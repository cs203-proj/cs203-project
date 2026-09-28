package com.cs203.healthwatch.ingestion.pm;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import com.cs203.healthwatch.ingestion.FetchClient;
import com.cs203.healthwatch.ingestion.readings.Reading;
import com.cs203.healthwatch.ingestion.readings.ReadingRepository;
import com.cs203.healthwatch.ingestion.sources.DataSourceRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class PmIngestionServiceImplTest {

    private static final String URL = "https://example.test/pm25";
    private static final String THREE_ITEMS = "{\"code\":0,\"data\":{\"items\":["
            + "{\"timestamp\":\"2026-09-27T09:00:00+08:00\",\"readings\":{\"pm25_one_hourly\":{\"west\":10}}},"
            + "{\"timestamp\":\"2026-09-27T10:00:00+08:00\",\"readings\":{\"pm25_one_hourly\":{\"west\":11}}},"
            + "{\"timestamp\":\"2026-09-27T11:00:00+08:00\",\"readings\":{\"pm25_one_hourly\":{\"west\":12}}}"
            + "]}}";

    @Mock private FetchClient fetchClient;
    @Mock private ReadingRepository repo;
    @Mock private DataSourceRegistry registry;

    private PmIngestionServiceImpl service;

    @BeforeEach
    void setUp() {
        PmSourceProperties props = new PmSourceProperties(URL, "data.gov.sg PM2.5", "AIR_QUALITY", "west");
        service = new PmIngestionServiceImpl(fetchClient, new PmParser(), repo, new ObjectMapper(), props, registry);
    }

    @Test
    void ingestLatest_fetchReturnsNothing_savesNothing() {
        when(fetchClient.fetch(URL)).thenReturn(null);

        service.ingestLatest();

        verify(repo, never()).save(any());
    }

    @Test
    void ingestLatest_bodyIsNotJson_savesNothingAndDoesNotThrow() {
        when(fetchClient.fetch(URL)).thenReturn("<html>502 Bad Gateway</html>");

        service.ingestLatest();

        verify(repo, never()).save(any());
    }

    @Test
    void ingestLatest_validResponse_savesEveryReadingWithIngestedAt() {
        when(fetchClient.fetch(URL)).thenReturn(THREE_ITEMS);
        when(registry.resolve(anyString(), anyString())).thenReturn(UUID.randomUUID());

        service.ingestLatest();

        verify(repo, times(3)).save(argThat((Reading r) -> r.getIngestedAt() != null && r.getSourceId() != null));
    }

    @Test
    void ingestLatest_duplicateReading_isSkippedAndRestOfBatchStillSaved() {
        when(fetchClient.fetch(URL)).thenReturn(THREE_ITEMS);
        when(registry.resolve(anyString(), anyString())).thenReturn(UUID.randomUUID());
        when(repo.save(any(Reading.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"))
                .thenAnswer(inv -> inv.getArgument(0));

        service.ingestLatest();

        verify(repo, times(3)).save(any(Reading.class));
    }

    @Test
    void ingestLatest_unexpectedSaveFailure_doesNotStopBatch() {
        when(fetchClient.fetch(URL)).thenReturn(THREE_ITEMS);
        when(registry.resolve(anyString(), anyString())).thenReturn(UUID.randomUUID());
        when(repo.save(any(Reading.class)))
                .thenAnswer(inv -> inv.getArgument(0))
                .thenThrow(new IllegalStateException("db hiccup"))
                .thenAnswer(inv -> inv.getArgument(0));

        service.ingestLatest();

        verify(repo, times(3)).save(any(Reading.class));
    }
}
