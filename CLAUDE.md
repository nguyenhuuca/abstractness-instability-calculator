# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A Spring Boot web app that computes Robert Martin's package metrics — Abstractness (A), Instability (I), and Distance from the main sequence (D = |A + I − 1|) — for a target Java/Spring Boot project. The user enters a project path in the browser; results are plotted on an interactive scatter chart. Packages analyzed are the Spring Modulith "application module" packages: the direct sub-packages of the package containing the `@SpringBootApplication` class.

## Build & run

Requires JDK 22 and Maven 3.6+. This is a **multi-module** Maven build (`core` + `web`):

```bash
mvn clean package              # reactor: build + test both modules
mvn clean package -DskipTests  # build only
java -jar web/target/aic-web.jar          # web UI on port 8081
java -jar core/target/aic-cli.jar --scan=<path>   # headless CLI / CI
```

- **`core`** (`aic-core`) — Spring-free analysis engine + CLI. Produces two jars: the lean library jar `core/target/aic-core-<version>.jar` (the Maven artifact the `web` module depends on) and a standalone executable `core/target/aic-cli.jar` (~3 MB, shaded). The shade uses `<outputFile>` so the CLI jar is written to a fixed path **without** replacing the library artifact — keeping `slf4j-simple` out of `web`'s classpath. Deps: ASM, jackson-databind, slf4j.
- **`web`** (`aic-web`) — Spring Boot UI depending on `core`. Fat jar `web/target/aic-web.jar`.

The web app serves on **port 8081** (set in `web/.../application.yaml`; README's 8080 is stale). A Nix flake (`nix develop`) is provided for outdated Java/Maven.

**Headless / CI mode:** `core/target/aic-cli.jar --scan=<path>` runs a one-shot scan with **no Spring and no web server**. Entry point `cli.CliMain` is a thin shell: it parses args, then delegates the whole pipeline to `application/AnalysisService` (see Architecture), prints the JSON metrics envelope + summaries, and exits `0` (passed) / `1` (gate violated) / `2` (scan error). The CLI's gate config defaults in code (`config/Defaults`) and is overridden by flags (`--fail-on-distance`, `--no-cycles`); gate logic is `GateConfig` → `ThresholdEvaluator`. `CycleDetector` (Tarjan SCC over the package dependency graph) finds circular dependencies; cycles appear in the JSON envelope (`cycles`) and as a banner in the web UI.

**Root package & module granularity:** the **root package** is resolved by the `RootPackageResolver` chain — explicit `analyze.rootPackage` (lets a non-Spring-Boot project opt in) → `@SpringBootApplication` source → inferred common prefix. A class is then assigned to its module (`ModuleResolver`) by truncating its package — by default the direct sub-package of the root (depth 1); `analyze.depth` / `analyze.expand` in `aic-check.yaml` make it finer (e.g. split `dto` into `dto.admin`, `dto.webapi`). Modules emerge from the classes seen (no fixed pre-list).

**More checks (all on the same ASM pass):** per-method **cyclomatic complexity** is aggregated into `PackageMetrics` (avg/max + worst method) and gated by `max-complexity`; **banned-APIs** (`domain/banned/BannedApiChecker` over `JavaClassAnalyzer.analyzeProject` → `ClassInfo`) is a gate with `allowedIn` exceptions; **dead-code** (`domain/deadcode/DeadCodeDetector`, class-level) is report-only. All configured under `aic-check.yaml` and surfaced in the JSON envelope + web banners.

**Per-project config (`aic-check.yaml`):** `config/CheckConfigLoader` discovers an `aic-check.yaml` in the scanned project (`src/main/resources/` or root) and resolves the effective `CheckConfig` (gates + architecture) by layering **code defaults < project file < CLI flags**. Both `CliMain` and the web controller use it, so a project can own its check policy and CI can run a bare `--scan`.

**Architecture checking (`--arch=<template|file.yaml>`):** `domain/arch/` holds a YAML-driven conformance engine — `ArchSpecLoader` loads a built-in template (`core/src/main/resources/arch/{layered,hexagonal,onion}.yaml`) or a user file; `ArchSpec` models components (matched by class-name regex) plus access/forbidden/naming/cycle rules; `ArchChecker` validates the first-party class graph from `JavaClassAnalyzer.buildClassDependencyGraph` (reuses the shared `CycleDetector.cyclesInGraph` Tarjan). Result is attached to the envelope (`architecture`) and a violation makes the CLI exit `1`. Web UI display is not wired yet.

### Tests

```bash
mvn test                                                   # all tests
mvn test -Dtest=PackageMetricsCalculatorTest               # single class
mvn test -Dtest=JavaClassAnalyzerTest#methodName           # single method
```

`PackageScannerControllerIT` is a `@SpringBootTest` + MockMvc integration test that builds a synthetic project tree in a `@TempDir` (it emits compiled `.class` fixtures via ASM, since the analyzer reads bytecode, not source). As a `*IT` it runs in the **`verify`** phase via the failsafe plugin, not in `mvn test` — CI runs `mvn -B verify`.

## Architecture

The single orchestration entry point is **`application/AnalysisService`** (the facade): both `cli.CliMain` and the web `PackageScannerController` just call `analysisService.analyze(AnalysisRequest)` and get back an `AnalysisResult` (the assembled `MetricsExport` envelope + the individual pieces). The service runs the whole pipeline on a **single** bytecode pass: `CheckConfigLoader.resolve` → `ProjectModelBuilder.build` (one ASM walk → `ProjectModel`) → resolve root package (`RootPackageResolver` chain) → `PackageMetricsCalculator` → `CycleDetector` → `ThresholdEvaluator` (gates, CLI only) → arch/banned/dead-code checks (all derived from the same `ProjectModel`) → envelope. Build it via the full constructor (web wires it as a `@Bean`) or `AnalysisService.create(exclusions)` (hand-wires the object graph for the CLI).

The code is organized in three layers. **`domain` and `application` live in the `core` module**, under `com.example.softwaremetrics.core` (plain POJOs, no Spring); **`infrastructure`, templates, and the Spring wiring live in `web`**, under `com.example.softwaremetrics` (`config`, `infrastructure`). The `web` module's `config/AnalysisConfig` exposes the core POJOs as `@Bean`s (incl. `AnalysisService`) and binds `application.yaml` onto them via `@Bean @ConfigurationProperties` (so core stays Spring-free while the web app stays YAML-configurable).

- **infrastructure** (web) — `PackageScannerController`: depends only on `AnalysisService`. `GET /` renders `index`, `POST /scan?path=...` returns Thymeleaf fragments `graph :: graph` (success) or `graph :: error` (on `IllegalArgumentException`/`IllegalStateException`); `GET /api/metrics` returns the JSON envelope. Templates live in `web/src/main/resources/templates/`.
- **application** — `AnalysisService` (facade, above): builds the `ProjectModel` **once**, resolves the root package, then derives metrics / cycles / gates / arch / banned / dead-code from that one model. Throws `IllegalArgumentException` when the root package can't be determined or has no module sub-packages. `AnalysisRequest`/`AnalysisResult` are the immutable in/out types.
- **domain** — the analysis core:
  - `domain.bytecode.ProjectModelBuilder` — the heart: one filesystem+ASM pass over `*.class` → a `ProjectModel` (a `ClassDetail` per class) capturing everything the checks need. Helpers `TypeNames` (type/array normalization) and `DependencyExclusions` (exclusion lists) live alongside it.
  - `domain.model.ProjectModel` — the single-pass result; derives the per-module metric input, the `classDependencyGraph(mainPackage)` (architecture), and the `classInfos(mainPackage)` (banned/dead-code). `MetricsAggregator` + `PackageMetricsCalculator` turn it into per-package `PackageMetrics` (Ce, Ca, abstract/total, A, I, D, complexity).
  - `domain.resolve.RootPackageResolver` (SPI) — decouples the root-package choice from Spring Boot: `ChainedRootPackageResolver` tries `Explicit` (from `analyze.rootPackage`) → `SpringBoot` (`@SpringBootApplication` source) → `CommonPrefix` (inferred), so plain-Java projects scan too.
  - `JavaClassAnalyzer` — a thin compatibility facade over `ProjectModelBuilder` (kept for the `@SpringBootApplication` source detection + the analyzer-shaped methods the tests use). `ProjectPathTraverser` — `Files.walk` wrapper.

### Key design facts (read before changing metric logic)

- **The analyzer reads compiled `.class` bytecode, not source**, using ASM (`org.ow2.asm`). The target project **must be compiled** (its `target/` dir populated) for a scan to find anything — source-only projects yield empty metrics. `JavaClassAnalyzer.analyzeClasses` walks the whole project path for `*.class` files, skipping `target/test-classes`.
- **Dependencies (Ce) are extracted from bytecode**: method signatures, exceptions, method-body instructions (method/field/type refs), local variables, and the class generic signature. Inner classes (`$`), `*Builder` classes, and excluded packages are filtered out.
- **Coupling counts**: a package's Ce is the count of distinct dependency *classes* it references outside itself; Ca is the count of distinct classes from other modules that reference it. Instability `I = Ce / (Ce + Ca)`, defaulting to 0 when both are 0.
- **Exclusion lists** (`instability-calculator.*` in `application.yaml`, bound by `InstabilityCalculatorProperties`) filter out Java-native packages, common external libraries (Spring, Apache, etc.), and basic types so only first-party coupling counts. Each list has a `disabled` flag that, confusingly, means "this filter is *active*" — `isJavaNativePackage` etc. return early (no filtering) when `isDisabled()` is false.

## Conventions (rules for changes)

Follow these when adding features or making changes so contributions stay consistent:

- **Plan before coding (mandatory).** Before editing any source file for a feature or non-trivial change, produce a short written plan: the goal, which layer(s)/files will change, the approach, and how it will be tested. Present the plan and get the user's confirmation before writing code — use plan mode (`EnterPlanMode`/`ExitPlanMode`) when available. Skip this only for truly trivial edits (typo, comment, one-line config). If scope changes mid-task, re-plan.
- **Respect the layering.** New analysis/metric logic goes in `domain` (bytecode extraction in `domain.bytecode`, derivations in `domain.model`, root-package strategies in `domain.resolve`); scan orchestration in `application` (`AnalysisService`); HTTP endpoints and view wiring in `infrastructure`. Domain classes must not depend on web/Spring-MVC types.
- **Dependency injection via constructors**, not field `@Autowired` (the field injection in `JavaClassAnalyzer` is legacy — don't copy it). Register components with `@Component`.
- **Add a test with every behavioural change.** Domain logic gets a focused unit test (see `PackageMetricsCalculatorTest`, `JavaClassAnalyzerTest`); endpoint/flow changes extend `PackageScannerControllerIT` (MockMvc + `@TempDir` synthetic project). Run `mvn test` before declaring done.
- **Metrics changes must preserve the formulas** unless the change is explicitly about them: `I = Ce/(Ce+Ca)` (0 when both are 0), `A = abstract/total` (0 when total is 0), `D = |A + I − 1|`. If you touch counting, update both the calculator and its test.
- **UI changes** live in `web/src/main/resources/templates/` (`index.html` = shell/styles, `graph.html` = chart + details fragment). Keep the dark theme palette (CSS variables in `index.html`) and the existing element IDs the htmx/Chart.js/D3 scripts depend on (`#result`, `#tabContainer`, `#packageSelect`, `metricsChart`, etc.).
- **Don't break the bytecode contract.** Anything that reads the target project assumes compiled `.class` files — never switch to source parsing for metrics. New exclusion entries go in `application.yaml` (mind the inverted `disabled` flag, above).
- **Verify end-to-end** for non-trivial changes with `/demo` (scan this repo) or `/analyze <path>` (scan an external project) before committing.
- **Branch & PR**: branch off `main`, keep commits scoped, and end commit messages with the `Co-Authored-By` trailer. CI (`.github/workflows/ci.yml`) runs `mvn -B package` on JDK 22 — make sure it passes locally.
