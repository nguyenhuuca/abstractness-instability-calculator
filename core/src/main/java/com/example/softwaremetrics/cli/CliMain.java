package com.example.softwaremetrics.cli;

import com.example.softwaremetrics.application.SpringBootPackageScanner;
import com.example.softwaremetrics.config.CheckConfig;
import com.example.softwaremetrics.config.CheckConfigLoader;
import com.example.softwaremetrics.config.Defaults;
import com.example.softwaremetrics.domain.ClassInfo;
import com.example.softwaremetrics.domain.CycleDetector;
import com.example.softwaremetrics.domain.GateResult;
import com.example.softwaremetrics.domain.JavaClassAnalyzer;
import com.example.softwaremetrics.domain.MetricsExport;
import com.example.softwaremetrics.domain.PackageLocator;
import com.example.softwaremetrics.domain.PackageMetrics;
import com.example.softwaremetrics.domain.PackageMetricsCalculator;
import com.example.softwaremetrics.domain.ProjectPathTraverser;
import com.example.softwaremetrics.domain.ThresholdEvaluator;
import com.example.softwaremetrics.domain.arch.ArchChecker;
import com.example.softwaremetrics.domain.arch.ArchResult;
import com.example.softwaremetrics.domain.arch.ArchSpec;
import com.example.softwaremetrics.domain.banned.BannedApiChecker;
import com.example.softwaremetrics.domain.deadcode.DeadCodeDetector;
import com.example.softwaremetrics.domain.deadcode.DeadCodeResult;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Headless / CI entry point — runs a scan with no Spring and no web server. Prints the JSON metrics
 * envelope (incl. gate and cycles) and exits {@code 0} (gates passed) / {@code 1} (gate violated) /
 * {@code 2} (scan error or bad usage).
 *
 * <p>Usage: {@code --scan=<path> [--output=<file>] [--fail-on-distance=<d>] [--no-cycles]}
 */
public final class CliMain {

    private static final String TOOL_VERSION = "1.0-SNAPSHOT";

    private CliMain() {
    }

    public static void main(String[] args) throws Exception {
        // slf4j-simple → keep diagnostic logs on stderr and quiet so stdout stays clean JSON.
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn");
        System.setProperty("org.slf4j.simpleLogger.logFile", "System.err");

        Args parsed = Args.parse(args);
        if (parsed.scanPath == null || parsed.scanPath.isBlank()) {
            System.err.println("Usage: --scan=<project-path> [--output=<file>] "
                    + "[--fail-on-distance=<d>] [--no-cycles] [--arch=<template|file.yaml>]");
            System.exit(2);
            return;
        }

        // Manually wire the analysis object graph (no Spring).
        JavaClassAnalyzer analyzer = new JavaClassAnalyzer(Defaults.exclusions());
        PackageLocator locator = new PackageLocator(analyzer, new ProjectPathTraverser());
        SpringBootPackageScanner scanner = new SpringBootPackageScanner(locator, new PackageMetricsCalculator(analyzer));
        CycleDetector cycleDetector = new CycleDetector();
        ThresholdEvaluator evaluator = new ThresholdEvaluator();
        ObjectMapper objectMapper = new ObjectMapper();

        try {
            Path projectPath = Path.of(parsed.scanPath);
            // Effective config: code defaults < project aic-check.yaml < CLI flags.
            CheckConfig config = CheckConfigLoader.resolve(projectPath,
                    new CheckConfigLoader.Overrides(parsed.failOnDistance, parsed.noCycles, parsed.archRef));

            Map<String, PackageMetrics> metrics = scanner.scanProject(parsed.scanPath);
            List<List<String>> cycles = cycleDetector.findCycles(metrics);
            GateResult gateResult = evaluator.evaluate(metrics, cycles, config.gate());
            MetricsExport export = MetricsExport.from(parsed.scanPath, TOOL_VERSION, metrics)
                    .withGate(gateResult)
                    .withCycles(cycles);

            ArchResult arch = null;
            if (config.architecture() != null) {
                arch = checkArchitecture(projectPath, config.architecture(), analyzer, locator);
                export = export.withArchitecture(arch);
            }

            // Banned-API gate + dead-code report (share one project-model pass).
            List<GateResult.Violation> bannedViolations = List.of();
            DeadCodeResult deadCode = null;
            if (!config.bannedApis().isEmpty() || config.deadCodeEnabled()) {
                String mainPackage = locator.findMainPackage(projectPath);
                if (mainPackage != null && !mainPackage.isEmpty()) {
                    List<ClassInfo> model = analyzer.analyzeProject(projectPath, mainPackage);
                    if (!config.bannedApis().isEmpty()) {
                        bannedViolations = new BannedApiChecker().check(model, config.bannedApis());
                        export = export.withBannedApis(bannedViolations);
                    }
                    if (config.deadCodeEnabled()) {
                        deadCode = new DeadCodeDetector().detect(model);
                        export = export.withDeadCode(deadCode);
                    }
                }
            }

            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(export);
            if (parsed.outputFile != null) {
                Files.writeString(Path.of(parsed.outputFile), json);
                System.err.println("Metrics written to " + parsed.outputFile);
            } else {
                System.out.println(json);
            }
            printSummary(gateResult);
            printArchSummary(arch);
            printBannedSummary(bannedViolations);
            printDeadCodeSummary(deadCode);

            boolean ok = gateResult.passed()
                    && (arch == null || arch.compliant())
                    && bannedViolations.isEmpty();
            System.exit(ok ? 0 : 1);
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("Scan failed: " + e.getMessage());
            System.exit(2);
        }
    }

    private static ArchResult checkArchitecture(Path projectPath, ArchSpec spec,
                                                JavaClassAnalyzer analyzer, PackageLocator locator) {
        String mainPackage = locator.findMainPackage(projectPath);
        if (mainPackage == null || mainPackage.isEmpty()) {
            throw new IllegalArgumentException("No @SpringBootApplication found — cannot check architecture.");
        }
        Map<String, Set<String>> classDeps = analyzer.buildClassDependencyGraph(projectPath, mainPackage);
        return new ArchChecker().check(spec, classDeps);
    }

    private static void printSummary(GateResult result) {
        if (result.passed()) {
            System.err.println("Quality gate PASSED.");
            return;
        }
        System.err.println("Quality gate FAILED with " + result.violations().size() + " violation(s):");
        for (GateResult.Violation v : result.violations()) {
            System.err.println("  - " + v.message());
        }
    }

    private static void printArchSummary(ArchResult arch) {
        if (arch == null) {
            return;
        }
        if (arch.compliant()) {
            System.err.println("Architecture '" + arch.specName() + "' PASSED.");
            return;
        }
        System.err.println("Architecture '" + arch.specName() + "' FAILED with "
                + arch.violations().size() + " violation(s):");
        for (ArchResult.Violation v : arch.violations()) {
            System.err.println("  - " + v.message());
        }
    }

    private static void printBannedSummary(List<GateResult.Violation> violations) {
        if (violations.isEmpty()) {
            return;
        }
        System.err.println("Banned-API check FAILED with " + violations.size() + " violation(s):");
        for (GateResult.Violation v : violations) {
            System.err.println("  - " + v.message());
        }
    }

    private static void printDeadCodeSummary(DeadCodeResult deadCode) {
        if (deadCode == null || deadCode.unusedClasses().isEmpty()) {
            return;
        }
        System.err.println("Dead-code report (report-only): " + deadCode.unusedClasses().size()
                + " potentially unused class(es):");
        for (String c : deadCode.unusedClasses()) {
            System.err.println("  - " + c);
        }
    }

    /** Minimal arg parsing for {@code --key=value}, {@code --key value} and boolean flags. */
    private static final class Args {
        String scanPath;
        String outputFile;
        Double failOnDistance;
        boolean noCycles;
        String archRef;

        static Args parse(String[] argv) {
            Args a = new Args();
            for (int i = 0; i < argv.length; i++) {
                String arg = argv[i];
                if (arg.equals("--no-cycles")) {
                    a.noCycles = true;
                } else if (arg.startsWith("--scan")) {
                    a.scanPath = valueOf(arg, argv, i);
                    if (!arg.contains("=")) i++;
                } else if (arg.startsWith("--output")) {
                    a.outputFile = valueOf(arg, argv, i);
                    if (!arg.contains("=")) i++;
                } else if (arg.startsWith("--arch")) {
                    a.archRef = valueOf(arg, argv, i);
                    if (!arg.contains("=")) i++;
                } else if (arg.startsWith("--fail-on-distance")) {
                    String v = valueOf(arg, argv, i);
                    if (!arg.contains("=")) i++;
                    if (v != null && !v.isBlank()) a.failOnDistance = Double.parseDouble(v.trim());
                }
            }
            return a;
        }

        /** Returns the value after '=' or the next argument. */
        private static String valueOf(String arg, String[] argv, int i) {
            int eq = arg.indexOf('=');
            if (eq >= 0) {
                return arg.substring(eq + 1);
            }
            return (i + 1 < argv.length) ? argv[i + 1] : null;
        }
    }
}
