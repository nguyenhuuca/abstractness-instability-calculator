# Abstractness and Instability Metrics Calculator

This application calculates abstractness and instability metrics for Java, Spring Boot projects, helping developers analyze the structure and dependencies of their codebase.

It follows the principles of Spring Modulith by analyzing the [application module packages](https://docs.spring.io/spring-modulith/reference/fundamentals.html#modules.simple). These are direct sub-packages of the _main_ package that contains the `@SpringBootApplication` annotated class. Ideally, these packages are expected to be functional layers rather than technical layers (controller, services, repositories etc.).

A [Nix Flake](#nix-flake) is provided to help build on systems with outdated java and maven installations.

![screenshot](https://github.com/user-attachments/assets/a496037d-62b2-42b5-809f-0eec2f63018a)

Dependency Visualization

![dependency_visualization_recording](https://github.com/user-attachments/assets/83ed8bae-5b0d-4b8c-a356-820e29c3ebad)

## Features

- Scans Spring Boot projects to identify packages and their relationships
- Calculates abstractness, instability, and distance from the main sequence for each package
- Provides a web interface for easy project analysis
- Visualizes results using an interactive scatter plot
- Dependency visualization

## Prerequisites

- Java 22 or higher
- Maven 3.6 or higher

## Installation

1. Clone the repository:
   ```
   git clone https://github.com/xsreality/abstractness-instability-calculator.git
   ```

2. Navigate to the project directory:
   ```
   cd abstractness-instability-calculator
   ```

3. Build the project (reactor builds both modules):
   ```
   mvn clean package
   ```
   This produces two artifacts:
   - `web/target/aic-web.jar` — the Spring Boot web UI (fat jar)
   - `core/target/aic-cli.jar` — a lean, Spring-free CLI for CI (~2.5 MB)

## Usage

1. Run the web application:
   ```
   java -jar web/target/aic-web.jar
   ```

2. Open a web browser and go to `http://localhost:8081`

3. Enter the path to your Java project in the input field

4. Click "Scan" to analyze the project

5. View the results in the interactive scatter plot

> The web UI also exposes `GET /api/metrics?path=<project-path>`, which returns the metrics as a
> JSON envelope (`generatedAt`, `projectPath`, `toolVersion`, `packageCount`, `summary`, `packages`)
> for archival or verification by another system.

## CI / CLI usage

Run the analyzer headless (no web server) to gate a build on architecture quality. Remember the
target project must be **compiled** first (the tool reads `.class` bytecode).

```
java -jar core/target/aic-cli.jar \
     --scan=/path/to/project [--fail-on-distance=0.7] [--no-cycles] [--output=metrics.json]
```

The CLI is a lean, Spring-free jar (~2.5 MB) that starts in well under a second — no web server is
started.

- Prints the JSON metrics envelope (with a `gate` section) to stdout, or to `--output=<file>`.
- Exit code: `0` = all gates passed, `1` = a gate was violated, `2` = scan error.

The CLI's quality gates default in code (`max-package-distance` @ 0.7 enabled, the rest off) and are
overridden by flags (`--fail-on-distance`, `--no-cycles`). The available gates:

| Gate | Fails the build when… |
|------|------------------------|
| `max-package-distance` | any package's distance `D` exceeds `threshold` (default `0.7`) |
| `forbidden-zones` | any package falls in the Zone of Pain or Zone of Uselessness |
| `max-average-distance` | the average `D` across packages exceeds `threshold` |
| `no-cycles` | any circular dependency between packages is detected |

The JSON envelope also includes a `cycles` array (each entry is a group of packages that form a
dependency cycle), and the web UI shows a warning banner listing them after a scan.

`--fail-on-distance=<d>` enables and overrides the per-package distance threshold from the command line.

Example GitHub Actions step:

```yaml
- name: Architecture quality gate
  run: |
    mvn -B -q clean package -DskipTests
    java -jar core/target/aic-cli.jar \
         --scan="$GITHUB_WORKSPACE" --fail-on-distance=0.7
```

## Nix Flake

1. Enter development environment
   ```
   nix develop
   ```

2. Build application
   ```
   mvn clean package -DskipTests
   ```

3. Run application
   ```
   java -jar web/target/aic-web.jar
   ```

## Understanding the Results

The scatter plot visualizes three key metrics for each package:

### Instability (I)
- **Range**: 0 to 1
- **Interpretation**: 
  - 0: Maximally stable
  - 1: Maximally unstable
- **Calculation**: I = Ce / (Ca + Ce), where:
  - Ce: Efferent Couplings (outgoing dependencies)
  - Ca: Afferent Couplings (incoming dependencies)
- **Practical Use**: 
  - Helps identify packages that are more likely to change due to changes in other packages.
  - Stable packages (low I) are good candidates for being depended upon.
  - Unstable packages (high I) should generally depend on stable packages to maintain system stability.

### Abstractness (A)
- **Range**: 0 to 1
- **Interpretation**:
  - 0: Completely concrete
  - 1: Completely abstract
- **Calculation**: A = (Number of abstract classes and interfaces) / (Total number of classes)
- **Practical Use**:
  - Indicates the level of abstraction in a package.
  - Highly abstract packages (high A) are often more flexible but may be less directly usable.
  - Concrete packages (low A) are typically more immediately usable but may be less flexible.

### Distance from the Main Sequence (D)
- **Range**: 0 to 1
- **Interpretation**:
  - 0: Directly on the Main Sequence (optimal)
  - 1: Furthest from the Main Sequence (problematic)
- **Calculation**: D = |A + I - 1|
- **Practical Use**:
  - Measures how well a package balances abstractness and stability.
  - Packages close to the Main Sequence (low D) are considered well-designed.
  - Helps identify packages that may need refactoring or restructuring.

### Interpreting the Scatter Plot

The plot visualizes these metrics and highlights two important zones:

1. **Zone of Pain** (Bottom-left corner):
   - High stability (low I) and low abstractness (low A)
   - Packages here are difficult to extend and have many dependents
   - Example: A database schema class that many other classes depend on

2. **Zone of Uselessness** (Top-right corner):
   - Low stability (high I) and high abstractness (high A)
   - Packages here are abstract but have no dependents, indicating potentially unused code
   - Example: An over-engineered set of interfaces with no implementations

3. **Main Sequence** (Diagonal line from top-left to bottom-right):
   - Represents an ideal balance between abstractness and instability
   - Packages should aim to be close to this line

### Color Coding
- **Green**: Packages close to the Main Sequence (D ≤ 0.5)
- **Red**: Packages far from the Main Sequence (D > 0.5)

### Practical Application
- Use these metrics to identify packages that may need refactoring:
  - Packages in the Zone of Pain might benefit from increased abstraction.
  - Packages in the Zone of Uselessness might need to be made more concrete or removed if unused.
  - Red packages (high D) are primary candidates for restructuring.
- Monitor these metrics over time to ensure your codebase maintains a good structure as it evolves.
- Use in conjunction with other software quality metrics and practices for a comprehensive view of your codebase's health.

While these metrics provide valuable insights, they should not be treated as absolute rules. Always consider the specific context and requirements of your project when making architectural decisions.

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

