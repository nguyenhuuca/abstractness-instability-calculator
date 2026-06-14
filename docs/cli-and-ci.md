# CLI & CI Gates

Run the analyzer headless (no web server) to gate a build on architecture quality. The CLI is a lean,
Spring-free jar (~2.8 MB) that starts in well under a second.

!!! warning "Compile the target first"
    The tool reads `.class` bytecode — build the project you scan before running.

```bash
java -jar core/target/aic-cli.jar \
     --scan=/path/to/project [--fail-on-distance=0.7] [--no-cycles] [--arch=layered] [--output=metrics.json]
```

- Prints the JSON metrics envelope (with a `gate` section) to **stdout**, or to `--output=<file>`.
- Diagnostic logs go to **stderr**, so stdout stays clean JSON.

## Options and defaults

| Flag | Default if not passed | Effect |
|------|------------------------|--------|
| `--scan=<path>` | *(required)* | project to analyze; must be compiled |
| `--output=<file>` | print JSON to **stdout** | write the JSON envelope to a file instead |
| `--fail-on-distance=<d>` | **`0.7`** (the `max-package-distance` gate is on by default) | enable & set the per-package distance threshold |
| `--no-cycles` | **off** | also fail on any package dependency cycle |
| `--arch=<template\|file>` | **no** architecture check | enforce an architecture spec (see [Architecture Checks](architecture-checks.md)) |

## Exit codes

| Code | Meaning |
|------|---------|
| `0` | all gates and the architecture check passed |
| `1` | a gate or architecture rule was violated |
| `2` | scan error (bad path, no `@SpringBootApplication`, or an invalid `--arch`) |

!!! tip "Configure once, in the project"
    Instead of passing flags every run, a project can ship an `aic-check.yaml` that defines its gates
    and architecture — see [Project Configuration](configuration.md). CI can then run a bare `--scan`.

## Quality gates

Gate defaults live in code (`max-package-distance` @ 0.7 enabled, the rest off), are overridden by a
project's `aic-check.yaml`, and finally by these flags.

| Gate | Fails the build when… |
|------|------------------------|
| `max-package-distance` | any package's distance `D` exceeds the threshold (default `0.7`) |
| `forbidden-zones` | any package falls in the Zone of Pain or Zone of Uselessness |
| `max-average-distance` | the average `D` across packages exceeds the threshold |
| `no-cycles` | any circular dependency between packages is detected |
| `max-complexity` | any method's cyclomatic complexity exceeds the threshold |
| `banned-apis` | any forbidden class/method/package is referenced (with `allowedIn` exceptions) |

A **dead-code** report (class-level, configured under `dead-code`) is also available but is
**report-only** — it lists potentially-unused classes without failing the build. See
[Project Configuration](configuration.md) for the `max-complexity`, `banned-apis`, and `dead-code` schema.

## GitHub Actions example

```yaml
- name: Architecture quality gate
  run: |
    mvn -B -q clean package -DskipTests
    java -jar core/target/aic-cli.jar \
         --scan="$GITHUB_WORKSPACE" --fail-on-distance=0.7 --no-cycles
```
