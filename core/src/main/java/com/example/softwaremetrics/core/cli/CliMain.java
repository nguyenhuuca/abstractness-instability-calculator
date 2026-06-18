package com.example.softwaremetrics.core.cli;

import com.example.softwaremetrics.core.application.AnalysisRequest;
import com.example.softwaremetrics.core.application.AnalysisResult;
import com.example.softwaremetrics.core.application.AnalysisService;
import com.example.softwaremetrics.core.config.CheckConfigLoader;
import com.example.softwaremetrics.core.config.Defaults;
import com.example.softwaremetrics.core.domain.GateResult;
import com.example.softwaremetrics.core.domain.arch.ArchResult;
import com.example.softwaremetrics.core.domain.deadcode.DeadCodeResult;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Headless / CI entry point — runs a scan with no Spring and no web server. Prints the JSON metrics
 * envelope (incl. gate and cycles) and exits {@code 0} (gates passed) / {@code 1} (gate violated) /
 * {@code 2} (scan error or bad usage).
 *
 * <p>Thin shell over {@link AnalysisService}: it only parses args, prints the JSON + summaries and
 * maps the outcome to an exit code — all orchestration lives in the service.
 *
 * <p>Usage: {@code --scan=<path> [--output=<file>] [--fail-on-distance=<d>] [--no-cycles]}
 */
public final class CliMain {

    private static final String TOOL_VERSION = "1.0-SNAPSHOT";

    private CliMain() {
    }

    public static void main(String[] args) throws Exception {
        System.exit(run(args));
    }

    /** Runs the full CLI pipeline and returns the exit code (0/1/2). Package-private for testing. */
    static int run(String[] args) throws Exception {
        // slf4j-simple → keep diagnostic logs on stderr and quiet so stdout stays clean JSON.
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn");
        System.setProperty("org.slf4j.simpleLogger.logFile", "System.err");

        Args parsed = Args.parse(args);
        if (parsed.scanPath == null || parsed.scanPath.isBlank()) {
            System.err.println("Usage: --scan=<project-path> [--output=<file>] "
                    + "[--fail-on-distance=<d>] [--no-cycles] [--arch=<template|file.yaml>]");
            return 2;
        }

        AnalysisService service = AnalysisService.create(Defaults.exclusions());
        ObjectMapper objectMapper = new ObjectMapper();

        try {
            AnalysisRequest request = new AnalysisRequest(parsed.scanPath,
                    new CheckConfigLoader.Overrides(parsed.failOnDistance, parsed.noCycles, parsed.archRef),
                    TOOL_VERSION, true);
            AnalysisResult result = service.analyze(request);

            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result.export());
            if (parsed.outputFile != null) {
                Files.writeString(Path.of(parsed.outputFile), json);
                System.err.println("Metrics written to " + parsed.outputFile);
            } else {
                System.out.println(json);
            }
            printSummary(result.gate());
            printArchSummary(result.architecture());
            printBannedSummary(result.bannedApiViolations());
            printDeadCodeSummary(result.deadCode());

            return result.success() ? 0 : 1;
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("Scan failed: " + e.getMessage());
            return 2;
        }
    }

    static void printSummary(GateResult result) {
        if (result == null) {
            return;
        }
        if (result.passed()) {
            System.err.println("Quality gate PASSED.");
            return;
        }
        System.err.println("Quality gate FAILED with " + result.violations().size() + " violation(s):");
        for (GateResult.Violation v : result.violations()) {
            System.err.println("  - " + v.message());
        }
    }

    static void printArchSummary(ArchResult arch) {
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

    static void printBannedSummary(List<GateResult.Violation> violations) {
        if (violations.isEmpty()) {
            return;
        }
        System.err.println("Banned-API check FAILED with " + violations.size() + " violation(s):");
        for (GateResult.Violation v : violations) {
            System.err.println("  - " + v.message());
        }
    }

    static void printDeadCodeSummary(DeadCodeResult deadCode) {
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
    static final class Args {
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
