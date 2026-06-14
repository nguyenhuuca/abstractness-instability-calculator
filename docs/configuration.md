# Project Configuration (`aic-check.yaml`)

A scanned project can carry its **own check policy** in an `aic-check.yaml` file that the tool
auto-discovers (logback-style) — controlling all quality gates and the architecture check. This means
CI can run a bare `--scan=<path>` and the project's own rules are applied.

## Precedence

Configuration is layered, lowest to highest:

```
code defaults  <  project aic-check.yaml  <  CLI flags
```

So a project file overrides the built-in defaults, and a CLI flag (`--fail-on-distance`, `--no-cycles`,
`--arch`) overrides the file for a one-off run.

## Discovery

The first existing of these, relative to the scanned project, is used:

1. `src/main/resources/aic-check.yaml` (or `.yml`)
2. `aic-check.yaml` (or `.yml`) at the project root

## File format

Every section and field is optional — anything omitted keeps its default.

```yaml
gates:
  max-package-distance:
    enabled: true
    threshold: 0.7
  forbidden-zones:
    enabled: false
  max-average-distance:
    enabled: false
    threshold: 0.5
  no-cycles:
    enabled: true
  max-complexity:             # fail if any method's cyclomatic complexity exceeds threshold
    enabled: false
    threshold: 15

# Forbidden APIs — a gate. Each rule bans a class / method / package; allowedIn exempts matching classes.
banned-apis:
  enabled: false
  rules:
    - method: java.lang.System.exit
      message: "Don't call System.exit"
    - class: java.util.Date
      message: "Use java.time"
    - package: java.sql
      message: "DB access only in the repository layer"
      allowedIn: ['.*\.repository\..*', '.*\.repo\..*']

# Dead-code report (class-level). Report-only — never fails the build (DI/reflection → false positives).
dead-code:
  enabled: false

architecture:
  enabled: true
  template: layered          # a built-in template (layered | hexagonal | onion) …
  # … OR inline the spec instead of a template:
  # spec:
  #   name: Custom
  #   components:
  #     - name: Web
  #       matches: ['.*\.web\..*']
  #     - name: Service
  #       matches: ['.*\.service\..*']
  #   access:
  #     Web: [Service]
  #     Service: []
  #   options:
  #     forbidCycles: true
```

- **`gates`** — see [CLI & CI Gates](cli-and-ci.md) for what each gate means.
- **`architecture`** — `enabled` plus either a built-in `template` or an inline `spec` (same schema as
  [Architecture Checks](architecture-checks.md)). `spec` wins if both are given.

## Example: enforce in CI with no flags

Commit `src/main/resources/aic-check.yaml` to your project, then the CI step is simply:

```bash
mvn -B package -DskipTests
java -jar aic-cli.jar --scan=.
```

The gates and architecture come from the file; the process exits non-zero on any violation.
