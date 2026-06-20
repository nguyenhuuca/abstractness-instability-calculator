package com.example.softwaremetrics.core.domain.resolve;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Small source-level scanner for the {@code @SpringBootApplication} annotation, used by
 * {@link SpringBootRootPackageResolver} to find the application's root package. It reads
 * {@code .java} source text (the annotation is reliably present there even before compilation),
 * ignoring occurrences inside comments or string literals.
 *
 * <p>The heavy bytecode extraction lives in {@code domain.bytecode.ProjectModelBuilder}; this class
 * deliberately does nothing else.
 */
public class SpringBootAnnotationScanner {

    private static final Logger logger = LoggerFactory.getLogger(SpringBootAnnotationScanner.class);

    private static final Pattern SPRING_BOOT_APP = Pattern.compile("@SpringBootApplication\\b");

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

    /** Extracts the {@code package} declaration from a source file, or {@code ""} when absent. */
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
}
