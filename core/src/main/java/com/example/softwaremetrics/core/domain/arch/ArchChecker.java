package com.example.softwaremetrics.core.domain.arch;

import com.example.softwaremetrics.core.domain.CycleDetector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Checks a project's class dependency graph against an {@link ArchSpec}. Pure logic (no I/O) so it
 * can be unit-tested directly. Evaluates four rule kinds: allow-list access, explicit forbidden
 * edges, per-component naming conventions, and (optionally) no cycles between components.
 */
public class ArchChecker {

    /**
     * @param spec      the architecture rules
     * @param classDeps first-party class dependency graph (FQCN -&gt; FQCNs it depends on); every
     *                  first-party class should appear as a key, even with an empty dependency set
     */
    public ArchResult check(ArchSpec spec, Map<String, Set<String>> classDeps) {
        List<ArchResult.Violation> violations = new ArrayList<>();
        Set<String> seenDependencyEdges = new LinkedHashSet<>(); // dedupe component-edge violations
        Map<String, Set<String>> componentGraph = new HashMap<>();

        for (Map.Entry<String, Set<String>> entry : classDeps.entrySet()) {
            String srcClass = entry.getKey();
            String srcComp = spec.componentOf(srcClass);
            if (srcComp == null) {
                // Unmatched first-party class: flagged only when the spec opts out of ignoreUnmatched.
                if (!spec.ignoreUnmatched()) {
                    violations.add(new ArchResult.Violation("unmatched", srcClass, null,
                            String.format("Class '%s' does not match any architecture component", srcClass)));
                }
                continue;
            }

            checkNaming(spec, srcComp, srcClass, violations);

            for (String tgtClass : entry.getValue()) {
                String tgtComp = spec.componentOf(tgtClass);
                if (tgtComp == null || tgtComp.equals(srcComp)) {
                    continue;
                }
                componentGraph.computeIfAbsent(srcComp, k -> new LinkedHashSet<>()).add(tgtComp);
                checkDependency(spec, srcComp, tgtComp, seenDependencyEdges, violations);
            }
        }

        if (spec.forbidCycles()) {
            checkCycles(componentGraph, violations);
        }

        return new ArchResult(spec.name(), violations.isEmpty(), violations);
    }

    private void checkNaming(ArchSpec spec, String component, String fqcn, List<ArchResult.Violation> out) {
        Pattern naming = spec.namingPattern(component);
        if (naming == null) {
            return;
        }
        String simpleName = fqcn.substring(fqcn.lastIndexOf('.') + 1);
        if (!naming.matcher(simpleName).matches()) {
            out.add(new ArchResult.Violation("naming", component, null,
                    String.format("Class '%s' in component '%s' does not match required name pattern '%s'",
                            fqcn, component, naming.pattern())));
        }
    }

    private void checkDependency(ArchSpec spec, String from, String to,
                                 Set<String> seen, List<ArchResult.Violation> out) {
        Set<String> allowed = spec.allowedTargets(from);
        boolean accessViolation = allowed != null && !allowed.contains(to);
        boolean forbiddenViolation = spec.isForbidden(from, to);
        if ((accessViolation || forbiddenViolation) && seen.add(from + " -> " + to)) {
            out.add(new ArchResult.Violation("forbiddenDependency", from, to,
                    String.format("Component '%s' must not depend on '%s'", from, to)));
        }
    }

    private void checkCycles(Map<String, Set<String>> componentGraph, List<ArchResult.Violation> out) {
        Map<String, List<String>> graph = new HashMap<>();
        componentGraph.forEach((k, v) -> graph.put(k, new ArrayList<>(v)));
        for (List<String> cycle : CycleDetector.cyclesInGraph(graph)) {
            out.add(new ArchResult.Violation("cycle", String.join(" -> ", cycle), null,
                    "Dependency cycle between components: " + String.join(" -> ", cycle)));
        }
    }
}
