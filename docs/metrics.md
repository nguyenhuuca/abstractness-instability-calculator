# Understanding the Metrics

The scatter plot visualizes three metrics for each package.

## Instability (I)

- **Range:** 0 (maximally stable) → 1 (maximally unstable)
- **Formula:** `I = Ce / (Ca + Ce)`
    - **Ce** — efferent couplings (outgoing dependencies)
    - **Ca** — afferent couplings (incoming dependencies)
- **Use:** stable packages (low I) are good to depend upon; unstable packages (high I) should depend on
  stable ones.

## Abstractness (A)

- **Range:** 0 (fully concrete) → 1 (fully abstract)
- **Formula:** `A = (abstract classes + interfaces) / total classes`
- **Use:** abstract packages are flexible but less directly usable; concrete packages are the reverse.

## Distance from the Main Sequence (D)

- **Range:** 0 (on the Main Sequence, optimal) → 1 (furthest, problematic)
- **Formula:** `D = |A + I − 1|`
- **Use:** packages close to the Main Sequence are well balanced; high-D packages are refactoring
  candidates.

## Complexity

Each package also reports method complexity (from bytecode):

- **Max complexity** — the highest cyclomatic complexity among the package's methods (and which method).
- **Avg complexity** — average cyclomatic complexity across the package's methods, with the method count.

Cyclomatic complexity ≈ 1 + decision points (conditional branches + switch cases). The
`max-complexity` gate fails the build when any method exceeds a configured threshold — see
[CLI & CI Gates](cli-and-ci.md).

## Reading the scatter plot

The chart highlights several regions:

- **Zone of Pain** (bottom-left): stable **and** concrete — hard to extend, many dependents (e.g. a
  schema class everyone depends on).
- **Zone of Uselessness** (top-right): unstable **and** abstract — abstractions nobody uses.
- **Main Sequence** (the diagonal): the ideal balance; packages should sit near it.
- **Safe Zone** (green band, `|D| ≤ 0.2`) and **Warning Zone** (amber band further out) shade how far a
  package drifts from the line.

### Color coding

- **Green** point — close to the Main Sequence (`D ≤ 0.5`)
- **Red** point — far from it (`D > 0.5`)

## Practical application

- Packages in the **Zone of Pain** may benefit from more abstraction.
- Packages in the **Zone of Uselessness** may need to be made concrete or removed if unused.
- **Red** packages (high D) are the primary restructuring candidates.
- Track these over time to keep the codebase healthy — but treat them as guidance, not absolute rules.
