# Getting Started

## Prerequisites

- **Java 22+**
- **Maven 3.6+**

A [Nix flake](https://nixos.wiki/wiki/Flakes) is also provided (`nix develop`) for systems with older
Java/Maven.

## Build

This is a multi-module Maven build; the reactor builds both modules:

```bash
git clone https://github.com/nguyenhuuca/aic.git
cd aic
mvn clean package
```

It produces two artifacts:

| Artifact | What it is |
|----------|------------|
| `web/target/aic-web.jar` | the Spring Boot web UI (fat jar) |
| `core/target/aic-cli.jar` | a lean, Spring-free CLI for CI (~2.8 MB) |

## Run the web app

```bash
java -jar web/target/aic-web.jar
```

Open <http://localhost:8081>, enter the path to a **compiled** Java project, optionally pick an
architecture template, and click **Scan**.

!!! note "The target project must be compiled"
    The tool reads `.class` bytecode, so the project you scan must have its `target/classes`
    (Maven) or `build/classes` (Gradle) populated. Source-only projects yield empty metrics.

## Run the CLI (headless / CI)

```bash
java -jar core/target/aic-cli.jar --scan=/path/to/project --fail-on-distance=0.7
```

Prints the JSON metrics envelope and exits `0` (gates passed) / `1` (a gate was violated) / `2` (scan
error). See [CLI & CI Gates](cli-and-ci.md) for all options.
