package com.example.softwaremetrics.cli;

import com.example.softwaremetrics.application.SpringBootPackageScanner;
import com.example.softwaremetrics.config.Defaults;
import com.example.softwaremetrics.domain.CycleDetector;
import com.example.softwaremetrics.domain.GateConfig;
import com.example.softwaremetrics.domain.GateResult;
import com.example.softwaremetrics.domain.JavaClassAnalyzer;
import com.example.softwaremetrics.domain.MetricsExport;
import com.example.softwaremetrics.domain.PackageLocator;
import com.example.softwaremetrics.domain.PackageMetrics;
import com.example.softwaremetrics.domain.PackageMetricsCalculator;
import com.example.softwaremetrics.domain.ProjectPathTraverser;
import com.example.softwaremetrics.domain.ThresholdEvaluator;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

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
            System.err.println("Usage: --scan=<project-path> [--output=<file>] [--fail-on-distance=<d>] [--no-cycles]");
            System.exit(2);
            return;
        }

        SpringBootPackageScanner scanner = wireScanner();
        CycleDetector cycleDetector = new CycleDetector();
        ThresholdEvaluator evaluator = new ThresholdEvaluator();
        ObjectMapper objectMapper = new ObjectMapper();

        try {
            Map<String, PackageMetrics> metrics = scanner.scanProject(parsed.scanPath);
            List<List<String>> cycles = cycleDetector.findCycles(metrics);
            GateConfig gateConfig = resolveGateConfig(parsed);
            GateResult gateResult = evaluator.evaluate(metrics, cycles, gateConfig);
            MetricsExport export = MetricsExport.from(parsed.scanPath, TOOL_VERSION, metrics)
                    .withGate(gateResult)
                    .withCycles(cycles);

            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(export);
            if (parsed.outputFile != null) {
                Files.writeString(Path.of(parsed.outputFile), json);
                System.err.println("Metrics written to " + parsed.outputFile);
            } else {
                System.out.println(json);
            }
            printSummary(gateResult);

            System.exit(gateResult.passed() ? 0 : 1);
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("Scan failed: " + e.getMessage());
            System.exit(2);
        }
    }

    /** Manually wires the analysis object graph (no Spring). */
    private static SpringBootPackageScanner wireScanner() {
        JavaClassAnalyzer analyzer = new JavaClassAnalyzer(Defaults.exclusions());
        ProjectPathTraverser traverser = new ProjectPathTraverser();
        PackageLocator locator = new PackageLocator(analyzer, traverser);
        PackageMetricsCalculator calculator = new PackageMetricsCalculator(analyzer);
        return new SpringBootPackageScanner(locator, calculator);
    }

    private static GateConfig resolveGateConfig(Args parsed) {
        GateConfig base = Defaults.gateConfig();
        boolean maxPkgEnabled = base.maxPackageDistanceEnabled();
        double maxPkg = base.maxPackageDistance();
        if (parsed.failOnDistance != null) {
            maxPkgEnabled = true;
            maxPkg = parsed.failOnDistance;
        }
        boolean noCycles = base.noCyclesEnabled() || parsed.noCycles;
        return new GateConfig(
                maxPkgEnabled, maxPkg,
                base.forbiddenZonesEnabled(),
                base.maxAverageDistanceEnabled(), base.maxAverageDistance(),
                noCycles);
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

    /** Minimal arg parsing for {@code --key=value}, {@code --key value} and boolean flags. */
    private static final class Args {
        String scanPath;
        String outputFile;
        Double failOnDistance;
        boolean noCycles;

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
