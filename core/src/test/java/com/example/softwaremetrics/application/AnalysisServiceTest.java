package com.example.softwaremetrics.application;

import com.example.softwaremetrics.config.CheckConfigLoader;
import com.example.softwaremetrics.config.Defaults;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
