package com.example.softwaremetrics.domain;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Evaluates the configurable quality gates against a scan result. Pure logic (no I/O, no Spring
 * config types) so it can be unit-tested directly.
 */
@Component
public class ThresholdEvaluator {

    // Zone corners matching the labelled zones in the UI.
    static final double ZONE_PAIN_MAX = 0.3;        // I <= 0.3 && A <= 0.3
    static final double ZONE_USELESSNESS_MIN = 0.7; // I >= 0.7 && A >= 0.7

    public GateResult evaluate(Map<String, PackageMetrics> metrics, GateConfig cfg) {
        List<GateResult.Violation> violations = new ArrayList<>();

        if (cfg.maxPackageDistanceEnabled()) {
            for (PackageMetrics m : metrics.values()) {
                if (m.getDistance() > cfg.maxPackageDistance()) {
                    violations.add(new GateResult.Violation(
                            "maxPackageDistance", m.getPackageName(), m.getDistance(), cfg.maxPackageDistance(),
                            String.format("Package '%s' distance %.2f exceeds max %.2f",
                                    m.getPackageName(), m.getDistance(), cfg.maxPackageDistance())));
                }
            }
        }

        if (cfg.forbiddenZonesEnabled()) {
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

        if (cfg.maxAverageDistanceEnabled()) {
            double avg = averageDistance(metrics);
            if (avg > cfg.maxAverageDistance()) {
                violations.add(new GateResult.Violation(
                        "maxAverageDistance", null, avg, cfg.maxAverageDistance(),
                        String.format("Average distance %.2f exceeds max %.2f", avg, cfg.maxAverageDistance())));
            }
        }

        return new GateResult(violations.isEmpty(), violations);
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
