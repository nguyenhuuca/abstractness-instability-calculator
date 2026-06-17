# Feature Specification: Cyclic Dependency Detection

## Metadata

**Status:** Implemented
**Author:** nguyenhuuca
**Date:** 2026-06-17
**Related PRD:** [PRD-cyclic-dependency-detection](../prd/PRD-cyclic-dependency-detection.md)
**Related ADR:** [0001-cyclic-dependency-detection](../adr/0001-cyclic-dependency-detection.md)

---

## Overview

`aic` runs Tarjan's Strongly-Connected-Components algorithm over the module package dependency graph
(derived from efferent class references extracted in the single ASM pass) and returns every SCC of
size ≥ 2 as a named cycle group. Cycles are always included in the JSON envelope and web UI banner;
an opt-in gate (`--no-cycles` / `gate.no-cycles.enabled: true`) causes the CLI to exit `1` when any
cycle is detected, enabling enforcement in CI pipelines.

---

## Business Rules

### Rule 1 — SCC definition

A cycle group is a strongly-connected component (SCC) of size ≥ 2 in the directed package
dependency graph. SCCs of size 1 (a node with no back-edges to itself) are not reported.

### Rule 2 — Package graph construction

The package dependency graph is built from `PackageMetrics.efferentDependencies` (class-level
references already collected during the ASM pass). An edge A→B is added when:
- A class in module package A references a class whose owning module package is B, **and**
- A ≠ B

Only module packages (direct sub-packages of the root, depth-controlled by `analyze.depth`) are
graph nodes. External classes (Spring, JDK, etc.) that do not belong to any module package produce
no graph node and no edge.

### Rule 3 — Owning-package resolution

A class is assigned to its owning module package by longest-prefix match: the module package `P`
such that `className.startsWith(P + ".")` and `P` is the longest such match. If no module package
matches the class, it is silently ignored (contributes no edge).

### Rule 4 — Deterministic output

The output must be fully deterministic:
- Within each cycle group: package names are sorted alphabetically.
- Across cycle groups: groups are sorted by their first element (alphabetically).

### Rule 5 — Gate is opt-in, detection is always-on

Cycle detection always runs and results always appear in the JSON envelope and web UI. The gate
(exit code `1` for CLI) is activated only when `noCyclesEnabled` is `true` in the resolved
`GateConfig`. Default is `false`.

### Rule 6 — Reuse via `cyclesInGraph`

`CycleDetector.cyclesInGraph(Map<String, List<String>>)` is a `public static` method that accepts
any directed graph. It must be used by `ArchChecker` for component-level cycle detection — there
must be no duplicate Tarjan implementation.

---

## Functional Requirements

### FR-1: Run Tarjan's SCC on every scan

The system **must** execute `CycleDetector.findCycles(metrics)` on every invocation of
`AnalysisService.analyze()`, regardless of whether the `--no-cycles` gate is enabled.

### FR-2: Package graph from efferent deps

The package graph **must** be built from `PackageMetrics.getEfferentDependencies()` using
`CycleDetector.buildPackageGraph()`. No second filesystem walk or ASM pass is permitted.

### FR-3: Filter to first-party packages only

External classes (not matching any module package by longest-prefix) **must not** appear as graph
nodes or edges. Only intra-module-package edges are included.

### FR-4: JSON envelope always contains `cycles`

`MetricsExport.cycles` **must** be set (via `withCycles(cycles)`) on every scan. It **must** be an
empty list `[]` when no cycles exist, and a non-empty list of cycle groups when cycles are detected.
It **must not** be `null` in the final envelope returned by `AnalysisService`.

### FR-5: Gate failure on cycles

When `GateConfig.noCyclesEnabled()` is `true` and at least one cycle is detected, `ThresholdEvaluator`
**must** add a `GateResult.Violation` with `gateType = "circularDependency"` for each cycle.
The violation message **must** follow the pattern:
`"Circular dependency between packages: pkgA -> pkgB -> pkgA"`

### FR-6: CLI exit code

When the gate is enabled and violations include `circularDependency`, the CLI **must** exit `1`.
If detection runs but the gate is disabled, the CLI **must** exit `0` (assuming no other gate
violations).

### FR-7: Web UI banner

`graph.html` **must** render a cycle banner when `cycles != null && !cycles.isEmpty()`.
Each cycle **must** be displayed as `pkgA → pkgB → pkgA` (the last element repeating the first).
No banner is rendered when `cycles` is empty.

### FR-8: Per-project gate config

`aic-check.yaml` **must** support `gate.no-cycles.enabled: true` to activate the gate without
requiring the `--no-cycles` CLI flag. Layering: code default (`false`) < `aic-check.yaml` value <
`--no-cycles` CLI flag (always forces `true`).

### FR-9: Architecture checker reuse

`ArchChecker` **must** call `CycleDetector.cyclesInGraph(graph)` (the static method) to find
component cycles. It **must not** contain its own Tarjan implementation.

---

## API Changes

### JSON Envelope — `MetricsExport`

No new fields added. The `cycles` field is pre-existing and already populated.

**`cycles` field contract:**

```json
{
  "cycles": [
    ["com.example.app.domain", "com.example.app.service"],
    ["com.example.app.controller", "com.example.app.repo", "com.example.app.util"]
  ]
}
```

| Property | Type | Nullable | Value when acyclic |
|----------|------|----------|--------------------|
| `cycles` | `List<List<String>>` | No | `[]` (empty array) |
| `cycles[n]` | `List<String>` | — | — (not present) |
| `cycles[n][m]` | `String` | — | Fully-qualified module package name |

**Ordering guarantee:**  
Each inner list is sorted alphabetically. Outer list is sorted by `cycles[n].get(0)`.

### CLI flags

| Flag | Type | Default | Effect |
|------|------|---------|--------|
| `--no-cycles` | boolean toggle | `false` | Enables the cycle gate; CLI exits `1` if any cycle detected |

### CLI error table

| Condition | Exit code | Output |
|-----------|-----------|--------|
| Gate disabled, cycles found | `0` | JSON includes `cycles` list; no gate violation |
| Gate enabled, no cycles | `0` | JSON `cycles: []`; gate passes |
| Gate enabled, ≥ 1 cycle found | `1` | JSON includes `cycles` and `gate.violations` entries |
| Scan error (path invalid, no root pkg) | `2` | JSON `error` field; `cycles` absent |

### Web endpoints — no changes

`POST /scan` and `GET /api/metrics` already return the `cycles` field from `MetricsExport`. No
endpoint signature changes.

---

## Domain Changes

No new domain types introduced. Existing types involved:

| Type | Location | Role |
|------|----------|------|
| `CycleDetector` | `core/domain/CycleDetector.java` | Algorithm + graph construction |
| `CycleDetector.Tarjan` (private) | same | Tarjan SCC inner class |
| `PackageMetrics.getEfferentDependencies()` | `core/domain/PackageMetrics.java` | Input to graph builder |
| `GateConfig.noCyclesEnabled()` | `core/domain/GateConfig.java` | Gate switch |
| `GateProperties.noCycles` | `core/domain/GateProperties.java` | `ToggleGate(false)` default |
| `ThresholdEvaluator` | `core/domain/ThresholdEvaluator.java` | Evaluates gate + builds violations |
| `MetricsExport.cycles` | `core/domain/MetricsExport.java` | Output contract |

---

## Gate / Config Changes

### `aic-check.yaml` schema (per-project file in scanned project)

```yaml
gate:
  no-cycles:
    enabled: false    # default; set true to fail build on any cycle
```

### `GateProperties` default

```java
private ToggleGate noCycles = new ToggleGate(false);  // off by default
```

### `CheckConfigLoader` layering

1. Code default: `GateProperties.noCycles.enabled = false`
2. `aic-check.yaml`: `gate.no-cycles.enabled: true` overrides the default
3. CLI `--no-cycles` flag: always forces `noCyclesEnabled = true` regardless of YAML

---

## UI Changes (Thymeleaf)

### `graph.html` — cycle banner

Rendered by the fragment `graph :: graph` (returned by `POST /scan`).

**Condition to show:** `${cycles != null and !cycles.empty}`

**Rendering:** one `<li>` per cycle, text content is the packages joined by ` → `, with the first
package appended again to close the cycle visually:

```
com.example.app.domain → com.example.app.service → com.example.app.domain
```

**Styling:** uses the existing `.cycle-banner` / `.cycle-banner-title` / `.cycle-list` CSS classes
already defined in `index.html`. No new CSS variables or class names.

### No changes to

- `index.html` shell or dark-theme CSS variables
- `metricsChart` scatter plot
- `#tabContainer`, `#packageSelect`, D3 dependency graph
- Any element IDs relied upon by htmx/Chart.js/D3 scripts

---

## No Database Changes

This tool has no persistence layer. No tables, migrations, or queries.

## No Caching Impact

Each scan is stateless. There is no in-memory cache involved in cycle detection.

---

## Security Requirements

### Authentication / Authorization

- Web: no authentication. The `/scan` endpoint and `/api/metrics` are public (same as all other
  existing endpoints).
- CLI: runs locally as the invoking user; no auth.

### Data Validation

| Input | Rule | Behaviour on violation |
|-------|------|------------------------|
| `--scan=<path>` | Path must exist and contain compiled `.class` files | `AnalysisService` throws `IllegalArgumentException` → exit `2` / `graph :: error` fragment |
| No module packages found | Root package resolved but no sub-packages | `IllegalArgumentException` ("no module sub-packages") → exit `2` |

Cycle detection itself has no additional validation requirements — an empty or single-package
project simply produces `cycles: []`.

---

## Non-Functional Requirements

### Performance

| Operation | Target | Rationale |
|-----------|--------|-----------|
| `CycleDetector.findCycles()` | < 5 ms | Tarjan is O(V+E); package graph has < 100 nodes on any real project |
| Total `AnalysisService.analyze()` latency impact | Negligible | No additional I/O; only in-memory graph traversal |

### Correctness

| Property | Guarantee |
|----------|-----------|
| No false negatives | Tarjan finds ALL SCCs; every package-level cycle is reported |
| No false positives from external deps | Package graph restricted to known module packages; external classes produce no nodes |
| Determinism | Alphabetical sort within SCC + sort by first element across SCCs — same input always gives same output |

---

## Edge Cases

### EC-1: Project with no cycles

**Condition:** Package graph is a DAG (no back-edges).  
**Expected:** `CycleDetector.findCycles()` returns `[]`. `MetricsExport.cycles = []`. No banner
rendered. CLI exits `0` (even if gate is enabled).

### EC-2: Single module package

**Condition:** Project has exactly one module package (no inter-package edges possible).  
**Expected:** Package graph has one node and no edges. `findCycles()` returns `[]`. No banner.

### EC-3: All packages in one large cycle

**Condition:** Every module package (transitively) depends on every other — one SCC containing all N
packages.  
**Expected:** One cycle group in output containing all N packages, sorted alphabetically.

### EC-4: Multiple disjoint cycles

**Condition:** Graph has two independent SCCs of size ≥ 2 (e.g. A↔B and C↔D).  
**Expected:** Two cycle groups returned. Outer list sorted by first element of each group.

### EC-5: External-only efferent dependencies

**Condition:** A module package references only Spring/JDK classes (no other module packages).  
**Expected:** No edges added to the package graph for those references. `findCycles()` unaffected.

### EC-6: Gate disabled, cycles present

**Condition:** `--no-cycles` not passed (or `gate.no-cycles.enabled: false`) but cycles exist.  
**Expected:** CLI exits `0`. JSON contains `cycles` list with cycle groups. `gate.violations` does
not include `circularDependency` entries.

### EC-7: `--no-cycles` overrides YAML `enabled: false`

**Condition:** `aic-check.yaml` has `gate.no-cycles.enabled: false` but `--no-cycles` flag is
passed.  
**Expected:** CLI flag wins — gate is enabled, CLI exits `1` if cycles found.

---

## Acceptance Criteria

- [ ] `CycleDetectorTest.detectsTwoNodeCycle()` passes: A→B, B→A produces one cycle group `[A, B]`
- [ ] `CycleDetectorTest.detectsThreeNodeCycle()` passes: A→B→C→A produces one cycle group `[A, B, C]`
- [ ] `CycleDetectorTest.acyclicGraphHasNoCycles()` passes: A→B→C (DAG) produces `[]`
- [ ] `CycleDetectorTest.reportsOnlyTheCyclicComponentInAMixedGraph()` passes
- [ ] `CycleDetectorTest.ignoresDependenciesOnClassesOutsideTheModulePackages()` passes
- [ ] `MetricsExport.cycles` is `[]` (not `null`) when no cycles found
- [ ] `MetricsExport.cycles` lists each cycle alphabetically sorted, groups sorted by first element
- [ ] CLI exits `1` when `--no-cycles` is passed and at least one cycle is detected
- [ ] CLI exits `0` when `--no-cycles` is passed and the graph is acyclic
- [ ] CLI exits `0` (no gate violation for cycles) when `--no-cycles` is not passed, even if cycles exist
- [ ] `gate.no-cycles.enabled: true` in `aic-check.yaml` activates the gate without CLI flag
- [ ] `--no-cycles` CLI flag overrides `gate.no-cycles.enabled: false` in `aic-check.yaml`
- [ ] Web UI renders the cycle banner when `cycles` is non-empty; no banner when empty
- [ ] Banner text is `pkgA → pkgB → pkgA` (first package repeated to close the cycle)
- [ ] `ArchChecker` calls `CycleDetector.cyclesInGraph()` — no duplicate Tarjan implementation
- [ ] `mvn verify` passes with no regressions

---

## Open Questions

- [ ] Should the D3 dependency graph visually highlight cycle edges (e.g. red arrows)? Currently
      deferred — cycles appear only in the text banner.
- [ ] Should `MetricsExport.cycles` include the directed edges within each cycle (not just node
      names) to allow consumers to reconstruct the cycle path without re-running the algorithm?

---

## Version History

| Version | Date | Author | Change |
|---------|------|--------|--------|
| 1.0 | 2026-06-17 | nguyenhuuca | Initial — documenting existing implementation |
