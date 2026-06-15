package com.example.softwaremetrics.domain;

import java.util.Arrays;
import java.util.Set;

/**
 * Maps a fully-qualified class name to the "module package" it belongs to, at a configurable
 * granularity. By default a module is a direct sub-package of the main package (Spring-Modulith depth 1);
 * {@code depth} analyzes more levels globally, and {@code expandedFqns} splits only the named packages
 * one extra level (e.g. expand {@code …dto} into {@code …dto.admin}, {@code …dto.auth}).
 *
 * <p>A class is assigned to its package truncated to the target depth, never deeper than its own
 * package — so no class is dropped. Classes directly in the main package map to {@code null}.
 */
public class ModuleResolver {

    private final String mainPackage;
    private final int mainDepth;
    private final int depth;
    private final Set<String> expandedFqns;

    public ModuleResolver(String mainPackage, int depth, Set<String> expandedFqns) {
        this.mainPackage = mainPackage;
        this.mainDepth = segmentCount(mainPackage);
        this.depth = Math.max(1, depth);
        this.expandedFqns = (expandedFqns == null) ? Set.of() : expandedFqns;
    }

    /** Default resolver: depth 1, no expansion (original Spring-Modulith behaviour). */
    public static ModuleResolver topLevel(String mainPackage) {
        return new ModuleResolver(mainPackage, 1, Set.of());
    }

    /** The module package for the given class, or null if it is not under the main package. */
    public String moduleOf(String fqcn) {
        if (fqcn == null) {
            return null;
        }
        String pkg = packageOf(fqcn);
        if (!pkg.startsWith(mainPackage + ".")) {
            return null; // external, or directly in the main package
        }
        int rel = segmentCount(pkg) - mainDepth;          // >= 1
        int target = isUnderExpanded(pkg) ? Math.max(depth, 2) : depth;
        int moduleDepth = mainDepth + Math.min(target, rel);
        return firstSegments(pkg, moduleDepth);
    }

    private boolean isUnderExpanded(String pkg) {
        for (String expanded : expandedFqns) {
            if (pkg.equals(expanded) || pkg.startsWith(expanded + ".")) {
                return true;
            }
        }
        return false;
    }

    private static String packageOf(String fqcn) {
        // strip any array suffix and the class simple name
        String cleaned = fqcn;
        while (cleaned.endsWith("[]")) {
            cleaned = cleaned.substring(0, cleaned.length() - 2);
        }
        int lastDot = cleaned.lastIndexOf('.');
        return (lastDot == -1) ? "" : cleaned.substring(0, lastDot);
    }

    private static int segmentCount(String pkg) {
        if (pkg.isEmpty()) {
            return 0;
        }
        return (int) pkg.chars().filter(c -> c == '.').count() + 1;
    }

    private static String firstSegments(String pkg, int n) {
        String[] parts = pkg.split("\\.");
        if (n >= parts.length) {
            return pkg;
        }
        return String.join(".", Arrays.copyOfRange(parts, 0, n));
    }
}
