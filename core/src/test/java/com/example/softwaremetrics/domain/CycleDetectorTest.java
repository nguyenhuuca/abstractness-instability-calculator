package com.example.softwaremetrics.domain;

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
}
