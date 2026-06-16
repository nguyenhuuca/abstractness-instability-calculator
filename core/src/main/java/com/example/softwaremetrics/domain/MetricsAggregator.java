package com.example.softwaremetrics.domain;

import com.example.softwaremetrics.domain.model.ClassDetail;
import com.example.softwaremetrics.domain.model.MethodComplexity;
import com.example.softwaremetrics.domain.model.ProjectModel;

import java.util.Map;
import java.util.Set;

/**
 * Aggregates a {@link ProjectModel} into the per-module raw counts used by
 * {@link PackageMetricsCalculator}: efferent/afferent class sets, abstract/total class counts and
 * complexity. Modules are assigned by the given {@link ModuleResolver}. This reproduces exactly what
 * the former {@code JavaClassAnalyzer.analyzeClasses} pass computed, just reading the prebuilt model
 * instead of re-walking the filesystem.
 */
final class MetricsAggregator {

    private MetricsAggregator() {
    }

    static void aggregate(ProjectModel model, ModuleResolver resolver,
                          Map<String, Set<String>> outgoingDependencies,
                          Map<String, Set<String>> incomingDependencies,
                          Map<String, Integer> abstractClassCount,
                          Map<String, Integer> totalClassCount,
                          Map<String, ComplexityStats> complexity) {
        for (ClassDetail cd : model.classes()) {
            String module = resolver.moduleOf(cd.fqcn());
            if (module == null || cd.builderType() || cd.inner()) {
                continue;
            }

            totalClassCount.merge(module, 1, Integer::sum);
            if (cd.abstractType()) {
                abstractClassCount.merge(module, 1, Integer::sum);
            }

            ComplexityStats stats = complexity.computeIfAbsent(module, k -> new ComplexityStats());
            for (MethodComplexity m : cd.methods()) {
                stats.add(m.name(), m.complexity());
            }

            for (String dependency : cd.dependencies()) {
                if (dependency.endsWith("Builder") || dependency.contains("$")) {
                    continue;
                }
                String dependencyModule = resolver.moduleOf(dependency);
                if (!module.equals(dependencyModule)) {
                    outgoingDependencies.computeIfAbsent(module, k -> new java.util.HashSet<>()).add(dependency);
                    if (dependencyModule != null) {
                        incomingDependencies.computeIfAbsent(dependencyModule, k -> new java.util.HashSet<>())
                                .add(cd.fqcn());
                    }
                }
            }
        }
    }
}
