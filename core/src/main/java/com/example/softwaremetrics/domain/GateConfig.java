package com.example.softwaremetrics.domain;

/**
 * Resolved quality-gate settings, decoupled from Spring configuration so the
 * {@link ThresholdEvaluator} stays pure and unit-testable. Each gate can be independently
 * enabled, and carries its threshold where applicable.
 */
public record GateConfig(
        boolean maxPackageDistanceEnabled, double maxPackageDistance,
        boolean forbiddenZonesEnabled,
        boolean maxAverageDistanceEnabled, double maxAverageDistance,
        boolean noCyclesEnabled) {

    /** All gates disabled — nothing fails the build. */
    public static GateConfig disabled() {
        return new GateConfig(false, 0.0, false, false, 0.0, false);
    }
}
