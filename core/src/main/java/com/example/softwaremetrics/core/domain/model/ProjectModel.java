package com.example.softwaremetrics.core.domain.model;

import com.example.softwaremetrics.core.domain.ClassInfo;
import com.example.softwaremetrics.core.domain.bytecode.TypeNames;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The result of analyzing a compiled project once: one {@link ClassDetail} per class. Every check
 * derives its own view from this single model (no extra filesystem/ASM passes):
 * <ul>
 *   <li>package metrics — via {@code PackageMetricsCalculator} with a {@code ModuleResolver};</li>
 *   <li>{@link #classDependencyGraph(String)} — first-party class graph for the architecture checker;</li>
 *   <li>{@link #classInfos(String)} — per-class model for the banned-API and dead-code checks.</li>
 * </ul>
 * The derivations reproduce exactly what the former per-check passes computed.
 */
public record ProjectModel(List<ClassDetail> classes) {

    /** Fully-qualified names of every analyzed class (used to infer a root package). */
    public Set<String> classNames() {
        Set<String> names = new LinkedHashSet<>();
        for (ClassDetail c : classes) {
            names.add(c.fqcn());
        }
        return names;
    }

    /**
     * First-party class dependency graph for architecture checking: each project class (FQCN under
     * {@code mainPackage}) maps to the set of project classes it depends on. Every first-party class
     * is a key, even with an empty set, so naming rules can cover it. Inner classes are skipped.
     */
    public Map<String, Set<String>> classDependencyGraph(String mainPackage) {
        String prefix = mainPackage + ".";
        Map<String, Set<String>> graph = new LinkedHashMap<>();
        for (ClassDetail c : classes) {
            if (!c.fqcn().startsWith(prefix) || c.inner()) {
                continue;
            }
            Set<String> firstPartyDeps = graph.computeIfAbsent(c.fqcn(), k -> new LinkedHashSet<>());
            for (String dependency : c.dependencies()) {
                String dep = TypeNames.stripArraySuffix(dependency);
                if (dep.contains("$") || dep.equals(c.fqcn())) {
                    continue;
                }
                if (dep.startsWith(prefix)) {
                    firstPartyDeps.add(dep);
                }
            }
        }
        return graph;
    }

    /**
     * Per-class model for the banned-API and dead-code checks: one {@link ClassInfo} per first-party,
     * non-inner class. {@code firstPartyClassRefs} are this class's references that resolve to other
     * project classes.
     */
    public List<ClassInfo> classInfos(String mainPackage) {
        String prefix = mainPackage + ".";
        List<ClassInfo> infos = new ArrayList<>();
        for (ClassDetail c : classes) {
            if (!c.fqcn().startsWith(prefix) || c.inner()) {
                continue;
            }
            Set<String> firstPartyClassRefs = new LinkedHashSet<>();
            for (String t : c.typeRefs()) {
                if (t.startsWith(prefix) && !t.equals(c.fqcn()) && !t.contains("$")) {
                    firstPartyClassRefs.add(t);
                }
            }
            infos.add(new ClassInfo(c.fqcn(), c.packageName(), c.entryPoint(),
                    firstPartyClassRefs, c.typeRefs(), c.methodRefs()));
        }
        return infos;
    }
}
