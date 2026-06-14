package com.example.softwaremetrics.domain;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * JavaClassAnalyzer provides utility methods to analyze Java class files for various metrics
 * such as dependencies, package information, and class counts.
 */
public class JavaClassAnalyzer {

    private final InstabilityCalculatorProperties props;

    private static final Logger logger = LoggerFactory.getLogger(JavaClassAnalyzer.class);

    public JavaClassAnalyzer(InstabilityCalculatorProperties props) {
        this.props = props;
    }

    /**
     * Checks whether the given file contains the @SpringBootApplication annotation.
     *
     * @param file the Path to the file to be checked
     * @return true if the file contains the @SpringBootApplication annotation, false otherwise
     */
    private static final Pattern SPRING_BOOT_APP = Pattern.compile("@SpringBootApplication\\b");

    boolean containsSpringBootApplication(Path file) {
        try (Stream<String> lines = Files.lines(file)) {
            return lines.anyMatch(this::isSpringBootApplicationAnnotation);
        } catch (IOException e) {
            logger.error("Error reading file: {}", file, e);
            return false;
        }
    }

    /**
     * Detects a real {@code @SpringBootApplication} annotation usage, ignoring occurrences inside
     * comments or string literals so the main package isn't picked from a file that merely
     * mentions the annotation in text (e.g. this analyzer's own source).
     */
    private boolean isSpringBootApplicationAnnotation(String line) {
        String trimmed = line.trim();
        if (trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")) {
            return false; // comment line
        }
        String code = trimmed.replaceAll("\"(\\\\.|[^\"\\\\])*\"", ""); // drop string literals
        return SPRING_BOOT_APP.matcher(code).find();
    }

    String extractPackage(Path file) {
        try (Stream<String> lines = Files.lines(file)) {
            return lines
                    .filter(line -> line.startsWith("package"))
                    .map(line -> line.split("\\s+")[1].replace(";", ""))
                    .findFirst()
                    .orElse("");
        } catch (IOException e) {
            logger.error("Error extracting package from file: {}", file, e);
            return "";
        }
    }

    void analyzeClasses(Path projectPath, List<String> modulePackages,
                        Map<String, Set<String>> outgoingDependencies,
                        Map<String, Set<String>> incomingDependencies,
                        Map<String, Integer> abstractClassCount,
                        Map<String, Integer> totalClassCount) {
        try (var walk = Files.walk(projectPath)) {
            walk
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".class"))
                    .filter(this::isNotTestClass)
                    .forEach(file -> analyzeClassFile(file, modulePackages, outgoingDependencies, incomingDependencies, abstractClassCount, totalClassCount));
        } catch (IOException e) {
            logger.error("Error while analyzing classes for {}", projectPath, e);
            throw new IllegalStateException(e);
        }
    }

    private boolean isNotTestClass(Path path) {
        String p = path.toString().replace('\\', '/'); // normalize Windows separators
        return !p.contains("/target/test-classes/")        // Maven
            && !p.contains("/build/classes/java/test/")    // Gradle (Java)
            && !p.contains("/build/classes/kotlin/test/"); // Gradle (Kotlin)
    }

    private void analyzeClassFile(Path file, List<String> modulePackages,
                                  Map<String, Set<String>> outgoingDependencies,
                                  Map<String, Set<String>> incomingDependencies,
                                  Map<String, Integer> abstractClassCount,
                                  Map<String, Integer> totalClassCount) {
        try {
            ClassReader classReader = new ClassReader(Files.newInputStream(file));
            ClassNode classNode = new ClassNode();
            classReader.accept(classNode, 0);

            String className = Type.getObjectType(classNode.name).getClassName();
            String packageName = getPackageName(className);
            String topLevelPackage = extractTopLevelPackageFrom(packageName, modulePackages);

            if (topLevelPackage == null) return;
            if (classNode.name.endsWith("Builder")) return;
            if (className.contains("$")) return; // Skip inner classes

            logger.trace("Analyzing class: {}", className);
            totalClassCount.merge(topLevelPackage, 1, Integer::sum);
            if ((classNode.access & Opcodes.ACC_ABSTRACT) != 0 || (classNode.access & Opcodes.ACC_INTERFACE) != 0) {
                abstractClassCount.merge(topLevelPackage, 1, Integer::sum);
            }

            Set<String> dependencies = new HashSet<>();
            for (MethodNode method : classNode.methods) {
                analyzeDependencies(method, dependencies);
            }
            analyzeClassSignature(classNode, dependencies);

            for (String dependency : dependencies) {
                if (topLevelPackage.contains("repo")) {
                    logger.info("test");
                }
                if (dependency.endsWith("Builder")) continue;
                if (dependency.contains("$")) continue; // Skip inner classes
                String dependencyPackage = getPackageName(dependency);
                String dependencyTopLevelPackage = extractTopLevelPackageFrom(dependencyPackage, modulePackages);
                if (!topLevelPackage.equals(dependencyTopLevelPackage) && !isExcludedDependency(dependency)) {
                    outgoingDependencies.computeIfAbsent(topLevelPackage, _ -> new HashSet<>()).add(dependency);
                    if (dependencyTopLevelPackage != null) {
                        incomingDependencies.computeIfAbsent(dependencyTopLevelPackage, _ -> new HashSet<>()).add(className);
                    }
                }
            }
        } catch (IOException e) {
            logger.error("Error analyzing class file: {}", file, e);
        }
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private boolean isExcludedDependency(String dependency) {
        return isJavaNativePackage(dependency) || isBasicType(dependency)
                || isJavaExternalPackage(dependency);
    }

    private boolean isJavaNativePackage(String packageName) {
        if (!props.getNativePackages().isDisabled()) return false;
        return props.getNativePackages().getValues().stream().anyMatch(packageName::startsWith);
    }

    private boolean isJavaExternalPackage(String packageName) {
        if (!props.getExternalPackages().isDisabled()) return false;
        return props.getExternalPackages().getValues().stream().anyMatch(packageName::startsWith);
    }

    private boolean isBasicType(String typeName) {
        if (!props.getBasicTypes().isDisabled()) return false;
        return props.getBasicTypes().getValues().contains(typeName) || props.getBasicTypes().getValues().contains(getPackageName(typeName));
    }

    private void addDependencyIfNotExcludedDescriptor(Set<String> dependencies, String descriptor) {
        String className = normalizeArrayType(descriptor);
        if (!isExcludedDependency(className)) {
            dependencies.add(className);
        }
    }

    private String normalizeArrayType(String rawType) {
        while (rawType.startsWith("[")) {
            rawType = rawType.substring(1);
        }
        if (rawType.startsWith("L") && rawType.endsWith(";")) {
            rawType = rawType.substring(1, rawType.length() - 1);
        }
        return rawType.replace('/', '.');
    }

    private void analyzeClassSignature(ClassNode classNode, Set<String> dependencies) {
        if (classNode.signature == null) return;

        SignatureReader reader = new SignatureReader(classNode.signature);
        reader.accept(new SignatureVisitor(Opcodes.ASM9) {
            @Override
            public void visitClassType(String name) {
                String className = name.replace('/', '.');
                addDependencyIfNotExcluded(dependencies, className);
                super.visitClassType(name);
            }
        });
    }

    private void analyzeDependencies(MethodNode method, Set<String> dependencies) {
        // Analyze method signature
        Type returnType = Type.getReturnType(method.desc);
        addDependencyIfNotExcluded(dependencies, returnType.getClassName());

        // Analyze parameter types
        for (Type paramType : Type.getArgumentTypes(method.desc)) {
            addDependencyIfNotExcluded(dependencies, paramType.getClassName());
        }

        // Analyze exceptions
        method.exceptions.forEach(exception -> {
            String exceptionName = Type.getObjectType(exception).getClassName();
            addDependencyIfNotExcluded(dependencies, exceptionName);
        });

        // Analyze method body
        method.instructions.forEach(instruction -> {
            if (instruction instanceof org.objectweb.asm.tree.MethodInsnNode methodInsn) {
                String methodOwner = Type.getObjectType(methodInsn.owner).getClassName();
                addDependencyIfNotExcluded(dependencies, methodOwner);
            } else if (instruction instanceof org.objectweb.asm.tree.FieldInsnNode fieldInsn) {
                String fieldOwner = Type.getObjectType(fieldInsn.owner).getClassName();
                addDependencyIfNotExcluded(dependencies, fieldOwner);
            } else if (instruction instanceof org.objectweb.asm.tree.TypeInsnNode typeInsn) {
                String typeName = Type.getObjectType(typeInsn.desc).getClassName();
                addDependencyIfNotExcluded(dependencies, typeName);
            }
        });

        // Analyze local variables
        if (method.localVariables != null) {
            for (org.objectweb.asm.tree.LocalVariableNode localVar : method.localVariables) {
                String localVarType = Type.getType(localVar.desc).getClassName();
                addDependencyIfNotExcluded(dependencies, localVarType);
            }
        }
    }

    private String normalizeArrayClassName(String rawType) {
        if (rawType == null) return "";

        // Nếu là descriptor (ASM format), như: [B, [Ljava/lang/String;
        if (rawType.startsWith("[")) {
            return normalizeArrayType(rawType); // đã xử lý từ descriptor format
        }

        // Nếu là kiểu Java: byte[], java.lang.String[], int[][]
        while (rawType.endsWith("[]")) {
            rawType = rawType.substring(0, rawType.length() - 2);
        }

        return rawType;
    }

    private void addDependencyIfNotExcluded(Set<String> dependencies, String dependency) {
        logger.info("before normalized dependency: {}", dependency);
        String normalized = normalizeArrayClassName(dependency);
        logger.info("after normalized dependency: {}", normalized);
        if (!isExcludedDependency(normalized)) {
            dependencies.add(dependency);
        }
    }

    private String extractTopLevelPackageFrom(String packageName, List<String> packages) {
        return packages.stream()
                .filter(packageName::startsWith)
                .findFirst()
                .orElse(null);
    }

    private String getPackageName(String className) {
        int lastDotIndex = className.lastIndexOf('.');
        return (lastDotIndex == -1) ? "" : className.substring(0, lastDotIndex);
    }
}
