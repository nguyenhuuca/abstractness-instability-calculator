package com.example.softwaremetrics.core.domain;

import com.example.softwaremetrics.core.domain.model.ProjectModel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Component for calculating various metrics for a given set of Java packages within a project.
 * The metrics include instability, abstractness, and distance from the main sequence.
 */
public class PackageMetricsCalculator {

    private static final Logger logger = LoggerFactory.getLogger(PackageMetricsCalculator.class);

    /**
     * Calculates per-module metrics from an already-built {@link ProjectModel} — the single-pass path
     * used by the analysis pipeline.
     *
     * @param model    the project analyzed once
     * @param resolver assigns each class to its module package
     */
    public Map<String, PackageMetrics> calculateMetrics(ProjectModel model, ModuleResolver resolver) {
        RawModuleMetrics raw = MetricsAggregator.aggregate(model, resolver);
        logger.debug("Dependency analysis completed for {} packages. Calculating final metrics.", raw.modules().size());

        Map<String, PackageMetrics> metrics = new ConcurrentHashMap<>();
        for (String pkg : raw.modules()) {
            int ce = raw.outgoingDependencies().getOrDefault(pkg, Set.of()).size();
            int ca = raw.incomingDependencies().getOrDefault(pkg, Set.of()).size();
            double instability = (ce + ca == 0) ? 0.0 : (double) ce / (ce + ca);

            int abstractClasses = raw.abstractClassCount().getOrDefault(pkg, 0);
            int totalClasses = raw.totalClassCount().getOrDefault(pkg, 0);
            double abstractness = (totalClasses == 0) ? 0.0 : (double) abstractClasses / totalClasses;

            double distance = Math.abs(abstractness + instability - 1.0);

            PackageMetrics pkgMetrics = PackageMetrics.of(pkg,
                    ce, raw.outgoingDependencies().getOrDefault(pkg, Set.of()),
                    ca, raw.incomingDependencies().getOrDefault(pkg, Set.of()),
                    abstractClasses, totalClasses, abstractness, instability, distance,
                    raw.complexity().get(pkg));
            metrics.put(pkg, pkgMetrics);

            logger.debug("Metrics for package {}: I={}, A={}, D={}, CE={}, CA={}",
                    pkg, instability, abstractness, distance, ce, ca);
        }
        return metrics;
    }
}