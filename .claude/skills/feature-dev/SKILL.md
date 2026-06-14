---
name: feature-dev
description: Playbook for adding features or improvements to the abstractness-instability-calculator (a Spring Boot bytecode-analysis app). Use when the user wants to add a new metric, endpoint, UI element, configuration, or otherwise develop/improve this repository.
---

# Developing features on the metrics calculator

This repo is a Spring Boot web app that computes Robert Martin's package metrics (Abstractness, Instability, Distance from the Main Sequence) for a target Java/Spring Boot project by reading its **compiled `.class` bytecode** via ASM. Read `CLAUDE.md` first — it has the architecture map, the key design facts, and the project conventions. This skill is the step-by-step playbook for changing the code.

## 0. Plan first — mandatory

**Do not write or edit any code before presenting a plan and getting the user's confirmation.** This is a hard rule for this repo.

The plan must state:
1. **Goal** — what feature/behaviour will exist after the change.
2. **Layers & files** — exactly which files you'll touch (use the table in step 1 to place them).
3. **Approach** — the key design decisions and how it fits the existing flow `Controller → SpringBootPackageScanner → PackageLocator + PackageMetricsCalculator → JavaClassAnalyzer`.
4. **Tests** — which tests you'll add or update.
5. **Verification** — how you'll prove it works (`/demo`, `/analyze`, manual UI check).

Use plan mode (`EnterPlanMode` / `ExitPlanMode`) when available so the user can approve before any edits. Only skip planning for truly trivial edits (typo, comment, one-line config tweak). If the scope changes while working, stop and re-plan.

## 1. Understand the change & locate the layer

Code is layered under `com.example.softwaremetrics`. Put each change where it belongs:

| Kind of change | Layer / file |
|---|---|
| New or modified metric, counting, or filtering logic | `domain` — `PackageMetricsCalculator`, `JavaClassAnalyzer`, `PackageMetrics` |
| Finding packages / traversing the project | `domain` — `PackageLocator`, `ProjectPathTraverser` |
| Scan orchestration / new high-level operation | `application` — `SpringBootPackageScanner` |
| New HTTP endpoint or view wiring | `infrastructure` — `PackageScannerController` |
| Chart, package-details, dependency graph, styling | `src/main/resources/templates/` (`index.html`, `graph.html`) |
| New tunable list/threshold | `application.yaml` + `InstabilityCalculatorProperties` |

Domain classes must stay free of web/Spring-MVC types.

## 2. Implement following conventions

- Constructor injection (not field `@Autowired`); annotate beans with `@Component`.
- Keep the metric formulas intact unless the task is explicitly about them: `I = Ce/(Ce+Ca)`, `A = abstract/total`, `D = |A+I−1|`, each guarding division by zero.
- Remember the bytecode contract: never switch metric extraction to source parsing; the target must be compiled.
- New exclusion entries go in `application.yaml` — the `disabled` flag is inverted (`disabled: true` means the list is ON). See `/add-exclusion`.
- For UI work, preserve the dark-theme CSS variables and the element IDs the htmx/Chart.js/D3 scripts rely on (`#result`, `#tabContainer`, `#packageSelect`, `metricsChart`, …).

## 3. Add or update tests

Every behavioural change needs test coverage:
- Domain logic → focused unit test (`PackageMetricsCalculatorTest`, `JavaClassAnalyzerTest`).
- Endpoint/flow → extend `PackageScannerControllerIT` (`@SpringBootTest` + MockMvc, builds a synthetic project in a `@TempDir`).

If you change counting, update the calculator AND its test together.

## 4. Build, test, verify

```bash
mvn -q clean package        # compile + run all tests
mvn test -Dtest=SomeTest    # iterate on one test
```

Then verify end-to-end:
- `/demo` — build, run on :8081, and scan this repo against itself.
- `/analyze <path>` — compile and scan an external project (handles the "must compile first" gotcha).

For UI changes, open `http://localhost:8081`, run a scan, and confirm both tabs (Metrics Chart, Dependency Visualization) render correctly.

## 5. Wrap up

- Confirm `mvn -B package` passes (this is what CI runs on JDK 22).
- Branch off `main`, keep the commit scoped, and end the commit message with the `Co-Authored-By` trailer. Commit/push only when the user asks.
