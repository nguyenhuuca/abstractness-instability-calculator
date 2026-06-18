# Code Review — `scanReportsCyclicDependencies` integration test

**Date:** 2026-06-18  
**Scope:** commit `90126e2` — `test: add integration test for cyclic dependency detection`  
**File reviewed:** `web/src/test/java/com/example/softwaremetrics/infrastructure/PackageScannerControllerIT.java`  
**Effort:** high (8 finder angles × 6 candidates → 1-vote verify → 5 findings)

---

## Summary

The new `scanReportsCyclicDependencies` test and its helpers are structurally sound and follow the existing project pattern. No production-code bugs were found. Five findings remain after verification: one confirmed cleanup, three plausible correctness/quality gaps, and one efficiency issue.

---

## Findings

### F1 — `internalName` sync hazard causes silent misclassification *(PLAUSIBLE · correctness)*

**Location:** `PackageScannerControllerIT.java:164`

**Problem:**  
`writeCyclicClass` accepts `internalName` as a caller-supplied string, but `ProjectModelBuilder` reads the class name back from `classNode.name` embedded in the bytecode bytes — not from the filesystem path. If a caller passes a mismatched `internalName`, the class is silently registered under the wrong package in `ProjectModel`. No exception is thrown; the cycle simply disappears from the output.

`internalName` is always mechanically derivable from `classFile`:
```
target/classes/com/example/domain/DomainClass.class
  → strip "target/classes/"  → com/example/domain/DomainClass.class
  → strip ".class"           → com/example/domain/DomainClass
```

**Fix:**

Drop the `internalName` parameter and derive it inside `writeCyclicClass`:

```java
private void writeCyclicClass(Path classFile, String methodName,
                               String methodDescriptor) throws IOException {
    // derive internal name from file path: strip everything up to and including "target/classes/"
    String internalName = classFile.toString()
            .replace('\\', '/')
            .replaceAll(".*/target/classes/", "")
            .replace(".class", "");

    ClassWriter cw = new ClassWriter(0);
    cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
    // ... rest unchanged
}
```

Update both call sites to remove the now-redundant argument:

```java
writeCyclicClass(
        projectRoot.resolve("target/classes/com/example/domain/DomainClass.class"),
        "getService", "()Lcom/example/service/ServiceClass;");

writeCyclicClass(
        projectRoot.resolve("target/classes/com/example/service/ServiceClass.class"),
        "getDomain", "()Lcom/example/domain/DomainClass;");
```

---

### F2 — POST `/scan` asserts model attribute but not rendered HTML *(PLAUSIBLE · correctness)*

**Location:** `PackageScannerControllerIT.java:122–125`

**Problem:**  
```java
.andExpect(model().attribute("cycles", hasSize(greaterThanOrEqualTo(1))));
```
This confirms the Spring MVC model was populated with the `"cycles"` key, but does **not** verify the template rendered a banner into the HTML response. If `graph.html`'s `th:if` condition is ever changed to reference a different variable name (e.g. `${cyclicDependencies}`), the HTML banner silently disappears while this assertion stays green.

**Fix:**

Add a `content()` assertion that checks the rendered HTML contains the expected banner text. The current template renders a `<div>` with the text "Circular dependencies detected" when cycles are present:

```java
mockMvc.perform(post("/scan").param("path", cyclicDir.toString()))
        .andExpect(status().isOk())
        .andExpect(view().name("graph :: graph"))
        .andExpect(model().attribute("cycles", hasSize(greaterThanOrEqualTo(1))))
        .andExpect(content().string(containsString("Circular dependencies")));
```

Add the import:
```java
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
```

Verify the exact banner text by reading `web/src/main/resources/templates/graph.html` and matching the `th:if="${cycles != null and !cycles.empty}"` block's visible text.

---

### F3 — `writeCyclicClass` duplicates `writeCompiledClass` constructor and flush tail *(CONFIRMED · cleanup)*

**Location:** `PackageScannerControllerIT.java:164–188` vs `227–246`

**Problem:**  
The 7-line `<init>()V` constructor block and the 2-line disk-flush tail are byte-for-byte identical in both methods:

```java
// constructor block — identical in both methods
MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
ctor.visitCode();
ctor.visitVarInsn(Opcodes.ALOAD, 0);
ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
ctor.visitInsn(Opcodes.RETURN);
ctor.visitMaxs(1, 1);
ctor.visitEnd();

// flush tail — identical in both methods
Files.createDirectories(classFile.getParent());
Files.write(classFile, cw.toByteArray());
```

Any change (e.g. a JDK version bump from `V1_8` to `V21`) must be applied in two places and will drift.

**Fix:**

Extract a shared `emitDefaultConstructor` helper and a `writeClassFile` helper, then have `writeCyclicClass` call `writeCompiledClass`-style shared logic. The simplest approach: merge the two methods into one flexible helper:

```java
/**
 * Emits a minimal public class with a default constructor and one extra method.
 * Pass methodName=null to emit only the constructor.
 */
private void writeAsmClass(Path classFile, String internalName,
                            String methodName, String methodDescriptor) throws IOException {
    ClassWriter cw = new ClassWriter(0);
    cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);

    MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
    ctor.visitCode();
    ctor.visitVarInsn(Opcodes.ALOAD, 0);
    ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
    ctor.visitInsn(Opcodes.RETURN);
    ctor.visitMaxs(1, 1);
    ctor.visitEnd();

    if (methodName != null) {
        boolean isVoid = methodDescriptor.endsWith("V");
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, methodName, methodDescriptor, null, null);
        mv.visitCode();
        if (!isVoid) {
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitInsn(Opcodes.ARETURN);
            mv.visitMaxs(1, 1);
        } else {
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 1);
        }
        mv.visitEnd();
    }

    cw.visitEnd();
    Files.createDirectories(classFile.getParent());
    Files.write(classFile, cw.toByteArray());
}
```

Replace both `writeCyclicClass` and `writeCompiledClass` with calls to `writeAsmClass`.

---

### F4 — `hasItems` + `greaterThanOrEqualTo(1)` is weaker than the comment states *(PLAUSIBLE · cleanup)*

**Location:** `PackageScannerControllerIT.java:117–119`

**Problem:**  
The comment says "exactly one cycle group with both module packages", but the assertions don't enforce this:
- `greaterThanOrEqualTo(1)` passes with 2 or more cycle groups
- `hasItems(...)` is a subset check — passes even if `cycles[0]` contains extra packages

```java
// comment says "exactly one cycle group" but code allows >=1
.andExpect(jsonPath("$.cycles.length()", greaterThanOrEqualTo(1)))
// hasItems is subset — passes if cycles[0] = ["com.example.domain","com.example.service","unexpected"]
.andExpect(jsonPath("$.cycles[0]", hasItems("com.example.domain", "com.example.service")));
```

The current fixture is constrained enough that a false pass is impossible today, but the assertion will silently miss regressions if the fixture or SCC algorithm changes.

**Fix:**

Match the comment's intent precisely:

```java
.andExpect(jsonPath("$.cycles.length()", equalTo(1)))
.andExpect(jsonPath("$.cycles[0].length()", equalTo(2)))
.andExpect(jsonPath("$.cycles[0]",
        containsInAnyOrder("com.example.domain", "com.example.service")));
```

Add the import:
```java
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
```

---

### F5 — Two full bytecode scans per test with no caching *(PLAUSIBLE · efficiency)*

**Location:** `PackageScannerControllerIT.java:113–125`

**Problem:**  
`scanReportsCyclicDependencies` issues two HTTP requests to the same `cyclicDir`:
1. `GET /api/metrics` → calls `analysisService.analyze()`
2. `POST /scan` → calls `analysisService.analyze()` again independently

`AnalysisService` has no result cache. Each call runs a fresh `Files.walk` + ASM bytecode parse + root-package source scan + metrics calculation + Tarjan SCC. This is the only test in the suite that hits two endpoints for the same project path in a single `@Test`.

**Fix (option A — split into two tests):**

Split into two focused `@Test` methods, each with its own `@TempDir`:

```java
@Test
void scanReportsCyclicDependenciesInJsonEnvelope(@TempDir Path cyclicDir) throws Exception {
    createCyclicProjectStructure(cyclicDir);
    mockMvc.perform(get("/api/metrics").param("path", cyclicDir.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.cycles.length()", equalTo(1)))
            .andExpect(jsonPath("$.cycles[0]",
                    containsInAnyOrder("com.example.domain", "com.example.service")));
}

@Test
void scanReportsCyclicDependenciesInThymeleafModel(@TempDir Path cyclicDir) throws Exception {
    createCyclicProjectStructure(cyclicDir);
    mockMvc.perform(post("/scan").param("path", cyclicDir.toString()))
            .andExpect(status().isOk())
            .andExpect(view().name("graph :: graph"))
            .andExpect(model().attribute("cycles", hasSize(greaterThanOrEqualTo(1))))
            .andExpect(content().string(containsString("Circular dependencies")));
}
```

**Fix (option B — keep one test, accept the double scan):**

If keeping a single test is preferred, add a comment explaining the two endpoints are intentionally covered in one shot and the double scan is accepted:

```java
// Two requests are intentional: JSON and Thymeleaf surfaces are only testable
// through their respective endpoints; AnalysisService has no result cache.
```

---

## Fix priority

| # | Finding | Severity | Effort |
|---|---------|----------|--------|
| F1 | `internalName` sync hazard | High | 5 min |
| F3 | Duplicated constructor / flush in `writeCyclicClass` | Medium | 15 min |
| F4 | Weak `hasItems` + `>=1` assertions | Medium | 5 min |
| F2 | POST `/scan` missing HTML content check | Medium | 5 min |
| F5 | Double scan per test | Low | 10 min |

Recommended order: F1 → F4 → F2 → F3 → F5.
