# Architecture Assessment — `aic` Current System

## Metadata

**Status:** Review · **Date:** 2026-06-20 · **Reviewer:** Principal Architect (Opus 4.8) · **Tags:** architecture, system-review
**Scope:** Whole system (`core` + `web`) as of commit `165d1e1`
**Tech Strategy:** Assessed against `.claude/rules/tech-strategy.md` Golden Paths

> Design assessment only — no implementation code. Findings reference `file:line`. Companion to the
> code-level audits in `CODE_HEALTH_Opus.md`; this document covers **systemic / structural** concerns,
> not local code smells.

---

## 1. Architecture Map (as-built)

```
                          ┌──────────────────────── web (Spring Boot) ────────────────────────┐
  HTTP ── GET / ─────────▶│ PackageScannerController ─┐                                        │
       ── POST /scan ─────▶│   (infrastructure)        │   AnalysisConfig (@Configuration)      │
       ── GET /api/metrics▶│                           │     ├─ @Bean InstabilityCalcProps      │
                          │                            ▼     │     (binds application.yaml)      │
                          │              AnalysisService.create(props) ◀── @Bean                │
                          └────────────────────────────┼──────────────────────────────────────┘
                                                        │ (depends on core only; never reverse)
  CLI ── --scan=path ────▶ CliMain.run ────────────────┤
       (no Spring)                                      ▼
                          ┌──────────────────── core (Spring-free) ───────────────────────────┐
                          │  application/AnalysisService  ── the single orchestration facade   │
                          │      1. CheckConfigLoader.resolve  (defaults < file < CLI flags)   │
                          │      2. ProjectModelBuilder.build  ── ONE ASM pass → ProjectModel  │
                          │      3. resolveRootPackage (Chain: Explicit→SpringBoot→CommonPrefix)│
                          │      4. PackageMetricsCalculator.calculateMetrics(model, resolver)  │
                          │      5. CycleDetector → ThresholdEvaluator (gates, CLI only)        │
                          │      6. runChecks: ArchChecker / BannedApiChecker / DeadCodeDetector│
                          │      7. assemble MetricsExport envelope                             │
                          └────────────────────────────────────────────────────────────────────┘
```

**Module boundary:** `web` → `core`, never the reverse. `core` has zero Spring imports. ✅
**Entry symmetry:** CLI and web both build an `AnalysisRequest` and call `AnalysisService.analyze()`.
The only behavioral difference is `evaluateGates` (CLI `true`, web `false`). ✅

---

## 2. Golden-Path Compliance

| Golden Path rule | Status | Evidence |
|------------------|--------|----------|
| `core` Spring-free; `web` depends on `core` | ✅ | `AnalysisService`, all `domain/*` are plain POJOs; Spring only in `web` |
| Bytecode-only analysis (ASM, never source) for metrics | ⚠️ **mostly** | Metrics path is pure bytecode (`ProjectModelBuilder`). **But** root-package resolution re-reads `.java` **source** (`JavaClassAnalyzer.containsSpringBootApplication`) — a second, source-based traversal. See Finding A. |
| Constructor injection, no field `@Autowired` | ⚠️ | One exception: `PackageScannerController.java:25` uses field `@Value` for `toolVersion` |
| Single orchestration facade (`AnalysisService`) | ✅ | Both entry points delegate to it; `analyze()` is the sole pipeline |
| Single ASM pass | ⚠️ | True for bytecode (`build()` called once, `AnalysisService.java:97`). Root-package step adds an independent filesystem walk. |
| `MetricsExport` additive-only contract | ✅ | Immutable record + `with*` withers (`MetricsExport.java:36-58`) |
| No persistence; stateless per scan | ✅ | By design (tech strategy) |

**Verdict:** Strong adherence. The one substantive divergence is the dual filesystem traversal in
root-package resolution (Finding A).

---

## 3. Systemic Strengths (preserve these)

1. **Single-model derivation.** Every check (metrics, cycles, gates, arch, banned, dead-code) reads
   one `ProjectModel` built by one ASM pass (`AnalysisService.runChecks`). This is the core
   architectural asset — additive checks cost ~zero extra I/O.
2. **Clean facade.** `AnalysisService.analyze()` is the only public seam; CLI/web are thin shells
   (`CliMain.java:58`, `PackageScannerController.java:47`). Easy to add a third consumer.
3. **Config layering is centralized.** `CheckConfigLoader.resolve` owns the defaults < file < flags
   precedence in one place (`CheckConfigLoader.java:50-82`).
4. **Root-package resolution is an SPI.** `ChainedRootPackageResolver` over an ordered list decouples
   the strategy from Spring Boot, enabling plain-Java scans.
5. **Immutable, self-describing export.** `MetricsExport` is the single API contract for both JSON
   endpoint and CLI stdout.

---

## 4. Systemic Findings

### Finding A — `JavaClassAnalyzer` straddles two concerns and forces a second traversal · **Severity: High (structural)**

`JavaClassAnalyzer` now does two unrelated things:
- **Source scanning** for `@SpringBootApplication` (`JavaClassAnalyzer.java:38-72`), used by
  `SpringBootRootPackageResolver` — reads `.java` files.
- **Bytecode delegation** (`analyzeClasses`, `buildClassDependencyGraph`, `analyzeProject`,
  `:75-100`) — now dead in production / test-only (confirmed in `CODE_HEALTH_Opus.md` DC2-DC4).

Two consequences:
1. `AnalysisService.create()` (`:77-80`) instantiates **two** `ProjectModelBuilder`s — one as
   `builder`, one hidden inside the `JavaClassAnalyzer` passed to `PackageMetricsCalculator`. The
   second is never used on the live path.
2. The system already knows entry points from bytecode: `ProjectModelBuilder.ENTRY_ANNOTATION_MARKERS`
   includes `"SpringBootApplication"` and sets `ClassDetail.entryPoint` (`ProjectModelBuilder.java:50-52,138`).
   Yet root-package resolution **ignores that** and re-reads source text instead — a redundant
   filesystem pass that also explains the Golden-Path "bytecode-only" divergence.

**Architectural opportunity:** derive the Spring-Boot root package from the already-built
`ProjectModel` (the entry-point class's package) instead of scanning source. That would let
`SpringBootRootPackageResolver`, `JavaClassAnalyzer`, and `ProjectPathTraverser` be retired entirely,
collapse to a genuine single traversal, and restore full Golden-Path compliance. See the trade-off
matrix in §5.

### Finding B — Check extensibility is closed (OCP) · **Severity: Medium (structural)**

The three optional checks have no shared abstraction. Adding a fourth requires editing four places:
`AnalysisService.runChecks` (`:155-172`), the private `Checks` record (`:148`), the envelope-assembly
block in `analyze()` (`:124-132`), and `MetricsExport`. Each check independently re-derives its view
(`model.classDependencyGraph` / `model.classInfos`). This is acceptable at N=3 but will not scale
cleanly. A `CheckRunner` SPI (`CheckResult run(ProjectModel, String mainPackage, CheckConfig)`) plus a
registered `List<CheckRunner>` would make checks additive without touching the orchestrator.

### Finding C — Web trust boundary: arbitrary host-path scan · **Severity: Medium-High (security/resilience)**

`POST /scan?path=…` and `GET /api/metrics?path=…` (`PackageScannerController.java:45,67`) take a
filesystem path from the client and walk it (`Files.walk` in `ProjectModelBuilder.build`). For the
intended **localhost single-user dev tool** this is fine and is consistent with the "no persistence,
path-per-request" design. **But** there is no path allow-listing, no sandboxing, and the scan is
**synchronous and unbounded** — a large or deep tree blocks an HTTP worker thread for the whole ASM
walk, and the entire `ProjectModel` is held in memory with no class-count ceiling. If this app is ever
exposed beyond localhost, this becomes a path-disclosure + DoS surface. Recommend: document the
localhost-only trust assumption explicitly, and (if multi-user is ever a goal) add a configurable
root-path allow-list, a scan timeout, and a class-count/size cap.

### Finding D — Resolver chain rebuilt per call; field misnamed · **Severity: Low**

`resolveRootPackage` (`AnalysisService.java:139-145`) constructs a fresh `ChainedRootPackageResolver`
on every `analyze()` call, and the held field is named `springBootResolver` though it is only one link
of the chain (`:51`). Minor: the chain is cheap to build, but the naming misleads and the
re-construction obscures that only the Spring-Boot link is injected. Resolves naturally if Finding A
is taken (the Spring-Boot link disappears).

### Finding E — `CheckConfigLoader` is a static utility mixing I/O + mapping + merging · **Severity: Low**

`CheckConfigLoader` (`:31-217`) is `final` with a private ctor and all-static methods. It discovers
the file, parses YAML, maps raw maps to domain objects, and applies the three-layer merge — four
responsibilities, untestable in isolation, and the empty-file path NPEs (`CODE_HEALTH_Opus.md` C1).
Not urgent, but if config grows (more sections), splitting parse from merge and making it an injectable
collaborator would improve testability and align with the DI golden path.

---

## 5. Trade-off Analysis — Finding A remediation (the one decision worth making now)

**Decision:** How to resolve the Spring-Boot root package.

| Option | Single traversal | Golden-path (bytecode-only) | Code to retire | Risk | Effort |
|--------|:---:|:---:|---|---|---|
| **1. Keep source-scan (status quo)** | ❌ (2 walks) | ❌ diverges | none | none | none |
| **2. Derive root pkg from `ProjectModel` entry-point class** | ✅ | ✅ restored | `JavaClassAnalyzer`, `ProjectPathTraverser`, `SpringBootRootPackageResolver`→thin | Low–Med: entry-point detection is annotation-marker based, slightly looser than the source regex (which strips comments/strings); must confirm no false-positive entry classes change the picked package | Med |
| **3. Hybrid — bytecode first, source fallback** | ⚠️ (1 walk usually) | ⚠️ | none | Low | Med |

**Recommendation: Option 2**, gated on a verification step. The `ProjectModel` already carries
`entryPoint` per class; the Spring-Boot resolver can pick the package of the entry-point class (or the
common prefix of entry-point classes when several exist), eliminating the source pass. **Risk to
retire first:** `ENTRY_ANNOTATION_MARKERS` is broad (`Controller`, `Service`, `Component`, …), so
"entry point" ≠ "the `@SpringBootApplication` class". The resolver must specifically key on the
`main`-method flag (`ProjectModelBuilder.isMainMethod`, already captured as `hasMain`) and/or the
`SpringBootApplication` marker, **not** the generic `entryPoint` boolean. This is the single design
detail to nail down before implementing. Recommend a focused ADR + a characterization test on a
multi-entry-point fixture before retiring `JavaClassAnalyzer`.

---

## 6. Prioritized Recommendations

| # | Recommendation | Type | Severity | Depends on |
|---|----------------|------|----------|------------|
| R1 | Fix `CheckConfigLoader` empty-file NPE (C1) | Bug | High | — |
| R2 | Document the **localhost-only trust boundary** for `/scan` in `docs/web-ui.md`; decide whether multi-user is a goal | Design/Security | Med-High | — |
| R3 | ADR: resolve Spring-Boot root package from `ProjectModel` (Option 2) → retire `JavaClassAnalyzer` + `ProjectPathTraverser`, restore single-traversal & bytecode-only golden path | Structural | High | characterization test |
| R4 | Introduce `CheckRunner` SPI to make checks additive (OCP) | Structural | Med | — |
| R5 | If multi-user ever in scope: scan timeout + class-count cap + root-path allow-list | Resilience | Med | R2 decision |
| R6 | Split `CheckConfigLoader` parse vs merge; consider injectable | Maintainability | Low | — |

R1, R3, R4 are the structural backbone. R2 is a **decision the owner must make** (trust model), and it
gates R5 — there is no point hardening the path API if the tool is deliberately localhost-only.

---

## 7. Open Question for the Owner

**Is the web app intended to ever run beyond localhost / single-user?** The current path-per-request
design is correct and safe for a personal dev tool, but unsafe as a shared service. The answer
determines whether Findings C/R5 are "document the assumption" (localhost) or "must fix before deploy"
(multi-user). I did not assume — it changes the whole resilience posture.
