# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.5.0] - 2026-06-19

### Added

- **Configurable module granularity** — `analyze.depth` and `analyze.expand` in `aic-check.yaml` let a project split or deepen module boundaries beyond the default one-level sub-package ([a78aadf](../../commit/a78aadf))
- **`AnalysisService` facade** — single orchestration entry point used by both the web controller and the CLI; replaces ad-hoc wiring in each caller ([2250013](../../commit/2250013))
- **`RootPackageResolver` SPI** — pluggable chain (`Explicit` → `SpringBoot` → `CommonPrefix`) decouples root-package detection from Spring Boot, enabling plain-Java projects to scan without a `@SpringBootApplication` class ([2b3b1f0](../../commit/2b3b1f0))
- **JaCoCo coverage reports** — integration coverage collected during `mvn verify` for both unit and IT phases ([c34609e](../../commit/c34609e))
- **`PackageScannerE2EIT`** — 12 full-stack HTTP-level integration tests covering scan success/error, cyclic detection, architecture conformance, JSON envelope, and error-message safety ([703ef1f](../../commit/703ef1f))

### Changed

- **Single-pass analysis** — `ProjectModelBuilder` walks bytecode once and builds a `ProjectModel`; all checks (metrics, cycles, architecture, banned-API, dead-code) derive from that shared model instead of re-scanning ([2b3b1f0](../../commit/2b3b1f0))
- **Core module package renamed** to `com.example.softwaremetrics.core`; web stays under `com.example.softwaremetrics` ([96e7e87](../../commit/96e7e87))
- **Project renamed** from the previous name to `aic` ([4b7748f](../../commit/4b7748f))
- **CI now runs integration tests** (`*IT`) via maven-failsafe on every push ([8c65d50](../../commit/8c65d50))

### Fixed

- **Accessibility** — added `<main>` landmark, `aria-label` on the Architecture `<select>`, `<meta name="description">`, and a favicon stub; Lighthouse scores raised to 100/100/100/100 ([703ef1f](../../commit/703ef1f))
- **Error messages no longer expose Java exception class names** — e.g. `java.nio.file.NoSuchFileException` was previously surfaced verbatim to the user; now shows a clean human-readable message ([703ef1f](../../commit/703ef1f))
- **Removed dead `htmx json-enc.js` script** that was causing a htmx 1/2 version mismatch warning in the browser console ([703ef1f](../../commit/703ef1f))

### Docs

- Refreshed README screenshots with current UI ([48057e9](../../commit/48057e9))
- Added PRD, ADR, spec, and implementation plan for cyclic dependency detection ([9232a30](../../commit/9232a30))

---

## [1.4.0] - 2025-xx-xx

### Added

- **Cyclomatic complexity metrics** — per-method complexity aggregated to avg/max per package, surfaced in the UI and the JSON envelope
- **Banned-API gate** — configurable list of forbidden types/methods; violations fail the CLI and appear as a banner in the web UI
- **Dead-code report** — class-level unused-class detection (report-only, not a gate)

### Fixed

- Count annotation usages and class-literal annotation values as coupling references
- Count implemented interfaces, superclass, and field types as coupling references

---

## [1.3.0] - 2025-xx-xx

### Added

- **Per-project `aic-check.yaml`** — a project can ship its own gate thresholds and architecture spec; precedence is code defaults < project file < CLI flags
- **Architecture conformance check** (`--arch`) — YAML-driven spec with built-in `layered`, `hexagonal`, and `onion` templates; violations exit `1` in the CLI and show a banner in the web UI

### Docs

- MkDocs (Material) documentation site published to GitHub Pages
- Web UI screenshots reference page

---

## [1.1.0] - 2025-xx-xx

### Added

- Initial public release with web UI and headless CLI
- Abstractness (A), Instability (I), Distance (D) metrics per package
- Interactive scatter plot with Zone of Pain / Zone of Uselessness / Safe Zone shading
- Dependency visualization tab (D3 force-directed graph)
- Circular dependency detection (Tarjan SCC) with UI banner and JSON output
- JSON export via `GET /api/metrics` and in-browser button
- CI quality gates: `max-package-distance`, `forbidden-zones`, `max-average-distance`, `no-cycles`

[1.5.0]: https://github.com/nguyenhuuca/aic/compare/v1.4.0...v1.5.0
[1.4.0]: https://github.com/nguyenhuuca/aic/compare/v1.3.0...v1.4.0
[1.3.0]: https://github.com/nguyenhuuca/aic/compare/v1.1.0...v1.3.0
[1.1.0]: https://github.com/nguyenhuuca/aic/releases/tag/v1.1.0
