package com.example.softwaremetrics.infrastructure;

import com.example.softwaremetrics.core.application.AnalysisRequest;
import com.example.softwaremetrics.core.application.AnalysisResult;
import com.example.softwaremetrics.core.application.AnalysisService;
import com.example.softwaremetrics.core.config.CheckConfigLoader;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.Map;

@Controller
public class PackageScannerController {

    private final AnalysisService analysisService;
    private final String toolVersion;

    public PackageScannerController(AnalysisService analysisService,
                                    @Value("${app.tool-version:dev}") String toolVersion) {
        this.analysisService = analysisService;
        this.toolVersion = toolVersion;
    }

    /** Builds a web request: project path + optional architecture override, gates left out of the JSON. */
    private AnalysisRequest request(String path, String arch) {
        return new AnalysisRequest(path,
                new CheckConfigLoader.Overrides(null, false, arch), toolVersion, false);
    }

    @GetMapping("/")
    public String index() {
        return "index";
    }

    @SuppressWarnings("SpringMVCViewInspection")
    @PostMapping("/scan")
    public String scan(@RequestParam String path, @RequestParam(defaultValue = "") String arch, Model model) {
        try {
            AnalysisResult result = analysisService.analyze(request(path, arch));
            model.addAttribute("metrics", result.metrics());
            model.addAttribute("cycles", result.cycles());
            model.addAttribute("architecture", result.architecture());
            model.addAttribute("bannedApiViolations", result.bannedApiViolations());
            model.addAttribute("deadCode", result.deadCode());
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
            AnalysisResult result = analysisService.analyze(request(path, arch));
            return ResponseEntity.ok(result.export());
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
