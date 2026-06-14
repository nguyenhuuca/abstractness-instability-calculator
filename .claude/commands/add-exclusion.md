---
description: Add a package prefix or type to the dependency exclusion lists in application.yaml
argument-hint: <package-prefix-or-type> [native|external|basic]
---

Add **$ARGUMENTS** to the dependency exclusion configuration in `src/main/resources/application.yaml` under `instability-calculator`.

Context — there are three lists, all bound by `InstabilityCalculatorProperties`:
- `native-packages` — JDK/runtime prefixes (e.g. `java.`, `jakarta.`)
- `external-packages` — third-party library prefixes (e.g. `org.springframework.`, `com.fasterxml.`)
- `basic-types` — primitive and `java.lang.*` type names

A dependency matching an *active* list is excluded from Ce/Ca coupling counts.

**Important gotcha:** each list has a `disabled` flag whose meaning is inverted in the code — the filter is only applied when `disabled: true`. So `disabled: true` = "this exclusion list is ON". Do not flip it to enable filtering.

Steps:
1. Parse `$ARGUMENTS`: the first token is the value to add; an optional second token (`native`/`external`/`basic`) picks the list. If no list is given, infer it — package prefixes ending in `.` go to `external-packages` (or `native-packages` for JDK namespaces like `java.`/`jdk.`/`sun.`), and bare type names go to `basic-types`.
2. Add the value to the appropriate `values:` list in `application.yaml`, keeping alphabetical-ish grouping and existing indentation. Skip if it's already present.
3. Confirm the change to the user and note which list it landed in.
