package com.example.softwaremetrics.application;

import com.example.softwaremetrics.domain.GateConfig;
import com.example.softwaremetrics.domain.GateProperties;
import com.example.softwaremetrics.domain.GateResult;
import com.example.softwaremetrics.domain.MetricsExport;
import com.example.softwaremetrics.domain.PackageMetrics;
import com.example.softwaremetrics.domain.ThresholdEvaluator;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Headless / CI entry point. Activated when the app is started with {@code --scan=<path>}: it scans
 * the project, prints the JSON metrics envelope (incl. gate result), and exits non-zero when a
 * configured quality gate is violated so a pipeline can fail the build.
 *
 * <p>Exit codes: {@code 0} = all gates passed, {@code 1} = gate violation, {@code 2} = scan error.
 */
@Component
public class ScanCliRunner implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(ScanCliRunner.class);

    private final SpringBootPackageScanner scanner;
    private final ThresholdEvaluator thresholdEvaluator;
    private final GateProperties gateProperties;
    private final ConfigurableApplicationContext context;
    private final ObjectMapper objectMapper;

    @Value("${app.tool-version:1.0-SNAPSHOT}")
    private String toolVersion;

    public ScanCliRunner(SpringBootPackageScanner scanner, ThresholdEvaluator thresholdEvaluator,
                         GateProperties gateProperties, ConfigurableApplicationContext context,
                         ObjectMapper objectMapper) {
        this.scanner = scanner;
        this.thresholdEvaluator = thresholdEvaluator;
        this.gateProperties = gateProperties;
        this.context = context;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!args.containsOption("scan")) {
            return; // not CLI mode — leave the web app running
        }

        // Accept both "--scan=<path>" and "--scan <path>" (path then arrives as a non-option arg).
        String path = firstValue(args, "scan");
        if ((path == null || path.isBlank()) && !args.getNonOptionArgs().isEmpty()) {
            path = args.getNonOptionArgs().get(0);
        }
        if (path == null || path.isBlank()) {
            System.err.println("Usage: --scan=<project-path> [--output=<file>] [--fail-on-distance=<d>]");
            exit(2);
            return;
        }

        try {
            Map<String, PackageMetrics> metrics = scanner.scanProject(path);
            GateConfig gateConfig = resolveGateConfig(args);
            GateResult gateResult = thresholdEvaluator.evaluate(metrics, gateConfig);
            MetricsExport export = MetricsExport.from(path, toolVersion, metrics).withGate(gateResult);

            writeJson(export, firstValue(args, "output"));
            printSummary(gateResult);

            exit(gateResult.passed() ? 0 : 1);
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("Scan failed: " + e.getMessage());
            exit(2);
        }
    }

    /** Builds the gate config from properties, with {@code --fail-on-distance} overriding the per-package gate. */
    private GateConfig resolveGateConfig(ApplicationArguments args) {
        GateConfig base = gateProperties.toConfig();
        String failOnDistance = firstValue(args, "fail-on-distance");
        if (failOnDistance != null && !failOnDistance.isBlank()) {
            double threshold = Double.parseDouble(failOnDistance.trim());
            return new GateConfig(
                    true, threshold,
                    base.forbiddenZonesEnabled(),
                    base.maxAverageDistanceEnabled(), base.maxAverageDistance());
        }
        return base;
    }

    private void writeJson(MetricsExport export, String outputFile) throws Exception {
        String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(export);
        if (outputFile != null && !outputFile.isBlank()) {
            Files.writeString(Path.of(outputFile.trim()), json);
            System.err.println("Metrics written to " + outputFile.trim());
        } else {
            System.out.println(json);
        }
    }

    private void printSummary(GateResult result) {
        if (result.passed()) {
            System.err.println("Quality gate PASSED.");
            return;
        }
        System.err.println("Quality gate FAILED with " + result.violations().size() + " violation(s):");
        for (GateResult.Violation v : result.violations()) {
            System.err.println("  - " + v.message());
        }
    }

    private String firstValue(ApplicationArguments args, String option) {
        List<String> values = args.getOptionValues(option);
        return (values == null || values.isEmpty()) ? null : values.get(0);
    }

    private void exit(int code) {
        logger.debug("Exiting CLI with code {}", code);
        System.exit(SpringApplication.exit(context, () -> code));
    }
}
