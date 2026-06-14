package com.example.softwaremetrics.infrastructure;

import com.example.softwaremetrics.application.SpringBootPackageScanner;
import com.example.softwaremetrics.domain.CycleDetector;
import com.example.softwaremetrics.domain.MetricsExport;
import com.example.softwaremetrics.domain.PackageMetrics;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.List;
import java.util.Map;

@Controller
public class PackageScannerController {

    private final SpringBootPackageScanner springBootPackageScanner;
    private final CycleDetector cycleDetector;

    @Value("${app.tool-version:1.0-SNAPSHOT}")
    private String toolVersion;

    public PackageScannerController(SpringBootPackageScanner springBootPackageScanner, CycleDetector cycleDetector) {
        this.springBootPackageScanner = springBootPackageScanner;
        this.cycleDetector = cycleDetector;
    }

    @GetMapping("/")
    public String index() {
        return "index";
    }

    @SuppressWarnings("SpringMVCViewInspection")
    @PostMapping("/scan")
    public String scan(@RequestParam String path, Model model) {
        try {
            Map<String, PackageMetrics> metrics = springBootPackageScanner.scanProject(path);
            model.addAttribute("metrics", metrics);
            model.addAttribute("cycles", cycleDetector.findCycles(metrics));
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
    public ResponseEntity<?> exportMetrics(@RequestParam String path) {
        try {
            Map<String, PackageMetrics> metrics = springBootPackageScanner.scanProject(path);
            List<List<String>> cycles = cycleDetector.findCycles(metrics);
            return ResponseEntity.ok(MetricsExport.from(path, toolVersion, metrics).withCycles(cycles));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
