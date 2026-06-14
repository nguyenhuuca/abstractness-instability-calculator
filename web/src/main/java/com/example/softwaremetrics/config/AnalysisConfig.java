package com.example.softwaremetrics.config;

import com.example.softwaremetrics.application.SpringBootPackageScanner;
import com.example.softwaremetrics.config.Defaults;
import com.example.softwaremetrics.domain.CycleDetector;
import com.example.softwaremetrics.domain.InstabilityCalculatorProperties;
import com.example.softwaremetrics.domain.JavaClassAnalyzer;
import com.example.softwaremetrics.domain.PackageLocator;
import com.example.softwaremetrics.domain.PackageMetricsCalculator;
import com.example.softwaremetrics.domain.ProjectPathTraverser;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the Spring-free {@code core} POJOs as beans and binds their configuration from
 * {@code application.yaml}. Using {@code @Bean @ConfigurationProperties} lets YAML override the
 * code defaults ({@link Defaults}) without the core classes depending on Spring.
 */
@Configuration
public class AnalysisConfig {

    @Bean
    @ConfigurationProperties(prefix = "instability-calculator")
    public InstabilityCalculatorProperties instabilityCalculatorProperties() {
        return Defaults.exclusions();
    }

    @Bean
    public JavaClassAnalyzer javaClassAnalyzer(InstabilityCalculatorProperties props) {
        return new JavaClassAnalyzer(props);
    }

    @Bean
    public ProjectPathTraverser projectPathTraverser() {
        return new ProjectPathTraverser();
    }

    @Bean
    public PackageLocator packageLocator(JavaClassAnalyzer analyzer, ProjectPathTraverser traverser) {
        return new PackageLocator(analyzer, traverser);
    }

    @Bean
    public PackageMetricsCalculator packageMetricsCalculator(JavaClassAnalyzer analyzer) {
        return new PackageMetricsCalculator(analyzer);
    }

    @Bean
    public SpringBootPackageScanner springBootPackageScanner(PackageLocator locator, PackageMetricsCalculator calculator) {
        return new SpringBootPackageScanner(locator, calculator);
    }

    @Bean
    public CycleDetector cycleDetector() {
        return new CycleDetector();
    }
}
