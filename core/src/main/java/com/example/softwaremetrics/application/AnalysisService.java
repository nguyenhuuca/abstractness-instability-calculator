package com.example.softwaremetrics.application;

import com.example.softwaremetrics.config.AnalyzeConfig;
import com.example.softwaremetrics.config.CheckConfig;
import com.example.softwaremetrics.config.CheckConfigLoader;
import com.example.softwaremetrics.domain.ClassInfo;
import com.example.softwaremetrics.domain.CycleDetector;
import com.example.softwaremetrics.domain.GateResult;
import com.example.softwaremetrics.domain.InstabilityCalculatorProperties;
import com.example.softwaremetrics.domain.JavaClassAnalyzer;
import com.example.softwaremetrics.domain.MetricsExport;
import com.example.softwaremetrics.domain.ModuleResolver;
import com.example.softwaremetrics.domain.PackageMetrics;
import com.example.softwaremetrics.domain.PackageMetricsCalculator;
import com.example.softwaremetrics.domain.ProjectPathTraverser;
import com.example.softwaremetrics.domain.ThresholdEvaluator;
import com.example.softwaremetrics.domain.arch.ArchChecker;
import com.example.softwaremetrics.domain.arch.ArchResult;
import com.example.softwaremetrics.domain.banned.BannedApiChecker;
import com.example.softwaremetrics.domain.bytecode.DependencyExclusions;
import com.example.softwaremetrics.domain.bytecode.ProjectModelBuilder;
import com.example.softwaremetrics.domain.deadcode.DeadCodeDetector;
import com.example.softwaremetrics.domain.deadcode.DeadCodeResult;
import com.example.softwaremetrics.domain.model.ProjectModel;
import com.example.softwaremetrics.domain.resolve.ChainedRootPackageResolver;
import com.example.softwaremetrics.domain.resolve.CommonPrefixRootPackageResolver;
import com.example.softwaremetrics.domain.resolve.ExplicitRootPackageResolver;
import com.example.softwaremetrics.domain.resolve.RootPackageResolver;
import com.example.softwaremetrics.domain.resolve.SpringBootRootPackageResolver;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The single public entry point for running an analysis. Encapsulates the whole orchestration —
 * resolve config, analyze the project <strong>once</strong> into a {@link ProjectModel}, resolve the
 * root package, then derive metrics, cycles, gates and the architecture / banned-API / dead-code
 * checks from that one model — so a consumer just calls {@link #analyze(AnalysisRequest)} instead of
 * wiring (and sequencing) the engine internals by hand.
 *
 * <p>Build it with the full constructor (the web module wires it as a Spring bean) or via
 * {@link #create(InstabilityCalculatorProperties)}, which hand-wires the entire object graph.
 */
public class AnalysisService {

    private final ProjectModelBuilder modelBuilder;
    private final PackageMetricsCalculator metricsCalculator;
    private final RootPackageResolver springBootResolver;
    private final CycleDetector cycleDetector;
    private final ThresholdEvaluator thresholdEvaluator;
    private final ArchChecker archChecker;
    private final BannedApiChecker bannedApiChecker;
    private final DeadCodeDetector deadCodeDetector;

    public AnalysisService(ProjectModelBuilder modelBuilder, PackageMetricsCalculator metricsCalculator,
                           RootPackageResolver springBootResolver, CycleDetector cycleDetector,
                           ThresholdEvaluator thresholdEvaluator, ArchChecker archChecker,
                           BannedApiChecker bannedApiChecker, DeadCodeDetector deadCodeDetector) {
        this.modelBuilder = modelBuilder;
        this.metricsCalculator = metricsCalculator;
        this.springBootResolver = springBootResolver;
        this.cycleDetector = cycleDetector;
        this.thresholdEvaluator = thresholdEvaluator;
        this.archChecker = archChecker;
        this.bannedApiChecker = bannedApiChecker;
        this.deadCodeDetector = deadCodeDetector;
    }

    /**
     * Hand-wires the full analysis object graph from a single configuration object — the wiring a
     * headless consumer (e.g. the CLI) would otherwise have to replicate.
     */
    public static AnalysisService create(InstabilityCalculatorProperties exclusions) {
        ProjectModelBuilder builder = new ProjectModelBuilder(new DependencyExclusions(exclusions));
        JavaClassAnalyzer analyzer = new JavaClassAnalyzer(exclusions);
        RootPackageResolver springBoot = new SpringBootRootPackageResolver(analyzer, new ProjectPathTraverser());
        return new AnalysisService(builder, new PackageMetricsCalculator(analyzer), springBoot,
                new CycleDetector(), new ThresholdEvaluator(), new ArchChecker(),
                new BannedApiChecker(), new DeadCodeDetector());
    }

    /**
     * Runs the full analysis for one request.
     *
     * @throws IllegalArgumentException if the root package cannot be determined or has no module
     *                                  sub-packages
     * @throws IllegalStateException    on an I/O error while reading the project
     */
    public AnalysisResult analyze(AnalysisRequest request) {
        Path projectPath = Path.of(request.projectPath());
        CheckConfig config = CheckConfigLoader.resolve(projectPath, request.overrides());

        // Single bytecode pass — every downstream check reads from this one model.
        ProjectModel model = modelBuilder.build(projectPath);

        String mainPackage = resolveRootPackage(projectPath, model, config.analyze());
        if (mainPackage == null || mainPackage.isEmpty()) {
            throw new IllegalArgumentException("Could not determine the project's root package. Set "
                    + "analyze.rootPackage in aic-check.yaml, or ensure a @SpringBootApplication class exists.");
        }

        ModuleResolver resolver = new ModuleResolver(mainPackage, config.analyze().depth(),
                expandedFqns(mainPackage, config.analyze()));
        Map<String, PackageMetrics> metrics = metricsCalculator.calculateMetrics(model, resolver);
        if (metrics.isEmpty()) {
            throw new IllegalArgumentException("No module sub-packages found under '" + mainPackage + "'.");
        }

        List<List<String>> cycles = cycleDetector.findCycles(metrics);
        GateResult gate = request.evaluateGates()
                ? thresholdEvaluator.evaluate(metrics, cycles, config.gate())
                : null;

        Checks checks = runChecks(model, mainPackage, config);

        MetricsExport export = MetricsExport.from(request.projectPath(), request.toolVersion(), metrics)
                .withCycles(cycles);
        if (gate != null) {
            export = export.withGate(gate);
        }
        if (checks.architecture() != null) {
            export = export.withArchitecture(checks.architecture());
        }
        if (!config.bannedApis().isEmpty()) {
            export = export.withBannedApis(checks.bannedApis());
        }
        if (checks.deadCode() != null) {
            export = export.withDeadCode(checks.deadCode());
        }

        return new AnalysisResult(export, metrics, cycles, gate,
                checks.architecture(), checks.bannedApis(), checks.deadCode());
    }

    /** Resolves the root package: explicit config → Spring Boot annotation → inferred common prefix. */
    private String resolveRootPackage(Path projectPath, ProjectModel model, AnalyzeConfig analyze) {
        RootPackageResolver commonPrefix = p -> CommonPrefixRootPackageResolver.commonPackagePrefix(model.classNames());
        return new ChainedRootPackageResolver(List.of(
                new ExplicitRootPackageResolver(analyze.rootPackage()),
                springBootResolver,
                commonPrefix)).resolve(projectPath);
    }

    /** Result of the optional checks driven by aic-check.yaml / the {@code --arch} override. */
    private record Checks(ArchResult architecture, List<GateResult.Violation> bannedApis, DeadCodeResult deadCode) {
    }

    /**
     * Runs architecture, banned-API and dead-code checks as configured, all reading the already-built
     * {@link ProjectModel}. Each result is null/empty when not configured.
     */
    private Checks runChecks(ProjectModel model, String mainPackage, CheckConfig config) {
        ArchResult architecture = null;
        if (config.architecture() != null) {
            architecture = archChecker.check(config.architecture(), model.classDependencyGraph(mainPackage));
        }
        List<GateResult.Violation> bannedApis = List.of();
        DeadCodeResult deadCode = null;
        if (!config.bannedApis().isEmpty() || config.deadCodeEnabled()) {
            List<ClassInfo> classInfos = model.classInfos(mainPackage);
            if (!config.bannedApis().isEmpty()) {
                bannedApis = bannedApiChecker.check(classInfos, config.bannedApis());
            }
            if (config.deadCodeEnabled()) {
                deadCode = deadCodeDetector.detect(classInfos);
            }
        }
        return new Checks(architecture, bannedApis, deadCode);
    }

    /** Resolves {@code expand} entries (simple name or FQN) to fully-qualified packages under main. */
    private Set<String> expandedFqns(String mainPackage, AnalyzeConfig analyze) {
        Set<String> result = new LinkedHashSet<>();
        if (analyze.expand() != null) {
            for (String entry : analyze.expand()) {
                if (entry == null || entry.isBlank()) {
                    continue;
                }
                String e = entry.trim();
                result.add(e.startsWith(mainPackage + ".") ? e : mainPackage + "." + e);
            }
        }
        return result;
    }
}
