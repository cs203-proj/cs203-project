package com.cs203.healthwatch.ingestion;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.cs203.healthwatch.detection.DetectionOrchestrator;

class IngestionJobTest {

    // DetectionOrchestrator isn't under test here; a plain mock is enough so the
    // constructor is satisfied. Swap for verify(...) calls if IngestionJob starts
    // invoking it directly after each ingestion cycle.
    private final DetectionOrchestrator detectionOrchestrator = mock(DetectionOrchestrator.class);

    @Test
    void run_oneServiceFails_otherServicesStillRun() {
        IngestionService failing = mock(IngestionService.class);
        IngestionService healthy = mock(IngestionService.class);
        doThrow(new RuntimeException("boom")).when(failing).ingestLatest();

        IngestionJob job = new IngestionJob(List.of(failing, healthy), detectionOrchestrator);

        assertThatCode(job::run).doesNotThrowAnyException();
        verify(healthy).ingestLatest();
    }

    @Test
    void run_failedCycle_doesNotPreventNextCycle() {
        IngestionService service = mock(IngestionService.class);
        doThrow(new RuntimeException("boom")).doNothing().when(service).ingestLatest();

        IngestionJob job = new IngestionJob(List.of(service), detectionOrchestrator);

        job.run(); // cycle 1 fails, exception swallowed and logged
        job.run(); // cycle 2 runs normally

        verify(service, times(2)).ingestLatest();
    }
}
