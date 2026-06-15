package com.example.softwaremetrics.infrastructure;

import com.example.softwaremetrics.application.SpringBootPackageScanner;
import com.example.softwaremetrics.config.CheckConfig;
import com.example.softwaremetrics.config.CheckConfigLoader;
import com.example.softwaremetrics.domain.ClassInfo;
import com.example.softwaremetrics.domain.CycleDetector;
import com.example.softwaremetrics.domain.GateResult;
import com.example.softwaremetrics.domain.JavaClassAnalyzer;
import com.example.softwaremetrics.domain.MetricsExport;
import com.example.softwaremetrics.domain.PackageLocator;
import com.example.softwaremetrics.domain.PackageMetrics;
import com.example.softwaremetrics.domain.arch.ArchChecker;
import com.example.softwaremetrics.domain.arch.ArchResult;
import com.example.softwaremetrics.domain.banned.BannedApiChecker;
import com.example.softwaremetrics.domain.deadcode.DeadCodeDetector;
import com.example.softwaremetrics.domain.deadcode.DeadCodeResult;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Controller
public class PackageScannerController {

    private final SpringBootPackageScanner springBootPackageScanner;
    private final CycleDetector cycleDetector;
    private final PackageLocator packageLocator;
    private final JavaClassAnalyzer javaClassAnalyzer;
    private final ArchChecker archChecker;

    @Value("${app.tool-version:1.0-SNAPSHOT}")
    private String toolVersion;

    public PackageScannerController(SpringBootPackageScanner springBootPackageScanner, CycleDetector cycleDetector,
                                    PackageLocator packageLocator, JavaClassAnalyzer javaClassAnalyzer,
                                    ArchChecker archChecker) {
        this.springBootPackageScanner = springBootPackageScanner;
        this.cycleDetector = cycleDetector;
        this.packageLocator = packageLocator;
        this.javaClassAnalyzer = javaClassAnalyzer;
        this.archChecker = archChecker;
    }

    /** Result of the optional checks driven by aic-check.yaml / the architecture dropdown. */
    private record Checks(ArchResult architecture, List<GateResult.Violation> bannedApis, DeadCodeResult deadCode) {
    }

    /**
     * Runs architecture, banned-API and dead-code checks per the project's {@code aic-check.yaml}
     * (the architecture dropdown overrides the file). Each is null/empty when not configured.
     */
    private Checks runChecks(Path projectPath, CheckConfig config) {
        boolean needsModel = !config.bannedApis().isEmpty() || config.deadCodeEnabled();
        boolean needsArch = config.architecture() != null;
        if (!needsModel && !needsArch) {
            return new Checks(null, List.of(), null);
        }
        String mainPackage = packageLocator.findMainPackage(projectPath);
        if (mainPackage == null || mainPackage.isEmpty()) {
            return new Checks(null, List.of(), null);
        }

        ArchResult architecture = null;
        if (needsArch) {
            architecture = archChecker.check(config.architecture(),
                    javaClassAnalyzer.buildClassDependencyGraph(projectPath, mainPackage));
        }
        List<GateResult.Violation> bannedApis = List.of();
        DeadCodeResult deadCode = null;
        if (needsModel) {
            List<ClassInfo> projectModel = javaClassAnalyzer.analyzeProject(projectPath, mainPackage);
            if (!config.bannedApis().isEmpty()) {
                bannedApis = new BannedApiChecker().check(projectModel, config.bannedApis());
            }
            if (config.deadCodeEnabled()) {
                deadCode = new DeadCodeDetector().detect(projectModel);
            }
        }
        return new Checks(architecture, bannedApis, deadCode);
    }

    @GetMapping("/")
    public String index() {
        return "index";
    }

    @SuppressWarnings("SpringMVCViewInspection")
    @PostMapping("/scan")
    public String scan(@RequestParam String path, @RequestParam(defaultValue = "") String arch, Model model) {
        try {
            CheckConfig config = CheckConfigLoader.resolve(Path.of(path),
                    new CheckConfigLoader.Overrides(null, false, arch));
            Map<String, PackageMetrics> metrics = springBootPackageScanner.scanProject(path, config.analyze());
            Checks checks = runChecks(Path.of(path), config);
            model.addAttribute("metrics", metrics);
            model.addAttribute("cycles", cycleDetector.findCycles(metrics));
            model.addAttribute("architecture", checks.architecture());
            model.addAttribute("bannedApiViolations", checks.bannedApis());
            model.addAttribute("deadCode", checks.deadCode());
            model.addAttribute("arch", arch);
            return "graph :: graph";
        } catch (IllegalArgumentException | IllegalStateException e) {
            model.addAttribute("error", "Error scanning project: " + e.getMessage());
            return "graph :: error";
        }
    }

    /**
     * Machine-consumable export: scans the project at {@code path} and returns the metrics as a
     * self-describing JSON envelope so another system can fetch and verify the results.
     */
    @GetMapping(value = "/api/metrics", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<?> exportMetrics(@RequestParam String path, @RequestParam(defaultValue = "") String arch) {
        try {
            CheckConfig config = CheckConfigLoader.resolve(Path.of(path),
                    new CheckConfigLoader.Overrides(null, false, arch));
            Map<String, PackageMetrics> metrics = springBootPackageScanner.scanProject(path, config.analyze());
            List<List<String>> cycles = cycleDetector.findCycles(metrics);
            Checks checks = runChecks(Path.of(path), config);
            MetricsExport export = MetricsExport.from(path, toolVersion, metrics)
                    .withCycles(cycles)
                    .withArchitecture(checks.architecture());
            if (!checks.bannedApis().isEmpty()) {
                export = export.withBannedApis(checks.bannedApis());
            }
            if (checks.deadCode() != null) {
                export = export.withDeadCode(checks.deadCode());
            }
            return ResponseEntity.ok(export);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
