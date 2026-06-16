package com.example.softwaremetrics.domain.resolve;

import com.example.softwaremetrics.domain.JavaClassAnalyzer;
import com.example.softwaremetrics.domain.ProjectPathTraverser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Resolves the root package as the package of the class annotated with {@code @SpringBootApplication}
 * — the original Spring-Modulith behaviour, now isolated behind {@link RootPackageResolver}. Scans
 * {@code src/main/java} sources (the annotation is reliably present there even before compilation).
 */
public class SpringBootRootPackageResolver implements RootPackageResolver {

    private static final Logger logger = LoggerFactory.getLogger(SpringBootRootPackageResolver.class);

    private final JavaClassAnalyzer javaClassAnalyzer;
    private final ProjectPathTraverser projectPathTraverser;

    public SpringBootRootPackageResolver(JavaClassAnalyzer javaClassAnalyzer, ProjectPathTraverser projectPathTraverser) {
        this.javaClassAnalyzer = javaClassAnalyzer;
        this.projectPathTraverser = projectPathTraverser;
    }

    @Override
    public String resolve(Path projectPath) {
        Path srcMainJavaPath = projectPath.resolve("src/main/java");
        if (!Files.exists(srcMainJavaPath)) {
            logger.debug("src/main/java not found under {}; no Spring Boot root package", projectPath);
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
