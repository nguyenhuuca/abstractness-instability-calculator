---
description: Build and run the calculator, then scan this repo itself for a live demo
---

Run a self-contained demo of the metrics calculator — useful for presentations.

Steps:
1. Build this project so its own bytecode exists: `mvn -q clean package -DskipTests`.
2. Start the app in the background on port 8081 (its default). Wait until `http://localhost:8081/` returns HTTP 200.
3. Scan this repo against itself: `curl -s -X POST "http://localhost:8081/scan" --data-urlencode "path=$(pwd)"` (use the absolute repo root). This works because step 1 populated `target/classes`.
4. Confirm the scan returned the `com.example.softwaremetrics.*` module packages and report a one-line summary.
5. Tell the user to open **http://localhost:8081** in a browser to walk through the dark-theme UI: the metrics scatter chart (Main Sequence, Safe / Warning / Pain / Uselessness zones) and the dependency visualization tab.
6. Leave the app running so they can present, and remind them how to stop it (e.g. stop the `java` process) when finished.

Do not stop the app automatically — the user needs it live for the demo.
