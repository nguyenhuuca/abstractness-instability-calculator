package com.example.softwaremetrics.core.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Evaluates the configurable quality gates against a scan result. Pure logic (no I/O, no Spring
 * config types) so it can be unit-tested directly.
 */
public class ThresholdEvaluator {

    // Zone corners matching the labelled zones in the UI.
    static final double ZONE_PAIN_MAX = 0.3;        // I <= 0.3 && A <= 0.3
    static final double ZONE_USELESSNESS_MIN = 0.7; // I >= 0.7 && A >= 0.7

    public GateResult evaluate(Map<String, PackageMetrics> metrics, List<List<String>> cycles, GateConfig cfg) {
        List<GateResult.Violation> violations = new ArrayList<>();

        if (cfg.maxPackageDistanceEnabled()) {
            checkMaxPackageDistance(metrics, cfg, violations);
        }
        if (cfg.forbiddenZonesEnabled()) {
            checkForbiddenZones(metrics, violations);
        }
        if (cfg.maxAverageDistanceEnabled()) {
            checkMaxAverageDistance(metrics, cfg, violations);
        }
        if (cfg.maxComplexityEnabled()) {
            checkMaxComplexity(metrics, cfg, violations);
        }
        if (cfg.noCyclesEnabled() && cycles != null) {
            checkNoCycles(cycles, violations);
        }

        return new GateResult(violations.isEmpty(), violations);
    }

    private void checkMaxPackageDistance(Map<String, PackageMetrics> metrics, GateConfig cfg,
                                         List<GateResult.Violation> violations) {
        for (PackageMetrics m : metrics.values()) {
            if (m.getDistance() > cfg.maxPackageDistance()) {
                violations.add(new GateResult.Violation(
                        "maxPackageDistance", m.getPackageName(), m.getDistance(), cfg.maxPackageDistance(),
                        String.format("Package '%s' distance %.2f exceeds max %.2f",
                                m.getPackageName(), m.getDistance(), cfg.maxPackageDistance())));
            }
        }
    }

    private void checkForbiddenZones(Map<String, PackageMetrics> metrics, List<GateResult.Violation> violations) {
        for (PackageMetrics m : metrics.values()) {
            String zone = zoneOf(m);
            if (zone != null) {
                violations.add(new GateResult.Violation(
                        "forbiddenZone", m.getPackageName(), m.getDistance(), 0.0,
                        String.format("Package '%s' is in the %s (I=%.2f, A=%.2f)",
                                m.getPackageName(), zone, m.getInstability(), m.getAbstractness())));
            }
        }
    }

    private void checkMaxAverageDistance(Map<String, PackageMetrics> metrics, GateConfig cfg,
                                         List<GateResult.Violation> violations) {
        double avg = averageDistance(metrics);
        if (avg > cfg.maxAverageDistance()) {
            violations.add(new GateResult.Violation(
                    "maxAverageDistance", null, avg, cfg.maxAverageDistance(),
                    String.format("Average distance %.2f exceeds max %.2f", avg, cfg.maxAverageDistance())));
        }
    }

    private void checkMaxComplexity(Map<String, PackageMetrics> metrics, GateConfig cfg,
                                    List<GateResult.Violation> violations) {
        for (PackageMetrics m : metrics.values()) {
            if (m.getMaxComplexity() > cfg.maxComplexity()) {
                violations.add(new GateResult.Violation(
                        "maxComplexity", m.getPackageName(), m.getMaxComplexity(), cfg.maxComplexity(),
                        String.format("Package '%s' has a method (%s) with cyclomatic complexity %d > %d",
                                m.getPackageName(), m.getMostComplexMethod(), m.getMaxComplexity(), cfg.maxComplexity())));
            }
        }
    }

    private void checkNoCycles(List<List<String>> cycles, List<GateResult.Violation> violations) {
        for (List<String> cycle : cycles) {
            violations.add(new GateResult.Violation(
                    "circularDependency", null, cycle.size(), 0.0,
                    "Circular dependency between packages: " + String.join(" -> ", cycle)));
        }
    }

    private String zoneOf(PackageMetrics m) {
        if (m.getInstability() <= ZONE_PAIN_MAX && m.getAbstractness() <= ZONE_PAIN_MAX) {
            return "Zone of Pain";
        }
        if (m.getInstability() >= ZONE_USELESSNESS_MIN && m.getAbstractness() >= ZONE_USELESSNESS_MIN) {
            return "Zone of Uselessness";
        }
        return null;
    }

    private double averageDistance(Map<String, PackageMetrics> metrics) {
        if (metrics.isEmpty()) {
            return 0.0;
        }
        return metrics.values().stream().mapToDouble(PackageMetrics::getDistance).average().orElse(0.0);
    }
}
