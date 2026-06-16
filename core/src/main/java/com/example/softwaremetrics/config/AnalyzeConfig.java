package com.example.softwaremetrics.config;

import java.util.List;

/**
 * Module-granularity settings for a scan.
 *
 * @param depth       how many package levels below the main package count as a module (default 1 =
 *                    Spring-Modulith direct sub-packages)
 * @param expand      depth-1 package names (simple or fully-qualified) to split one extra level into
 *                    their sub-packages (e.g. {@code dto} → {@code dto.admin}, {@code dto.auth})
 * @param rootPackage explicit root package to analyze; when blank the engine resolves it (Spring Boot
 *                    {@code @SpringBootApplication}, else the inferred common prefix), so non-Spring
 *                    projects can opt in by naming their root
 */
public record AnalyzeConfig(int depth, List<String> expand, String rootPackage) {

    public static AnalyzeConfig defaults() {
        return new AnalyzeConfig(1, List.of(), null);
    }
}
