# Tech Strategy - Golden Paths (aic)

This is the **SINGLE SOURCE OF TRUTH** for technology choices in this repo.

## Compliance

1. **Follow This File**: Use the technologies listed in the Golden Paths below
2. **No Deviations**: Do not suggest alternatives unless explicitly instructed
3. **Latest Stable**: Always use the latest stable version unless pinned

## Language Golden Path

### Java

| Component | Choice |
|-----------|--------|
| Runtime | JDK 22 |
| Build Tool | Maven 3.6+ (multi-module reactor: `core` + `web`) |
| `core` module | Spring-free analysis engine + headless CLI (`aic-core` library jar + shaded `aic-cli.jar`) |
| `web` module | Spring Boot 3.x web UI, depends on `core` |
| Bytecode analysis | ASM (`org.ow2.asm`) — reads compiled `.class` files, never source |
| JSON | Jackson Databind (envelope serialization) |
| Logging | SLF4J API in `core`; `slf4j-simple` bundled only into the shaded CLI jar (must never leak into `web`, which uses Logback) |
| YAML config | SnakeYAML (`aic-check.yaml` loading) |
| Testing | JUnit 5 + AssertJ |
| Architecture | Layered: `domain` (pure logic) → `application` (`AnalysisService` facade) → `infrastructure` (Spring MVC, web module only) |
| Dependency Injection | Constructor injection; `@Component`/`@Bean` registration — never field `@Autowired` |

## Frontend Golden Path

| Component | Choice |
|-----------|--------|
| Templating | Thymeleaf fragments (`graph :: graph`, `graph :: error`) |
| Interactivity | htmx (form submission, fragment swap) |
| Charts | Chart.js (metrics scatter plot) |
| Dependency graph | D3.js |
| Styling | Plain CSS with dark-theme CSS variables defined in `index.html` |
| Location | `web/src/main/resources/templates/` (`index.html` = shell/styles, `graph.html` = chart + details fragment) |

There is no separate frontend build pipeline (no npm/Vite/webpack) — templates are server-rendered and shipped inside the Spring Boot fat jar.

## Data

| Component | Choice |
|-----------|--------|
| Database | None — this app has no persistence layer |
| State | Each scan is stateless: read target project bytecode, compute metrics, return/render result |
| Target-project input | A filesystem path to a compiled Java/Spring Boot project, supplied per-request (web) or per-invocation (CLI) |

## CI/CD Pipeline

| Component | Choice |
|-----------|--------|
| Platform | GitHub Actions (`.github/workflows/ci.yml`, `docs.yml`, `publish-packages.yml`, `release.yml`) |
| Build | `mvn -B package` on JDK 22 |
| Tests | `mvn test` (unit) + `mvn -B verify` (adds `*IT` failsafe integration tests, e.g. `PackageScannerControllerIT`) |
| Docs | mkdocs (`mkdocs.yml`), published via `docs.yml` workflow |
| Packaging | Maven artifacts published via `publish-packages.yml`; releases via `release.yml` |
| Containerization | None currently (no Dockerfile/Kubernetes in this repo) |

## Code Quality

| Component | Choice |
|-----------|--------|
| Test placement | Domain logic → focused unit test next to the class under test; endpoint/flow → extend `PackageScannerControllerIT` |
| Coverage | No enforced threshold tool configured; every behavioural change must add/update a test (see `CLAUDE.md` conventions) |
| Self-checking | This repo can scan itself: `/demo` runs the CLI/web app against its own compiled output as a sanity check |

## Configuration Management

| Component | Choice |
|-----------|--------|
| Web config | `web/src/main/resources/application.yaml`, bound via `@ConfigurationProperties` (e.g. `InstabilityCalculatorProperties`) |
| Per-target-project config | `aic-check.yaml`, discovered in the **scanned** project (not this repo) via `CheckConfigLoader`; layered code defaults < project file < CLI flags |
| CLI flags | `--scan=<path>`, `--fail-on-distance`, `--no-cycles`, `--arch=<template|file.yaml>` |

## API Standards

| Component | Choice |
|-----------|--------|
| JSON envelope | `MetricsExport` — the single self-describing export contract returned by `GET /api/metrics` and printed by the CLI |
| Web endpoints | `GET /` (index), `POST /scan` (Thymeleaf fragment), `GET /api/metrics` (JSON) |
| Error handling | `IllegalArgumentException`/`IllegalStateException` → `graph :: error` fragment (web) or `error` JSON field / exit code `2` (CLI) |
| CLI exit codes | `0` passed, `1` gate violated, `2` scan error |

## Design Philosophy

Design principles (deep modules, information hiding, pull complexity downwards, define errors out of
existence, strategic over tactical) live in **`design-philosophy.md`** — apply them when designing new
modules, refactors, or API shapes. The Prohibited Patterns below enforce the ones with teeth.

## Prohibited Patterns

- ❌ No source-code parsing for metrics — the analyzer must only read compiled `.class` bytecode (ASM)
- ❌ No shallow modules or pass-through methods that merely forward to another layer without adding abstraction (see `design-philosophy.md`)
- ❌ No leaking one design decision across modules — e.g. bytecode/ASM details outside `domain.bytecode`, metric formulas outside the calculator
- ❌ No field `@Autowired` — use constructor injection
- ❌ No domain classes depending on Spring/web-MVC types — keep `domain`/`application` Spring-free
- ❌ No breaking changes to the `MetricsExport` JSON shape — additive fields only, unless the change is explicitly about the contract
- ❌ No changing the `I = Ce/(Ce+Ca)`, `A = abstract/total`, `D = |A+I−1|` formulas without updating the calculator AND its test together
- ❌ No `slf4j-simple` leaking from `core`'s shaded CLI jar into `web`'s classpath
