package com.example.softwaremetrics.domain;

import java.util.List;

/**
 * Outcome of evaluating the quality gates against a scan result. {@code passed} is true when no
 * enabled gate was violated; {@code violations} lists every breach for reporting.
 */
public record GateResult(boolean passed, List<Violation> violations) {

    /**
     * A single gate breach.
     *
     * @param type        gate identifier (e.g. {@code maxPackageDistance})
     * @param packageName offending package, or {@code null}/empty for project-wide gates
     * @param value       the measured value that breached the gate
     * @param threshold   the configured threshold it exceeded
     * @param message     human-readable description
     */
    public record Violation(String type, String packageName, double value, double threshold, String message) {
    }
}
