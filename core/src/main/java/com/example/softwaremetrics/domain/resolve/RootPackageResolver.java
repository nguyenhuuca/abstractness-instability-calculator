package com.example.softwaremetrics.domain.resolve;

import java.nio.file.Path;

/**
 * Strategy for determining a project's <em>root package</em> — the package whose direct sub-packages
 * are treated as the analysis modules. Abstracting this decouples the engine from Spring Boot: a
 * Spring-Modulith project can be located by its {@code @SpringBootApplication}, while a plain Java
 * project can supply an explicit prefix or fall back to the inferred common prefix.
 */
public interface RootPackageResolver {

    /**
     * @param projectPath the (compiled) project to inspect
     * @return the resolved root package, or {@code null}/blank if this strategy cannot determine one
     */
    String resolve(Path projectPath);
}
