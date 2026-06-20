# Codebase Health Report — `aic`

**Date:** 2026-06-20
**Health Score:** B
**Critical Issues:** 1
**Total Issues:** 31

---

## Executive Summary

The codebase has a clean layered architecture and consistent Spring-free domain. The main debt concentrates in four areas:

1. A `JavaClassAnalyzer` compatibility facade that has outlived its purpose and left behind dead production code paths.
2. A 5-map parameter clump that ripples through the metric calculation layer.
3. An inverted boolean flag (`disabled = true` means "active") documented as confusing in CLAUDE.md but never fixed.
4. Test fixture helpers duplicated verbatim across 2–3 test classes.

One genuine defect: `CheckConfigLoader` will NPE on an empty `aic-check.yaml`.

---

## Critical / Defects

| # | Finding | File:Line | Action |
|---|---------|-----------|--------|
| C1 | **NullPointerException on empty `aic-check.yaml`** — `Yaml().load()` returns `null` for an empty file; `asMap(root)` then dereferences it | `core/.../config/CheckConfigLoader.java:198–205` | Add null-guard after `Yaml().load(in)`: return early when `root == null` |

---

## DRY Violations (MUST-FIX)

| # | Files:Lines | Pattern | Action |
|---|-------------|---------|--------|
| D1 | `AnalysisService.java:77–78` vs `JavaClassAnalyzer.java:33–34` | `ProjectModelBuilder` constructed identically from the same `props` in two places; adding a constructor param requires two edits | Have `JavaClassAnalyzer` accept an externally-built `ProjectModelBuilder` from `AnalysisService.create()` |
| D2 | `PackageMetricsCalculator.java:34–43` + `JavaClassAnalyzer.analyzeClasses` | Dead `calculateMetrics(Path, ModuleResolver)` overload causes a second `ProjectModelBuilder.build()` pass if called; `AnalysisService` never calls it | Remove the `Path` overload and the `javaClassAnalyzer` field; migrate `PackageMetricsCalculatorTest` to the `ProjectModel` overload |
| D3 | `PackageScannerControllerIT.java:174–236` vs `PackageScannerE2EIT.java:208–286` | `emitDefaultConstructor`, `writeClassToFile`, `createTestProjectStructure`, `createCyclicProjectStructure` copy-pasted verbatim; cyclic-class name derivation already diverged between the two copies | Extract to a `TestProjectFixtures` utility class in `web/src/test/` |

---

## SOLID Violations

| Principle | File:Line | Description | Severity |
|-----------|-----------|-------------|----------|
| **SRP** | `JavaClassAnalyzer.java:33–101` | Two responsibilities: `@SpringBootApplication` source scanning (for `SpringBootRootPackageResolver`) and bytecode bridge delegation (for `PackageMetricsCalculator`). These change for different reasons. | Med |
| **SRP** | `AnalysisService.java:76–83` | `create()` factory embeds full object-graph assembly inside the orchestrator class | Med |
| **SRP** | `CheckConfigLoader.java:50–217` | YAML parsing, domain-object mapping, and config layering mixed in one class | Low |
| **OCP** | `AnalysisService.java:117–135` | Each new check type (arch, bannedApis, deadCode) requires opening `analyze()` and adding another `if` block | Med — introduce `CheckRunner` interface |
| **LSP** | `CommonPrefixRootPackageResolver.java:23–51` | Propagates `IllegalStateException` on I/O error while the other two resolvers return `null`; breaks the chain's failure contract | Med — catch and return `null` |
| **DIP** | `PackageMetricsCalculator.java:23–25` | Holds a concrete `JavaClassAnalyzer` field that is dead weight on the hot path (only used by the dead `Path` overload) | Med — removed when D2 is fixed |
| **ISP** | `InstabilityCalculatorProperties.java:10–61` | Mutable `@ConfigurationProperties` JavaBean leaks into `DependencyExclusions`; domain only needs read access | Low — extract `ExclusionConfig` read-only interface |

---

## Code Smells (High/Medium)

| Smell | Location | Severity | Action |
|-------|----------|----------|--------|
| **Data Clumps** — 5-map group (`outgoing`, `incoming`, `abstract`, `total`, `complexity`) appears as parameter set in 3 places | `PackageMetricsCalculator.java:35–77`, `MetricsAggregator.java:26–27` | **High** | Extract `RawModuleMetrics` record; reduces `MetricsAggregator.aggregate` from 7 params to 3 |
| **Inverted boolean flag** — `disabled = true` means "active"; documented confusion in CLAUDE.md | `InstabilityCalculatorProperties.java:41–54`, `DependencyExclusions.java:26,32,39`, `Defaults.java:46` | **High** | Rename to `enabled`, invert checks in `DependencyExclusions`, update `application.yaml` |
| **Long Method** — `analyzeClassFile` 58 lines, CC 12, nesting depth 4 | `ProjectModelBuilder.java:87–146` | **High** | Extract `detectFlags(ClassNode)`, `collectFieldRefs(ClassNode, Set)` helpers |
| **Mutable JavaBean in domain** — `PackageMetrics` has 14 setters; every other domain type is a record | `PackageMetrics.java:5–67` | Med | Convert to a record with a factory method |
| **Feature Envy** — `computeMetrics` does 10 setter calls to build `PackageMetrics` | `PackageMetricsCalculator.java:97–117` | Med | Move to `PackageMetrics.of(pkg, ce, ca, ...)` factory |
| **Duplicated `with*` builders** — 5 wither methods each repeat the full 11-arg constructor | `MetricsExport.java:36–58` | Med | Extract a private `copy(field, value)` helper |
| **Switch-by-null-check** — 3 `else if (rm.get("x") != null)` branches for `BannedApiRule.Kind` | `CheckConfigLoader.java:159–181` | Med | Give each `Kind` constant a `yamlKey()` method, iterate `values()` |
| **`ArchSpec.fromYaml`** — CC 8, 56 lines, 4 nesting levels, 4 unchecked casts | `ArchSpec.java:87` | Med | Extract `parseComponents`, `parseAccess`, `parseForbidden`, `parseNaming` |
| **`ThresholdEvaluator.evaluate`** — CC ~10, 54 lines, 5 sequential gate blocks | `ThresholdEvaluator.java:17` | Med | Extract per-gate private methods; `evaluate` becomes a 6-line orchestrator |

---

## Dead Code

| Type | File:Line | Description | Action |
|------|-----------|-------------|--------|
| Dead method | `ProjectPathTraverser.java:29–39` | `findPackages(Path)` has no production callers | Remove |
| Dead method | `PackageMetricsCalculator.java:34–43` | `calculateMetrics(Path,...)` overload — see D2 | Remove (part of D2) |
| Dead method | `JavaClassAnalyzer.java:74–84` | `analyzeClasses` only called from the dead overload above | Remove (part of D2) |
| Dead facade methods | `JavaClassAnalyzer.java` | `buildClassDependencyGraph`, `analyzeProject` called only from `JavaClassAnalyzerTest`, not production; `AnalysisService` uses `ProjectModel` directly | Migrate tests; rename surviving source-scanning logic to `SpringBootAnnotationScanner` |
| Unread field | `ArchSpec.java:32` | `ignoreUnmatched` stored from YAML but `ArchChecker` never reads it — flag silently has no effect | Enforce in `ArchChecker` or remove field + YAML key |
| Dead constant | `CliMain.java:29` | `TOOL_VERSION = "1.0-SNAPSHOT"` hard-coded, diverges silently on each release | Read from `MANIFEST.MF` implementation version |

---

## Consistency Issues

| Area | Finding | File:Line | Recommendation |
|------|---------|-----------|----------------|
| Constructor injection | `PackageScannerController` uses `@Value` field injection for `toolVersion`, inconsistent with the rest | `PackageScannerController.java:25–26` | Move to constructor param; wire real version from `application.yaml` |
| Imports | `MetricsAggregator` uses `new java.util.HashSet<>()` inline; `ProjectModel` uses `java.util.ArrayList` inline | `MetricsAggregator.java:50,52`, `ProjectModel.java:66` | Add proper imports |
| Stream idioms | `ProjectPathTraverser` uses `Collectors.toList()`; rest of codebase uses `.toList()` (Java 16+) | `ProjectPathTraverser.java:22,34` | Replace and remove unused `Collectors` import |
| Debug leftovers | Unconditional `logger.info` in `addDependencyIfNotExcluded` and `if (topLevelPackage.contains("repo")) logger.info("test")` — noted in CLAUDE.md as unresolved | `JavaClassAnalyzer.java` / `ProjectModelBuilder.java` | Remove (or run `/clean-debug`) |
| Field naming | `AnalysisService.springBootResolver` holds a resolver chain step, not the full chain | `AnalysisService.java:51` | Rename to `rootPackageResolver` |

---

## Complexity Hotspots

| File | Method | Est. CC | Lines | Action |
|------|--------|---------|-------|--------|
| `ProjectModelBuilder.java` | `analyzeClassFile` | 12 | 58 | Highest in codebase — extract helpers (see Code Smells) |
| `ThresholdEvaluator.java` | `evaluate` | ~10 | 54 | Extract per-gate methods |
| `ArchSpec.java` | `fromYaml` | 8 | 56 | Extract per-section parsers |
| `CheckConfigLoader.java` | `resolve` | 6 | 32 | Extract `applyCliOverrides` |
| `MetricsAggregator.java` | `aggregate` | — | 7 params | Fix with `RawModuleMetrics` record |
| `CycleDetector.java` | `buildPackageGraph` | 4 | 16 | `owningPackage` O(n) scan per dep — cache as `HashMap<prefix, pkg>` |

---

## Prioritized Remediation Order

### Do first — correctness & documented confusion

1. **C1** — NPE in `CheckConfigLoader` on empty YAML (`CheckConfigLoader.java:198–205`)
2. Rename `disabled` → `enabled` in `InstabilityCalculatorProperties` — trivial, stops ongoing confusion documented in CLAUDE.md
3. **D2** + Dead Code — remove `calculateMetrics(Path,...)` overload, `analyzeClasses`, `findPackages`
4. Debug leftovers — remove unconditional `logger.info` calls (`/clean-debug`)

### Do next — structural debt

5. **D3** — extract `TestProjectFixtures` to stop test fixture drift
6. Introduce `RawModuleMetrics` record — kills 5-map clump (unblocks `PackageMetrics` refactor)
7. Convert `PackageMetrics` to a record + `of(...)` factory
8. Decompose `analyzeClassFile` — CC 12 → ~4
9. **LSP fix** — `CommonPrefixRootPackageResolver` swallows `IllegalStateException`, returns `null`
10. Null-guard in `CheckConfigLoader.parse` (C1 fix)

### Do later — design improvements

11. `CheckRunner` interface for OCP in `AnalysisService`
12. `ArchSpec.fromYaml` extraction (CC 8, 56 lines)
13. `ThresholdEvaluator.evaluate` extraction (CC ~10)
14. Rename `JavaClassAnalyzer` surviving piece to `SpringBootAnnotationScanner`
15. Wire real `app.tool-version` from Maven resource filtering into `application.yaml`
