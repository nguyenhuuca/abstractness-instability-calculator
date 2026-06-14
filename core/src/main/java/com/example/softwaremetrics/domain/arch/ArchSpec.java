package com.example.softwaremetrics.domain.arch;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * An immutable architecture specification: named components defined by package-name patterns, the
 * allowed/forbidden dependency rules between them, optional naming conventions, and options. A
 * "component" can be a technical layer (controller/service/repository) or a functional module — both
 * are just patterns.
 */
public final class ArchSpec {

    /** A named component matched by one or more fully-qualified-class-name patterns. */
    public record Component(String name, List<Pattern> matches) {
    }

    /** A forbidden directed dependency from one component to another. */
    public record Edge(String from, String to) {
    }

    private final String name;
    private final List<Component> components;
    private final Map<String, Set<String>> access;   // component -> allowed targets (absent = unrestricted)
    private final Set<Edge> forbidden;
    private final Map<String, Pattern> naming;        // component -> required class simple-name pattern
    private final boolean ignoreUnmatched;
    private final boolean forbidCycles;

    public ArchSpec(String name, List<Component> components, Map<String, Set<String>> access,
                    Set<Edge> forbidden, Map<String, Pattern> naming,
                    boolean ignoreUnmatched, boolean forbidCycles) {
        this.name = name;
        this.components = components;
        this.access = access;
        this.forbidden = forbidden;
        this.naming = naming;
        this.ignoreUnmatched = ignoreUnmatched;
        this.forbidCycles = forbidCycles;
    }

    public String name() {
        return name;
    }

    /** Returns the name of the first component whose patterns match {@code fqcn}, or null. */
    public String componentOf(String fqcn) {
        for (Component c : components) {
            for (Pattern p : c.matches()) {
                if (p.matcher(fqcn).matches()) {
                    return c.name();
                }
            }
        }
        return null;
    }

    /** Allowed target components for the given component, or null when no allow-list is configured. */
    public Set<String> allowedTargets(String component) {
        return access.get(component);
    }

    public boolean isForbidden(String from, String to) {
        return forbidden.contains(new Edge(from, to));
    }

    /** Required class simple-name pattern for the component, or null. */
    public Pattern namingPattern(String component) {
        return naming.get(component);
    }

    public boolean ignoreUnmatched() {
        return ignoreUnmatched;
    }

    public boolean forbidCycles() {
        return forbidCycles;
    }

    /** Builds a spec from a parsed YAML document (see the bundled templates for the schema). */
    @SuppressWarnings("unchecked")
    public static ArchSpec fromYaml(Map<String, Object> root) {
        if (root == null) {
            throw new IllegalArgumentException("Architecture spec is empty");
        }
        String name = String.valueOf(root.getOrDefault("name", "architecture"));

        List<Component> components = new ArrayList<>();
        Object compsRaw = root.get("components");
        if (!(compsRaw instanceof List<?> comps) || comps.isEmpty()) {
            throw new IllegalArgumentException("Architecture spec must define at least one component");
        }
        for (Object o : comps) {
            Map<String, Object> cm = (Map<String, Object>) o;
            String cname = String.valueOf(cm.get("name"));
            List<Pattern> patterns = new ArrayList<>();
            for (Object m : asList(cm.get("matches"))) {
                patterns.add(Pattern.compile(String.valueOf(m)));
            }
            if (patterns.isEmpty()) {
                throw new IllegalArgumentException("Component '" + cname + "' has no matches patterns");
            }
            components.add(new Component(cname, patterns));
        }

        Map<String, Set<String>> access = new LinkedHashMap<>();
        if (root.get("access") instanceof Map<?, ?> am) {
            for (Map.Entry<?, ?> e : am.entrySet()) {
                Set<String> targets = new LinkedHashSet<>();
                for (Object t : asList(e.getValue())) {
                    targets.add(String.valueOf(t));
                }
                access.put(String.valueOf(e.getKey()), targets);
            }
        }

        Set<Edge> forbidden = new LinkedHashSet<>();
        for (Object o : asList(root.get("forbidden"))) {
            Map<String, Object> em = (Map<String, Object>) o;
            forbidden.add(new Edge(String.valueOf(em.get("from")), String.valueOf(em.get("to"))));
        }

        Map<String, Pattern> naming = new LinkedHashMap<>();
        if (root.get("naming") instanceof Map<?, ?> nm) {
            for (Map.Entry<?, ?> e : nm.entrySet()) {
                naming.put(String.valueOf(e.getKey()), Pattern.compile(String.valueOf(e.getValue())));
            }
        }

        boolean ignoreUnmatched = true;
        boolean forbidCycles = false;
        if (root.get("options") instanceof Map<?, ?> opts) {
            ignoreUnmatched = boolOption(opts.get("ignoreUnmatched"), true);
            forbidCycles = boolOption(opts.get("forbidCycles"), false);
        }

        return new ArchSpec(name, components, access, forbidden, naming, ignoreUnmatched, forbidCycles);
    }

    private static List<?> asList(Object value) {
        return (value instanceof List<?> l) ? l : List.of();
    }

    private static boolean boolOption(Object value, boolean dflt) {
        return (value instanceof Boolean b) ? b : dflt;
    }
}
