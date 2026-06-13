---
description: Compile a target Java/Spring Boot project, then scan it with the metrics calculator
argument-hint: <path-to-target-project>
---

Analyze the abstractness/instability metrics of the project at: **$ARGUMENTS**

This calculator reads **compiled `.class` bytecode**, not source — so the target project MUST be compiled first or the scan returns empty metrics.

Steps:
1. Verify the path `$ARGUMENTS` exists and contains a `src/main/java` directory with an `@SpringBootApplication` class. If not, stop and tell the user why.
2. Detect the target's build tool and compile it so `target/` (Maven) or `build/` (Gradle) is populated:
   - Maven (`pom.xml`): `mvn -q -f "$ARGUMENTS/pom.xml" clean compile`
   - Gradle (`build.gradle`/`build.gradle.kts`): `gradle -p "$ARGUMENTS" classes` (or the wrapper)
3. Build this calculator if needed and start it on a free port (e.g. `--server.port=8099`), running in the background. Wait until `http://localhost:<port>/` responds.
4. POST the scan: `curl -s -X POST "http://localhost:<port>/scan" --data-urlencode "path=$ARGUMENTS"`.
5. Summarize the returned metrics for the user (packages found, which are in the Safe zone vs. need attention, notable Zone of Pain / Uselessness packages). If they want to explore visually, point them to the running URL.
6. Stop the background app and clean up any temp files when done.

If the scan returns no packages, the most likely cause is that the target was not compiled — re-check step 2.
