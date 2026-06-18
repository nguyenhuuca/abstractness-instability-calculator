package com.example.softwaremetrics.core.domain;

import com.example.softwaremetrics.core.domain.arch.ArchResult;
import com.example.softwaremetrics.core.domain.deadcode.DeadCodeResult;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MetricsExportTest {

    private PackageMetrics pkg(String name, double distance) {
        PackageMetrics m = new PackageMetrics();
        m.setPackageName(name);
        m.setDistance(distance);
        return m;
    }

    @Test
    void computesSummaryFromMetrics() {
        Map<String, PackageMetrics> metrics = new LinkedHashMap<>();
        metrics.put("a", pkg("a", 0.10)); // well designed (<= 0.5)
        metrics.put("b", pkg("b", 0.50)); // well designed (boundary)
        metrics.put("c", pkg("c", 0.90)); // needs attention

        MetricsExport export = MetricsExport.from("/some/project", "1.0-TEST", metrics);

        assertThat(export.projectPath()).isEqualTo("/some/project");
        assertThat(export.toolVersion()).isEqualTo("1.0-TEST");
        assertThat(export.generatedAt()).isNotBlank();
        assertThat(export.packageCount()).isEqualTo(3);
        assertThat(export.packages()).isSameAs(metrics);

        MetricsExport.Summary summary = export.summary();
        assertThat(summary.wellDesigned()).isEqualTo(2);
        assertThat(summary.needsAttention()).isEqualTo(1);
        // (0.10 + 0.50 + 0.90) / 3 = 0.50
        assertThat(summary.averageDistance()).isEqualTo(0.50);
    }

    @Test
    void handlesEmptyMetrics() {
        MetricsExport export = MetricsExport.from("/empty", "1.0-TEST", Map.of());

        assertThat(export.packageCount()).isZero();
        assertThat(export.summary().wellDesigned()).isZero();
        assertThat(export.summary().needsAttention()).isZero();
        assertThat(export.summary().averageDistance()).isZero();
    }

    @Test
    void gateIsNullByDefaultAndAttachedByWithGate() {
        MetricsExport export = MetricsExport.from("/p", "1.0-TEST", Map.of());
        assertThat(export.gate()).isNull();

        GateResult gate = new GateResult(false, List.of(
                new GateResult.Violation("maxPackageDistance", "a", 0.8, 0.7, "msg")));
        MetricsExport withGate = export.withGate(gate);

        assertThat(withGate.gate()).isSameAs(gate);
        assertThat(withGate.packageCount()).isEqualTo(export.packageCount());
        assertThat(export.gate()).isNull(); // original unchanged
    }

    @Test
    void cyclesAreNullByDefaultAndAttachedByWithCycles() {
        MetricsExport export = MetricsExport.from("/p", "1.0-TEST", Map.of());
        assertThat(export.cycles()).isNull();

        List<List<String>> cycles = List.of(List.of("a", "b"));
        MetricsExport withCycles = export.withCycles(cycles);

        assertThat(withCycles.cycles()).isSameAs(cycles);
        assertThat(export.cycles()).isNull(); // original unchanged
    }

    @Test
    void bannedApisAreNullByDefaultAndAttachedByWithBannedApis() {
        MetricsExport export = MetricsExport.from("/p", "1.0-TEST", Map.of());
        assertThat(export.bannedApiViolations()).isNull();

        List<GateResult.Violation> violations = List.of(
                new GateResult.Violation("bannedApi", "com.example.Foo", 1.0, 0.0, "uses banned API"));
        MetricsExport withBanned = export.withBannedApis(violations);

        assertThat(withBanned.bannedApiViolations()).isSameAs(violations);
        assertThat(withBanned.packageCount()).isEqualTo(export.packageCount());
        assertThat(export.bannedApiViolations()).isNull(); // original unchanged
    }

    @Test
    void architectureIsNullByDefaultAndAttachedByWithArchitecture() {
        MetricsExport export = MetricsExport.from("/p", "1.0-TEST", Map.of());
        assertThat(export.architecture()).isNull();

        ArchResult arch = new ArchResult("layered", true, List.of());
        MetricsExport withArch = export.withArchitecture(arch);

        assertThat(withArch.architecture()).isSameAs(arch);
        assertThat(withArch.packageCount()).isEqualTo(export.packageCount());
        assertThat(export.architecture()).isNull(); // original unchanged
    }

    @Test
    void deadCodeIsNullByDefaultAndAttachedByWithDeadCode() {
        MetricsExport export = MetricsExport.from("/p", "1.0-TEST", Map.of());
        assertThat(export.deadCode()).isNull();

        DeadCodeResult deadCode = new DeadCodeResult(List.of("com.example.Unused"));
        MetricsExport withDeadCode = export.withDeadCode(deadCode);

        assertThat(withDeadCode.deadCode()).isSameAs(deadCode);
        assertThat(withDeadCode.packageCount()).isEqualTo(export.packageCount());
        assertThat(export.deadCode()).isNull(); // original unchanged
    }
}
