package com.example.softwaremetrics.domain;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ThresholdEvaluatorTest {

    private final ThresholdEvaluator evaluator = new ThresholdEvaluator();

    private PackageMetrics pkg(String name, double instability, double abstractness, double distance) {
        PackageMetrics m = new PackageMetrics();
        m.setPackageName(name);
        m.setInstability(instability);
        m.setAbstractness(abstractness);
        m.setDistance(distance);
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
    void allGatesDisabledAlwaysPasses() {
        Map<String, PackageMetrics> metrics = map(pkg("a", 0.0, 0.0, 1.0)); // terrible package
        GateResult result = evaluator.evaluate(metrics, List.of(), GateConfig.disabled());
        assertThat(result.passed()).isTrue();
        assertThat(result.violations()).isEmpty();
    }

    @Test
    void maxPackageDistanceFlagsOnlyPackagesOverThreshold() {
        Map<String, PackageMetrics> metrics = map(
                pkg("ok", 0.5, 0.5, 0.40),
                pkg("bad", 0.9, 0.0, 0.80));
        GateConfig cfg = new GateConfig(true, 0.7, false, false, 0.0, false);

        GateResult result = evaluator.evaluate(metrics, List.of(), cfg);

        assertThat(result.passed()).isFalse();
        assertThat(result.violations()).hasSize(1);
        GateResult.Violation v = result.violations().get(0);
        assertThat(v.type()).isEqualTo("maxPackageDistance");
        assertThat(v.packageName()).isEqualTo("bad");
    }

    @Test
    void forbiddenZonesFlagsPainAndUselessness() {
        Map<String, PackageMetrics> metrics = map(
                pkg("pain", 0.1, 0.1, 0.80),        // Zone of Pain
                pkg("useless", 0.9, 0.9, 0.80),     // Zone of Uselessness
                pkg("fine", 0.5, 0.5, 0.0));        // on the main sequence
        GateConfig cfg = new GateConfig(false, 0.0, true, false, 0.0, false);

        GateResult result = evaluator.evaluate(metrics, List.of(), cfg);

        assertThat(result.passed()).isFalse();
        assertThat(result.violations()).hasSize(2);
        assertThat(result.violations()).allSatisfy(v -> assertThat(v.type()).isEqualTo("forbiddenZone"));
        assertThat(result.violations()).extracting(GateResult.Violation::packageName)
                .containsExactlyInAnyOrder("pain", "useless");
    }

    @Test
    void maxAverageDistanceFlagsProjectWide() {
        Map<String, PackageMetrics> metrics = map(
                pkg("a", 0.5, 0.5, 0.60),
                pkg("b", 0.5, 0.5, 0.80)); // avg = 0.70
        GateConfig cfg = new GateConfig(false, 0.0, false, true, 0.5, false);

        GateResult result = evaluator.evaluate(metrics, List.of(), cfg);

        assertThat(result.passed()).isFalse();
        assertThat(result.violations()).hasSize(1);
        GateResult.Violation v = result.violations().get(0);
        assertThat(v.type()).isEqualTo("maxAverageDistance");
        assertThat(v.packageName()).isNull();
        assertThat(v.value()).isEqualTo(0.70);
    }

    @Test
    void passesWhenAllPackagesWithinThresholds() {
        Map<String, PackageMetrics> metrics = map(
                pkg("a", 0.4, 0.6, 0.0),
                pkg("b", 0.6, 0.4, 0.0));
        GateConfig cfg = new GateConfig(true, 0.7, true, true, 0.5, true);

        GateResult result = evaluator.evaluate(metrics, List.of(), cfg);

        assertThat(result.passed()).isTrue();
        assertThat(result.violations()).isEmpty();
    }

    @Test
    void noCyclesGateFlagsEachCycleGroupWhenEnabled() {
        Map<String, PackageMetrics> metrics = map(pkg("a", 0.5, 0.5, 0.0), pkg("b", 0.5, 0.5, 0.0));
        List<List<String>> cycles = List.of(List.of("a", "b"));
        GateConfig cfg = new GateConfig(false, 0.0, false, false, 0.0, true);

        GateResult result = evaluator.evaluate(metrics, cycles, cfg);

        assertThat(result.passed()).isFalse();
        assertThat(result.violations()).hasSize(1);
        assertThat(result.violations().get(0).type()).isEqualTo("circularDependency");

        // Same cycles but gate disabled → passes.
        GateConfig off = new GateConfig(false, 0.0, false, false, 0.0, false);
        assertThat(evaluator.evaluate(metrics, cycles, off).passed()).isTrue();
    }
}
