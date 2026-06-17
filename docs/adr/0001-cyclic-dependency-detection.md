# ADR-0001: Cyclic Dependency Detection — Algorithm, Granularity, and Gate Model

## Metadata

**Status:** Accepted · **Date:** 2026-06-17 · **Deciders:** nguyenhuuca · **Tags:** domain, quality-gate, architecture  
**Related PRD:** [PRD-cyclic-dependency-detection](../prd/PRD-cyclic-dependency-detection.md) · **Supersedes:** N/A · **Superseded By:** N/A

**Tech Strategy:** ✅ Follows Golden Path — pure `core/domain` class, Spring-free, single ASM pass

---

## Context

Robert Martin's Acyclic Dependencies Principle states that the package dependency graph must be a
DAG (Directed Acyclic Graph). `aic` already computes Abstractness, Instability, and Distance per
module package; adding cycle detection completes the picture of package-level health.

Three independent design decisions were made together:

1. **Which algorithm** to use to find cycles in the package dependency graph
2. **Which granularity** (package-level vs class-level) to detect cycles at
3. **How to expose the gate** — opt-in CLI flag, always-fail, or warn-only

The package dependency graph is built from efferent class references already extracted during the
single ASM pass (`ProjectModelBuilder`), so no additional bytecode scan is needed. The graph is
small (typically < 100 nodes for any real Spring Boot project).

---

## Decision Drivers

- **Correctness** — must find all cycles, including transitive ones (A→B→C→A), not just direct mutual dependencies
- **Determinism** — same input must always produce the same sorted cycle list (for reproducible CI output)
- **Single-pass constraint** — no second filesystem walk; detection must derive from the already-built `ProjectModel`
- **Spring-free core** — `CycleDetector` must be a plain Java class in `domain`, testable without Spring
- **Reusability** — the architecture checker (`ArchChecker`) also needs cycle detection on an arbitrary directed graph; duplication should be avoided
- **Non-breaking default** — many existing projects have cycles; the gate must be opt-in so it doesn't break adoption

---

## Decision 1: Algorithm — Tarjan's SCC

### Option 1: Tarjan's Strongly Connected Components (chosen)

Finds all strongly-connected components (SCCs) of size ≥ 2 in a single O(V+E) DFS pass.
Each SCC is a maximal set of nodes that are mutually reachable — i.e., a cycle group.

| Pros | Cons |
|------|------|
| O(V+E) — linear in graph size | Recursive DFS may stack-overflow on extremely deep graphs |
| Finds ALL cycles in one pass, including transitive multi-node cycles | Slightly more complex to implement than simple DFS |
| Returns maximal cycle groups (not just pairs) | |
| Well-studied algorithm with proven correctness | |
| Works on any directed graph — reusable for `ArchChecker` | |

### Option 2: Simple DFS back-edge detection

Perform DFS; a back-edge (edge to an already-visited ancestor) indicates a cycle.

| Pros | Cons |
|------|------|
| Simpler to implement | Only detects whether a cycle exists — does not return the cycle members |
| Familiar to most developers | Cannot list the packages involved without additional tracking |

### Option 3: Floyd-Warshall transitive closure

Build the full reachability matrix; a cycle exists when node i can reach itself.

| Pros | Cons |
|------|------|
| Easy to understand | O(V³) — prohibitive for large graphs |
| Returns full reachability | Memory: O(V²) matrix |
| | Overkill for small package graphs |

**Decision:** Option 1 — Tarjan's SCC.
Tarjan is the standard algorithm for this problem. It is O(V+E), finds all maximal cycle groups in
one pass, and returns the members of each cycle — exactly what is needed to both gate on cycles and
display them in the UI/JSON. The recursive implementation is safe for package graphs (< 100 nodes).

---

## Decision 2: Granularity — Package-Level, Not Class-Level

### Option 1: Package-level cycle detection (chosen)

Build one graph node per module package. An edge A→B exists when any class in package A references
any class in package B (and A ≠ B). Run Tarjan on this graph.

| Pros | Cons |
|------|------|
| Consistent with the rest of the tool — all other metrics (A, I, D) are also package-level | Does not surface which specific classes cause the cycle |
| Graph is small (< 100 nodes) — fast and readable output | A cycle between two packages may be caused by a single class; that class is not identified |
| Output matches the Abstractness/Instability scatter chart's granularity | |
| Easy to display in the web UI (`pkgA → pkgB → pkgA`) | |

### Option 2: Class-level cycle detection

Build one graph node per class. An edge A→B exists when class A references class B. Run Tarjan.

| Pros | Cons |
|------|------|
| Pinpoints the exact classes causing the cycle | Graph can have thousands of nodes — slower and much noisier output |
| More actionable for the developer fixing the cycle | Class-level cycles are common even in well-designed code (e.g. two collaborating classes in the same package) — very high false-positive rate |
| | Output would be overwhelming and inconsistent with the package-centric UX |

**Decision:** Option 1 — Package-level.
Architectural cycles between packages are the concern; within-package coupling is intentional. The
package-level view is consistent with all other metrics in `aic` and produces actionable,
readable output.

---

## Decision 3: Gate Model — Opt-In Flag / YAML Key

### Option 1: Opt-in flag (--no-cycles / gate.no-cycles: true) — chosen

Cycle detection always runs (results appear in JSON and web UI); the gate (exit `1`) is activated
only when the flag is passed or the YAML key is set.

| Pros | Cons |
|------|------|
| Zero-friction adoption — existing projects with cycles are not broken on first use | Projects that don't set the flag get detection results but no enforcement |
| Consistent with the other gates (`--fail-on-distance`, `gate.no-cycles`) — all opt-in | Teams must explicitly enable it; passive adoption may be slow |
| Cycles visible in UI/JSON even without the gate, so developers see the problem | |
| Per-project policy via `aic-check.yaml` without changing CI scripts | |

### Option 2: Always-fail when cycles detected

Any cycle found causes CLI exit `1`, unconditionally.

| Pros | Cons |
|------|------|
| Strongest enforcement, zero ambiguity | Breaks any project that has pre-existing cycles on first scan — blocks adoption |
| No extra configuration needed | Not all teams want the cycle gate; some projects intentionally tolerate certain cycles |

### Option 3: Warn-only (never exit 1 for cycles)

Cycles appear in output but never affect the exit code.

| Pros | Cons |
|------|------|
| Cannot break CI | Cycles can never be enforced — purely informational |
| Lowest risk | Defeats the purpose of having a quality gate |

**Decision:** Option 1 — opt-in gate.
Consistent with all other `aic` gates. Existing projects with cycles get visibility immediately
(JSON + web banner) without being blocked; teams choose enforcement when they are ready.

---

## Decision Outcome

**Algorithm:** Tarjan's SCC (O(V+E), finds all cycle groups, deterministic sorted output)  
**Granularity:** Package-level (consistent with A/I/D metrics, readable output)  
**Gate model:** Opt-in (`--no-cycles` CLI flag or `gate.no-cycles: true` in `aic-check.yaml`)

**Key design choice:** `CycleDetector.cyclesInGraph(Map<String, List<String>>)` is a public static
method that accepts an arbitrary directed graph. This decouples the algorithm from the
package-metrics domain, enabling `ArchChecker` to reuse Tarjan for architecture component cycles
without duplicating code.

### Quantified Impact

| Metric | Value | Notes |
|--------|-------|-------|
| Algorithm complexity | O(V+E) | V = packages, E = inter-package deps; negligible on real projects |
| Additional scan latency | < 1 ms | Package graphs are small; measured informally on this repo |
| Graph construction | Zero extra I/O | Derives from `PackageMetrics.efferentDependencies` already built |

---

## Consequences

**Positive:**
- All circular dependencies between module packages are detected in a single, efficient pass
- Output is deterministic and comparable across CI runs
- The `ArchChecker` reuses the same Tarjan implementation — single source of truth for cycle detection
- Opt-in gate allows gradual enforcement without breaking existing projects
- `CycleDetector` is a plain Java POJO — zero Spring dependency, trivially unit-testable

**Negative:**
- Package-level detection does not identify the specific classes causing a cycle; developers must
  investigate manually once a cycle is flagged
- Recursive Tarjan will stack-overflow if a single package transitively depends on thousands of
  other packages (not a realistic concern for the tool's target domain)

**Risks:**
- If the exclusion lists in `DependencyExclusions` miss a common external library, classes from that
  library could appear as package-graph nodes and create spurious cycles. Mitigation: the package
  graph builder restricts nodes to the set of known module packages, so unrecognised external classes
  simply produce no graph node and no edge.

---

## Validation

- [x] Unit tests pass: 2-node cycle, 3-node cycle, acyclic graph, mixed graph, external-dep exclusion (`CycleDetectorTest`)
- [x] Tech Strategy alignment confirmed — `CycleDetector` is Spring-free `core/domain`
- [x] Gate wired into `ThresholdEvaluator`, `GateConfig`, `CheckConfigLoader`, `CliMain`
- [x] JSON envelope includes `cycles` field (`MetricsExport`)
- [x] Web UI banner renders cycles (`graph.html`)
- [ ] Related spec document: `docs/specs/spec-cyclic-dependency-detection.md` (next step)

---

## Links

- [PRD](../prd/PRD-cyclic-dependency-detection.md)
- [Implementation Plan](../plans/plan-cyclic-dependency-detection.md) *(to be created)*
- Tarjan's SCC reference: Tarjan, R. E. (1972). "Depth-first search and linear graph algorithms."

---

## Changelog

| Date | Author | Change |
|------|--------|--------|
| 2026-06-17 | nguyenhuuca | Initial — documenting existing implementation decisions |
