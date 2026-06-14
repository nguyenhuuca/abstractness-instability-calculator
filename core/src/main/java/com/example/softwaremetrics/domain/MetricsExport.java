package com.example.softwaremetrics.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Self-describing envelope for exporting scan results as JSON. Wraps the raw per-package
 * metrics with metadata (when/where it was generated, tool version) and a quick summary so
 * an external system can consume and verify the results unambiguously. The optional {@code gate}
 * section is populated only by the CLI/CI mode and omitted from the web API response.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MetricsExport(
        String generatedAt,
        String projectPath,
        String toolVersion,
        int packageCount,
        Summary summary,
        Map<String, PackageMetrics> packages,
        GateResult gate,
        List<List<String>> cycles) {

    /** Aggregate counts derived from the per-package metrics. */
    public record Summary(int wellDesigned, int needsAttention, double averageDistance) {
    }

    /** Returns a copy of this envelope with the gate evaluation attached. */
    public MetricsExport withGate(GateResult gate) {
        return new MetricsExport(generatedAt, projectPath, toolVersion, packageCount, summary, packages, gate, cycles);
    }

    /** Returns a copy of this envelope with the detected circular-dependency groups attached. */
    public MetricsExport withCycles(List<List<String>> cycles) {
        return new MetricsExport(generatedAt, projectPath, toolVersion, packageCount, summary, packages, gate, cycles);
    }

    /**
     * Builds an export envelope from a scan result. A package is considered "well designed"
     * when its distance from the main sequence is {@code <= 0.5} (matching the UI's color
     * threshold); the rest "need attention".
     */
    public static MetricsExport from(String projectPath, String toolVersion,
                                     Map<String, PackageMetrics> metrics) {
        int wellDesigned = 0;
        double totalDistance = 0.0;
        for (PackageMetrics m : metrics.values()) {
            totalDistance += m.getDistance();
            if (m.getDistance() <= 0.5) {
                wellDesigned++;
            }
        }
        int packageCount = metrics.size();
        int needsAttention = packageCount - wellDesigned;
        double averageDistance = (packageCount == 0)
                ? 0.0
                : Math.round((totalDistance / packageCount) * 100.0) / 100.0;

        return new MetricsExport(
                Instant.now().toString(),
                projectPath,
                toolVersion,
                packageCount,
                new Summary(wellDesigned, needsAttention, averageDistance),
                metrics,
                null,
                null);
    }
}
