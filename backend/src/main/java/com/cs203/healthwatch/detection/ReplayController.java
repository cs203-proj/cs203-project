package com.cs203.healthwatch.detection;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Admin-only: enforced by the "/demo/**" rule in SecurityConfig
@RestController
@RequestMapping("/demo")
@RequiredArgsConstructor
@Tag(name = "Demo replay", description = "Push a historical haze episode through detection (admin only)")
public class ReplayController {

    private final DetectionOrchestrator detectionOrchestrator;

    @PostMapping("/replay")
    @Operation(
            summary = "Replay the Sept 2026 haze episode through detection",
            description = "Reads the configured window of baseline_dev.readings oldest first and runs each reading "
                    + "through the normal detector against the baseline_dev baseline. The first sustained breach "
                    + "becomes a NEW event tagged as a replay, so it is never mistaken for a live detection. "
                    + "Only one replay event can be open at a time: dismiss it to replay again.")
    public ResponseEntity<Map<String, Object>> replay() {
        ReplayResult result = detectionOrchestrator.replayDemoWindow();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("outcome", result.outcome());
        body.put("message", result.message());
        body.put("readingsScanned", result.readingsScanned());
        if (result.baseline() != null) {
            body.put("baselineMedian", result.baseline().median());
            body.put("baselineMad", result.baseline().scaledMad());
        }
        if (result.event() != null) {
            body.put("eventId", result.event().getId());
            body.put("detectedAt", result.event().getDetectedAt());
            body.put("deviation", result.event().getDeviation());
        }
        return ResponseEntity.ok(body);
    }
}
