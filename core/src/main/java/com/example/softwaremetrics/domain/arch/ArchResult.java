package com.example.softwaremetrics.domain.arch;

import java.util.List;

/**
 * Outcome of checking a project against an {@link ArchSpec}. {@code compliant} is true when there
 * are no violations.
 */
public record ArchResult(String specName, boolean compliant, List<Violation> violations) {

    /**
     * A single architecture-rule breach.
     *
     * @param type    one of {@code forbiddenDependency}, {@code naming}, {@code cycle}
     * @param from    source component (or class, for naming)
     * @param to      target component (or null)
     * @param message human-readable description
     */
    public record Violation(String type, String from, String to, String message) {
    }
}
