package com.example.softwaremetrics.config;

import java.util.List;

/**
 * Module-granularity settings for a scan.
 *
 * @param depth  how many package levels below the main package count as a module (default 1 =
 *               Spring-Modulith direct sub-packages)
 * @param expand depth-1 package names (simple or fully-qualified) to split one extra level into their
 *               sub-packages (e.g. {@code dto} → {@code dto.admin}, {@code dto.auth})
 */
public record AnalyzeConfig(int depth, List<String> expand) {

    public static AnalyzeConfig defaults() {
        return new AnalyzeConfig(1, List.of());
    }
}
