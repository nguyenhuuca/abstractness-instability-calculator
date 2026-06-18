package com.example.softwaremetrics.core.domain.bytecode;

import com.example.softwaremetrics.core.config.Defaults;
import com.example.softwaremetrics.core.domain.model.ClassDetail;
import com.example.softwaremetrics.core.domain.model.ProjectModel;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the branches in {@link ProjectModelBuilder} that are missed by the higher-level
 * {@code JavaClassAnalyzerTest}: {@code addAnnotationValue} (Type / AnnotationNode / List / enum
 * branches), the {@code analyzeClassSignature} anonymous SignatureVisitor, and the IOException
 * catch block in {@code analyzeClassFile}.
 *
 * <p>Each test writes minimal compiled {@code .class} files to a {@code @TempDir} using ASM and
 * then calls {@link ProjectModelBuilder#build(Path)} so the same pipeline that runs in production
 * is exercised.
 */
class ProjectModelBuilderTest {

    private ProjectModelBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new ProjectModelBuilder(new DependencyExclusions(Defaults.exclusions()));
    }

    // -------------------------------------------------------------------------
    // addAnnotationValue — Type branch (class literal annotation member)
    // -------------------------------------------------------------------------

    /**
     * A class with an annotation whose member value is {@code Foo.class} (an ASM {@link Type}).
     * ASM stores this as a {@link Type} object inside {@code AnnotationNode.values}; the Type
     * branch in {@code addAnnotationValue} must add the class name to typeRefs.
     */
    @Test
    void addAnnotationValue_typeBranch_classLiteralMemberAddsToTypeRefs(@TempDir Path tempDir) throws IOException {
        // Annotation type referenced as a class literal value
        String annotatedClass  = "com/example/pkg/Annotated";
        String fooClass        = "com/example/pkg/Foo";
        String myAnnotation    = "com/example/pkg/MyAnnotation";

        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, annotatedClass, null, "java/lang/Object", null);

        // @MyAnnotation(target = Foo.class)
        AnnotationVisitor av = cw.visitAnnotation("L" + myAnnotation + ";", true);
        av.visit("target", Type.getType("L" + fooClass + ";"));
        av.visitEnd();

        addConstructor(cw);
        cw.visitEnd();

        writeClass(tempDir, annotatedClass + ".class", cw.toByteArray());

        ProjectModel model = builder.build(tempDir);
        Set<String> typeRefs = classDetailFor(model, "com.example.pkg.Annotated").typeRefs();
        assertThat(typeRefs).contains("com.example.pkg.Foo");
    }

    // -------------------------------------------------------------------------
    // addAnnotationValue — AnnotationNode branch (nested annotation member)
    // -------------------------------------------------------------------------

    /**
     * A class with an annotation that carries a nested annotation as one of its members.
     * ASM represents the nested annotation as an {@link org.objectweb.asm.tree.AnnotationNode};
     * the AnnotationNode branch must add the nested annotation's own descriptor type to typeRefs.
     */
    @Test
    void addAnnotationValue_nestedAnnotationBranch_nestedTypeAddsToTypeRefs(@TempDir Path tempDir) throws IOException {
        String annotatedClass   = "com/example/pkg/Annotated";
        String outerAnnotation  = "com/example/pkg/Outer";
        String innerAnnotation  = "com/example/pkg/Inner";

        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, annotatedClass, null, "java/lang/Object", null);

        // @Outer(inner = @Inner)
        AnnotationVisitor av = cw.visitAnnotation("L" + outerAnnotation + ";", true);
        AnnotationVisitor nested = av.visitAnnotation("inner", "L" + innerAnnotation + ";");
        nested.visitEnd();
        av.visitEnd();

        addConstructor(cw);
        cw.visitEnd();

        writeClass(tempDir, annotatedClass + ".class", cw.toByteArray());

        ProjectModel model = builder.build(tempDir);
        Set<String> typeRefs = classDetailFor(model, "com.example.pkg.Annotated").typeRefs();
        assertThat(typeRefs).contains("com.example.pkg.Inner");
    }

    // -------------------------------------------------------------------------
    // addAnnotationValue — List branch (array-valued annotation member)
    // -------------------------------------------------------------------------

    /**
     * A class with an annotation whose member is an array of class literals.
     * ASM stores array members as a {@link java.util.List} of values; the List branch in
     * {@code addAnnotationValue} must recurse into each element.  Each element is a
     * {@link Type}, so the Type branch fires for every item in the array.
     */
    @Test
    void addAnnotationValue_listBranch_arrayOfClassLiteralsAddsAllToTypeRefs(@TempDir Path tempDir) throws IOException {
        String annotatedClass = "com/example/pkg/Annotated";
        String myAnnotation   = "com/example/pkg/MyAnnotation";
        String barClass       = "com/example/pkg/Bar";
        String bazClass       = "com/example/pkg/Baz";

        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, annotatedClass, null, "java/lang/Object", null);

        // @MyAnnotation(classes = {Bar.class, Baz.class})
        AnnotationVisitor av = cw.visitAnnotation("L" + myAnnotation + ";", true);
        AnnotationVisitor arr = av.visitArray("classes");
        arr.visit(null, Type.getType("L" + barClass + ";"));
        arr.visit(null, Type.getType("L" + bazClass + ";"));
        arr.visitEnd();
        av.visitEnd();

        addConstructor(cw);
        cw.visitEnd();

        writeClass(tempDir, annotatedClass + ".class", cw.toByteArray());

        ProjectModel model = builder.build(tempDir);
        Set<String> typeRefs = classDetailFor(model, "com.example.pkg.Annotated").typeRefs();
        assertThat(typeRefs).contains("com.example.pkg.Bar", "com.example.pkg.Baz");
    }

    // -------------------------------------------------------------------------
    // addAnnotationValue — enum branch (String[]{desc, name})
    // -------------------------------------------------------------------------

    /**
     * A class with an annotation that has an enum-valued member.  ASM stores enum members as a
     * two-element {@code String[]{descriptor, valueName}}; the enum branch must extract the enum
     * type from the descriptor and add it to typeRefs.
     */
    @Test
    void addAnnotationValue_enumBranch_enumTypeAddsToTypeRefs(@TempDir Path tempDir) throws IOException {
        String annotatedClass = "com/example/pkg/Annotated";
        String myAnnotation   = "com/example/pkg/MyAnnotation";
        String statusEnum     = "com/example/pkg/Status";

        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, annotatedClass, null, "java/lang/Object", null);

        // @MyAnnotation(status = Status.ACTIVE)
        AnnotationVisitor av = cw.visitAnnotation("L" + myAnnotation + ";", true);
        av.visitEnum("status", "L" + statusEnum + ";", "ACTIVE");
        av.visitEnd();

        addConstructor(cw);
        cw.visitEnd();

        writeClass(tempDir, annotatedClass + ".class", cw.toByteArray());

        ProjectModel model = builder.build(tempDir);
        Set<String> typeRefs = classDetailFor(model, "com.example.pkg.Annotated").typeRefs();
        assertThat(typeRefs).contains("com.example.pkg.Status");
    }

    // -------------------------------------------------------------------------
    // analyzeClassSignature — visitClassType fires for generic supertype
    // -------------------------------------------------------------------------

    /**
     * A class whose bytecode carries a generic signature (e.g. implements {@code Comparable<Foo>}).
     * {@code analyzeClassSignature} installs an anonymous {@link org.objectweb.asm.signature.SignatureVisitor}
     * whose {@code visitClassType} method must fire and add the referenced type to dependencies.
     *
     * <p>We use a first-party package ({@code com.example}) so the dependency is NOT excluded by the
     * default {@link DependencyExclusions} (which only excludes JDK/Spring/etc. prefixes).
     */
    @Test
    void analyzeClassSignature_genericSignature_visitClassTypeFiresAndAddsDependency(@TempDir Path tempDir) throws IOException {
        // Class: com.example.pkg.GenericClass implements Comparable<com.example.pkg.Payload>
        // Generic class signature: Ljava/lang/Object;Ljava/lang/Comparable<Lcom/example/pkg/Payload;>;
        String genericClass = "com/example/pkg/GenericClass";
        String payloadClass = "com/example/pkg/Payload";
        String signature    = "Ljava/lang/Object;Ljava/lang/Comparable<L" + payloadClass + ";>;";

        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC,
                genericClass,
                signature,   // <— triggers analyzeClassSignature
                "java/lang/Object",
                new String[]{"java/lang/Comparable"});

        addConstructor(cw);
        cw.visitEnd();

        writeClass(tempDir, genericClass + ".class", cw.toByteArray());

        ProjectModel model = builder.build(tempDir);

        // The dependency on the Payload type must appear in the class's exclusion-filtered
        // dependencies (both java.lang.Comparable and the payload are referenced via the signature).
        ClassDetail detail = classDetailFor(model, "com.example.pkg.GenericClass");
        assertThat(detail.dependencies()).contains("com.example.pkg.Payload");
    }

    // -------------------------------------------------------------------------
    // analyzeClassFile IOException — unreadable .class file is silently skipped
    // -------------------------------------------------------------------------

    /**
     * A {@code .class} file whose read permissions have been removed causes
     * {@code Files.newInputStream(file)} to throw {@link IOException}.  The catch block inside
     * {@code analyzeClassFile} must swallow the error and return {@code null}; the caller omits
     * null results, so the model is still populated from the remaining valid classes.
     *
     * <p>This exercises the {@code catch (IOException e) { … return null; }} branch at the bottom
     * of {@link ProjectModelBuilder#analyzeClassFile}.
     *
     * <p>This test is skipped on Windows because POSIX permission removal is not available there.
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void analyzeClassFile_unreadableClassFile_skippedAndModelContainsOnlyValidClass(@TempDir Path tempDir) throws IOException {
        // A valid class that should appear in the model.
        String validClass = "com/example/pkg/Valid";
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, validClass, null, "java/lang/Object", null);
        addConstructor(cw);
        cw.visitEnd();
        writeClass(tempDir, validClass + ".class", cw.toByteArray());

        // A second valid class file whose read permission is then revoked.
        String unreadableClass = "com/example/pkg/Unreadable";
        ClassWriter cw2 = new ClassWriter(0);
        cw2.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, unreadableClass, null, "java/lang/Object", null);
        addConstructor(cw2);
        cw2.visitEnd();
        Path unreadablePath = tempDir.resolve(unreadableClass + ".class");
        Files.createDirectories(unreadablePath.getParent());
        Files.write(unreadablePath, cw2.toByteArray());

        // Remove all POSIX permissions so Files.newInputStream throws IOException.
        Files.setPosixFilePermissions(unreadablePath, EnumSet.noneOf(PosixFilePermission.class));

        try {
            // build() must not throw; the unreadable file is skipped, only Valid survives.
            ProjectModel model = builder.build(tempDir);
            assertThat(model).isNotNull();
            assertThat(model.classes()).hasSize(1);
            assertThat(model.classes().get(0).fqcn()).isEqualTo("com.example.pkg.Valid");
        } finally {
            // Restore read permission so @TempDir cleanup can delete the file.
            Files.setPosixFilePermissions(unreadablePath, EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Adds a no-arg constructor that calls {@code super()} — required for well-formed bytecode. */
    private static void addConstructor(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
    }

    /** Writes {@code bytes} to {@code classRelativePath} under {@code baseDir}, creating parents. */
    private static void writeClass(Path baseDir, String classRelativePath, byte[] bytes) throws IOException {
        Path target = baseDir.resolve(classRelativePath);
        Files.createDirectories(target.getParent());
        Files.write(target, bytes);
    }

    /** Finds the {@link ClassDetail} for {@code fqcn} in the model, failing if absent. */
    private static ClassDetail classDetailFor(ProjectModel model, String fqcn) {
        Optional<ClassDetail> found = model.classes().stream()
                .filter(c -> c.fqcn().equals(fqcn))
                .findFirst();
        assertThat(found).as("ClassDetail for %s", fqcn).isPresent();
        return found.get();
    }
}
