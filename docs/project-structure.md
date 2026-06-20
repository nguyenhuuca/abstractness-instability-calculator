# Project Structure

A multi-module Maven build splits the lean analysis engine from the web UI.

```mermaid
flowchart TD
    P[parent pom<br/>packaging=pom] --> C[core]
    P --> W[web]
    C -->|"shaded → aic-cli.jar (~2.8 MB, no Spring)"| CLI[Headless CLI]
    W -->|"depends on core, repackaged → aic-web.jar"| UI[Spring Boot Web UI]
```

## `core` — analysis engine + CLI (Spring-free)

Plain Java (no Spring); dependencies are only ASM, Jackson, slf4j, and snakeyaml.

- `domain` — the analysis core:
    - `bytecode/ProjectModelBuilder` — reads `.class` bytecode (ASM) in a single pass into a `ProjectModel` (dependencies, class counts, complexity, type/method refs).
    - `resolve/` — the `RootPackageResolver` chain (`Explicit` → `SpringBoot` → `CommonPrefix`, the Spring-Boot step backed by `SpringBootAnnotationScanner`) finds the root package; `ProjectPathTraverser` is a `Files.walk` wrapper; `ModuleResolver` maps each class to its module package at the configured granularity (`analyze.depth` / `analyze.expand`).
    - `PackageMetricsCalculator` / `MetricsAggregator` / `PackageMetrics` — aggregate the model into per-package A/I/D, Ce/Ca.
    - `CycleDetector` — Tarjan SCC over the package graph.
    - `GateConfig` / `ThresholdEvaluator` — quality gates.
    - `arch/` — `ArchSpec`, `ArchSpecLoader`, `ArchChecker` (YAML-driven conformance).
    - `MetricsExport` — the JSON envelope.
- `application` — `AnalysisService` orchestrates a scan (the single facade for both the CLI and web).
- `cli` — `CliMain` wires the POJOs by hand and runs the headless scan.
- `config` — `Defaults` (exclusion lists + default gate config).

## `web` — Spring Boot UI

Depends on `core`. `config/AnalysisConfig` exposes the core POJOs as `@Bean`s and binds
`application.yaml` onto them via `@Bean @ConfigurationProperties`, so `core` stays Spring-free while
the web app stays YAML-configurable. `infrastructure/PackageScannerController` serves `GET /`,
`POST /scan`, and `GET /api/metrics`; templates live in `web/src/main/resources/templates/`.

## The bytecode contract

Everything that reads a target project assumes **compiled `.class` files** — the analyzer never parses
source. Compile the target before scanning. Exclusion lists (JDK/native packages, common libraries,
basic types) keep only first-party coupling in the metrics.
