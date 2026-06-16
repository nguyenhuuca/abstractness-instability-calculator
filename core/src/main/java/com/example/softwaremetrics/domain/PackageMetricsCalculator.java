package com.example.softwaremetrics.domain;

import com.example.softwaremetrics.domain.model.ProjectModel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Component for calculating various metrics for a given set of Java packages within a project.
 * The metrics include instability, abstractness, and distance from the main sequence.
 */
public class PackageMetricsCalculator {

    private static final Logger logger = LoggerFactory.getLogger(PackageMetricsCalculator.class);
    private final JavaClassAnalyzer javaClassAnalyzer;

    public PackageMetricsCalculator(JavaClassAnalyzer javaClassAnalyzer) {
        this.javaClassAnalyzer = javaClassAnalyzer;
    }

    /**
     * Calculates the metrics by scanning the project at {@code projectPath}. Convenience entry point
     * that builds the project model itself; prefer {@link #calculateMetrics(ProjectModel, ModuleResolver)}
     * when a model has already been built (so the project is scanned only once).
     */
    public Map<String, PackageMetrics> calculateMetrics(Path projectPath, ModuleResolver resolver) {
        Map<String, Set<String>> outgoingDependencies = new ConcurrentHashMap<>();
        Map<String, Set<String>> incomingDependencies = new ConcurrentHashMap<>();
        Map<String, Integer> abstractClassCount = new ConcurrentHashMap<>();
        Map<String, Integer> totalClassCount = new ConcurrentHashMap<>();
        Map<String, ComplexityStats> complexity = new ConcurrentHashMap<>();

        javaClassAnalyzer.analyzeClasses(projectPath, resolver, outgoingDependencies, incomingDependencies, abstractClassCount, totalClassCount, complexity);
        return compute(outgoingDependencies, incomingDependencies, abstractClassCount, totalClassCount, complexity);
    }

    /**
     * Calculates per-module metrics from an already-built {@link ProjectModel} — the single-pass path
     * used by the analysis pipeline.
     *
     * @param model    the project analyzed once
     * @param resolver assigns each class to its module package
     */
    public Map<String, PackageMetrics> calculateMetrics(ProjectModel model, ModuleResolver resolver) {
        Map<String, Set<String>> outgoingDependencies = new ConcurrentHashMap<>();
        Map<String, Set<String>> incomingDependencies = new ConcurrentHashMap<>();
        Map<String, Integer> abstractClassCount = new ConcurrentHashMap<>();
        Map<String, Integer> totalClassCount = new ConcurrentHashMap<>();
        Map<String, ComplexityStats> complexity = new ConcurrentHashMap<>();

        MetricsAggregator.aggregate(model, resolver, outgoingDependencies, incomingDependencies, abstractClassCount, totalClassCount, complexity);
        return compute(outgoingDependencies, incomingDependencies, abstractClassCount, totalClassCount, complexity);
    }

    private Map<String, PackageMetrics> compute(Map<String, Set<String>> outgoingDependencies,
                                                Map<String, Set<String>> incomingDependencies,
                                                Map<String, Integer> abstractClassCount,
                                                Map<String, Integer> totalClassCount,
                                                Map<String, ComplexityStats> complexity) {
        // Modules emerge from the classes seen (no fixed list); union the keys touched by the analysis.
        Set<String> modules = new TreeSet<>();
        modules.addAll(totalClassCount.keySet());
        modules.addAll(outgoingDependencies.keySet());
        modules.addAll(incomingDependencies.keySet());
        modules.addAll(complexity.keySet());

        logger.debug("Dependency analysis completed for {} packages. Calculating final metrics.", modules.size());
        return computeMetrics(modules, outgoingDependencies, incomingDependencies, abstractClassCount, totalClassCount, complexity);
    }

    private Map<String, PackageMetrics> computeMetrics(Collection<String> modulePackages,
                                                       Map<String, Set<String>> outgoingDependencies,
                                                       Map<String, Set<String>> incomingDependencies,
                                                       Map<String, Integer> abstractClassCount,
                                                       Map<String, Integer> totalClassCount,
                                                       Map<String, ComplexityStats> complexity) {
        Map<String, PackageMetrics> metrics = new ConcurrentHashMap<>();
        for (String pkg : modulePackages) {
            int ce = outgoingDependencies.getOrDefault(pkg, Set.of()).size();
            int ca = incomingDependencies.getOrDefault(pkg, Set.of()).size();
            double instability = (ce + ca == 0) ? 0.0 : (double) ce / (ce + ca);

            int abstractClasses = abstractClassCount.getOrDefault(pkg, 0);
            int totalClasses = totalClassCount.getOrDefault(pkg, 0);
            double abstractness = (totalClasses == 0) ? 0.0 : (double) abstractClasses / totalClasses;

            double distance = Math.abs(abstractness + instability - 1.0);

            PackageMetrics pkgMetrics = new PackageMetrics();
            pkgMetrics.setPackageName(pkg);
            pkgMetrics.setCe(ce);
            pkgMetrics.setEfferentDependencies(new ArrayList<>(outgoingDependencies.getOrDefault(pkg, Set.of())));
            pkgMetrics.setCa(ca);
            pkgMetrics.setAfferentDependencies(new ArrayList<>(incomingDependencies.getOrDefault(pkg, Set.of())));
            pkgMetrics.setAbstractClassCount(abstractClasses);
            pkgMetrics.setTotalClassCount(totalClasses);
            pkgMetrics.setAbstractness(abstractness);
            pkgMetrics.setInstability(instability);
            pkgMetrics.setDistance(distance);

            ComplexityStats stats = complexity.get(pkg);
            if (stats != null) {
                pkgMetrics.setMethodCount(stats.methodCount());
                pkgMetrics.setAvgComplexity(stats.averageComplexity());
                pkgMetrics.setMaxComplexity(stats.maxComplexity());
                pkgMetrics.setMostComplexMethod(stats.mostComplexMethod());
            }

            metrics.put(pkg, pkgMetrics);

            logger.debug("Metrics for package {}: I={}, A={}, D={}, CE={}, CA={}",
                    pkg, instability, abstractness, distance, ce, ca);
        }

        return metrics;
    }
}