package com.example.softwaremetrics.domain;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Locates the project's main package — the package of the class annotated with
 * {@code @SpringBootApplication}. Module packages are derived from class names by {@link ModuleResolver}.
 */
public class PackageLocator {

    private static final Logger logger = LoggerFactory.getLogger(PackageLocator.class);

    private final JavaClassAnalyzer javaClassAnalyzer;
    private final ProjectPathTraverser projectPathTraverser;

    public PackageLocator(JavaClassAnalyzer javaClassAnalyzer, ProjectPathTraverser projectPathTraverser) {
        this.javaClassAnalyzer = javaClassAnalyzer;
        this.projectPathTraverser = projectPathTraverser;
    }

    public String findMainPackage(Path projectPath) {
        logger.debug("Searching for main package in project path: {}", projectPath);
        Path srcMainJavaPath = projectPath.resolve("src/main/java");
        if (!Files.exists(srcMainJavaPath)) {
            logger.warn("src/main/java directory not found in project path: {}", projectPath);
            return null;
        }
        List<Path> javaFiles = projectPathTraverser.findJavaFiles(srcMainJavaPath);
        return javaFiles.stream()
                .filter(javaClassAnalyzer::containsSpringBootApplication)
                .map(javaClassAnalyzer::extractPackage)
                .findFirst()
                .orElse(null);
    }
}
