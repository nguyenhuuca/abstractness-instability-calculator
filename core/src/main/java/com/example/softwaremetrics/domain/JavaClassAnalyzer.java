package com.example.softwaremetrics.domain;

import com.example.softwaremetrics.domain.bytecode.DependencyExclusions;
import com.example.softwaremetrics.domain.bytecode.ProjectModelBuilder;
import com.example.softwaremetrics.domain.model.ProjectModel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Compatibility facade over the single-pass {@link ProjectModelBuilder}: it keeps the small
 * source-level detection of {@code @SpringBootApplication} (used by the Spring-Boot root-package
 * resolver) and exposes the metrics / class-graph / class-model views by delegating to a
 * {@link ProjectModel}. The heavy bytecode extraction now lives in {@code domain.bytecode}.
 */
public class JavaClassAnalyzer {

    private static final Logger logger = LoggerFactory.getLogger(JavaClassAnalyzer.class);

    private static final Pattern SPRING_BOOT_APP = Pattern.compile("@SpringBootApplication\\b");

    private final ProjectModelBuilder modelBuilder;

    public JavaClassAnalyzer(InstabilityCalculatorProperties props) {
        this.modelBuilder = new ProjectModelBuilder(new DependencyExclusions(props));
    }

    /** Checks whether the given source file contains a real {@code @SpringBootApplication} annotation. */
    public boolean containsSpringBootApplication(Path file) {
        try (Stream<String> lines = Files.lines(file)) {
            return lines.anyMatch(this::isSpringBootApplicationAnnotation);
        } catch (IOException e) {
            logger.error("Error reading file: {}", file, e);
            return false;
        }
    }

    /**
     * Detects a real {@code @SpringBootApplication} annotation usage, ignoring occurrences inside
     * comments or string literals so the main package isn't picked from a file that merely mentions
     * the annotation in text.
     */
    private boolean isSpringBootApplicationAnnotation(String line) {
        String trimmed = line.trim();
        if (trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")) {
            return false; // comment line
        }
        String code = trimmed.replaceAll("\"(\\\\.|[^\"\\\\])*\"", ""); // drop string literals
        return SPRING_BOOT_APP.matcher(code).find();
    }

    public String extractPackage(Path file) {
        try (Stream<String> lines = Files.lines(file)) {
            return lines
                    .filter(line -> line.startsWith("package"))
                    .map(line -> line.split("\\s+")[1].replace(";", ""))
                    .findFirst()
                    .orElse("");
        } catch (IOException e) {
            logger.error("Error extracting package from file: {}", file, e);
            return "";
        }
    }

    /** Builds the project model once and aggregates it into the per-module raw metric maps. */
    void analyzeClasses(Path projectPath, ModuleResolver resolver,
                        Map<String, Set<String>> outgoingDependencies,
                        Map<String, Set<String>> incomingDependencies,
                        Map<String, Integer> abstractClassCount,
                        Map<String, Integer> totalClassCount,
                        Map<String, ComplexityStats> complexity) {
        ProjectModel model = modelBuilder.build(projectPath);
        MetricsAggregator.aggregate(model, resolver, outgoingDependencies, incomingDependencies,
                abstractClassCount, totalClassCount, complexity);
    }

    /**
     * Builds a first-party class dependency graph for architecture checking: maps each project class
     * (FQCN starting with {@code mainPackage}) to the set of project classes it depends on.
     */
    public Map<String, Set<String>> buildClassDependencyGraph(Path projectPath, String mainPackage) {
        return modelBuilder.build(projectPath).classDependencyGraph(mainPackage);
    }

    /**
     * Builds a per-class model (refs + entry-point flag) for the dead-code and banned-API checks.
     * Captures ALL references (incl. JDK/3rd-party) — exclusion lists are NOT applied here.
     */
    public List<ClassInfo> analyzeProject(Path projectPath, String mainPackage) {
        return modelBuilder.build(projectPath).classInfos(mainPackage);
    }
}
