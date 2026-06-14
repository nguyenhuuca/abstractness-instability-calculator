package com.example.softwaremetrics.application;

import com.example.softwaremetrics.domain.PackageLocator;
import com.example.softwaremetrics.domain.PackageMetrics;
import com.example.softwaremetrics.domain.PackageMetricsCalculator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

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

    public Map<String, PackageMetrics> scanProject(String projectPath) {
        logger.info("Starting project scan for path: {}", projectPath);
        Path path = Paths.get(projectPath);

        String mainPackage = packageLocator.findMainPackage(path);
        if (mainPackage == null || mainPackage.isEmpty()) {
            logger.error("No @SpringBootApplication found in the project.");
            throw new IllegalArgumentException("No @SpringBootApplication found in the project.");
        }
        logger.debug("Main package found: {}", mainPackage);

        List<String> applicationModulePackages = packageLocator.findApplicationModulePackages(path, mainPackage);
        if (applicationModulePackages.isEmpty()) {
            logger.error("No subpackages found.");
            throw new IllegalArgumentException("No subpackages found.");
        }
        logger.debug("Top-level packages found: {}", applicationModulePackages);

        return packageMetricsCalculator.calculateMetrics(path, applicationModulePackages);
    }
}
