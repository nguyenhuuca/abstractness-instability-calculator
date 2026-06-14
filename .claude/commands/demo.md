---
description: Build and run the web UI for a live demo
---

Run the metrics calculator web UI — useful for presentations.

Steps:
1. Build the project: `mvn -q clean package -DskipTests` (produces `web/target/aic-web.jar` and `core/target/aic-cli.jar`).
2. Start the web app in the background on port 8081: `java -jar web/target/aic-web.jar`. Wait until `http://localhost:8081/` returns HTTP 200.
3. Tell the user to open **http://localhost:8081** and enter the path to a **compiled** Spring Boot project (one with `target/classes` populated) — e.g. another project on disk. The tool reads `.class` bytecode, so the target must be built first.
4. Point out the dark-theme UI: the metrics scatter chart (Main Sequence, Safe / Warning / Pain / Uselessness zones), the dependency visualization tab, the circular-dependency banner, and the **Export JSON** button.
5. For a non-interactive demo, also show the lean CLI: `java -jar core/target/aic-cli.jar --scan="<compiled-project>" --fail-on-distance=0.7` — prints the JSON envelope and exits 0/1/2.
6. Leave the app running so they can present; remind them how to stop it (stop the `java` process) when finished.

Note: this repo is now multi-module, so scanning the repo root itself yields no packages (no `src/main/java` at the root) — point the scan at a normal single-module project instead.

Do not stop the app automatically — the user needs it live for the demo.
