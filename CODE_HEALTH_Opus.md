# Codebase Health Report — `aic` (Opus 4.8, verified pass)

**Date:** 2026-06-20
**Reviewer:** Opus 4.8 — every finding below was checked against the actual source, not taken on trust.
**Health Score:** B (solid architecture; debt is localized and mostly mechanical to clear)
**Confirmed defects:** 1 · **Total actionable issues:** 22 · **False positives caught:** 1

---

## Resolution status (updated 2026-06-20)

Fixes applied in one pass; verified with `mvn -B verify` (158 core + 20 web IT tests green, 1 OS-skipped)
and a CLI self-scan **JSON-contract diff** that is byte-identical before/after (no metric or
`MetricsExport` shape drift). Dead-code claims were first proven at runtime on a real project
(`D:\DO\assessment\api`) via temporary instrumentation — zero hits — then the code was removed.

**Fixed:** C1 NPE guard (+test) · all dead code removed (`findPackages`, `calculateMetrics(Path)`,
`analyzeClasses`, `buildClassDependencyGraph`/`analyzeProject`) · `JavaClassAnalyzer` →
`SpringBootAnnotationScanner` (SRP) · DIP (concrete field dropped) · LSP
(`CommonPrefixRootPackageResolver` catches I/O) · inverted flag `disabled`→`enabled` ·
`RawModuleMetrics` record (data clumps) · `PackageMetrics.of(...)` factory (feature envy) ·
`ignoreUnmatched` now enforced (+tests) · `toRule` enum dispatch · decompositions
(`analyzeClassFile`, `ThresholdEvaluator.evaluate`, `ArchSpec.fromYaml`, `CheckConfigLoader.resolve`) ·
`CycleDetector` prefix-scan tidy · consistency (constructor injection, `.toList()`, imports,
manifest-derived version, field rename) · `TestProjectFixtures` (D1) · stale CLAUDE.md note removed.

**Deliberately deferred (engineering judgment — would conflict with this repo's own rules):**
- **`PackageMetrics` → record** — user-approved skip; the bean is in the frozen `MetricsExport` JSON
  contract. Feature-Envy addressed via factory instead.
- **`CheckRunner` SPI (OCP)** — `AnalysisResult`/`MetricsExport` carry *typed per-check fields* and the
  JSON shape is frozen, so a generic SPI can't make checks additive without a contract change; a naive
  version would also double-compute `classInfos`. Net: machinery without the OCP payoff.
- **`ExclusionConfig` interface (ISP)** — clean version needs `PackageListConfig` promoted to a
  top-level type; churn/risk outweighs the gain for one consumer.
- **`MetricsExport` `with*` wither dedup** — incidental, records make withers inherently verbose, and
  the envelope is contract-sensitive.

These four are available on request.

> This report supersedes the Sonnet-driven `CODE_HEALTH.md`. Where the two differ, the
> discrepancy is called out explicitly under **Corrections to the prior report**.

---

## Executive Summary

The layered design (`domain` → `application` → `infrastructure`, Spring-free core) is clean and
consistently applied. Real debt clusters in four places:

1. **One genuine NPE** — `CheckConfigLoader` crashes on an empty `aic-check.yaml`.
2. **A dead `JavaClassAnalyzer` path** kept alive only by tests, dragging a concrete dependency
   into `PackageMetricsCalculator`.
3. **An inverted boolean flag** (`disabled == true` means "active") — confusing but documented.
4. **A silently-ignored arch option** (`ignoreUnmatched`) that is parsed, stored, and never read.

The bytecode-extraction core (`ProjectModelBuilder`) is well-factored; its one long method is the
top complexity item but is low-risk.

---

## Corrections to the Prior Report

| Prior claim | Verdict | Evidence |
|-------------|---------|----------|
| "Debug leftovers — unconditional `logger.info` in `addDependencyIfNotExcluded` and `if (topLevelPackage.contains("repo")) logger.info(\"test\")`" (repeated from CLAUDE.md) | **FALSE POSITIVE** | `addDependencyIfNotExcluded` now lives in `ProjectModelBuilder.java:203-207` and is clean. A repo-wide grep for `logger.info` / `contains("repo")` / `printStackTrace` finds **none** in `core/src/main`. The only `System.out` is the intentional, documented JSON emit in `CliMain.java:65`. **The CLAUDE.md "Notes" section is itself stale and should be deleted.** |
| Dead code: `findPackages`, `calculateMetrics(Path,…)`, `analyzeClasses` | **CONFIRMED — but test-only, not unreferenced** | Each has live *test* callers (`ProjectPathTraverserTest`, `PackageMetricsCalculatorTest:43`, `JavaClassAnalyzerTest:80,118`). Safe to remove, but the tests must be migrated/deleted in the same change — not a silent delete. |
| `ArchSpec.ignoreUnmatched` stored but never read | **CONFIRMED** | Getter `ignoreUnmatched()` (`ArchSpec.java:77`) has zero callers. `ArchChecker.java:34` `continue`s on unmatched sources unconditionally with a comment claiming "ignoreUnmatched applies" — the flag has no effect. |

---

## Confirmed Defects

| # | Finding | File:Line | Verification | Action |
|---|---------|-----------|--------------|--------|
| **C1** | **NPE on empty `aic-check.yaml`** | `CheckConfigLoader.java:60-61`, `197-207` | `parse()` returns `asMap(null)` → `null` for an empty file; `resolve()` then calls `root.get("gates")` on that `null`. Traced by hand — genuine crash. | In `parse()`, return `Map.of()` (or guard in `resolve()`) when `root == null`. Add a test with an empty `aic-check.yaml`. |

---

## DRY Violations

| # | Files:Lines | Pattern | Severity | Action |
|---|-------------|---------|----------|--------|
| D1 | `PackageScannerControllerIT.java` vs `PackageScannerE2EIT.java` | `emitDefaultConstructor` / `writeClassToFile` byte-for-byte identical; `createCyclicProjectStructure` already **diverged** (one derives the cyclic class name from the path, the other takes it as a parameter) — a latent bug if either is edited alone | **High** | Extract a shared `TestProjectFixtures` helper in `web/src/test`. The divergence makes this the highest-value DRY fix. |
| D2 | `PackageMetricsCalculator.java:34-43` + `:52-61` | The two `calculateMetrics` overloads share an identical 5-map init block, then both funnel into `compute`. The `Path` overload exists only to call the dead `analyzeClasses`. | Med | Delete the `Path` overload (see Dead Code DC2); the duplication disappears with it. |
| D3 | `AnalysisService.create()` vs `JavaClassAnalyzer` ctor | Both wrap `new ProjectModelBuilder(new DependencyExclusions(props))` from the same props | Low | Once the dead path is removed, only one site needs the builder. Resolves naturally with DC2. |

---

## SOLID Violations

| Principle | File:Line | Description | Severity |
|-----------|-----------|-------------|----------|
| **DIP** | `PackageMetricsCalculator.java:23-26` | Holds a concrete `JavaClassAnalyzer` field used **only** by the dead `Path` overload. The live path (`AnalysisService.java:107`) never touches it. Dead concrete coupling. | Med — vanishes with DC2 |
| **LSP** | `CommonPrefixRootPackageResolver` | Can propagate `IllegalStateException` from `modelBuilder.build()` while the sibling resolvers (`Explicit`, `SpringBoot`) return `null` on failure, breaking the chain's "return null when I can't decide" contract | Med |
| **SRP** | `JavaClassAnalyzer.java:25-101` | Two unrelated jobs: source-text `@SpringBootApplication` detection (still used) + bytecode-view delegation (now dead/test-only). Once the delegators go, rename the survivor to `SpringBootAnnotationScanner`. | Med |
| **OCP** | `AnalysisService` (`runChecks`) | Each new check type (arch/banned/dead-code) is added by editing the orchestrator with another `if` block rather than registering a `CheckRunner` | Med (design, not a bug) |
| **SRP** | `CheckConfigLoader.java:50-217` | Mixes YAML I/O, raw-map→domain mapping, and three-layer merging | Low |
| **ISP** | `InstabilityCalculatorProperties` | Mutable `@ConfigurationProperties` JavaBean leaks into domain `DependencyExclusions`, which only needs read access | Low |

---

## Code Smells

| Smell | Location | Severity | Suggestion |
|-------|----------|----------|------------|
| **Inverted boolean flag** — `disabled == true` means "filter ACTIVE" | `InstabilityCalculatorProperties.java:53-58`, `DependencyExclusions.java:26,33,40`, `Defaults.java:46` | **High** | Rename to `enabled` (default `true`), invert the three `!isDisabled()` guards. Verified the inversion is real and load-bearing — the `Defaults.java:46` comment even apologizes for it. |
| **Data Clumps** — the 5 maps (`outgoing`, `incoming`, `abstract`, `total`, `complexity`) travel together through 4 signatures (`MetricsAggregator.aggregate` takes 7 params) | `PackageMetricsCalculator.java:34-84`, `MetricsAggregator`, `JavaClassAnalyzer.analyzeClasses` | **High** | Introduce a `RawModuleMetrics` record; cuts every signature roughly in half and unblocks the `PackageMetrics` cleanup. |
| **Long Method** — `analyzeClassFile` (~58 lines, CC ≈ 12, nesting 4) | `ProjectModelBuilder.java:87-146` | Med | Extract `extractClassLevelRefs(ClassNode, Set)` and a per-method `extractMethodData`. Top complexity item, but well-tested and low-risk. |
| **Mutable JavaBean in domain** — `PackageMetrics` has 14 setters; every sibling domain type is a record | `PackageMetrics.java` | Med | Convert to a record + `PackageMetrics.of(...)` factory; folds in the `computeMetrics` setter chain (`PackageMetricsCalculator.java:97-117`). |
| **Switch-by-null-check** — 3-branch `if (rm.get("method") != null) … else if … class … else if … package` | `CheckConfigLoader.java:159-181` | Low | `BannedApiRule.Kind` is already an enum; give each constant a `yamlKey()` and iterate. |
| **Long parse method** — `ArchSpec.fromYaml` (~56 lines, 4 sequential parse blocks, unchecked casts) | `ArchSpec.java:87-143` | Low | Split into `parseComponents/parseAccess/parseForbidden/parseNaming`. Cosmetic; behavior is correct. |

---

## Dead Code (all verified test-only, safe to remove with their tests)

| # | File:Line | Status | Action |
|---|-----------|--------|--------|
| DC1 | `ProjectPathTraverser.findPackages` (`:29-39`) | Only callers are `ProjectPathTraverserTest` | Remove method + its 3 tests |
| DC2 | `PackageMetricsCalculator.calculateMetrics(Path,…)` (`:34-43`) | Only caller is `PackageMetricsCalculatorTest:43`; production uses the `ProjectModel` overload | Remove overload, the `javaClassAnalyzer` field, and migrate the test to the `ProjectModel` overload |
| DC3 | `JavaClassAnalyzer.analyzeClasses` (`:75-84`) | Only callers are DC2 + `JavaClassAnalyzerTest` | Remove once DC2 lands |
| DC4 | `JavaClassAnalyzer.buildClassDependencyGraph` / `analyzeProject` (`:90-100`) | Production uses `ProjectModel.classDependencyGraph` / `classInfos` directly; these serve only `JavaClassAnalyzerTest` | Migrate tests to `ProjectModel`, then remove; rename the survivor to `SpringBootAnnotationScanner` |
| DC5 | `ArchSpec.ignoreUnmatched` field + getter (`:32,77`) | Parsed & stored, **never read**; `ArchChecker:34` ignores unmatched sources unconditionally | Either honor the flag in `ArchChecker` or delete field + getter + YAML option + docs |

---

## Consistency Issues

| Area | Finding | File:Line | Recommendation |
|------|---------|-----------|----------------|
| Stale doc | CLAUDE.md "Notes" section describes debug code that no longer exists | `CLAUDE.md` | Delete the stale note (see Corrections) |
| DI style | `PackageScannerController` uses field `@Value` for `toolVersion` while everything else is constructor-injected | `PackageScannerController.java:25-26` | Move to constructor; wire real version from `application.yaml` |
| Stream idiom | `ProjectPathTraverser` uses `Collectors.toList()`; rest of codebase uses `.toList()` | `ProjectPathTraverser.java:22,34` | Switch to `.toList()`, drop the import |
| Imports | Inline `new java.util.HashSet<>()` / `java.util.ArrayList<>()` instead of importing | `MetricsAggregator`, `ProjectModel` | Add imports |
| Version drift | `CliMain.TOOL_VERSION = "1.0-SNAPSHOT"` hard-coded, diverges from `pom.xml` each release | `CliMain.java` | Read from `MANIFEST.MF` implementation version |

---

## Complexity Hotspots

| File | Method | Est. CC | Lines | Action |
|------|--------|---------|-------|--------|
| `ProjectModelBuilder.java` | `analyzeClassFile` | ~12 | 58 | Extract class-level + per-method helpers (highest in codebase) |
| `ThresholdEvaluator.java` | `evaluate` | ~10 | 54 | Extract one private method per gate |
| `ArchSpec.java` | `fromYaml` | ~8 | 56 | Extract per-section parsers |
| `CheckConfigLoader.java` | `resolve` | ~6 | 32 | Extract `applyCliOverrides` |
| `CycleDetector.java` | `buildPackageGraph` | ~4 | 16 | `owningPackage` does an O(n) prefix scan per dependency — precompute a prefix→package map for large projects |

---

## Prioritized Remediation Plan

**Tier 1 — correctness & cheap clarity (do now):**
1. **C1** — fix the empty-YAML NPE in `CheckConfigLoader` (+ test).
2. **Delete the stale CLAUDE.md debug note** — it has misled at least one prior audit.
3. **Rename `disabled` → `enabled`** across `InstabilityCalculatorProperties` / `DependencyExclusions` / `Defaults` / `application.yaml` (mechanical, removes a standing trap).

**Tier 2 — remove dead weight (one coherent change):**
4. **DC2 + DC3 + D2 + DIP** — drop `calculateMetrics(Path,…)`, `analyzeClasses`, and the `javaClassAnalyzer` field; migrate the one test. This single change clears a DRY, a DIP, and two dead methods at once.
5. **DC1** — remove `findPackages` + tests.
6. **DC5** — decide `ignoreUnmatched`: honor it in `ArchChecker` or delete it (don't leave a lying flag).

**Tier 3 — structural polish (as capacity allows):**
7. `RawModuleMetrics` record → kills the 5-map clump → unblocks `PackageMetrics` → record.
8. Decompose `analyzeClassFile`, `ThresholdEvaluator.evaluate`, `ArchSpec.fromYaml`.
9. `CommonPrefixRootPackageResolver` — catch I/O exception, return `null` (LSP).
10. `CheckRunner` interface for OCP; `D1` test fixture extraction; `@Value` → constructor injection.
