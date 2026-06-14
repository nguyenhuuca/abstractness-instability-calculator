package com.example.softwaremetrics.domain;

import com.example.softwaremetrics.domain.deadcode.DeadCodeDetector;
import com.example.softwaremetrics.domain.deadcode.DeadCodeResult;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("SameParameterValue")
class JavaClassAnalyzerTest {

    private JavaClassAnalyzer javaClassAnalyzer;

    @BeforeEach
    void setUp() {
        javaClassAnalyzer = new JavaClassAnalyzer(TestProperties.defaults());
    }

    @Test
    void testContainsSpringBootApplication(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("TestApplication.java");
        Files.writeString(file, "@SpringBootApplication\npublic class TestApplication {}");
        assertTrue(javaClassAnalyzer.containsSpringBootApplication(file));

        Path nonSpringBootFile = tempDir.resolve("RegularClass.java");
        Files.writeString(nonSpringBootFile, "public class RegularClass {}");
        assertFalse(javaClassAnalyzer.containsSpringBootApplication(nonSpringBootFile));
    }

    @Test
    void testContainsSpringBootApplicationWithAttributes(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("App.java");
        Files.writeString(file, "@SpringBootApplication(scanBasePackages = \"com.example\")\npublic class App {}");
        assertTrue(javaClassAnalyzer.containsSpringBootApplication(file));
    }

    @Test
    void testIgnoresSpringBootApplicationInCommentsAndStrings(@TempDir Path tempDir) throws IOException {
        // The annotation mentioned only in a Javadoc/comment must not count.
        Path comment = tempDir.resolve("Commented.java");
        Files.writeString(comment,
                "/**\n * Checks for the @SpringBootApplication annotation.\n */\npublic class Commented {}");
        assertFalse(javaClassAnalyzer.containsSpringBootApplication(comment));

        // The annotation appearing only inside a string literal must not count
        // (this is what broke scanning of this analyzer's own source).
        Path stringLiteral = tempDir.resolve("Literal.java");
        Files.writeString(stringLiteral,
                "public class Literal {\n    boolean b = line.contains(\"@SpringBootApplication\");\n}");
        assertFalse(javaClassAnalyzer.containsSpringBootApplication(stringLiteral));
    }

    @Test
    void testAnalyzeClassesExcludesCompiledTestClasses(@TempDir Path tempDir) throws IOException {
        // A main class and a test class for the same package, under Maven output dirs.
        createTestClass(tempDir.resolve("target/classes"),
                "com/example/subpackage/Bar.class", "com.example.subpackage.Bar", false, "com.example.subpackage.Bar");
        createTestClass(tempDir.resolve("target/test-classes"),
                "com/example/subpackage/FooTest.class", "com.example.subpackage.FooTest", false, "com.example.subpackage.Bar");

        List<String> packages = List.of("com.example.subpackage");
        Map<String, Set<String>> outgoing = new ConcurrentHashMap<>();
        Map<String, Set<String>> incoming = new ConcurrentHashMap<>();
        Map<String, Integer> abstractCount = new HashMap<>();
        Map<String, Integer> totalCount = new HashMap<>();
        Map<String, ComplexityStats> complexity = new HashMap<>();

        javaClassAnalyzer.analyzeClasses(tempDir, packages, outgoing, incoming, abstractCount, totalCount, complexity);

        // Only Bar (under target/classes) is counted; FooTest under target/test-classes is excluded.
        assertEquals(1, totalCount.get("com.example.subpackage"));
    }

    @Test
    void testExtractPackage(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("TestClass.java");
        Files.writeString(file, "package com.example.test;\npublic class TestClass {}");
        assertEquals("com.example.test", javaClassAnalyzer.extractPackage(file));

        Path noPackageFile = tempDir.resolve("NoPackageClass.java");
        Files.writeString(noPackageFile, "public class NoPackageClass {}");
        assertEquals("", javaClassAnalyzer.extractPackage(noPackageFile));
    }

    @Test
    void testAnalyzeClasses(@TempDir Path tempDir) throws IOException {
        // Create a simple project structure
        Path srcMainJava = tempDir.resolve("src/main/java");
        Files.createDirectories(srcMainJava);

        // Create test classes with dependencies
        createTestClass(srcMainJava, "com/example/anothersubpackage/ClassA.class", "com.example.anothersubpackage.ClassA", false, "com.example.subpackage.ClassC");
        createTestClass(srcMainJava, "com/example/anothersubpackage/ClassB.class", "com.example.anothersubpackage.ClassB", true, "com.example.subpackage.ClassC");
        createTestClass(srcMainJava, "com/example/subpackage/ClassC.class", "com.example.subpackage.ClassC", false, "com.example.anothersubpackage.ClassA");
        createTestClassWithJavaLangDependency(srcMainJava, "com/example/anothersubpackage/ClassD.class", "com.example.anothersubpackage.ClassD", false);

        // Prepare input for analyzeClasses
        List<String> packages = Arrays.asList("com.example.anothersubpackage", "com.example.subpackage");
        Map<String, Set<String>> outgoingDependencies = new ConcurrentHashMap<>();
        Map<String, Set<String>> incomingDependencies = new ConcurrentHashMap<>();
        Map<String, Integer> abstractClassCount = new HashMap<>();
        Map<String, Integer> totalClassCount = new HashMap<>();
        Map<String, ComplexityStats> complexity = new HashMap<>();

        // Run the analysis
        javaClassAnalyzer.analyzeClasses(tempDir, packages, outgoingDependencies, incomingDependencies, abstractClassCount, totalClassCount, complexity);

        // Verify the results
        assertEquals(3, totalClassCount.get("com.example.anothersubpackage"));
        assertEquals(1, abstractClassCount.get("com.example.anothersubpackage"));
        assertEquals(1, totalClassCount.get("com.example.subpackage"));
        assertNull(abstractClassCount.get("com.example.subpackage"));

        assertTrue(outgoingDependencies.get("com.example.anothersubpackage").contains("com.example.subpackage.ClassC"));
        assertTrue(outgoingDependencies.get("com.example.subpackage").contains("com.example.anothersubpackage.ClassA"));
        assertTrue(incomingDependencies.get("com.example.anothersubpackage").contains("com.example.subpackage.ClassC"));
        assertTrue(incomingDependencies.get("com.example.subpackage").contains("com.example.anothersubpackage.ClassA"));

        assertEquals(1, outgoingDependencies.get("com.example.anothersubpackage").size());
        assertEquals(1, outgoingDependencies.get("com.example.subpackage").size());
        assertEquals(1, incomingDependencies.get("com.example.anothersubpackage").size());
        assertEquals(2, incomingDependencies.get("com.example.subpackage").size());

        // Verify that java.lang dependencies are not included
        assertFalse(outgoingDependencies.get("com.example.anothersubpackage").contains("java.lang.String"));

        // Complexity is recorded per package (each method has at least complexity 1).
        assertTrue(complexity.get("com.example.anothersubpackage").methodCount() > 0);
        assertTrue(complexity.get("com.example.anothersubpackage").maxComplexity() >= 1);
    }

    @Test
    void testAnalyzeProjectCapturesReferences(@TempDir Path tempDir) throws IOException {
        createTestClass(tempDir, "com/app/web/FooController.class", "com.app.web.FooController", false, "com.app.service.FooService");
        createTestClass(tempDir, "com/app/service/FooService.class", "com.app.service.FooService", false, "com.app.service.FooService");

        List<ClassInfo> model = javaClassAnalyzer.analyzeProject(tempDir, "com.app");

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

        List<ClassInfo> model = javaClassAnalyzer.analyzeProject(tempDir, "com.app");

        ClassInfo impl = model.stream()
                .filter(c -> c.fqcn().endsWith("InviteServiceImpl"))
                .findFirst().orElseThrow();
        assertTrue(impl.firstPartyClassRefs().contains("com.app.service.InviteService"),
                "the implemented interface must be a reference");

        // and therefore the interface is NOT reported as dead code
        DeadCodeResult dead = new DeadCodeDetector().detect(model);
        assertFalse(dead.unusedClasses().contains("com.app.service.InviteService"));
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

    @Test
    void testBuildClassDependencyGraph(@TempDir Path tempDir) throws IOException {
        // FooController -> FooService -> FooRepo (FooRepo only depends on java.lang, which is excluded)
        createTestClass(tempDir, "com/app/web/FooController.class", "com.app.web.FooController", false, "com.app.service.FooService");
        createTestClass(tempDir, "com/app/service/FooService.class", "com.app.service.FooService", false, "com.app.repo.FooRepo");
        createTestClassWithJavaLangDependency(tempDir, "com/app/repo/FooRepo.class", "com.app.repo.FooRepo", false);

        Map<String, Set<String>> graph = javaClassAnalyzer.buildClassDependencyGraph(tempDir, "com.app");

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

    private void createTestClass(Path baseDir, String classPath, String className, boolean isAbstract, String dependencyClass) throws IOException {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, isAbstract ? Opcodes.ACC_PUBLIC + Opcodes.ACC_ABSTRACT : Opcodes.ACC_PUBLIC, 
                 className.replace('.', '/'), null, "java/lang/Object", null);

        // Add a constructor
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();

        // Add a method that references another class
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

        Path fullPath = baseDir.resolve(classPath);
        Files.createDirectories(fullPath.getParent());
        Files.write(fullPath, cw.toByteArray());
    }

    private void createTestClassWithJavaLangDependency(Path baseDir, String classPath, String className, boolean isAbstract) throws IOException {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, isAbstract ? Opcodes.ACC_PUBLIC + Opcodes.ACC_ABSTRACT : Opcodes.ACC_PUBLIC, 
                 className.replace('.', '/'), null, "java/lang/Object", null);

        // Add a constructor
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();

        // Add a method that uses java.lang.String
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "someMethod", "()Ljava/lang/String;", null, null);
        mv.visitCode();
        mv.visitLdcInsn("Hello, World!");
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();

        cw.visitEnd();

        Path fullPath = baseDir.resolve(classPath);
        Files.createDirectories(fullPath.getParent());
        Files.write(fullPath, cw.toByteArray());
    }
}