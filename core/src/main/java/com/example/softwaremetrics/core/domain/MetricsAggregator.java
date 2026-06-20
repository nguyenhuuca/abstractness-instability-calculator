package com.example.softwaremetrics.core.domain;

import com.example.softwaremetrics.core.domain.model.ClassDetail;
import com.example.softwaremetrics.core.domain.model.MethodComplexity;
import com.example.softwaremetrics.core.domain.model.ProjectModel;

import java.util.HashSet;

/**
 * Aggregates a {@link ProjectModel} into the per-module {@link RawModuleMetrics} used by
 * {@link PackageMetricsCalculator}: efferent/afferent class sets, abstract/total class counts and
 * complexity. Modules are assigned by the given {@link ModuleResolver}. This reads the prebuilt model
 * rather than re-walking the filesystem.
 */
final class MetricsAggregator {

    private MetricsAggregator() {
    }

    static RawModuleMetrics aggregate(ProjectModel model, ModuleResolver resolver) {
        RawModuleMetrics raw = RawModuleMetrics.empty();
        for (ClassDetail cd : model.classes()) {
            String module = resolver.moduleOf(cd.fqcn());
            if (module == null || cd.builderType() || cd.inner()) {
                continue;
            }

            raw.totalClassCount().merge(module, 1, Integer::sum);
            if (cd.abstractType()) {
                raw.abstractClassCount().merge(module, 1, Integer::sum);
            }

            ComplexityStats stats = raw.complexity().computeIfAbsent(module, k -> new ComplexityStats());
            for (MethodComplexity m : cd.methods()) {
                stats.add(m.name(), m.complexity());
            }

            for (String dependency : cd.dependencies()) {
                if (dependency.endsWith("Builder") || dependency.contains("$")) {
                    continue;
                }
                String dependencyModule = resolver.moduleOf(dependency);
                if (!module.equals(dependencyModule)) {
                    raw.outgoingDependencies().computeIfAbsent(module, k -> new HashSet<>()).add(dependency);
                    if (dependencyModule != null) {
                        raw.incomingDependencies().computeIfAbsent(dependencyModule, k -> new HashSet<>())
                                .add(cd.fqcn());
                    }
                }
            }
        }
        return raw;
    }
}
