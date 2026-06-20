package com.example.softwaremetrics.infrastructure;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Shared synthetic-project builders for the web integration tests ({@code PackageScannerControllerIT}
 * and {@code PackageScannerE2EIT}). The analyzer reads compiled bytecode, so each fixture emits both a
 * {@code @SpringBootApplication} source marker (for root-package resolution) and matching {@code .class}
 * files under {@code target/classes} via ASM.
 */
final class TestProjectFixtures {

    private TestProjectFixtures() {
    }

    /** A minimal valid project with one module package {@code com.example.subpackage}. */
    static void createTestProjectStructure(Path projectRoot) throws IOException {
        Path mainAppPath = projectRoot.resolve("src/main/java/com/example/TestApplication.java");
        Files.createDirectories(mainAppPath.getParent());
        Files.writeString(mainAppPath, """
                package com.example;
                import org.springframework.boot.SpringApplication;
                import org.springframework.boot.autoconfigure.SpringBootApplication;
                @SpringBootApplication
                public class TestApplication {
                    public static void main(String[] args) {
                        SpringApplication.run(TestApplication.class, args);
                    }
                }
                """);
        writeCompiledClass(
                projectRoot.resolve("target/classes/com/example/subpackage/TestClass.class"),
                "com/example/subpackage/TestClass");
    }

    /**
     * A project where {@code com.example.domain} and {@code com.example.service} form a mutual
     * dependency (each declares a method returning the other), producing exactly one package cycle.
     */
    static void createCyclicProjectStructure(Path projectRoot) throws IOException {
        Path mainApp = projectRoot.resolve("src/main/java/com/example/CyclicTestApp.java");
        Files.createDirectories(mainApp.getParent());
        Files.writeString(mainApp, """
                package com.example;
                import org.springframework.boot.autoconfigure.SpringBootApplication;
                @SpringBootApplication
                public class CyclicTestApp {}
                """);
        writeCyclicClass(
                projectRoot.resolve("target/classes/com/example/domain/DomainClass.class"),
                "com/example/domain/DomainClass",
                "getService", "()Lcom/example/service/ServiceClass;");
        writeCyclicClass(
                projectRoot.resolve("target/classes/com/example/service/ServiceClass.class"),
                "com/example/service/ServiceClass",
                "getDomain", "()Lcom/example/domain/DomainClass;");
    }

    private static void writeCompiledClass(Path classFile, String internalName) throws IOException {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        emitDefaultConstructor(cw);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "testMethod", "()V", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 1);
        mv.visitEnd();
        writeClassToFile(cw, classFile);
    }

    private static void writeCyclicClass(Path classFile, String internalName,
                                         String methodName, String methodDescriptor) throws IOException {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        emitDefaultConstructor(cw);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, methodName, methodDescriptor, null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
        writeClassToFile(cw, classFile);
    }

    private static void emitDefaultConstructor(ClassWriter cw) {
        MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(1, 1);
        ctor.visitEnd();
    }

    private static void writeClassToFile(ClassWriter cw, Path classFile) throws IOException {
        cw.visitEnd();
        Files.createDirectories(classFile.getParent());
        Files.write(classFile, cw.toByteArray());
    }
}
