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

- **`core`** (`abstractness-instability-calculator-core`) — Spring-free analysis engine + CLI. Produces a lean shaded jar `core/target/aic-cli.jar` (~2.5 MB). Deps: ASM, jackson-databind, slf4j.
- **`web`** (`abstractness-instability-calculator-web`) — Spring Boot UI depending on `core`. Fat jar `web/target/aic-web.jar`.

The web app serves on **port 8081** (set in `web/.../application.yaml`; README's 8080 is stale). A Nix flake (`nix develop`) is provided for outdated Java/Maven.

**Headless / CI mode:** `core/target/aic-cli.jar --scan=<path>` runs a one-shot scan with **no Spring and no web server** (entry point `cli.CliMain`, which wires the core POJOs by hand), prints the JSON metrics envelope, evaluates the quality gates, and exits `0` (passed) / `1` (gate violated) / `2` (scan error). The CLI's gate config defaults in code (`config/Defaults`) and is overridden by flags (`--fail-on-distance`, `--no-cycles`); gate logic is `GateConfig` → `ThresholdEvaluator`. `CycleDetector` (Tarjan SCC over the package dependency graph) finds circular dependencies; cycles appear in the JSON envelope (`cycles`) and as a banner in the web UI.

### Tests

```bash
mvn test                                                   # all tests
mvn test -Dtest=PackageMetricsCalculatorTest               # single class
mvn test -Dtest=JavaClassAnalyzerTest#methodName           # single method
```

`PackageScannerControllerIT` is a `@SpringBootTest` + MockMvc integration test that builds a synthetic project tree in a `@TempDir`.

## Architecture

The flow is `Controller → SpringBootPackageScanner → PackageLocator + PackageMetricsCalculator → JavaClassAnalyzer`, organized in three layers under `com.example.softwaremetrics`. **`domain` and `application` live in the `core` module** (plain POJOs, no Spring); **`infrastructure`, templates, and the Spring wiring live in `web`**. The `web` module's `config/AnalysisConfig` exposes the core POJOs as `@Bean`s and binds `application.yaml` onto them via `@Bean @ConfigurationProperties` (so core stays Spring-free while the web app stays YAML-configurable).

- **infrastructure** (web) — `PackageScannerController`: `GET /` renders `index`, `POST /scan?path=...` returns Thymeleaf fragments `graph :: graph` (success) or `graph :: error` (on `IllegalArgumentException`/`IllegalStateException`). Templates live in `web/src/main/resources/templates/`.
- **application** — `SpringBootPackageScanner`: orchestrates a scan. Locates the main package, finds module packages, delegates metric calculation. Throws `IllegalArgumentException` when no `@SpringBootApplication` or no sub-packages are found.
- **domain** — the analysis core:
  - `PackageLocator` — finds the main package (file containing `@SpringBootApplication`) and the module packages (sub-packages exactly one level below it).
  - `ProjectPathTraverser` — thin `Files.walk` wrapper for finding `.java` files and directories.
  - `JavaClassAnalyzer` — the heart of the tool.
  - `PackageMetricsCalculator` — aggregates analyzer output into per-package `PackageMetrics` (Ce, Ca, abstract/total class counts, A, I, D).

### Key design facts (read before changing metric logic)

- **The analyzer reads compiled `.class` bytecode, not source**, using ASM (`org.ow2.asm`). The target project **must be compiled** (its `target/` dir populated) for a scan to find anything — source-only projects yield empty metrics. `JavaClassAnalyzer.analyzeClasses` walks the whole project path for `*.class` files, skipping `target/test-classes`.
- **Dependencies (Ce) are extracted from bytecode**: method signatures, exceptions, method-body instructions (method/field/type refs), local variables, and the class generic signature. Inner classes (`$`), `*Builder` classes, and excluded packages are filtered out.
- **Coupling counts**: a package's Ce is the count of distinct dependency *classes* it references outside itself; Ca is the count of distinct classes from other modules that reference it. Instability `I = Ce / (Ce + Ca)`, defaulting to 0 when both are 0.
- **Exclusion lists** (`instability-calculator.*` in `application.yaml`, bound by `InstabilityCalculatorProperties`) filter out Java-native packages, common external libraries (Spring, Apache, etc.), and basic types so only first-party coupling counts. Each list has a `disabled` flag that, confusingly, means "this filter is *active*" — `isJavaNativePackage` etc. return early (no filtering) when `isDisabled()` is false.

## Conventions (rules for changes)

Follow these when adding features or making changes so contributions stay consistent:

- **Plan before coding (mandatory).** Before editing any source file for a feature or non-trivial change, produce a short written plan: the goal, which layer(s)/files will change, the approach, and how it will be tested. Present the plan and get the user's confirmation before writing code — use plan mode (`EnterPlanMode`/`ExitPlanMode`) when available. Skip this only for truly trivial edits (typo, comment, one-line config). If scope changes mid-task, re-plan.
- **Respect the layering.** New analysis/metric logic goes in `domain`; scan orchestration in `application` (`SpringBootPackageScanner`); HTTP endpoints and view wiring in `infrastructure`. Domain classes must not depend on web/Spring-MVC types.
- **Dependency injection via constructors**, not field `@Autowired` (the field injection in `JavaClassAnalyzer` is legacy — don't copy it). Register components with `@Component`.
- **Add a test with every behavioural change.** Domain logic gets a focused unit test (see `PackageMetricsCalculatorTest`, `JavaClassAnalyzerTest`); endpoint/flow changes extend `PackageScannerControllerIT` (MockMvc + `@TempDir` synthetic project). Run `mvn test` before declaring done.
- **Metrics changes must preserve the formulas** unless the change is explicitly about them: `I = Ce/(Ce+Ca)` (0 when both are 0), `A = abstract/total` (0 when total is 0), `D = |A + I − 1|`. If you touch counting, update both the calculator and its test.
- **UI changes** live in `web/src/main/resources/templates/` (`index.html` = shell/styles, `graph.html` = chart + details fragment). Keep the dark theme palette (CSS variables in `index.html`) and the existing element IDs the htmx/Chart.js/D3 scripts depend on (`#result`, `#tabContainer`, `#packageSelect`, `metricsChart`, etc.).
- **Don't break the bytecode contract.** Anything that reads the target project assumes compiled `.class` files — never switch to source parsing for metrics. New exclusion entries go in `application.yaml` (mind the inverted `disabled` flag, above).
- **Verify end-to-end** for non-trivial changes with `/demo` (scan this repo) or `/analyze <path>` (scan an external project) before committing.
- **Branch & PR**: branch off `main`, keep commits scoped, and end commit messages with the `Co-Authored-By` trailer. CI (`.github/workflows/maven.yml`) runs `mvn -B package` on JDK 22 — make sure it passes locally.

## Notes

- `JavaClassAnalyzer` currently contains leftover debug code: unconditional `logger.info` calls in `addDependencyIfNotExcluded`, and a no-op `if (topLevelPackage.contains("repo")) logger.info("test")`. Clean these up if touching that file (or run `/clean-debug`).
