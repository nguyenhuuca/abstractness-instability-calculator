package com.example.softwaremetrics.domain.resolve;

import java.nio.file.Path;

/**
 * Returns a root package configured by the user (e.g. {@code analyze.rootPackage} in
 * {@code aic-check.yaml}). Lets a non-Spring-Boot project state its root package directly. Resolves
 * to {@code null} when no prefix was configured, so a chain can fall through to other strategies.
 */
public class ExplicitRootPackageResolver implements RootPackageResolver {

    private final String rootPackage;

    public ExplicitRootPackageResolver(String rootPackage) {
        this.rootPackage = (rootPackage == null || rootPackage.isBlank()) ? null : rootPackage.trim();
    }

    @Override
    public String resolve(Path projectPath) {
        return rootPackage;
    }
}
