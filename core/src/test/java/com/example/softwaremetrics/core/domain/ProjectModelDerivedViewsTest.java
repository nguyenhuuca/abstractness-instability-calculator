package com.example.softwaremetrics.core.domain;

import com.example.softwaremetrics.core.config.Defaults;
import com.example.softwaremetrics.core.domain.bytecode.DependencyExclusions;
import com.example.softwaremetrics.core.domain.bytecode.ProjectModelBuilder;
import com.example.softwaremetrics.core.domain.deadcode.DeadCodeDetector;
import com.example.softwaremetrics.core.domain.deadcode.DeadCodeResult;
import com.example.softwaremetrics.core.domain.model.ProjectModel;

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
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the derived views of a {@link ProjectModel} that the production pipeline consumes —
 * per-module metric aggregation ({@link MetricsAggregator#aggregate}), the class dependency graph
 * ({@link ProjectModel#classDependencyGraph}) and the per-class info list
 * ({@link ProjectModel#classInfos}). These were previously routed through the (now removed)
 * {@code JavaClassAnalyzer} facade; they now go straight through {@link ProjectModelBuilder}.
 */
@SuppressWarnings("SameParameterValue")
class ProjectModelDerivedViewsTest {

    private ProjectModelBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new ProjectModelBuilder(new DependencyExclusions(Defaults.exclusions()));
    }

    @Test
    void aggregateExcludesCompiledTestClasses(@TempDir Path tempDir) throws IOException {
        // A main class and a test class for the same package, under Maven output dirs.
        createTestClass(tempDir.resolve("target/classes"),
                "com/example/subpackage/Bar.class", "com.example.subpackage.Bar", false, "com.example.subpackage.Bar");
        createTestClass(tempDir.resolve("target/test-classes"),
                "com/example/subpackage/FooTest.class", "com.example.subpackage.FooTest", false, "com.example.subpackage.Bar");

        ModuleResolver resolver = new ModuleResolver("com.example", 1, Set.of());
        RawModuleMetrics raw = MetricsAggregator.aggregate(builder.build(tempDir), resolver);

        // Only Bar (under target/classes) is counted; FooTest under target/test-classes is excluded.
        assertEquals(1, raw.totalClassCount().get("com.example.subpackage"));
    }

    @Test
    void aggregateComputesPerModuleCouplingAndComplexity(@TempDir Path tempDir) throws IOException {
        Path srcMainJava = tempDir.resolve("src/main/java");
        Files.createDirectories(srcMainJava);

        createTestClass(srcMainJava, "com/example/anothersubpackage/ClassA.class", "com.example.anothersubpackage.ClassA", false, "com.example.subpackage.ClassC");
        createTestClass(srcMainJava, "com/example/anothersubpackage/ClassB.class", "com.example.anothersubpackage.ClassB", true, "com.example.subpackage.ClassC");
        createTestClass(srcMainJava, "com/example/subpackage/ClassC.class", "com.example.subpackage.ClassC", false, "com.example.anothersubpackage.ClassA");
        createTestClassWithJavaLangDependency(srcMainJava, "com/example/anothersubpackage/ClassD.class", "com.example.anothersubpackage.ClassD", false);

        ModuleResolver resolver = new ModuleResolver("com.example", 1, Set.of());
        RawModuleMetrics raw = MetricsAggregator.aggregate(builder.build(tempDir), resolver);

        assertEquals(3, raw.totalClassCount().get("com.example.anothersubpackage"));
        assertEquals(1, raw.abstractClassCount().get("com.example.anothersubpackage"));
        assertEquals(1, raw.totalClassCount().get("com.example.subpackage"));
        assertNull(raw.abstractClassCount().get("com.example.subpackage"));

        assertTrue(raw.outgoingDependencies().get("com.example.anothersubpackage").contains("com.example.subpackage.ClassC"));
        assertTrue(raw.outgoingDependencies().get("com.example.subpackage").contains("com.example.anothersubpackage.ClassA"));
        assertTrue(raw.incomingDependencies().get("com.example.anothersubpackage").contains("com.example.subpackage.ClassC"));
        assertTrue(raw.incomingDependencies().get("com.example.subpackage").contains("com.example.anothersubpackage.ClassA"));

        assertEquals(1, raw.outgoingDependencies().get("com.example.anothersubpackage").size());
        assertEquals(1, raw.outgoingDependencies().get("com.example.subpackage").size());
        assertEquals(1, raw.incomingDependencies().get("com.example.anothersubpackage").size());
        assertEquals(2, raw.incomingDependencies().get("com.example.subpackage").size());

        // java.lang dependencies are excluded.
        assertFalse(raw.outgoingDependencies().get("com.example.anothersubpackage").contains("java.lang.String"));

        // Complexity is recorded per package (each method has at least complexity 1).
        assertTrue(raw.complexity().get("com.example.anothersubpackage").methodCount() > 0);
        assertTrue(raw.complexity().get("com.example.anothersubpackage").maxComplexity() >= 1);
    }

    @Test
    void classInfosCapturesReferences(@TempDir Path tempDir) throws IOException {
        createTestClass(tempDir, "com/app/web/FooController.class", "com.app.web.FooController", false, "com.app.service.FooService");
        createTestClass(tempDir, "com/app/service/FooService.class", "com.app.service.FooService", false, "com.app.service.FooService");

        List<ClassInfo> model = builder.build(tempDir).classInfos("com.app");

        ClassInfo controller = model.stream()
                .filter(c -> c.fqcn().equals("com.app.web.FooController"))
                .findFirst().orElseThrow();
        assertFalse(controller.entryPoint());
        assertTrue(controller.firstPartyClassRefs().contains("com.app.service.FooService"));
        assertTrue(controller.typeRefs().contains("com.app.service.FooService"));
    }

    @Test
    void implementedInterfaceCountsAsReferenced(@TempDir Path tempDir) throws IOException {
        writeInterface(tempDir, "com/app/service/InviteService.class", "com.app.service.InviteService");
        writeImplementingClass(tempDir, "com/app/service/impl/InviteServiceImpl.class",
                "com.app.service.impl.InviteServiceImpl", "com.app.service.InviteService");

        List<ClassInfo> model = builder.build(tempDir).classInfos("com.app");

        ClassInfo impl = model.stream()
                .filter(c -> c.fqcn().endsWith("InviteServiceImpl"))
                .findFirst().orElseThrow();
        assertTrue(impl.firstPartyClassRefs().contains("com.app.service.InviteService"),
                "the implemented interface must be a reference");

        // and therefore the interface is NOT reported as dead code
        DeadCodeResult dead = new DeadCodeDetector().detect(model);
        assertFalse(dead.unusedClasses().contains("com.app.service.InviteService"));
    }

    @Test
    void usedAnnotationCountsAsReferenced(@TempDir Path tempDir) throws IOException {
        writeAnnotation(tempDir, "com/app/aop/AuditLog.class", "com.app.aop.AuditLog");
        writeClassWithMethodAnnotation(tempDir, "com/app/service/Svc.class", "com.app.service.Svc", "com.app.aop.AuditLog");

        List<ClassInfo> model = builder.build(tempDir).classInfos("com.app");

        ClassInfo svc = model.stream().filter(c -> c.fqcn().endsWith(".Svc")).findFirst().orElseThrow();
        assertTrue(svc.typeRefs().contains("com.app.aop.AuditLog"), "the applied annotation must be a reference");

        DeadCodeResult dead = new DeadCodeDetector().detect(model);
        assertFalse(dead.unusedClasses().contains("com.app.aop.AuditLog"));
    }

    @Test
    void classDependencyGraphCapturesFirstPartyEdgesOnly(@TempDir Path tempDir) throws IOException {
        // FooController -> FooService -> FooRepo (FooRepo only depends on java.lang, which is excluded)
        createTestClass(tempDir, "com/app/web/FooController.class", "com.app.web.FooController", false, "com.app.service.FooService");
        createTestClass(tempDir, "com/app/service/FooService.class", "com.app.service.FooService", false, "com.app.repo.FooRepo");
        createTestClassWithJavaLangDependency(tempDir, "com/app/repo/FooRepo.class", "com.app.repo.FooRepo", false);

        Map<String, Set<String>> graph = builder.build(tempDir).classDependencyGraph("com.app");

        // Every first-party class is a node, even one with no first-party dependencies.
        assertTrue(graph.containsKey("com.app.web.FooController"));
        assertTrue(graph.containsKey("com.app.service.FooService"));
        assertTrue(graph.containsKey("com.app.repo.FooRepo"));

        // First-party edges are captured...
        assertTrue(graph.get("com.app.web.FooController").contains("com.app.service.FooService"));
        assertTrue(graph.get("com.app.service.FooService").contains("com.app.repo.FooRepo"));

        // ...and excluded / external dependencies (java.lang.String) are not.
        assertFalse(graph.get("com.app.repo.FooRepo").contains("java.lang.String"));
        assertFalse(graph.containsKey("java.lang.String"));
    }

    // ------------------------------------------------------------------ helpers

    private void createTestClass(Path baseDir, String classPath, String className, boolean isAbstract, String dependencyClass) throws IOException {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, isAbstract ? Opcodes.ACC_PUBLIC + Opcodes.ACC_ABSTRACT : Opcodes.ACC_PUBLIC,
                className.replace('.', '/'), null, "java/lang/Object", null);

        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();

        mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "someMethod", "()V", null, null);
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, dependencyClass.replace('.', '/'));
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, dependencyClass.replace('.', '/'), "<init>", "()V", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(2, 1);
        mv.visitEnd();

        cw.visitEnd();
        writeBytes(baseDir, classPath, cw.toByteArray());
    }

    private void createTestClassWithJavaLangDependency(Path baseDir, String classPath, String className, boolean isAbstract) throws IOException {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, isAbstract ? Opcodes.ACC_PUBLIC + Opcodes.ACC_ABSTRACT : Opcodes.ACC_PUBLIC,
                className.replace('.', '/'), null, "java/lang/Object", null);

        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();

        mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "someMethod", "()Ljava/lang/String;", null, null);
        mv.visitCode();
        mv.visitLdcInsn("Hello, World!");
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();

        cw.visitEnd();
        writeBytes(baseDir, classPath, cw.toByteArray());
    }

    private void writeAnnotation(Path baseDir, String classPath, String className) throws IOException {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_ANNOTATION | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
                className.replace('.', '/'), null, "java/lang/Object",
                new String[]{"java/lang/annotation/Annotation"});
        cw.visitEnd();
        writeBytes(baseDir, classPath, cw.toByteArray());
    }

    private void writeClassWithMethodAnnotation(Path baseDir, String classPath, String className, String annotationName) throws IOException {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, className.replace('.', '/'), null, "java/lang/Object", null);
        MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(1, 1);
        ctor.visitEnd();
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "doWork", "()V", null, null);
        mv.visitAnnotation("L" + annotationName.replace('.', '/') + ";", true).visitEnd();
        mv.visitCode();
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        writeBytes(baseDir, classPath, cw.toByteArray());
    }

    private void writeInterface(Path baseDir, String classPath, String className) throws IOException {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
                className.replace('.', '/'), null, "java/lang/Object", null);
        cw.visitEnd();
        writeBytes(baseDir, classPath, cw.toByteArray());
    }

    private void writeImplementingClass(Path baseDir, String classPath, String className, String interfaceName) throws IOException {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, className.replace('.', '/'), null, "java/lang/Object",
                new String[]{interfaceName.replace('.', '/')});
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
        cw.visitEnd();
        writeBytes(baseDir, classPath, cw.toByteArray());
    }

    private void writeBytes(Path baseDir, String classPath, byte[] bytes) throws IOException {
        Path full = baseDir.resolve(classPath);
        Files.createDirectories(full.getParent());
        Files.write(full, bytes);
    }
}
