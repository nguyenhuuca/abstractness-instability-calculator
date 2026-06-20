---
description: Remove leftover debug/dead code from the bytecode-analysis classes
---

Clean up any leftover debug code in the bytecode-analysis core — primarily
`core/src/main/java/com/example/softwaremetrics/core/domain/bytecode/ProjectModelBuilder.java`
(and its helpers `DependencyExclusions`, `TypeNames`), which is where the ASM extraction lives.

1. Scan for stray `logger.info`/`System.out`/`System.err` debug statements, no-op conditional debug
   blocks, and commented-out dead code, and remove them. Preserve `logger.debug`/`logger.trace` that
   convey real diagnostics and the legitimate `logger.error` calls in catch blocks.
2. Do not change the dependency-extraction behaviour — this is a cleanup only. The metric output (the
   `MetricsExport` JSON shape and A/I/D/Ce/Ca values) must stay identical.
3. After editing, run `mvn -q compile` to confirm it still builds, then `mvn -q -pl core test` to
   confirm the analysis tests pass.

Note: the historical debug code in the former `JavaClassAnalyzer` has already been removed (that class
was split into `ProjectModelBuilder` + `SpringBootAnnotationScanner`), so there may be nothing to do —
report that if the classes are already clean.
