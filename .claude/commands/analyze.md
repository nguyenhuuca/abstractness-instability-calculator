---
description: Compile a target Java/Spring Boot project, then scan it with the headless CLI
argument-hint: <path-to-target-project> [--fail-on-distance=<d>]
---

Analyze the abstractness/instability metrics of the project at: **$ARGUMENTS**

This calculator reads **compiled `.class` bytecode**, not source — so the target project MUST be compiled first or the scan returns empty metrics.

Steps:
1. Verify the target path exists and contains a `src/main/java` directory with an `@SpringBootApplication` class. If not, stop and tell the user why.
2. Compile the target so its bytecode exists:
   - Maven (`pom.xml`): `mvn -q -f "<path>/pom.xml" clean compile`
   - Gradle (`build.gradle`/`.kts`): `gradle -p "<path>" classes` (or the wrapper)
3. Build this calculator if needed: `mvn -q clean package -DskipTests` (produces `core/target/aic-cli.jar`).
4. Run the **lean headless CLI** against the target (Spring-free, no web server):
   ```
   java -jar core/target/aic-cli.jar --scan="<path>"
   ```
   Add `--fail-on-distance=<d>` if the user passed one, and `--output=<file>` if they want the JSON saved.
5. Read the JSON envelope from stdout (or the output file) and summarize: packages found, the `summary`
   (well-designed vs. needs-attention, average distance), any `gate` violations, and the process exit code
   (`0` passed / `1` gate violated / `2` scan error).
6. If the user wants the interactive chart instead, mention they can run the web UI
   (`java -jar web/target/aic-web.jar`) and open `http://localhost:8081`.

If the scan returns no packages, the most likely cause is that the target was not compiled — re-check step 2.
