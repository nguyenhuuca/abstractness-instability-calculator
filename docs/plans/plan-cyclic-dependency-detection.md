# Plan: Cyclic Dependency Detection

## Overview

**Status:** In Progress (core complete; integration test gap remaining)
**Author:** nguyenhuuca
**Date:** 2026-06-17
**Beads Issue:** N/A (bd not installed; see task list below)
**Related PRD:** [PRD-cyclic-dependency-detection](../prd/PRD-cyclic-dependency-detection.md)
**Related ADR:** [0001-cyclic-dependency-detection](../adr/0001-cyclic-dependency-detection.md)
**Related Spec:** [spec-cyclic-dependency-detection](../specs/spec-cyclic-dependency-detection.md)

## Objective

Complete the cyclic dependency detection feature: the core algorithm, gate, JSON envelope, and web
UI banner are implemented. The one remaining gap is an integration test that exercises the cycle
path end-to-end with a synthetic cyclic project. Two open enhancements (D3 edge highlighting,
directed edges in JSON) are deferred to future plans.

## Scope

### In Scope

- Integration test: synthetic project with A→B→A class references, verifying `cycles` in JSON and
  in the Thymeleaf view model
- Plan documentation for the complete feature

### Out of Scope

- D3 dependency graph cycle-edge highlighting (deferred; D3 graph itself is not yet built)
- Adding directed edge data to `MetricsExport.cycles` entries (deferred open question)
- Class-level cycle detection (out of scope per PRD)

## Technical Approach

### Architecture — already implemented

```
ProjectModelBuilder (ASM pass)
    └─ PackageMetrics.efferentDependencies[]
           │
           ▼
CycleDetector.buildPackageGraph()     ← first-party package nodes only
           │
           ▼
CycleDetector.cyclesInGraph()         ← Tarjan SCC, static (shared with ArchChecker)
           │
           ▼
AnalysisService.analyze()
    ├─ MetricsExport.withCycles(cycles)     → GET /api/metrics JSON
    ├─ ThresholdEvaluator.evaluate()        → gate.violations (if --no-cycles)
    └─ AnalysisResult.cycles()              → POST /scan → graph.html banner
```

### Key Decisions

| Decision | Rationale |
|----------|-----------|
| Tarjan's SCC, not DFS back-edge detection | Returns cycle members, not just cycle existence; O(V+E) |
| Package-level granularity, not class-level | Consistent with A/I/D metrics; class-level is too noisy |
| Opt-in gate (`--no-cycles`) | Does not break projects with pre-existing cycles on first scan |
| Static `cyclesInGraph()` method | Reusable by `ArchChecker` without duplication |
| Always attach `cycles` to envelope | Consumers can detect acyclicity without activating the gate |

## Implementation Steps

### Phase 1: Core algorithm and pipeline wiring ✅ DONE

- [x] **1.1** `CycleDetector.java` — Tarjan SCC inner class + `buildPackageGraph()` + `findCycles()` + static `cyclesInGraph()`
  - Files: `core/src/main/java/.../domain/CycleDetector.java`

- [x] **1.2** Wire into `AnalysisService.analyze()` — call `cycleDetector.findCycles(metrics)` unconditionally and attach via `withCycles(cycles)`
  - Files: `core/src/main/java/.../application/AnalysisService.java`

- [x] **1.3** `MetricsExport.cycles` field + `withCycles()` wither
  - Files: `core/src/main/java/.../domain/MetricsExport.java`

- [x] **1.4** `GateConfig.noCyclesEnabled` + `GateProperties.noCycles = ToggleGate(false)` (default off)
  - Files: `core/src/main/java/.../domain/GateConfig.java`, `GateProperties.java`

- [x] **1.5** `ThresholdEvaluator` — gate check for `noCyclesEnabled`, adds `GateResult.Violation` per cycle
  - Files: `core/src/main/java/.../domain/ThresholdEvaluator.java`

- [x] **1.6** `CheckConfigLoader` — layer `gate.no-cycles.enabled` from `aic-check.yaml` + `--no-cycles` CLI override
  - Files: `core/src/main/java/.../config/CheckConfigLoader.java`

- [x] **1.7** `CliMain` — parse `--no-cycles` flag into `Overrides.noCycles`
  - Files: `core/src/main/java/.../cli/CliMain.java`

- [x] **1.8** `ArchChecker` — reuse `CycleDetector.cyclesInGraph()` for component cycle detection
  - Files: `core/src/main/java/.../domain/arch/ArchChecker.java`

### Phase 2: Web UI ✅ DONE

- [x] **2.1** `graph.html` cycle banner — conditional render when `${cycles != null and !cycles.empty}`, format `pkgA → pkgB → pkgA`
  - Files: `web/src/main/resources/templates/graph.html`
  - CSS: uses existing `.cycle-banner` / `.cycle-banner-title` / `.cycle-list` classes

### Phase 3: Unit tests ✅ DONE

- [x] **3.1** `CycleDetectorTest` — five cases: 2-node cycle, 3-node cycle, acyclic DAG, mixed cyclic+acyclic, external-dep ignored
  - Files: `core/src/test/java/.../domain/CycleDetectorTest.java`

### Phase 4: Integration test gap ⬜ TODO

- [ ] **4.1** Add integration test in `PackageScannerControllerIT` that builds a synthetic project with two packages that mutually depend on each other (A references class in B; B references class in A) using ASM bytecode generation.

  **Test outline:**
  ```java
  @Test
  void scanDetectsCyclicDependencies() {
      // emit TestApplication.class (@SpringBootApplication) in root package
      // emit com.example.domain.DomainClass.class with reference to com.example.service.ServiceClass
      // emit com.example.service.ServiceClass.class with reference to com.example.domain.DomainClass
      // POST /scan?path=<tempDir>
      // assert JSON $.cycles has size 1
      // assert JSON $.cycles[0] contains "com.example.domain" and "com.example.service"
  }
  ```

  - Files: `web/src/test/java/.../infrastructure/PackageScannerControllerIT.java`
  - Pattern: follow existing `createTestProjectStructure()` — emit `.class` via ASM `ClassWriter`,
    add field/method references to `methodVisitor.visitFieldInsn()` or `visitMethodInsn()` to inject
    cross-package class references
  - Also assert: POST /scan (Thymeleaf) model attribute `cycles` is non-empty (check banner text
    appears in response HTML)

- [ ] **4.2** Run `mvn -B verify` to confirm `PackageScannerControllerIT` passes with the new test.

## Files to Modify

| File | Action | Description |
|------|--------|-------------|
| `core/src/main/java/.../domain/CycleDetector.java` | ✅ Exists | Tarjan SCC + graph builder |
| `core/src/main/java/.../application/AnalysisService.java` | ✅ Exists | Wires cycle detector into pipeline |
| `core/src/main/java/.../domain/MetricsExport.java` | ✅ Exists | `cycles` field + `withCycles()` |
| `core/src/main/java/.../domain/GateConfig.java` | ✅ Exists | `noCyclesEnabled` flag |
| `core/src/main/java/.../domain/GateProperties.java` | ✅ Exists | `noCycles = ToggleGate(false)` |
| `core/src/main/java/.../domain/ThresholdEvaluator.java` | ✅ Exists | Gate evaluation |
| `core/src/main/java/.../config/CheckConfigLoader.java` | ✅ Exists | YAML + CLI override layering |
| `core/src/main/java/.../cli/CliMain.java` | ✅ Exists | `--no-cycles` flag |
| `core/src/main/java/.../domain/arch/ArchChecker.java` | ✅ Exists | Reuses `cyclesInGraph()` |
| `web/src/main/resources/templates/graph.html` | ✅ Exists | Web UI banner |
| `core/src/test/java/.../domain/CycleDetectorTest.java` | ✅ Exists | Unit tests (5 cases) |
| `web/src/test/java/.../infrastructure/PackageScannerControllerIT.java` | **Modify** | Add cyclic project integration test |

## Dependencies

### Code Dependencies

| Library | Already in project | Purpose |
|---------|--------------------|---------|
| Java standard library (`java.util.*`) | Yes | Graph structures (HashMap, ArrayDeque, etc.) |
| ASM (`org.ow2.asm`) | Yes | Used by `ProjectModelBuilder`; IT test emits `.class` via `ClassWriter` |
| JUnit 5 + AssertJ | Yes | Unit tests |
| Spring Boot Test + MockMvc | Yes | `PackageScannerControllerIT` |

No new dependencies required.

## Testing Strategy

### Unit Tests — ✅ Complete

| Component | Test Cases | Status |
|-----------|------------|--------|
| `CycleDetector` | 2-node cycle, 3-node cycle, acyclic DAG, mixed graph, external-dep exclusion | ✅ Done |

### Integration Tests

| Scenario | Expected Outcome | Status |
|----------|------------------|--------|
| Acyclic synthetic project | `$.cycles` is `[]` in JSON | ✅ Exists (`testExportMetricsJson`) |
| **Cyclic synthetic project (A↔B)** | `$.cycles` has 1 group containing both packages; Thymeleaf model `cycles` non-empty | ⬜ TODO |
| `--no-cycles` gate via JSON (CLI mode) | Gate violation for `circularDependency` in `$.gate.violations` | ⬜ TODO (lower priority) |

### Manual Testing

- [ ] Build: `mvn clean package`
- [ ] Run CLI against a project known to have cycles: `java -jar core/target/aic-cli.jar --scan=<path> --no-cycles`; confirm exit `1` and cycles in JSON output
- [ ] Run web UI: `java -jar web/target/aic-web.jar`; scan a cyclic project; confirm red banner appears with cycle text
- [ ] Scan acyclic project; confirm no banner rendered

## Rollback Plan

All changes are additive; the `cycles` field is already present in `MetricsExport` and defaults to
`null` in `MetricsExport.from()` before `withCycles()` is called. Reverting the integration test
addition is a single file change. No schema or API contract changes are involved.

## Risks

| Risk | Mitigation |
|------|------------|
| ASM bytecode generation in IT test is complex | Follow existing `createTestProjectStructure` pattern exactly; emit minimal valid `.class` files |
| Recursive Tarjan stack overflow on very deep graphs | Package graphs are small (< 100 nodes); acceptable for current scope |
| `@JsonInclude(NON_NULL)` omits `cycles` if somehow `null` | `withCycles()` always sets a non-null list; `findCycles()` returns `Collections.unmodifiableList` (never null) |

## Beads Task List

> **Note:** `bd` CLI is not installed in this environment. Tasks below are written in Beads
> format for future tracking when `bd` is available, or can be tracked via GitHub Issues.

```
# Epic
bd create --title="Cyclic Dependency Detection" --type=feature --priority=2

# Remaining task
bd create --title="Add PackageScannerControllerIT test for cyclic project" --type=task
  Acceptance: POST /scan with A↔B cyclic project returns cycles list in JSON and Thymeleaf model

# Future enhancements (deferred)
bd create --title="D3 cycle-edge visualization in dependency graph" --type=enhancement
bd create --title="Include directed edges in MetricsExport.cycles entries" --type=enhancement
```

## Checklist

### Before Starting Integration Test

- [x] PRD, ADR, Spec approved
- [x] Core implementation done (`mvn test` passes)
- [x] `CycleDetectorTest` all 5 cases passing

### Before PR (for Step 4.1)

- [ ] `PackageScannerControllerIT` new test passes locally
- [ ] `mvn -B verify` passes (includes `*IT` via failsafe plugin)
- [ ] No regressions in existing IT tests

### Before Merge

- [ ] Code review
- [ ] `mvn -B verify` passes in CI (JDK 22)

## Notes

The D3 "dependency graph" tab referenced in `CLAUDE.md` and `docs/web-ui.md` is not yet
implemented in `graph.html` — the current UI uses Chart.js for the scatter plot only. The open
question about D3 cycle-edge highlighting is therefore blocked until D3 is added.

---

## Progress Log

| Date | Update |
|------|--------|
| 2026-06-17 | Core algorithm, gate, JSON envelope, web banner, and unit tests confirmed complete. Integration test gap identified via codebase exploration. Plan created. |
