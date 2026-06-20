package com.example.softwaremetrics.core.domain;

import com.example.softwaremetrics.core.domain.bytecode.DependencyExclusions;
import com.example.softwaremetrics.core.domain.bytecode.ProjectModelBuilder;
import com.example.softwaremetrics.core.domain.model.ProjectModel;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class PackageMetricsCalculatorTest {

    private static final Logger logger = LoggerFactory.getLogger(PackageMetricsCalculatorTest.class);

    @TempDir
    Path tempDir;

    private PackageMetricsCalculator calculator;
    private ProjectModelBuilder modelBuilder;

    @BeforeEach
    void setUp() {
        calculator = new PackageMetricsCalculator();
        modelBuilder = new ProjectModelBuilder(new DependencyExclusions(TestProperties.defaults()));
        logger.info("Set up PackageMetricsCalculator for testing");
    }

    @Test
    void testCalculateMetrics() throws IOException {
        logger.info("Starting testCalculateMetrics");

        createMockProjectStructure();

        // main package = com.example → modules are its sub-packages: service, model.
        ProjectModel projectModel = modelBuilder.build(tempDir);
        Map<String, PackageMetrics> metrics =
                calculator.calculateMetrics(projectModel, new ModuleResolver("com.example", 1, Set.of()));

        logger.debug("Calculated metrics: {}", metrics);

        assertNotNull(metrics, "Metrics should not be null");
        assertEquals(2, metrics.size(), "Should have metrics for the 2 module packages");

        // service: 2 classes (1 abstract) → A=0.5; depends on model (Ce=1), nothing depends on it (Ca=0) → I=1.0
        PackageMetrics service = metrics.get("com.example.service");
        assertNotNull(service, "Metrics for com.example.service should exist");
        assertEquals(0.5, service.getAbstractness(), "Abstractness for service should be 0.5");
        assertEquals(1.0, service.getInstability(), "Instability for service should be 1.0");
        assertEquals(0.5, service.getDistance(), "Distance for service should be 0.5");

        // model: 1 concrete class → A=0; service depends on it (Ca=1), it depends on nothing (Ce=0) → I=0
        PackageMetrics model = metrics.get("com.example.model");
        assertNotNull(model, "Metrics for com.example.model should exist");
        assertEquals(0.0, model.getAbstractness(), "Abstractness for model should be 0.0");
        assertEquals(0.0, model.getInstability(), "Instability for model should be 0.0");
        assertEquals(1.0, model.getDistance(), "Distance for model should be 1.0");

        logger.info("testCalculateMetrics completed successfully");
    }

    private void createMockProjectStructure() throws IOException {
        logger.info("Creating mock project structure in {}", tempDir);

        Path service = Files.createDirectories(tempDir.resolve("com/example/service"));
        Path model = Files.createDirectories(tempDir.resolve("com/example/model"));

        createMockClassFile(service.resolve("ServiceClass.class"), "com.example.service.ServiceClass", false, "com.example.model.ModelClass");
        createMockClassFile(service.resolve("ServiceApi.class"), "com.example.service.ServiceApi", true);
        createMockClassFile(model.resolve("ModelClass.class"), "com.example.model.ModelClass", false);

        logger.debug("Created mock project structure");
    }

    private void createMockClassFile(Path path, String className, boolean isAbstract, String... dependencies) throws IOException {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V22, isAbstract ? Opcodes.ACC_PUBLIC + Opcodes.ACC_ABSTRACT : Opcodes.ACC_PUBLIC,
                className.replace('.', '/'), null, "java/lang/Object", null);

        // Add a constructor
        cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).visitEnd();

        // Add a method that uses the dependencies
        org.objectweb.asm.MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "useDependencies", "()V", null, null);
        mv.visitCode();
        for (String dependency : dependencies) {
            mv.visitTypeInsn(Opcodes.NEW, dependency.replace('.', '/'));
            mv.visitInsn(Opcodes.DUP);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, dependency.replace('.', '/'), "<init>", "()V", false);
            mv.visitInsn(Opcodes.POP);
        }
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(2, 1);
        mv.visitEnd();

        cw.visitEnd();
        Files.write(path, cw.toByteArray());
        logger.debug("Created mock class file: {}", path);
    }
}