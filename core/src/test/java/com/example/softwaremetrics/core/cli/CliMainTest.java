package com.example.softwaremetrics.core.cli;

import com.example.softwaremetrics.core.domain.GateResult;
import com.example.softwaremetrics.core.domain.arch.ArchResult;
import com.example.softwaremetrics.core.domain.deadcode.DeadCodeResult;

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

/**
 * Unit / integration tests for {@link CliMain}. Uses the package-private {@code run(String[])} method
 * to avoid actually calling {@code System.exit}. Synthetic compiled projects are built with ASM (same
 * pattern as {@code AnalysisServiceTest}) because the analyzer reads bytecode, not source.
 */
class CliMainTest {

    // -------------------------------------------------------------------------
    // Helpers — synthetic project builders
    // -------------------------------------------------------------------------

    /**
     * Writes a minimal compiled class that references {@code dependencyInternalName} via a NEW
     * instruction, creating a first-party coupling between the two packages.
     */
    private void writeClass(Path baseDir, String classPath, String internalName,
                            String dependencyInternalName) throws IOException {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);

        // default constructor
        MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(1, 1);
        ctor.visitEnd();

        // method that references the dependency class
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "use", "()V", null, null);
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, dependencyInternalName);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(2, 1);
        mv.visitEnd();

        cw.visitEnd();

        Path full = baseDir.resolve(classPath);
        Files.createDirectories(full.getParent());
        Files.write(full, cw.toByteArray());
    }

    /**
     * Writes a class whose method has the given return-type descriptor, producing a coupling via the
     * method return type (captured by ProjectModelBuilder from the method descriptor). Used for cyclic
     * projects — the return-type reference is cheaper than NEW + INVOKESPECIAL.
     */
    private void writeCyclicClass(Path classFile, String methodName,
                                  String methodDescriptor) throws IOException {
        String internalName = classFile.toString().replace('\\', '/')
                .replaceAll(".*/target/classes/", "").replace(".class", "");
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);

        // default constructor
        MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(1, 1);
        ctor.visitEnd();

        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, methodName, methodDescriptor, null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();

        cw.visitEnd();
        Files.createDirectories(classFile.getParent());
        Files.write(classFile, cw.toByteArray());
    }

    /**
     * Builds a minimal valid synthetic project under {@code projectRoot}:
     * a {@code @SpringBootApplication} source marker + two sub-package classes with mutual NEW refs
     * (so both modules emerge and the scan succeeds).
     */
    private void createValidProject(Path projectRoot) throws IOException {
        Path srcMainJava = projectRoot.resolve("src/main/java");
        Files.createDirectories(srcMainJava.resolve("com/example"));
        Files.writeString(srcMainJava.resolve("com/example/DemoApp.java"),
                "package com.example;\n@SpringBootApplication\npublic class DemoApp {}");

        writeClass(srcMainJava, "com/example/web/WebClass.class",
                "com/example/web/WebClass", "com/example/service/ServiceClass");
        writeClass(srcMainJava, "com/example/service/ServiceClass.class",
                "com/example/service/ServiceClass", "com/example/web/WebClass");
    }

    /**
     * Builds a synthetic project with a cyclic dependency between {@code com.example.domain} and
     * {@code com.example.service} (same pattern as {@code PackageScannerControllerIT}).
     */
    private void createCyclicProject(Path projectRoot) throws IOException {
        Path mainApp = projectRoot.resolve("src/main/java/com/example/CyclicApp.java");
        Files.createDirectories(mainApp.getParent());
        Files.writeString(mainApp,
                "package com.example;\n@SpringBootApplication\npublic class CyclicApp {}");

        // domain -> service
        writeCyclicClass(
                projectRoot.resolve("target/classes/com/example/domain/DomainClass.class"),
                "getService", "()Lcom/example/service/ServiceClass;");

        // service -> domain
        writeCyclicClass(
                projectRoot.resolve("target/classes/com/example/service/ServiceClass.class"),
                "getDomain", "()Lcom/example/domain/DomainClass;");
    }

    // =========================================================================
    // Scenario 1: no args -> exit 2
    // =========================================================================

    @Test
    void noArgs_returns2() throws Exception {
        int code = CliMain.run(new String[]{});
        assertThat(code).isEqualTo(2);
    }

    // =========================================================================
    // Scenario 2: valid project -> exit 0
    // =========================================================================

    @Test
    void validProject_returns0(@TempDir Path tempDir) throws Exception {
        createValidProject(tempDir);

        int code = CliMain.run(new String[]{"--scan=" + tempDir});

        assertThat(code).isEqualTo(0);
    }

    // =========================================================================
    // Scenario 3: non-existent path -> exit 2
    // =========================================================================

    @Test
    void nonExistentPath_returns2() throws Exception {
        int code = CliMain.run(new String[]{"--scan=/nonexistent/path/xyz_does_not_exist"});
        assertThat(code).isEqualTo(2);
    }

    // =========================================================================
    // Scenario 4: --fail-on-distance=0.0 on valid project -> exit 1 (gate violated)
    // =========================================================================

    @Test
    void failOnDistanceZero_returns1(@TempDir Path tempDir) throws Exception {
        createValidProject(tempDir);

        int code = CliMain.run(new String[]{"--scan=" + tempDir, "--fail-on-distance=0.0"});

        assertThat(code).isEqualTo(1);
    }

    // =========================================================================
    // Scenario 5: --no-cycles on cyclic project -> exit 1
    // =========================================================================

    @Test
    void noCycles_onCyclicProject_returns1(@TempDir Path tempDir) throws Exception {
        createCyclicProject(tempDir);

        int code = CliMain.run(new String[]{"--scan=" + tempDir, "--no-cycles"});

        assertThat(code).isEqualTo(1);
    }

    // =========================================================================
    // Scenario 6: --output=<file> -> exit 0, output file written with JSON
    // =========================================================================

    @Test
    void outputFile_writesJsonAndReturns0(@TempDir Path tempDir) throws Exception {
        createValidProject(tempDir);
        Path outputFile = tempDir.resolve("metrics.json");

        int code = CliMain.run(new String[]{"--scan=" + tempDir, "--output=" + outputFile});

        assertThat(code).isEqualTo(0);
        assertThat(outputFile).exists();
        String content = Files.readString(outputFile);
        assertThat(content).contains("packageCount");
    }

    // =========================================================================
    // Scenario 7: --arch=layered on valid project -> 0 or 1 without crash
    // =========================================================================

    @Test
    void archLayered_runsWithoutCrash(@TempDir Path tempDir) throws Exception {
        createValidProject(tempDir);

        int code = CliMain.run(new String[]{"--scan=" + tempDir, "--arch=layered"});

        assertThat(code).isIn(0, 1);
    }

    // =========================================================================
    // Scenarios 8-10: printSummary branches
    // =========================================================================

    @Test
    void printSummary_null_doesNotThrow() {
        // Should return immediately without throwing
        CliMain.printSummary(null);
    }

    @Test
    void printSummary_passed_doesNotThrow() {
        GateResult passed = new GateResult(true, List.of());
        CliMain.printSummary(passed);
    }

    @Test
    void printSummary_withViolations_doesNotThrow() {
        GateResult.Violation v = new GateResult.Violation(
                "maxPackageDistance", "com.example.web", 0.9, 0.7,
                "Package 'com.example.web' distance 0.90 exceeds max 0.70");
        GateResult failed = new GateResult(false, List.of(v));
        CliMain.printSummary(failed);
    }

    // =========================================================================
    // Scenarios 11-13: printArchSummary branches
    // =========================================================================

    @Test
    void printArchSummary_null_doesNotThrow() {
        CliMain.printArchSummary(null);
    }

    @Test
    void printArchSummary_compliant_doesNotThrow() {
        ArchResult compliant = new ArchResult("layered", true, List.of());
        CliMain.printArchSummary(compliant);
    }

    @Test
    void printArchSummary_withViolations_doesNotThrow() {
        ArchResult.Violation violation = new ArchResult.Violation(
                "forbiddenDependency", "presentation", "data",
                "Presentation must not depend on Data");
        ArchResult nonCompliant = new ArchResult("layered", false, List.of(violation));
        CliMain.printArchSummary(nonCompliant);
    }

    // =========================================================================
    // Scenarios 14-15: printBannedSummary branches
    // =========================================================================

    @Test
    void printBannedSummary_empty_doesNotThrow() {
        CliMain.printBannedSummary(List.of());
    }

    @Test
    void printBannedSummary_withViolations_doesNotThrow() {
        GateResult.Violation violation = new GateResult.Violation(
                "bannedApi", "com.example.service", 0.0, 0.0,
                "Class com.example.service.Foo uses banned API java.io.File");
        CliMain.printBannedSummary(List.of(violation));
    }

    // =========================================================================
    // Scenarios 16-18: printDeadCodeSummary branches
    // =========================================================================

    @Test
    void printDeadCodeSummary_null_doesNotThrow() {
        CliMain.printDeadCodeSummary(null);
    }

    @Test
    void printDeadCodeSummary_empty_doesNotThrow() {
        DeadCodeResult noUnused = new DeadCodeResult(List.of());
        CliMain.printDeadCodeSummary(noUnused);
    }

    @Test
    void printDeadCodeSummary_withUnusedClasses_doesNotThrow() {
        DeadCodeResult withUnused = new DeadCodeResult(
                List.of("com.example.util.OldHelper", "com.example.util.LegacyConverter"));
        CliMain.printDeadCodeSummary(withUnused);
    }

    // =========================================================================
    // Scenarios 19-20: Args.parse — space-separated and equals forms
    // =========================================================================

    @Test
    void argsParse_spaceSeparatedScan() {
        CliMain.Args args = CliMain.Args.parse(new String[]{"--scan", "/some/path"});
        assertThat(args.scanPath).isEqualTo("/some/path");
    }

    @Test
    void argsParse_equalsScan() {
        CliMain.Args args = CliMain.Args.parse(new String[]{"--scan=/some/path"});
        assertThat(args.scanPath).isEqualTo("/some/path");
    }

    @Test
    void argsParse_allFlagsEqualsForm() {
        CliMain.Args args = CliMain.Args.parse(new String[]{
                "--scan=/proj",
                "--output=/out.json",
                "--fail-on-distance=0.5",
                "--no-cycles",
                "--arch=layered"
        });
        assertThat(args.scanPath).isEqualTo("/proj");
        assertThat(args.outputFile).isEqualTo("/out.json");
        assertThat(args.failOnDistance).isEqualTo(0.5);
        assertThat(args.noCycles).isTrue();
        assertThat(args.archRef).isEqualTo("layered");
    }

    @Test
    void argsParse_noArgsProducesNullScanPath() {
        CliMain.Args args = CliMain.Args.parse(new String[]{});
        assertThat(args.scanPath).isNull();
    }

    @Test
    void argsParse_noCyclesFlag_setsNoCycles() {
        CliMain.Args args = CliMain.Args.parse(new String[]{"--no-cycles"});
        assertThat(args.noCycles).isTrue();
        assertThat(args.scanPath).isNull();
    }

    @Test
    void argsParse_spaceSeparatedOutput() {
        CliMain.Args args = CliMain.Args.parse(new String[]{"--output", "/report.json"});
        assertThat(args.outputFile).isEqualTo("/report.json");
    }

    @Test
    void argsParse_spaceSeparatedArch() {
        CliMain.Args args = CliMain.Args.parse(new String[]{"--arch", "hexagonal"});
        assertThat(args.archRef).isEqualTo("hexagonal");
    }

    @Test
    void argsParse_spaceSeparatedFailOnDistance() {
        CliMain.Args args = CliMain.Args.parse(new String[]{"--fail-on-distance", "0.3"});
        assertThat(args.failOnDistance).isEqualTo(0.3);
    }
}
