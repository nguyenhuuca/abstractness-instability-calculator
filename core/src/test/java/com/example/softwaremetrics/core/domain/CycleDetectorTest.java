package com.example.softwaremetrics.core.domain;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CycleDetectorTest {

    private final CycleDetector detector = new CycleDetector();

    /** A package whose efferent dependencies are classes in the given target packages. */
    private PackageMetrics pkg(String name, String... dependsOnPackages) {
        PackageMetrics m = new PackageMetrics();
        m.setPackageName(name);
        List<String> efferent = new java.util.ArrayList<>();
        for (String target : dependsOnPackages) {
            efferent.add(target + ".SomeClass"); // a class living in the target package
        }
        m.setEfferentDependencies(efferent);
        return m;
    }

    private Map<String, PackageMetrics> map(PackageMetrics... pkgs) {
        Map<String, PackageMetrics> m = new LinkedHashMap<>();
        for (PackageMetrics p : pkgs) {
            m.put(p.getPackageName(), p);
        }
        return m;
    }

    @Test
    void detectsTwoNodeCycle() {
        Map<String, PackageMetrics> metrics = map(
                pkg("com.app.a", "com.app.b"),
                pkg("com.app.b", "com.app.a"));

        List<List<String>> cycles = detector.findCycles(metrics);

        assertThat(cycles).hasSize(1);
        assertThat(cycles.get(0)).containsExactly("com.app.a", "com.app.b");
    }

    @Test
    void detectsThreeNodeCycle() {
        Map<String, PackageMetrics> metrics = map(
                pkg("com.app.a", "com.app.b"),
                pkg("com.app.b", "com.app.c"),
                pkg("com.app.c", "com.app.a"));

        List<List<String>> cycles = detector.findCycles(metrics);

        assertThat(cycles).hasSize(1);
        assertThat(cycles.get(0)).containsExactlyInAnyOrder("com.app.a", "com.app.b", "com.app.c");
    }

    @Test
    void acyclicGraphHasNoCycles() {
        Map<String, PackageMetrics> metrics = map(
                pkg("com.app.a", "com.app.b"),
                pkg("com.app.b", "com.app.c"),
                pkg("com.app.c")); // leaf

        assertThat(detector.findCycles(metrics)).isEmpty();
    }

    @Test
    void reportsOnlyTheCyclicComponentInAMixedGraph() {
        Map<String, PackageMetrics> metrics = map(
                pkg("com.app.a", "com.app.b"),
                pkg("com.app.b", "com.app.a"), // a <-> b cycle
                pkg("com.app.util"),           // unrelated, acyclic
                pkg("com.app.web", "com.app.util"));

        List<List<String>> cycles = detector.findCycles(metrics);

        assertThat(cycles).hasSize(1);
        assertThat(cycles.get(0)).containsExactly("com.app.a", "com.app.b");
    }

    @Test
    void ignoresDependenciesOnClassesOutsideTheModulePackages() {
        Map<String, PackageMetrics> metrics = map(
                pkg("com.app.a", "org.springframework.stereotype")); // external, not a module package

        assertThat(detector.findCycles(metrics)).isEmpty();
    }

    @Test
    void sortLambdaOrdersPackagesAlphabeticallyWithinSccAndSccsByFirstElement() {
        // Three-node cycle inserted in reverse-alphabetical order so that:
        //   - The "sorted within SCC" lambda must reorder z -> m -> a to [a, m, z]
        //   - The "sorted SCCs by first element" comparator is exercised via a second isolated cycle
        // First cycle: z -> m -> a -> z  (non-alphabetical insertion order)
        Map<String, PackageMetrics> metrics = new LinkedHashMap<>();
        metrics.put("com.app.z", pkg("com.app.z", "com.app.m"));
        metrics.put("com.app.m", pkg("com.app.m", "com.app.a"));
        metrics.put("com.app.a", pkg("com.app.a", "com.app.z"));
        // Second cycle: p -> q -> p  (starts after "a" alphabetically, so it sorts second)
        metrics.put("com.app.p", pkg("com.app.p", "com.app.q"));
        metrics.put("com.app.q", pkg("com.app.q", "com.app.p"));

        List<List<String>> cycles = detector.findCycles(metrics);

        // Two distinct SCCs
        assertThat(cycles).hasSize(2);
        // First SCC (alphabetically smallest first element = "com.app.a")
        assertThat(cycles.get(0)).containsExactly("com.app.a", "com.app.m", "com.app.z");
        // Second SCC (first element = "com.app.p")
        assertThat(cycles.get(1)).containsExactly("com.app.p", "com.app.q");
    }
}
