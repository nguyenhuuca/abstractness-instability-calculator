package com.example.softwaremetrics.core.domain;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The raw per-module counts produced by {@link MetricsAggregator} from a single {@code ProjectModel}
 * pass and consumed by {@link PackageMetricsCalculator}: the efferent/afferent class sets, the
 * abstract/total class counts, and the complexity stats — all keyed by module package. Grouping these
 * five maps into one value avoids passing them around as a parameter clump.
 */
record RawModuleMetrics(
        Map<String, Set<String>> outgoingDependencies,
        Map<String, Set<String>> incomingDependencies,
        Map<String, Integer> abstractClassCount,
        Map<String, Integer> totalClassCount,
        Map<String, ComplexityStats> complexity) {

    /** A fresh, mutable accumulator for {@link MetricsAggregator} to fill. */
    static RawModuleMetrics empty() {
        return new RawModuleMetrics(new ConcurrentHashMap<>(), new ConcurrentHashMap<>(),
                new ConcurrentHashMap<>(), new ConcurrentHashMap<>(), new ConcurrentHashMap<>());
    }

    /** Every module the analysis touched, sorted (the union of all five maps' keys). */
    Set<String> modules() {
        Set<String> modules = new TreeSet<>();
        modules.addAll(totalClassCount.keySet());
        modules.addAll(outgoingDependencies.keySet());
        modules.addAll(incomingDependencies.keySet());
        modules.addAll(complexity.keySet());
        return modules;
    }
}
