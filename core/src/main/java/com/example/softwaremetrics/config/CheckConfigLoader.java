package com.example.softwaremetrics.config;

import com.example.softwaremetrics.domain.GateProperties;
import com.example.softwaremetrics.domain.GateProperties.ThresholdGate;
import com.example.softwaremetrics.domain.GateProperties.ToggleGate;
import com.example.softwaremetrics.domain.arch.ArchSpec;
import com.example.softwaremetrics.domain.arch.ArchSpecLoader;
import com.example.softwaremetrics.domain.banned.BannedApiRule;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Resolves the effective {@link CheckConfig} by layering, lowest to highest precedence:
 * <ol>
 *   <li>code defaults ({@link GateProperties} defaults; architecture off),</li>
 *   <li>the scanned project's {@code aic-check.yaml} (if present),</li>
 *   <li>CLI flag overrides.</li>
 * </ol>
 * The project file lets a project own its check policy (gates + architecture) so CI can run a bare
 * {@code --scan}; flags still override per run.
 */
public final class CheckConfigLoader {

    private static final String[] CANDIDATES = {
            "src/main/resources/aic-check.yaml",
            "src/main/resources/aic-check.yml",
            "aic-check.yaml",
            "aic-check.yml"
    };

    private CheckConfigLoader() {
    }

    /** CLI flag overrides; any field may be null/false to mean "not specified". */
    public record Overrides(Double failOnDistance, boolean noCycles, String archRef) {
        public static Overrides none() {
            return new Overrides(null, false, null);
        }
    }

    public static CheckConfig resolve(Path projectPath, Overrides cli) {
        GateProperties gates = new GateProperties();   // code defaults
        ArchSpec architecture = null;
        List<BannedApiRule> bannedApis = List.of();
        boolean deadCodeEnabled = false;
        AnalyzeConfig analyze = AnalyzeConfig.defaults();

        // 2. project aic-check.yaml
        Path file = discover(projectPath);
        if (file != null) {
            Map<String, Object> root = parse(file);
            applyGates(gates, asMap(root.get("gates")));
            architecture = architectureFrom(asMap(root.get("architecture")));
            bannedApis = bannedApisFrom(asMap(root.get("banned-apis")));
            deadCodeEnabled = sectionEnabled(asMap(root.get("dead-code")));
            analyze = analyzeFrom(asMap(root.get("analyze")));
        }

        // 3. CLI flags (highest precedence)
        Overrides o = (cli == null) ? Overrides.none() : cli;
        if (o.failOnDistance() != null) {
            gates.getMaxPackageDistance().setEnabled(true);
            gates.getMaxPackageDistance().setThreshold(o.failOnDistance());
        }
        if (o.noCycles()) {
            gates.getNoCycles().setEnabled(true);
        }
        if (o.archRef() != null && !o.archRef().isBlank()) {
            architecture = ArchSpecLoader.load(o.archRef());
        }

        return new CheckConfig(gates.toConfig(), architecture, bannedApis, deadCodeEnabled, analyze);
    }

    private static AnalyzeConfig analyzeFrom(Map<String, Object> section) {
        if (section == null) {
            return AnalyzeConfig.defaults();
        }
        int depth = (section.get("depth") instanceof Number n) ? Math.max(1, n.intValue()) : 1;
        List<String> expand = new ArrayList<>();
        if (section.get("expand") instanceof List<?> list) {
            for (Object o : list) {
                expand.add(String.valueOf(o));
            }
        }
        return new AnalyzeConfig(depth, expand);
    }

    /** Returns the first existing {@code aic-check.yaml} candidate under the project, or null. */
    static Path discover(Path projectPath) {
        for (String candidate : CANDIDATES) {
            Path p = projectPath.resolve(candidate);
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        return null;
    }

    private static ArchSpec architectureFrom(Map<String, Object> arch) {
        if (arch == null || !bool(arch.get("enabled"), false)) {
            return null;
        }
        Map<String, Object> inline = asMap(arch.get("spec"));
        if (inline != null) {
            return ArchSpec.fromYaml(inline);
        }
        Object template = arch.get("template");
        if (template != null) {
            return ArchSpecLoader.load(String.valueOf(template));
        }
        throw new IllegalArgumentException(
                "aic-check.yaml: architecture.enabled is true but neither 'spec' nor 'template' is set");
    }

    private static void applyGates(GateProperties gates, Map<String, Object> g) {
        if (g == null) {
            return;
        }
        applyGate(gates.getMaxPackageDistance(), g.get("max-package-distance"));
        applyGate(gates.getForbiddenZones(), g.get("forbidden-zones"));
        applyGate(gates.getMaxAverageDistance(), g.get("max-average-distance"));
        applyGate(gates.getNoCycles(), g.get("no-cycles"));
        applyGate(gates.getMaxComplexity(), g.get("max-complexity"));
    }

    private static boolean sectionEnabled(Map<String, Object> section) {
        return section != null && bool(section.get("enabled"), false);
    }

    @SuppressWarnings("unchecked")
    private static List<BannedApiRule> bannedApisFrom(Map<String, Object> section) {
        if (!sectionEnabled(section)) {
            return List.of();
        }
        List<BannedApiRule> rules = new ArrayList<>();
        Object rawRules = section.get("rules");
        if (rawRules instanceof List<?> list) {
            for (Object o : list) {
                Map<String, Object> rm = asMap(o);
                if (rm != null) {
                    rules.add(toRule(rm));
                }
            }
        }
        return rules;
    }

    private static BannedApiRule toRule(Map<String, Object> rm) {
        BannedApiRule.Kind kind;
        String target;
        if (rm.get("method") != null) {
            kind = BannedApiRule.Kind.METHOD;
            target = String.valueOf(rm.get("method"));
        } else if (rm.get("class") != null) {
            kind = BannedApiRule.Kind.CLASS;
            target = String.valueOf(rm.get("class"));
        } else if (rm.get("package") != null) {
            kind = BannedApiRule.Kind.PACKAGE;
            target = String.valueOf(rm.get("package"));
        } else {
            throw new IllegalArgumentException("banned-apis rule must have one of: class, method, package");
        }
        String message = rm.get("message") != null ? String.valueOf(rm.get("message")) : null;
        List<Pattern> allowedIn = new ArrayList<>();
        if (rm.get("allowedIn") instanceof List<?> patterns) {
            for (Object p : patterns) {
                allowedIn.add(Pattern.compile(String.valueOf(p)));
            }
        }
        return new BannedApiRule(kind, target, message, allowedIn);
    }

    private static void applyGate(ToggleGate gate, Object cfg) {
        Map<String, Object> m = asMap(cfg);
        if (m == null) {
            return;
        }
        if (m.containsKey("enabled")) {
            gate.setEnabled(bool(m.get("enabled"), gate.isEnabled()));
        }
        if (gate instanceof ThresholdGate tg && m.get("threshold") instanceof Number n) {
            tg.setThreshold(n.doubleValue());
        }
    }

    private static Map<String, Object> parse(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            Object root = new Yaml().load(in);
            if (root != null && !(root instanceof Map)) {
                throw new IllegalArgumentException(file.getFileName() + " must be a YAML mapping");
            }
            return asMap(root);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + file, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (value instanceof Map) ? (Map<String, Object>) value : null;
    }

    private static boolean bool(Object value, boolean dflt) {
        return (value instanceof Boolean b) ? b : dflt;
    }
}
