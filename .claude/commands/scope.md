---
description: Scope a feature idea into a full PRD — asks clarifying questions when the description is unclear, then writes docs/prd/PRD-{slug}.md using the project's official template
allowed-tools: Read, Write, Glob, Grep, AskUserQuestion
argument-hint: [feature description]
---

# Scope → PRD

Turn a rough feature idea into a structured PRD. Ask clarifying questions before writing — never produce a PRD from an ambiguous description.

## How to use

```
/scope <feature description>
```

Example: `/scope add a "worst offenders" leaderboard of packages by Distance to the dashboard`

---

## Workflow

### Step 1 — Parse the input

Read `$ARGUMENTS`. Extract what is already clear:
- Feature name
- Problem being solved (if stated)
- Target users / persona (if stated)
- Success signal (if stated)
- Known constraints (if stated)

### Step 2 — Identify gaps and ask questions one at a time

Before writing anything, check which of the following are **missing or ambiguous**. Ask ONLY for what is genuinely unclear — do not re-ask information already in the input.

**Required information:**

| # | Question | Why it matters |
|---|----------|---------------|
| Q1 | What problem does this solve? Who has this problem and why does it matter? | Problem Statement + Evidence sections |
| Q2 | Who are the primary users / personas? | User Stories section |
| Q3 | How will we measure success? (metrics and targets) | Goals & Success Metrics section |
| Q4 | What is explicitly IN scope for v1? | Scope section |
| Q5 | What is explicitly OUT of scope (defer to v2+)? | Scope section |
| Q6 | Any known technical constraints, dependencies, or risks? | Dependencies + Risks sections |

**Rules:**
- Ask questions **one at a time** — use `AskUserQuestion` for each, wait for the answer, then ask the next
- Work through Q1 → Q6 in order, skipping any already answered in the input
- For each question, provide **3–4 contextually relevant options** derived from the feature description. Mark your best guess with `(Recommended)` at the end of its label. The tool automatically appends an "Other" option so the user can type a free-form answer.
- After each answer, briefly acknowledge it (one sentence) before asking the next question
- Once all gaps are filled, summarize what you understood and confirm before writing
- If the user says "just write it" or "use your best judgment", proceed with clearly labeled assumptions

**Example — asking Q1 for a "worst offenders" leaderboard feature:**
```
AskUserQuestion({
  questions: [{
    question: "What problem does this feature solve, and who has it?",
    header: "Problem",
    multiSelect: false,
    options: [
      { label: "Teams can't tell which packages need attention first (Recommended)", description: "The scatter chart shows all packages at once; there's no ranked view of the worst Distance scores" },
      { label: "No quick way to track improvement over time", description: "Without a ranked list, it's hard to see if refactoring is reducing the worst offenders" },
      { label: "CI gate failures don't show prioritized context", description: "The CLI fails the gate but doesn't surface which packages are driving the failure" }
    ]
  }]
})
```

Adapt the options to the actual feature — do not reuse these verbatim.

### Step 3 — Read existing codebase context

Use Glob and Grep to find relevant existing domain classes, application services, or endpoints related to the feature (see `CLAUDE.md`'s Architecture section for the layer map: `domain` → `application` → `infrastructure`). This informs the Requirements, Dependencies, and Next Steps sections.

Also read the official PRD template:
```
templates/artifacts/prd.template.md
```

### Step 4 — Write the PRD

Once all gaps are resolved, create `docs/prd/PRD-{kebab-slug}.md` following the **official template** from `templates/artifacts/prd.template.md`.

Fill every section — do not leave placeholder text. Where information is assumed rather than stated by the user, mark it with `> **Assumption:** ...` so it is easy to review.

**Project-specific conventions to follow:**
- This is a Spring-free `core` (domain/application) + Spring Boot `web` (infrastructure) split — new analysis logic belongs in `core`, never in `web`
- The analyzer reads compiled `.class` bytecode via ASM; there is no source-parsing path and no database
- Reference `docs/adr/` for ADR links and `templates/artifacts/` for the template set
- The JSON envelope (`MetricsExport`) and the `aic-check.yaml` gate config are the two contracts most features touch — call out explicitly if a feature changes either

### Step 5 — Offer next steps

After writing the PRD, offer:
1. Create **ADR** (`docs/adr/00NN-{slug}.md`) using `templates/artifacts/adr.template.md`
   - Key sections to fill: Metadata, Context, Decision Drivers, Considered Options (pros/cons per option), Decision Outcome, Consequences, Validation, Links, Changelog
   - Do NOT include Technical Details or Implementation Steps in the ADR — those go in the Plan
2. Create **Implementation Plan** (`docs/plans/plan-{slug}.md`) using `templates/artifacts/plan.template.md`
3. Add entries to **`mkdocs.yml`** nav

Do not auto-create these — wait for the user to confirm.

---

## Rules

- **Never skip the clarification step** if any of Q1–Q4 are unanswered
- **One question at a time** — use `AskUserQuestion` for each; wait for answer before asking Q2, etc.
- **Always provide options** — 3–4 contextually relevant choices, mark best guess `(Recommended)`, "Other" is auto-added for free text
- **Acknowledge each answer** before moving to the next question
- **Assumptions must be labelled** — mark with `> **Assumption:**` inline
- **Use the official template** from `templates/artifacts/prd.template.md` — do not invent a different structure
- **Fill every section** — use `N/A` or `TBD` only when genuinely unknown, not as a shortcut

$ARGUMENTS
