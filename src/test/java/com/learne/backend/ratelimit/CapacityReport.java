package com.learne.backend.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.function.Supplier;

/** Never traverse measurement state unless its writers have been proven quiescent. */
final class CapacityReport {
    static void write(Path path, boolean workloadQuiescent, Supplier<Map<String, Object>> fullReport) throws IOException {
        // The failure branch has no references to measurement collections, workers or mutable gates.
        Map<String, Object> evidence = workloadQuiescent ? fullReport.get() : Map.of(
                "finishedAt", Instant.now().toString(),
                "failure", "Workload cleanup did not complete; full measurements withheld",
                "measurementsCompleted", false,
                "allMeasuredGatesPassed", false,
                "gates", Map.of("workloadQuiescent", false));
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(path.toFile(), evidence);
    }
}
