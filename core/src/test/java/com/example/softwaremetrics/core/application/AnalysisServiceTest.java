package com.example.softwaremetrics.core.application;

import com.example.softwaremetrics.core.config.CheckConfigLoader;
import com.example.softwaremetrics.core.config.Defaults;
import com.example.softwaremetrics.core.domain.GateResult;
import com.example.softwaremetrics.core.domain.arch.ArchResult;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the facade orchestrates a full scan end-to-end (config → metrics → cycles → gate →
 * export) over a synthetic compiled project, and that gate evaluation is toggled by the request.
 */
class AnalysisServiceTest {

    private AnalysisService service;

    @BeforeEach
    void setUp() {
        service = AnalysisService.create(Defaults.exclusions());
    }

    @Test
    void analyzesSyntheticProjectAndAssemblesExport(@TempDir Path tempDir) throws IOException {
        syntheticProject(tempDir);

        AnalysisResult result = service.analyze(
                new AnalysisRequest(tempDir.toString(), CheckConfigLoader.Overrides.none(), "9.9-TEST", true));

        // Modules emerge from the two sub-packages.
        assertTrue(result.metrics().containsKey("com.example.web"));
        assertTrue(result.metrics().containsKey("com.example.service"));

        // The export envelope is assembled with metadata + gate.
        assertNotNull(result.export());
        assertEquals("9.9-TEST", result.export().toolVersion());
        assertEquals(result.metrics().size(), result.export().packageCount());
        assertNotNull(result.gate());
        assertSame(result.gate(), result.export().gate());

        // No banned/dead-code/arch configured → those stay empty/null.
        assertTrue(result.bannedApiViolations().isEmpty());
        assertNull(result.architecture());
        assertNull(result.deadCode());
        assertTrue(result.success());
    }

    @Test
    void skipsGateEvaluationWhenNotRequested(@TempDir Path tempDir) throws IOException {
        syntheticProject(tempDir);

        AnalysisResult result = service.analyze(AnalysisRequest.of(tempDir.toString(), "9.9-TEST"));

        assertNull(result.gate(), "web-style request must not evaluate gates");
        assertNull(result.export().gate(), "and the gate must be omitted from the export");
        assertFalse(result.metrics().isEmpty());
    }

    @Test
    void scansPlainJavaProjectViaCommonPrefix(@TempDir Path tempDir) throws IOException {
        // No @SpringBootApplication, no src/main/java, no aic-check.yaml — the root package is inferred
        // as the common prefix com.plain, and its sub-packages a/b become modules.
        writeClass(tempDir.resolve("target/classes"), "com/plain/a/Foo.class",
                "com.plain.a.Foo", "com.plain.b.Bar");
        writeClass(tempDir.resolve("target/classes"), "com/plain/b/Bar.class",
                "com.plain.b.Bar", "com.plain.a.Foo");

        AnalysisResult result = service.analyze(AnalysisRequest.of(tempDir.toString(), "9.9-TEST"));

        assertTrue(result.metrics().containsKey("com.plain.a"));
        assertTrue(result.metrics().containsKey("com.plain.b"));
    }

    @Test
    void throwsWhenRootPackageCannotBeDetermined(@TempDir Path tempDir) throws IOException {
        Files.createDirectories(tempDir.resolve("src/main/java")); // empty, no classes at all
        assertThrows(IllegalArgumentException.class,
                () -> service.analyze(AnalysisRequest.of(tempDir.toString(), "9.9-TEST")));
    }

    // -------------------------------------------------------------------------
    // AnalysisRequest builder methods
    // -------------------------------------------------------------------------

    @Test
    void withOverridesReturnsCopyWithNewOverrides() {
        AnalysisRequest base = AnalysisRequest.of("/tmp", "1.0");
        CheckConfigLoader.Overrides overrides = new CheckConfigLoader.Overrides(0.5, true, "layered");
        AnalysisRequest withOv = base.withOverrides(overrides);
        assertThat(withOv.overrides()).isEqualTo(overrides);
        // original is unchanged
        assertThat(base.overrides()).isEqualTo(CheckConfigLoader.Overrides.none());
    }

    @Test
    void withGateEvaluationReturnsCopyWithGateFlag() {
        AnalysisRequest base = AnalysisRequest.of("/tmp", "1.0");
        // default is false (no gate)
        assertFalse(base.evaluateGates());
        AnalysisRequest withGate = base.withGateEvaluation(false);
        assertFalse(withGate.evaluateGates());
        AnalysisRequest withGateOn = base.withGateEvaluation(true);
        assertTrue(withGateOn.evaluateGates());
        // original still false
        assertFalse(base.evaluateGates());
    }

    // -------------------------------------------------------------------------
    // AnalysisResult.success() branches
    // -------------------------------------------------------------------------

    @Test
    void successIsTrueWhenGateIsNull() {
        // gate = null means gates were not evaluated; success must be true regardless
        AnalysisResult result = new AnalysisResult(null, java.util.Map.of(),
                List.of(), null, null, List.of(), null);
        assertTrue(result.success());
    }

    @Test
    void successIsTrueWhenGatePassedAndArchNull() {
        GateResult passedGate = new GateResult(true, List.of());
        AnalysisResult result = new AnalysisResult(null, java.util.Map.of(),
                List.of(), passedGate, null, List.of(), null);
        assertTrue(result.success());
    }

    @Test
    void successIsFalseWhenGateFailed() {
        GateResult failedGate = new GateResult(false,
                List.of(new GateResult.Violation("maxPackageDistance", "com.example.web", 0.9, 0.5, "D too high")));
        AnalysisResult result = new AnalysisResult(null, java.util.Map.of(),
                List.of(), failedGate, null, List.of(), null);
        assertFalse(result.success());
    }

    @Test
    void successIsTrueWhenGatePassedAndArchCompliant() {
        GateResult passedGate = new GateResult(true, List.of());
        ArchResult compliantArch = new ArchResult("layered", true, List.of());
        AnalysisResult result = new AnalysisResult(null, java.util.Map.of(),
                List.of(), passedGate, compliantArch, List.of(), null);
        assertTrue(result.success());
    }

    @Test
    void successIsFalseWhenArchNonCompliant() {
        GateResult passedGate = new GateResult(true, List.of());
        ArchResult nonCompliantArch = new ArchResult("layered", false,
                List.of(new ArchResult.Violation("forbiddenDependency", "web", "domain", "forbidden")));
        AnalysisResult result = new AnalysisResult(null, java.util.Map.of(),
                List.of(), passedGate, nonCompliantArch, List.of(), null);
        assertFalse(result.success());
    }

    // -------------------------------------------------------------------------
    // expandedFqns — indirectly via aic-check.yaml with analyze.expand entries
    // -------------------------------------------------------------------------

    @Test
    void expandedFqnsUsesFullyQualifiedEntryUnchanged(@TempDir Path tempDir) throws IOException {
        // synthetic project where web has two sub-packages: web.rest and web.filter
        syntheticProjectWithWebSubpackages(tempDir);

        // Write an aic-check.yaml that expands "web" and also provides a fully-qualified entry
        // (starts with mainPackage) to exercise the "already FQN" branch
        Path yaml = tempDir.resolve("src/main/resources/aic-check.yaml");
        Files.createDirectories(yaml.getParent());
        Files.writeString(yaml, """
                analyze:
                  expand:
                    - web
                    - com.example.web
                """);

        AnalysisResult result = service.analyze(AnalysisRequest.of(tempDir.toString(), "9.9-TEST"));

        // web.rest and web.filter should become separate modules (expanded)
        assertThat(result.metrics()).containsKey("com.example.web.rest");
        assertThat(result.metrics()).containsKey("com.example.web.filter");
        // service remains a top-level module
        assertThat(result.metrics()).containsKey("com.example.service");
    }

    @Test
    void expandedFqnsSkipsBlankEntries(@TempDir Path tempDir) throws IOException {
        // synthetic project with a normal two-module layout
        syntheticProject(tempDir);

        // aic-check.yaml with blank expand entries — should be silently ignored
        Path yaml = tempDir.resolve("src/main/resources/aic-check.yaml");
        Files.createDirectories(yaml.getParent());
        Files.writeString(yaml, """
                analyze:
                  expand:
                    - ""
                    - "   "
                """);

        // scan must succeed and produce the normal modules (no crash on blank entries)
        AnalysisResult result = service.analyze(AnalysisRequest.of(tempDir.toString(), "9.9-TEST"));
        assertThat(result.metrics()).containsKey("com.example.web");
        assertThat(result.metrics()).containsKey("com.example.service");
    }

    // -------------------------------------------------------------------------
    // runChecks — architecture path via CLI Overrides.archRef
    // -------------------------------------------------------------------------

    @Test
    void runChecksPopulatesArchResultWhenArchRefSupplied(@TempDir Path tempDir) throws IOException {
        syntheticProject(tempDir);

        // Pass the "layered" built-in template via CLI overrides — exercises the arch check branch
        CheckConfigLoader.Overrides overrides = new CheckConfigLoader.Overrides(null, false, "layered");
        AnalysisRequest req = AnalysisRequest.of(tempDir.toString(), "9.9-TEST")
                .withOverrides(overrides);
        AnalysisResult result = service.analyze(req);

        assertNotNull(result.architecture(), "arch check must run when archRef is set");
        assertNotNull(result.architecture().specName());
    }

    // -------------------------------------------------------------------------
    // runChecks — dead-code path via aic-check.yaml
    // -------------------------------------------------------------------------

    @Test
    void runChecksPopulatesDeadCodeWhenEnabledInConfig(@TempDir Path tempDir) throws IOException {
        syntheticProject(tempDir);

        Path yaml = tempDir.resolve("src/main/resources/aic-check.yaml");
        Files.createDirectories(yaml.getParent());
        Files.writeString(yaml, """
                dead-code:
                  enabled: true
                """);

        AnalysisResult result = service.analyze(AnalysisRequest.of(tempDir.toString(), "9.9-TEST"));

        assertNotNull(result.deadCode(), "dead-code result must be populated when enabled");
        // The DeadCodeResult may or may not list classes, but the object must be present.
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** A minimal compiled project: a @SpringBootApplication source + two module classes that depend on each other. */
    private void syntheticProject(Path projectRoot) throws IOException {
        Path srcMainJava = projectRoot.resolve("src/main/java");
        Files.createDirectories(srcMainJava.resolve("com/example"));
        Files.writeString(srcMainJava.resolve("com/example/DemoApplication.java"),
                "package com.example;\n@SpringBootApplication\npublic class DemoApplication {}");

        writeClass(srcMainJava, "com/example/web/FooController.class",
                "com.example.web.FooController", "com.example.service.FooService");
        writeClass(srcMainJava, "com/example/service/FooService.class",
                "com.example.service.FooService", "com.example.web.FooController");
    }

    /**
     * A project with three modules: web.rest, web.filter (sub-packages of web) and service —
     * used to verify the expand configuration splits the web module finer.
     */
    private void syntheticProjectWithWebSubpackages(Path projectRoot) throws IOException {
        Path srcMainJava = projectRoot.resolve("src/main/java");
        Files.createDirectories(srcMainJava.resolve("com/example"));
        Files.writeString(srcMainJava.resolve("com/example/DemoApplication.java"),
                "package com.example;\n@SpringBootApplication\npublic class DemoApplication {}");

        writeClass(srcMainJava, "com/example/web/rest/RestController.class",
                "com.example.web.rest.RestController", "com.example.service.FooService");
        writeClass(srcMainJava, "com/example/web/filter/AuthFilter.class",
                "com.example.web.filter.AuthFilter", "com.example.service.FooService");
        writeClass(srcMainJava, "com/example/service/FooService.class",
                "com.example.service.FooService", "com.example.web.rest.RestController");
    }

    private void writeClass(Path baseDir, String classPath, String className, String dependency) throws IOException {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, className.replace('.', '/'), null, "java/lang/Object", null);
        MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(1, 1);
        ctor.visitEnd();
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "use", "()V", null, null);
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, dependency.replace('.', '/'));
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(2, 1);
        mv.visitEnd();
        cw.visitEnd();

        Path full = baseDir.resolve(classPath);
        Files.createDirectories(full.getParent());
        Files.write(full, cw.toByteArray());
    }
}
