package com.example.softwaremetrics.core.domain;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Detects circular dependencies between module packages. Builds a package-level dependency graph
 * from each package's efferent (outgoing) class dependencies and returns the strongly-connected
 * components of size &gt;= 2 — each is a group of packages that (transitively) depend on each other.
 */
public class CycleDetector {

    /**
     * @param metrics scan result keyed by module package name
     * @return circular-dependency groups; each is a sorted list of the packages in one cycle.
     * Empty when the package graph is acyclic.
     */
    public List<List<String>> findCycles(Map<String, PackageMetrics> metrics) {
        return cyclesInGraph(buildPackageGraph(metrics));
    }

    /**
     * Finds dependency cycles in an arbitrary directed graph (node -&gt; nodes it depends on) via
     * Tarjan's SCC. Returns each strongly-connected component of size &gt;= 2 as a sorted list, with
     * the groups in deterministic order. Shared by the package-level cycle gate and the architecture
     * checker.
     */
    public static List<List<String>> cyclesInGraph(Map<String, List<String>> graph) {
        return new Tarjan(graph).stronglyConnectedComponents().stream()
                .filter(scc -> scc.size() >= 2)
                .map(scc -> scc.stream().sorted().toList())
                .sorted(Comparator.comparing(g -> g.get(0)))
                .toList();
    }

    /** package -> packages it depends on (edges only between distinct module packages). */
    private Map<String, List<String>> buildPackageGraph(Map<String, PackageMetrics> metrics) {
        // Sort packages longest-first once, so owningPackage returns on its first (= longest) match.
        List<String> packagesByLengthDesc = metrics.keySet().stream()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
        Map<String, List<String>> graph = new HashMap<>();
        for (String pkg : metrics.keySet()) {
            Set<String> targets = new LinkedHashSet<>();
            List<String> efferent = metrics.get(pkg).getEfferentDependencies();
            if (efferent != null) {
                for (String dependencyClass : efferent) {
                    String target = owningPackage(dependencyClass, packagesByLengthDesc);
                    if (target != null && !target.equals(pkg)) {
                        targets.add(target);
                    }
                }
            }
            graph.put(pkg, new ArrayList<>(targets));
        }
        return graph;
    }

    /**
     * Finds the module package that owns the given class name (longest matching prefix). Expects
     * {@code packagesByLengthDesc} sorted by descending length, so the first prefix match is the
     * longest.
     */
    private String owningPackage(String className, List<String> packagesByLengthDesc) {
        for (String pkg : packagesByLengthDesc) {
            if (className.startsWith(pkg + ".")) {
                return pkg;
            }
        }
        return null;
    }

    /** Tarjan's strongly-connected-components algorithm (recursive; package graphs are small). */
    private static final class Tarjan {
        private final Map<String, List<String>> graph;
        private final Map<String, Integer> index = new HashMap<>();
        private final Map<String, Integer> lowLink = new HashMap<>();
        private final Set<String> onStack = new HashSet<>();
        private final Deque<String> stack = new ArrayDeque<>();
        private final List<List<String>> sccs = new ArrayList<>();
        private int nextIndex = 0;

        Tarjan(Map<String, List<String>> graph) {
            this.graph = graph;
        }

        List<List<String>> stronglyConnectedComponents() {
            for (String node : graph.keySet()) {
                if (!index.containsKey(node)) {
                    strongConnect(node);
                }
            }
            return sccs;
        }

        private void strongConnect(String v) {
            index.put(v, nextIndex);
            lowLink.put(v, nextIndex);
            nextIndex++;
            stack.push(v);
            onStack.add(v);

            for (String w : graph.getOrDefault(v, List.of())) {
                if (!index.containsKey(w)) {
                    strongConnect(w);
                    lowLink.put(v, Math.min(lowLink.get(v), lowLink.get(w)));
                } else if (onStack.contains(w)) {
                    lowLink.put(v, Math.min(lowLink.get(v), index.get(w)));
                }
            }

            if (lowLink.get(v).equals(index.get(v))) {
                List<String> component = new ArrayList<>();
                String w;
                do {
                    w = stack.pop();
                    onStack.remove(w);
                    component.add(w);
                } while (!w.equals(v));
                sccs.add(component);
            }
        }
    }
}
