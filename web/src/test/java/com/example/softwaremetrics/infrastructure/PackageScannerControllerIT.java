package com.example.softwaremetrics.infrastructure;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

@SpringBootTest
@AutoConfigureMockMvc
public class PackageScannerControllerIT {

    @Autowired
    private MockMvc mockMvc;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        createTestProjectStructure(tempDir);
    }

    @Test
    public void testIndexPage() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(view().name("index"));
    }

    @Test
    public void testScanSuccess() throws Exception {
        mockMvc.perform(post("/scan").param("path", tempDir.toString()))
                .andExpect(status().isOk())
                .andExpect(view().name("graph :: graph"))
                .andExpect(model().attributeExists("metrics"))
                .andExpect(model().attribute("metrics", org.hamcrest.Matchers.hasKey("com.example.subpackage")));
    }

    @Test
    public void testScanWithArchitecture() throws Exception {
        mockMvc.perform(post("/scan").param("path", tempDir.toString()).param("arch", "layered"))
                .andExpect(status().isOk())
                .andExpect(view().name("graph :: graph"))
                .andExpect(model().attributeExists("architecture"));
    }

    @Test
    public void testExportMetricsWithArchitecture() throws Exception {
        mockMvc.perform(get("/api/metrics").param("path", tempDir.toString()).param("arch", "layered"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.architecture").exists())
                .andExpect(jsonPath("$.architecture.specName", notNullValue()));
    }

    @Test
    public void testScanError() throws Exception {
        mockMvc.perform(post("/scan").param("path", "/non/existent/path"))
                .andExpect(status().isOk())
                .andExpect(view().name("graph :: error"))
                .andExpect(model().attributeExists("error"))
                .andExpect(model().attribute("error", org.hamcrest.Matchers.containsString("Error scanning project")));
    }

    @Test
    public void testExportMetricsJson() throws Exception {
        mockMvc.perform(get("/api/metrics").param("path", tempDir.toString()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.generatedAt", notNullValue()))
                .andExpect(jsonPath("$.projectPath", containsString(tempDir.toString())))
                .andExpect(jsonPath("$.packageCount", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.summary").exists())
                .andExpect(jsonPath("$.cycles", org.hamcrest.Matchers.empty()))
                .andExpect(jsonPath("$.packages", org.hamcrest.Matchers.hasKey("com.example.subpackage")));
    }

    @Test
    public void testExportMetricsError() throws Exception {
        mockMvc.perform(get("/api/metrics").param("path", "/non/existent/path"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", notNullValue()));
    }

    @Test
    void scanReportsCyclicDependencies(@TempDir Path cyclicDir) throws Exception {
        createCyclicProjectStructure(cyclicDir);

        mockMvc.perform(get("/api/metrics").param("path", cyclicDir.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cycles").isArray())
                .andExpect(jsonPath("$.cycles.length()", equalTo(1)))
                .andExpect(jsonPath("$.cycles[0].length()", equalTo(2)))
                .andExpect(jsonPath("$.cycles[0]",
                        containsInAnyOrder("com.example.domain", "com.example.service")));

        mockMvc.perform(post("/scan").param("path", cyclicDir.toString()))
                .andExpect(status().isOk())
                .andExpect(view().name("graph :: graph"))
                .andExpect(model().attribute("cycles", hasSize(equalTo(1))))
                .andExpect(content().string(containsString("Circular dependencies detected")));
    }

    /**
     * Builds a minimal synthetic project where com.example.domain and com.example.service
     * form a mutual dependency: DomainClass declares a method returning ServiceClass, and
     * ServiceClass declares a method returning DomainClass.  Method return types are captured
     * by ProjectModelBuilder (via {@code Type.getReturnType(method.desc)}) so this is the
     * lightest bytecode change that produces a first-party cross-package reference.
     */
    private void createCyclicProjectStructure(Path projectRoot) throws IOException {
        // Source marker so SpringBootRootPackageResolver finds the root package
        Path mainApp = projectRoot.resolve("src/main/java/com/example/CyclicTestApp.java");
        Files.createDirectories(mainApp.getParent());
        Files.writeString(mainApp, """
                package com.example;
                import org.springframework.boot.autoconfigure.SpringBootApplication;
                @SpringBootApplication
                public class CyclicTestApp {}
                """);

        // domain → service edge
        writeCyclicClass(
                projectRoot.resolve("target/classes/com/example/domain/DomainClass.class"),
                "getService", "()Lcom/example/service/ServiceClass;");

        // service → domain edge
        writeCyclicClass(
                projectRoot.resolve("target/classes/com/example/service/ServiceClass.class"),
                "getDomain", "()Lcom/example/domain/DomainClass;");
    }

    private void writeCyclicClass(Path classFile, String methodName,
                                   String methodDescriptor) throws IOException {
        String internalName = classFile.toString().replace('\\', '/')
                .replaceAll(".*/target/classes/", "").replace(".class", "");
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

    private void createTestProjectStructure(Path projectRoot) throws IOException {
        // Create main application class
        Path mainAppPath = projectRoot.resolve("src/main/java/com/example/TestApplication.java");
        Files.createDirectories(mainAppPath.getParent());
        Files.writeString(mainAppPath,
                """
                        package com.example;
                        import org.springframework.boot.SpringApplication;
                        import org.springframework.boot.autoconfigure.SpringBootApplication;
                        
                        @SpringBootApplication
                        public class TestApplication {
                            public static void main(String[] args) {
                                SpringApplication.run(TestApplication.class, args);
                            }
                        }
                        """
        );

        // Create a subpackage with a class
        Path subPackagePath = projectRoot.resolve("src/main/java/com/example/subpackage/TestClass.java");
        Files.createDirectories(subPackagePath.getParent());
        Files.writeString(subPackagePath,
                """
                        package com.example.subpackage;

                        public class TestClass {
                            public void testMethod() {}
                        }
                        """);

        // The analyzer reads compiled bytecode, not source — emit a matching .class so the scan finds
        // the module 'com.example.subpackage' (a source-only project yields empty metrics).
        writeCompiledClass(projectRoot.resolve("target/classes/com/example/subpackage/TestClass.class"),
                "com.example.subpackage.TestClass");
    }

    private void writeCompiledClass(Path classFile, String className) throws IOException {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, className.replace('.', '/'), null, "java/lang/Object", null);
        emitDefaultConstructor(cw);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "testMethod", "()V", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 1);
        mv.visitEnd();
        writeClassToFile(cw, classFile);
    }

    private void emitDefaultConstructor(ClassWriter cw) {
        MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(1, 1);
        ctor.visitEnd();
    }

    private void writeClassToFile(ClassWriter cw, Path classFile) throws IOException {
        cw.visitEnd();
        Files.createDirectories(classFile.getParent());
        Files.write(classFile, cw.toByteArray());
    }
}