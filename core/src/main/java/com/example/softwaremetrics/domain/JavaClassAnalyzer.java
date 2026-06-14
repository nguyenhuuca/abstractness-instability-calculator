package com.example.softwaremetrics.domain;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
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
                        Map<String, Integer> totalClassCount,
                        Map<String, ComplexityStats> complexity) {
        try (var walk = Files.walk(projectPath)) {
            walk
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".class"))
                    .filter(this::isNotTestClass)
                    .forEach(file -> analyzeClassFile(file, modulePackages, outgoingDependencies, incomingDependencies, abstractClassCount, totalClassCount, complexity));
        } catch (IOException e) {
            logger.error("Error while analyzing classes for {}", projectPath, e);
            throw new IllegalStateException(e);
        }
    }

    /**
     * Builds a first-party class dependency graph for architecture checking: maps each project class
     * (FQCN starting with {@code mainPackage}) to the set of project classes it depends on. Every
     * first-party class appears as a key (possibly with an empty set) so naming rules can cover it.
     * Reuses the same ASM dependency extraction as the metrics pass.
     */
    public Map<String, Set<String>> buildClassDependencyGraph(Path projectPath, String mainPackage) {
        Map<String, Set<String>> graph = new HashMap<>();
        String prefix = mainPackage + ".";
        try (var walk = Files.walk(projectPath)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".class"))
                    .filter(this::isNotTestClass)
                    .forEach(file -> collectClassEdges(file, prefix, graph));
        } catch (IOException e) {
            logger.error("Error building class dependency graph for {}", projectPath, e);
            throw new IllegalStateException(e);
        }
        return graph;
    }

    private void collectClassEdges(Path file, String firstPartyPrefix, Map<String, Set<String>> graph) {
        try {
            ClassReader classReader = new ClassReader(Files.newInputStream(file));
            ClassNode classNode = new ClassNode();
            classReader.accept(classNode, 0);

            String className = Type.getObjectType(classNode.name).getClassName();
            if (!className.startsWith(firstPartyPrefix)) return;
            if (className.contains("$")) return; // skip inner classes

            Set<String> dependencies = new HashSet<>();
            for (MethodNode method : classNode.methods) {
                analyzeDependencies(method, dependencies);
            }
            analyzeClassSignature(classNode, dependencies);

            Set<String> firstPartyDeps = graph.computeIfAbsent(className, k -> new LinkedHashSet<>());
            for (String dependency : dependencies) {
                String dep = stripArraySuffix(dependency);
                if (dep.contains("$") || dep.equals(className)) continue;
                if (dep.startsWith(firstPartyPrefix) && !isExcludedDependency(dep)) {
                    firstPartyDeps.add(dep);
                }
            }
        } catch (IOException e) {
            logger.error("Error reading class file: {}", file, e);
        }
    }

    private String stripArraySuffix(String type) {
        while (type.endsWith("[]")) {
            type = type.substring(0, type.length() - 2);
        }
        return type;
    }

    /** Cyclomatic complexity of a method = 1 + conditional branches + switch cases. */
    private int cyclomaticComplexity(MethodNode method) {
        int complexity = 1;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof JumpInsnNode jump) {
                int op = jump.getOpcode();
                if (op != Opcodes.GOTO && op != Opcodes.JSR) {
                    complexity++;
                }
            } else if (insn instanceof TableSwitchInsnNode ts) {
                complexity += ts.labels.size();
            } else if (insn instanceof LookupSwitchInsnNode ls) {
                complexity += ls.labels.size();
            }
        }
        return complexity;
    }

    private static final Set<String> ENTRY_ANNOTATION_MARKERS = Set.of(
            "SpringBootApplication", "RestController", "Controller", "Service",
            "Repository", "Component", "Configuration", "Entity");

    /**
     * Builds a per-class model (refs + entry-point flag) for the dead-code and banned-API checks.
     * Captures ALL references (incl. JDK/3rd-party) — exclusion lists are NOT applied here, because
     * banned-API rules target exactly those (e.g. {@code java.lang.System.exit}).
     */
    public List<ClassInfo> analyzeProject(Path projectPath, String mainPackage) {
        List<ClassInfo> classes = new ArrayList<>();
        String prefix = mainPackage + ".";
        try (var walk = Files.walk(projectPath)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".class"))
                    .filter(this::isNotTestClass)
                    .forEach(file -> {
                        ClassInfo info = collectClassInfo(file, prefix);
                        if (info != null) {
                            classes.add(info);
                        }
                    });
        } catch (IOException e) {
            logger.error("Error analyzing project model for {}", projectPath, e);
            throw new IllegalStateException(e);
        }
        return classes;
    }

    private ClassInfo collectClassInfo(Path file, String firstPartyPrefix) {
        try {
            ClassNode classNode = new ClassNode();
            new ClassReader(Files.newInputStream(file)).accept(classNode, 0);

            String fqcn = Type.getObjectType(classNode.name).getClassName();
            if (!fqcn.startsWith(firstPartyPrefix) || fqcn.contains("$")) {
                return null;
            }

            Set<String> typeRefs = new LinkedHashSet<>();
            Set<String> methodRefs = new LinkedHashSet<>();
            boolean hasMain = false;
            for (MethodNode method : classNode.methods) {
                collectRawReferences(method, typeRefs, methodRefs);
                hasMain = hasMain || isMainMethod(method);
            }

            // Superclass, implemented interfaces, and field types are references too — without these,
            // an interface used only via `implements` / field injection looks unused (dead-code false positive).
            if (classNode.superName != null) {
                typeRefs.add(Type.getObjectType(classNode.superName).getClassName());
            }
            if (classNode.interfaces != null) {
                for (String itf : classNode.interfaces) {
                    typeRefs.add(Type.getObjectType(itf).getClassName());
                }
            }
            if (classNode.fields != null) {
                for (FieldNode field : classNode.fields) {
                    typeRefs.add(stripArraySuffix(Type.getType(field.desc).getClassName()));
                }
            }

            boolean entryPoint = hasMain || hasEntryAnnotation(classNode);

            Set<String> firstPartyClassRefs = new LinkedHashSet<>();
            for (String t : typeRefs) {
                if (t.startsWith(firstPartyPrefix) && !t.equals(fqcn) && !t.contains("$")) {
                    firstPartyClassRefs.add(t);
                }
            }
            return new ClassInfo(fqcn, getPackageName(fqcn), entryPoint, firstPartyClassRefs, typeRefs, methodRefs);
        } catch (IOException e) {
            logger.error("Error reading class file: {}", file, e);
            return null;
        }
    }

    /** Collects every referenced type and method ({@code owner.name}) of a method, unfiltered. */
    private void collectRawReferences(MethodNode method, Set<String> typeRefs, Set<String> methodRefs) {
        typeRefs.add(stripArraySuffix(Type.getReturnType(method.desc).getClassName()));
        for (Type p : Type.getArgumentTypes(method.desc)) {
            typeRefs.add(stripArraySuffix(p.getClassName()));
        }
        if (method.exceptions != null) {
            for (String ex : method.exceptions) {
                typeRefs.add(Type.getObjectType(ex).getClassName());
            }
        }
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode m) {
                String owner = Type.getObjectType(m.owner).getClassName();
                typeRefs.add(owner);
                methodRefs.add(owner + "." + m.name);
            } else if (insn instanceof FieldInsnNode f) {
                typeRefs.add(Type.getObjectType(f.owner).getClassName());
            } else if (insn instanceof TypeInsnNode t) {
                typeRefs.add(Type.getObjectType(t.desc).getClassName());
            }
        }
    }

    private boolean isMainMethod(MethodNode m) {
        return "main".equals(m.name)
                && "([Ljava/lang/String;)V".equals(m.desc)
                && (m.access & Opcodes.ACC_STATIC) != 0;
    }

    private boolean hasEntryAnnotation(ClassNode classNode) {
        return annotationMatches(classNode.visibleAnnotations) || annotationMatches(classNode.invisibleAnnotations);
    }

    private boolean annotationMatches(List<AnnotationNode> annotations) {
        if (annotations == null) {
            return false;
        }
        for (AnnotationNode a : annotations) {
            for (String marker : ENTRY_ANNOTATION_MARKERS) {
                if (a.desc != null && a.desc.contains(marker)) {
                    return true;
                }
            }
        }
        return false;
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
                                  Map<String, Integer> totalClassCount,
                                  Map<String, ComplexityStats> complexity) {
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

            String simpleName = className.substring(className.lastIndexOf('.') + 1);
            ComplexityStats stats = complexity.computeIfAbsent(topLevelPackage, k -> new ComplexityStats());

            Set<String> dependencies = new HashSet<>();
            for (MethodNode method : classNode.methods) {
                analyzeDependencies(method, dependencies);
                if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0) {
                    stats.add(simpleName + "#" + method.name, cyclomaticComplexity(method));
                }
            }
            analyzeClassSignature(classNode, dependencies);

            for (String dependency : dependencies) {
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
        String normalized = normalizeArrayClassName(dependency);
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
