package com.example.softwaremetrics.domain.resolve;

import java.nio.file.Path;
import java.util.List;

/**
 * Tries each delegate in order and returns the first non-blank root package. The conventional order
 * is explicit configuration → Spring Boot annotation → inferred common prefix.
 */
public class ChainedRootPackageResolver implements RootPackageResolver {

    private final List<RootPackageResolver> delegates;

    public ChainedRootPackageResolver(List<RootPackageResolver> delegates) {
        this.delegates = List.copyOf(delegates);
    }

    @Override
    public String resolve(Path projectPath) {
        for (RootPackageResolver delegate : delegates) {
            String resolved = delegate.resolve(projectPath);
            if (resolved != null && !resolved.isBlank()) {
                return resolved.trim();
            }
        }
        return null;
    }
}
