---
description: Generate a Feature Specification from a PRD and ADR — asks clarifying questions one at a time, then writes docs/specs/spec-{slug}.md
allowed-tools: Read, Write, Glob, Grep, AskUserQuestion
argument-hint: <path/to/PRD.md> <path/to/ADR.md>
---

# Spec → Feature Specification

Turn an approved PRD + ADR into a precise, implementable Feature Specification.
Ask clarifying questions before writing — the spec must be unambiguous.

## How to use

```
/spec docs/prd/PRD-{slug}.md docs/adr/NNNN-{slug}.md
```

Example: `/spec docs/prd/PRD-worst-offenders-leaderboard.md docs/adr/0003-worst-offenders-leaderboard-design.md`

---

## Workflow

### Step 1 — Read inputs

Read both files from `$ARGUMENTS`:
- **PRD** — extract: feature name, functional requirements, user stories, scope, NFRs
- **ADR** — extract: chosen approach, data-shape sketch, API sketch, constraints, consequences

Derive the kebab slug from the PRD filename (e.g. `PRD-bookmark-feature.md` → `bookmark-feature`).

### Step 2 — Identify gaps and ask questions one at a time

The PRD and ADR answer the "why" and "how". The Spec must answer the "exactly what".
Ask ONLY for what is genuinely unclear or not stated in the inputs.

**Required information:**

| # | Question | Why it matters |
|---|----------|---------------|
| Q1 | What are the exact rules/formulas that must be enforced? (numbered, precise) | Business Rules section |
| Q2 | What are all the error cases? Include HTTP status (web) or exit code (CLI) and the exact condition. | API/CLI error table |
| Q3 | Does this change the JSON envelope (`MetricsExport`) or `aic-check.yaml` schema? | API Changes + compatibility section |
| Q4 | What are the edge cases that must be explicitly handled? (empty project, no root package, cycles, zero Ce/Ca) | Edge Cases section |
| Q5 | What are the performance targets, if any? (e.g. scan time for large projects) | NFR section |
| Q6 | Any UI requirements? (new chart, new tab, new banner in `index.html`/`graph.html`) | UI Changes section |

**Rules:**
- Ask questions **one at a time** — use `AskUserQuestion` for each, wait for the answer, then ask the next
- Work through Q1 → Q6 in order, skipping any already answered in the PRD/ADR
- For each question, provide **3–4 contextually relevant options** derived from the feature. Mark best guess `(Recommended)`.
- After each answer, acknowledge it in one sentence before asking the next
- If the user says "just write it" or "use your best judgment", proceed with clearly labeled assumptions

**Example — asking Q1 for a "worst offenders" leaderboard feature:**
```
AskUserQuestion({
  questions: [{
    question: "What are the core ranking rules for the leaderboard?",
    header: "Business Rules",
    multiSelect: true,
    options: [
      { label: "Rank by Distance descending, top 10 (Recommended)", description: "Sort packages by D = |A+I-1| descending, show top 10" },
      { label: "Exclude packages below a minimum class count", description: "Avoid noisy rankings from packages with 1-2 classes" },
      { label: "Tie-break by total class count", description: "When Distance ties, larger packages rank higher" }
    ]
  }]
})
```

**Example — asking Q4 for edge cases:**
```
AskUserQuestion({
  questions: [{
    question: "Which edge cases must be explicitly handled?",
    header: "Edge Cases",
    multiSelect: true,
    options: [
      { label: "Project with fewer than 10 packages (Recommended)", description: "Leaderboard shows all packages, no padding/placeholder rows" },
      { label: "All packages tied at D=0", description: "Stable, deterministic ordering (e.g. alphabetical) when scores tie" },
      { label: "Package with Ce=0 and Ca=0", description: "I defaults to 0 per the existing formula — leaderboard must not divide by zero" }
    ]
  }]
})
```

### Step 3 — Read codebase context

Use Glob/Grep to verify:
- Existing domain types related to the feature (`PackageMetrics`, `MetricsExport`, `ProjectModel`, etc.)
- Existing error-handling pattern (`IllegalArgumentException`/`IllegalStateException` mapped to `graph :: error` / JSON `error` field)
- Existing gate pattern (`GateConfig` → `ThresholdEvaluator`) if the feature adds a new gate
- Template/script conventions in `web/src/main/resources/templates/` if the feature touches the UI

This ensures the spec reflects the actual codebase, not assumptions.

### Step 4 — Write the Spec

Create `docs/specs/spec-{kebab-slug}.md` using `templates/artifacts/spec.template.md`.

Fill every section. Where information comes from assumption rather than user input, mark with `> **Assumption:** ...`.

**Project-specific conventions:**
- JSON envelope: `MetricsExport` is the single export contract — additions must be additive (new fields), never breaking renames
- Gate config: new gates follow the `GateConfig` → `ThresholdEvaluator` pattern and plug into `aic-check.yaml` with sane code defaults in `config/Defaults`
- CLI exit codes: `0` passed, `1` gate violated, `2` scan error — preserve this contract
- Bytecode-only: never introduce a source-parsing path; everything derives from `ProjectModel`/`ClassDetail` built by `ProjectModelBuilder`
- UI: preserve existing element IDs (`#result`, `#tabContainer`, `#packageSelect`, `metricsChart`) and the dark-theme CSS variables in `index.html`

**Sections to fill (never leave placeholder text):**
- Overview — 1–3 sentences, system-level outcome
- Business Rules — numbered, each independently testable
- Functional Requirements — FR-1, FR-2... with "must"/"must not"
- API Changes — exact JSON envelope / endpoint / CLI flag changes, with error table
- Domain Changes — new/changed types in `domain`, formula changes (if any) called out explicitly
- Gate/Config Changes — new `aic-check.yaml` keys, defaults, CLI flag overrides
- UI Changes — new routes/fragments/chart elements, if any
- Non-Functional Requirements — performance targets, if any
- Edge Cases — EC-1, EC-2... with exact expected behavior
- Acceptance Criteria — testable checklist QA can use directly

### Step 5 — Update tracking and offer next steps

After writing the Spec:
1. Update `docs/tracking.md` if one exists — set Spec column for this feature to link + 📋
2. Offer to create **Implementation Plan** (`docs/plans/plan-{slug}.md`) via `/swarm-plan`
3. Offer to add entry to **`mkdocs.yml`** nav under Specs

Do not auto-create these — wait for the user to confirm.

---

## Rules

- **Read PRD and ADR first** — never ask questions already answered there
- **One question at a time** — use `AskUserQuestion`; wait for answer before next
- **Always provide options** — 3–4 relevant choices, mark best guess `(Recommended)`
- **Acknowledge each answer** before moving to the next question
- **No ambiguity allowed** — every edge case must be resolved, not left as TBD
- **Assumptions must be labelled** — mark with `> **Assumption:**` inline
- **Fill every section** — use `N/A` only when genuinely not applicable, never as shortcut
- **Match codebase patterns** — verify type/method names against actual code

$ARGUMENTS
