package com.example.softwaremetrics.core.domain.resolve;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpringBootAnnotationScannerTest {

    private SpringBootAnnotationScanner scanner;

    @BeforeEach
    void setUp() {
        scanner = new SpringBootAnnotationScanner();
    }

    @Test
    void testContainsSpringBootApplication(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("TestApplication.java");
        Files.writeString(file, "@SpringBootApplication\npublic class TestApplication {}");
        assertTrue(scanner.containsSpringBootApplication(file));

        Path nonSpringBootFile = tempDir.resolve("RegularClass.java");
        Files.writeString(nonSpringBootFile, "public class RegularClass {}");
        assertFalse(scanner.containsSpringBootApplication(nonSpringBootFile));
    }

    @Test
    void testContainsSpringBootApplicationWithAttributes(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("App.java");
        Files.writeString(file, "@SpringBootApplication(scanBasePackages = \"com.example\")\npublic class App {}");
        assertTrue(scanner.containsSpringBootApplication(file));
    }

    @Test
    void testIgnoresSpringBootApplicationInCommentsAndStrings(@TempDir Path tempDir) throws IOException {
        // The annotation mentioned only in a Javadoc/comment must not count.
        Path comment = tempDir.resolve("Commented.java");
        Files.writeString(comment,
                "/**\n * Checks for the @SpringBootApplication annotation.\n */\npublic class Commented {}");
        assertFalse(scanner.containsSpringBootApplication(comment));

        // The annotation appearing only inside a string literal must not count
        // (this is what broke scanning of this analyzer's own source).
        Path stringLiteral = tempDir.resolve("Literal.java");
        Files.writeString(stringLiteral,
                "public class Literal {\n    boolean b = line.contains(\"@SpringBootApplication\");\n}");
        assertFalse(scanner.containsSpringBootApplication(stringLiteral));
    }

    @Test
    void testExtractPackage(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("TestClass.java");
        Files.writeString(file, "package com.example.test;\npublic class TestClass {}");
        assertEquals("com.example.test", scanner.extractPackage(file));

        Path noPackageFile = tempDir.resolve("NoPackageClass.java");
        Files.writeString(noPackageFile, "public class NoPackageClass {}");
        assertEquals("", scanner.extractPackage(noPackageFile));
    }
}
