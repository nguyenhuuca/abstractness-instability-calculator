---
description: Remove leftover debug/dead code from JavaClassAnalyzer
---

Clean up the leftover debug code in `src/main/java/com/example/softwaremetrics/domain/JavaClassAnalyzer.java`:

1. Remove the unconditional `logger.info("before normalized dependency: {}", dependency)` and `logger.info("after normalized dependency: {}", normalized)` calls inside `addDependencyIfNotExcluded` — they spam logs on every dependency. Keep the actual normalization/filtering logic intact.
2. Remove the no-op debug block inside `analyzeClassFile`:
   ```java
   if (topLevelPackage.contains("repo")) {
       logger.info("test");
   }
   ```
3. Scan the rest of the file for any other stray `logger.info`/`System.out` debug statements or commented-out dead code and remove them, but preserve `logger.trace`/`logger.debug` that convey real diagnostics.
4. After editing, run `mvn -q compile` to confirm it still builds, then run `mvn -q test` to confirm the analyzer tests pass.

Do not change the dependency-extraction behaviour — this is a cleanup only.
