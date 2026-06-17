# PRD: Cyclic Dependency Detection

## Overview

**Status:** Approved
**Author:** nguyenhuuca
**Date:** 2026-06-17
**Version:** 1.0
**Beads Issue:** N/A
**Stakeholders:** Java/Spring Boot developers, CI/CD pipeline operators

## Problem Statement

Circular dependencies between module packages — where package A depends on B which (transitively)
depends back on A — violate clean layering, make code hard to test and refactor, and indicate
architectural breakdown. Without automated detection, cycles accumulate silently: the compiler
accepts them, tests pass, but the codebase steadily becomes harder to change.

Developers scanning a Java project with `aic` need to know not only where they stand on the
Abstractness/Instability plane but also whether their packages form cycles — because a high
Distance score and circular dependencies are two independent failure modes, each requiring
different remediation.

### Evidence

**Qualitative Evidence:**
- Circular dependencies are a well-documented antipattern in object-oriented design (Robert Martin,
  "Clean Architecture", Ch. 14 — "The Acyclic Dependencies Principle")
- Projects that grow by adding features without enforcing acyclicity often reach a state where no
  single package can be tested or released in isolation, because everything depends on everything
- CI pipelines that only check metrics (Distance gate) miss this class of architectural debt
  entirely without a dedicated cycle gate (`--no-cycles`)

## Goals & Success Metrics

| Goal | Metric | Target |
|------|--------|--------|
| Detect all package-level cycles in one scan pass | Cycles found = true cycles in the graph (no false negatives) | 100% recall on synthetic test cases |
| Zero false positives from external dependencies | External packages (Spring, JDK) never appear in cycle results | 0 false positives in all test cases |
| Surface cycles clearly to developers | Cycles visible in web UI banner and JSON envelope | Banner shown whenever `cycles` list non-empty |
| Enable CI enforcement | CLI exits `1` when `--no-cycles` flag set and cycles found | Verified by `ThresholdEvaluatorTest` |
| No performance impact on single-pass analysis | Cycle detection adds < 5 ms to any project scan | O(V+E) Tarjan's SCC — negligible on package graphs |

## User Stories

### Developer (web UI)

- As a developer, I want to see which packages form circular dependencies after scanning my project
  so that I know which coupling relationships to break first.
  - Acceptance: when cycles exist, a red banner appears above the metrics chart listing each
    cycle as `pkgA → pkgB → pkgA`; no banner is shown when the graph is acyclic.

- As a developer, I want cycles excluded from the Distance-only view so that I can reason about
  Abstractness/Instability and cyclic coupling as separate concerns.
  - Acceptance: the scatter chart and package details render normally regardless of whether cycles
    are present; cycles appear only in the dedicated banner.

### CI/CD pipeline operator

- As a CI engineer, I want the CLI to fail the build when package cycles are detected so that
  cycle-introducing commits are caught before merge.
  - Acceptance: `java -jar aic-cli.jar --scan=<path> --no-cycles` exits `1` when cycles are
    found, `0` when the graph is acyclic.

- As a CI engineer, I want the cycle groups listed in the JSON output so that my pipeline can
  parse and report them in structured form.
  - Acceptance: `GET /api/metrics` and CLI JSON output both contain a `cycles` array; each
    element is a sorted list of package names forming one strongly-connected component.

### Per-project configuration owner

- As a project owner, I want to enable the no-cycles gate in `aic-check.yaml` so that CI
  enforces it without requiring a CLI flag on every invocation.
  - Acceptance: setting `gate.no-cycles: true` in `aic-check.yaml` has the same effect as
    `--no-cycles` on the command line.

## Requirements

### Functional Requirements

| ID | Requirement | Priority | Notes |
|----|-------------|----------|-------|
| FR-1 | Detect all strongly-connected components of size ≥ 2 in the module package dependency graph | Must Have | Tarjan's SCC, O(V+E) |
| FR-2 | Build the package graph from efferent class dependencies extracted during the single ASM pass | Must Have | No second filesystem scan |
| FR-3 | Exclude external dependencies (Spring, JDK, etc.) from the package graph | Must Have | Only first-party module packages are nodes |
| FR-4 | Return each cycle as a sorted, deterministic list of package names | Must Have | Alphabetical within cycle, cycles sorted by first element |
| FR-5 | Expose cycles in the `MetricsExport` JSON envelope (`cycles` field) | Must Have | Additive field; `[]` when acyclic |
| FR-6 | Display a web UI banner listing each cycle when cycles are present | Must Have | Thymeleaf fragment in `graph.html` |
| FR-7 | Fail CLI with exit code `1` when `--no-cycles` flag is set and cycles are detected | Must Have | Implemented in `ThresholdEvaluator` |
| FR-8 | Support `gate.no-cycles: true` in `aic-check.yaml` for per-project gate configuration | Must Have | Layered config via `CheckConfigLoader` |
| FR-9 | Reuse the same Tarjan implementation for architecture-checker cycle detection (`ArchChecker`) | Should Have | `CycleDetector.cyclesInGraph(graph)` is `static` and shared |

### Non-Functional Requirements

| ID | Requirement | Target |
|----|-------------|--------|
| NFR-1 | Performance | Cycle detection must complete within the single analysis pass; < 5 ms additional latency on any real-world project |
| NFR-2 | Correctness | Tarjan's SCC is a textbook algorithm with proven correctness; unit tests cover 2-node, 3-node, mixed acyclic/cyclic, and external-dep cases |
| NFR-3 | Determinism | Same input always produces the same cycle output (alphabetical sort within SCC, SCCs sorted by first element) |
| NFR-4 | Spring-free | `CycleDetector` is a plain Java class in `core/domain` — no Spring types, no web dependencies |

## Scope

### In Scope

- Package-level cycle detection (module packages = direct sub-packages of the root, depth configurable via `analyze.depth`)
- Tarjan's SCC algorithm over the package dependency graph built from efferent class references
- `--no-cycles` CLI flag / `gate.no-cycles` YAML key as a hard CI gate (exit `1`)
- `cycles` array in the `MetricsExport` JSON envelope
- Web UI red banner listing cycle groups (Thymeleaf fragment)
- Static `CycleDetector.cyclesInGraph(graph)` utility reused by `ArchChecker`

### Out of Scope

- **Class-level cycle detection** — only package-level SCCs are reported; individual class-to-class circular references within or across packages are not surfaced
- **Fix suggestions** — the tool reports which packages form a cycle but does not suggest how to break it (no dependency inversion hints, no refactoring proposals)
- **Incremental / watch mode** — cycle detection runs once per scan invocation; no continuous monitoring
- **Visualising the cycle in the D3 dependency graph** — cycles appear in the text banner but are not highlighted as a subgraph in the D3 view (deferred to future enhancement)
- **Self-referential single-package cycles** — SCCs of size 1 (a package depending on itself) are filtered out; they cannot occur via the class-reference extraction currently in use

## Dependencies

| Dependency | Owner | Status | Risk |
|------------|-------|--------|------|
| `ProjectModelBuilder` (single ASM pass) | core/domain | Implemented | Low — cycle detection reads `PackageMetrics.efferentDependencies` built by the existing pass |
| `MetricsExport` JSON envelope | core/domain | Implemented | Low — `cycles` field is already present |
| `ThresholdEvaluator` gate evaluation | core/domain | Implemented | Low |
| `CheckConfigLoader` layered config | core/config | Implemented | Low |
| Thymeleaf `graph.html` fragment | web/templates | Implemented | Low |

## Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| Recursive Tarjan stack overflow on very deep package graphs | Low | Medium | Package graphs are small (tens of nodes); recursive implementation is acceptable. Can be converted to iterative if a pathological project is found. |
| Efferent dependencies include transitively resolved external classes not filtered by exclusion lists | Low | Medium | External packages are excluded by `DependencyExclusions`; the package-graph builder further restricts nodes to the known module package set |
| `--no-cycles` breaks CI for legacy projects with pre-existing cycles | Medium | Low | Gate is opt-in (`false` by default); teams adopt it deliberately |

## Open Questions

- [ ] Should the D3 dependency graph visually highlight cycle edges (e.g. red arrows)? Currently deferred to out-of-scope.
- [ ] Should the JSON envelope include the directed edges within each cycle (not just the node list) to allow consumers to reconstruct the cycle path?

## Appendix

### Implementation location

| Component | File |
|-----------|------|
| Algorithm | `core/src/main/java/.../domain/CycleDetector.java` |
| Test | `core/src/test/java/.../domain/CycleDetectorTest.java` |
| Gate evaluation | `core/src/main/java/.../domain/ThresholdEvaluator.java` |
| Gate config | `core/src/main/java/.../domain/GateConfig.java` |
| Config loading | `core/src/main/java/.../config/CheckConfigLoader.java` |
| Pipeline wiring | `core/src/main/java/.../application/AnalysisService.java` |
| JSON envelope | `core/src/main/java/.../domain/MetricsExport.java` |
| Web UI banner | `web/src/main/resources/templates/graph.html` |
| ArchChecker reuse | `core/src/main/java/.../domain/arch/ArchChecker.java` |

---

## Approval

| Role | Name | Date | Status |
|------|------|------|--------|
| Engineering | nguyenhuuca | 2026-06-17 | Approved |

---

## Next Steps & Handoffs

1. [x] **PRD** — this document
2. [ ] **ADR** — `/architect` → `docs/adr/0001-cyclic-dependency-detection.md`
3. [ ] **Spec** — `/spec` → `docs/specs/spec-cyclic-dependency-detection.md`
4. [ ] **Plan** — `/swarm-plan` → `docs/plans/plan-cyclic-dependency-detection.md`

---

## Version History

| Version | Date | Author | Changes |
|---------|------|--------|---------|
| 1.0 | 2026-06-17 | nguyenhuuca | Initial — documenting existing implementation |
