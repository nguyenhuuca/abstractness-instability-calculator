package com.example.softwaremetrics.domain;

import java.util.Set;

/**
 * Lightweight per-class model produced by {@link JavaClassAnalyzer#analyzeProject} for the dead-code
 * and banned-API checks.
 *
 * @param fqcn                fully-qualified class name
 * @param packageName         its package
 * @param entryPoint          true if it has a {@code main} method or a framework stereotype/entity annotation
 * @param firstPartyClassRefs project classes it references
 * @param typeRefs            ALL referenced types (incl. JDK/3rd-party), unfiltered
 * @param methodRefs          referenced methods as {@code owner.method} (incl. JDK/3rd-party)
 */
public record ClassInfo(
        String fqcn,
        String packageName,
        boolean entryPoint,
        Set<String> firstPartyClassRefs,
        Set<String> typeRefs,
        Set<String> methodRefs) {
}
