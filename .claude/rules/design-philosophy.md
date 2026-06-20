# Design Philosophy — *A Philosophy of Software Design* (John Ousterhout)

When **designing** (new modules, refactors, API shapes), apply these principles. The goal is the book's
central thesis: **minimize complexity** — anything that makes the system hard to understand or change.
Watch for its three symptoms: *change amplification* (one decision forces edits in many places),
*cognitive load* (how much you must know to make a change), and *unknown unknowns* (it's not obvious
what must change). Each rule below is tied to how this repo already works.

| Principle | What it means | How it shows up here |
|-----------|---------------|----------------------|
| **Complexity is incremental** | Every shortcut adds a little complexity; it compounds. Design *strategically*, not tactically — no "tactical tornado" quick hacks. | Investment in the single-pass model & facade over the old three-pass design. |
| **Modules should be deep** | Simple interface, powerful implementation. Interface cost small, functionality large. Avoid *shallow* modules whose interface is as complex as their body. | `AnalysisService.analyze(request)` — one method hides the whole pipeline; `ProjectModel` exposes a few derived views over a heavy single ASM pass. |
| **Information hiding** | Each module encapsulates one design decision; the same knowledge must not leak into several places. | ASM/bytecode details live only in `domain.bytecode`; exclusion lists only in `DependencyExclusions`; metric formulas only in the calculator. |
| **Pull complexity downwards** | Better for a module to absorb complexity internally than to push it onto every caller. | `RootPackageResolver` chain & `CheckConfigLoader` layering hide resolution rules so callers just get a result. |
| **Define errors out of existence** | Design APIs so error cases can't arise, rather than forcing callers to handle them. | Root-package resolver chain always returns a best-effort answer; exclusion checks return early instead of throwing. |
| **Different layer → different abstraction** | Adjacent layers should not mirror each other. Pass-through methods/variables that add nothing are a red flag. | `domain` → `application` (facade) → `infrastructure` (MVC) each raise the abstraction level; the controller does not re-expose engine internals. |
| **General-purpose is deeper** | Prefer a slightly general interface over many special-purpose ones. | `MetricsExport` is one self-describing envelope shared by CLI and web, not per-consumer DTOs. |
| **Design it twice** | Sketch at least two designs before committing to one — especially for interfaces. | Use plan mode (see `CLAUDE.md` "Plan before coding") to compare approaches first. |
| **Comments describe what code can't** | Document the *why* and the abstraction/contract, not a paraphrase of the code. Write interface comments before the body. | The intent javadoc on `AnalysisService`, `ProjectModel`, `ProjectModelBuilder`. |
| **Consistency & obvious code** | Reuse conventions so readers reason by analogy; if code isn't obvious, redesign rather than over-comment. | Constructor DI everywhere, uniform `*Resolver`/`*Checker` naming, layered package layout. |

**Red flags to refactor when you see them:** shallow module · information leakage · temporal
decomposition · pass-through method/variable · repetition · special-vs-general mixture · vague or
hard-to-pick name · code that needs a comment to be understood.

These principles complement the SOLID/DRY rules in `code-quality.md` and the Golden Paths in
`tech-strategy.md`; the related Prohibited Patterns are enforced there.
