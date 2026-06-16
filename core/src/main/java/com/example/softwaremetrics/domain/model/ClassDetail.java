package com.example.softwaremetrics.domain.model;

import java.util.List;
import java.util.Set;

/**
 * Everything extracted from one compiled class in the single bytecode pass — enough to derive the
 * package metrics, the class dependency graph (architecture), and the per-class model (banned-API /
 * dead-code) without re-reading the file. Prefix/module decisions are applied later by the consumers,
 * so this stays root-package-independent.
 *
 * @param fqcn         fully-qualified class name
 * @param packageName  its package
 * @param abstractType true if abstract or an interface (counts toward abstractness)
 * @param builderType  true if the (internal) class name ends with {@code Builder} (excluded from metrics)
 * @param inner        true if a nested/inner class ({@code $} in the name)
 * @param entryPoint   true if it has a {@code main} method or a framework stereotype/entity annotation
 * @param methods      per concrete method complexity (abstract/native methods excluded)
 * @param dependencies exclusion-filtered type references (method sigs/bodies/locals + class signature)
 *                     — the basis for both efferent coupling and the architecture graph
 * @param typeRefs     ALL referenced types incl. JDK/3rd-party (sigs/bodies + super/interfaces/fields/
 *                     annotations), unfiltered — the basis for banned-API / dead-code checks
 * @param methodRefs   referenced methods as {@code owner.method}, unfiltered — for banned-API rules
 */
public record ClassDetail(
        String fqcn,
        String packageName,
        boolean abstractType,
        boolean builderType,
        boolean inner,
        boolean entryPoint,
        List<MethodComplexity> methods,
        Set<String> dependencies,
        Set<String> typeRefs,
        Set<String> methodRefs) {
}
