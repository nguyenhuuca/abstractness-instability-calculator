package com.example.softwaremetrics.application;

import com.example.softwaremetrics.config.AnalyzeConfig;
import com.example.softwaremetrics.domain.ModuleResolver;
import com.example.softwaremetrics.domain.PackageLocator;
import com.example.softwaremetrics.domain.PackageMetrics;
import com.example.softwaremetrics.domain.PackageMetricsCalculator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Scans project directories and estimates metrics for the packages within. Locates the main package
 * and computes the per-package metrics via collaborating classes. Plain POJO — wired by Spring in the
 * web module and constructed directly by the CLI.
 */
public class SpringBootPackageScanner {

    private static final Logger logger = LoggerFactory.getLogger(SpringBootPackageScanner.class);

    private final PackageLocator packageLocator;
    private final PackageMetricsCalculator packageMetricsCalculator;

    public SpringBootPackageScanner(PackageLocator packageLocator, PackageMetricsCalculator packageMetricsCalculator) {
        this.packageLocator = packageLocator;
        this.packageMetricsCalculator = packageMetricsCalculator;
    }

    /** Scans with the default module granularity (Spring-Modulith depth 1). */
    public Map<String, PackageMetrics> scanProject(String projectPath) {
        return scanProject(projectPath, AnalyzeConfig.defaults());
    }

    public Map<String, PackageMetrics> scanProject(String projectPath, AnalyzeConfig analyze) {
        logger.info("Starting project scan for path: {}", projectPath);
        Path path = Paths.get(projectPath);

        String mainPackage = packageLocator.findMainPackage(path);
        if (mainPackage == null || mainPackage.isEmpty()) {
            logger.error("No @SpringBootApplication found in the project.");
            throw new IllegalArgumentException("No @SpringBootApplication found in the project.");
        }
        logger.debug("Main package found: {}", mainPackage);

        ModuleResolver resolver = new ModuleResolver(mainPackage, analyze.depth(), expandedFqns(mainPackage, analyze));
        Map<String, PackageMetrics> metrics = packageMetricsCalculator.calculateMetrics(path, resolver);
        if (metrics.isEmpty()) {
            logger.error("No subpackages found.");
            throw new IllegalArgumentException("No subpackages found.");
        }
        logger.debug("Module packages found: {}", metrics.keySet());
        return metrics;
    }

    /** Resolves {@code expand} entries (simple name or FQN) to fully-qualified packages under main. */
    private Set<String> expandedFqns(String mainPackage, AnalyzeConfig analyze) {
        Set<String> result = new LinkedHashSet<>();
        if (analyze.expand() != null) {
            for (String entry : analyze.expand()) {
                if (entry == null || entry.isBlank()) {
                    continue;
                }
                String e = entry.trim();
                result.add(e.startsWith(mainPackage + ".") ? e : mainPackage + "." + e);
            }
        }
        return result;
    }
}
