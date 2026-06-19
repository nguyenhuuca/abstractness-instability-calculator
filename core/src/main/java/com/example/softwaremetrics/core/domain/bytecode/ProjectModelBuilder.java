package com.example.softwaremetrics.core.domain.bytecode;

import com.example.softwaremetrics.core.domain.model.ClassDetail;
import com.example.softwaremetrics.core.domain.model.MethodComplexity;
import com.example.softwaremetrics.core.domain.model.ProjectModel;

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
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads a compiled project's {@code .class} files <strong>once</strong> and produces a
 * {@link ProjectModel}. Each class is parsed a single time; from that one {@link ClassNode} it
 * captures both the exclusion-filtered dependency set (for metrics + architecture) and the unfiltered
 * type/method references + entry-point flag + complexity (for banned-API / dead-code). This replaces
 * the three separate filesystem+ASM passes the analyzer used to make.
 *
 * <p>The extraction logic is moved verbatim from the former {@code JavaClassAnalyzer}, so the derived
 * numbers are unchanged.
 */
public class ProjectModelBuilder {

    private static final Logger logger = LoggerFactory.getLogger(ProjectModelBuilder.class);

    private static final Set<String> ENTRY_ANNOTATION_MARKERS = Set.of(
            "SpringBootApplication", "RestController", "Controller", "Service",
            "Repository", "Component", "Configuration", "Entity");

    private final DependencyExclusions exclusions;

    public ProjectModelBuilder(DependencyExclusions exclusions) {
        this.exclusions = exclusions;
    }

    /** Walks the project for non-test {@code .class} files and builds the model (one parse per class). */
    public ProjectModel build(Path projectPath) {
        List<ClassDetail> classes = new ArrayList<>();
        try (var walk = Files.walk(projectPath)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".class"))
                    .filter(ProjectModelBuilder::isNotTestClass)
                    .forEach(file -> {
                        ClassDetail detail = analyzeClassFile(file);
                        if (detail != null) {
                            classes.add(detail);
                        }
                    });
        } catch (IOException e) {
            logger.error("Error while analyzing classes for {}", projectPath, e);
            throw new IllegalStateException("Cannot read project at '" + projectPath + "'", e);
        }
        return new ProjectModel(classes);
    }

    private static boolean isNotTestClass(Path path) {
        String p = path.toString().replace('\\', '/'); // normalize Windows separators
        return !p.contains("/target/test-classes/")        // Maven
            && !p.contains("/build/classes/java/test/")    // Gradle (Java)
            && !p.contains("/build/classes/kotlin/test/"); // Gradle (Kotlin)
    }

    private ClassDetail analyzeClassFile(Path file) {
        try {
            ClassNode classNode = new ClassNode();
            new ClassReader(Files.newInputStream(file)).accept(classNode, 0);

            String className = Type.getObjectType(classNode.name).getClassName();
            boolean abstractType = (classNode.access & Opcodes.ACC_ABSTRACT) != 0
                    || (classNode.access & Opcodes.ACC_INTERFACE) != 0;
            boolean builderType = classNode.name.endsWith("Builder");
            boolean inner = className.contains("$");
            String simpleName = className.substring(className.lastIndexOf('.') + 1);

            Set<String> dependencies = new HashSet<>();   // metrics + architecture (exclusion-filtered)
            Set<String> typeRefs = new LinkedHashSet<>();  // banned/dead-code (unfiltered)
            Set<String> methodRefs = new LinkedHashSet<>();
            List<MethodComplexity> methods = new ArrayList<>();
            boolean hasMain = false;

            for (MethodNode method : classNode.methods) {
                analyzeDependencies(method, dependencies);
                collectRawReferences(method, typeRefs, methodRefs);
                if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0) {
                    methods.add(new MethodComplexity(simpleName + "#" + method.name, cyclomaticComplexity(method)));
                }
                hasMain = hasMain || isMainMethod(method);
                addAnnotationTypes(method.visibleAnnotations, typeRefs);
                addAnnotationTypes(method.invisibleAnnotations, typeRefs);
                addParameterAnnotationTypes(method.visibleParameterAnnotations, typeRefs);
                addParameterAnnotationTypes(method.invisibleParameterAnnotations, typeRefs);
            }
            analyzeClassSignature(classNode, dependencies);

            // Superclass, implemented interfaces, field types and annotations are references too.
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
                    typeRefs.add(TypeNames.stripArraySuffix(Type.getType(field.desc).getClassName()));
                    addAnnotationTypes(field.visibleAnnotations, typeRefs);
                    addAnnotationTypes(field.invisibleAnnotations, typeRefs);
                }
            }
            addAnnotationTypes(classNode.visibleAnnotations, typeRefs);
            addAnnotationTypes(classNode.invisibleAnnotations, typeRefs);

            boolean entryPoint = hasMain || hasEntryAnnotation(classNode);

            return new ClassDetail(className, TypeNames.getPackageName(className),
                    abstractType, builderType, inner, entryPoint, methods, dependencies, typeRefs, methodRefs);
        } catch (IOException e) {
            logger.error("Error reading class file: {}", file, e);
            return null;
        }
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

    /** Exclusion-filtered dependency extraction: signatures, exceptions, body refs and local variables. */
    private void analyzeDependencies(MethodNode method, Set<String> dependencies) {
        addDependencyIfNotExcluded(dependencies, Type.getReturnType(method.desc).getClassName());
        for (Type paramType : Type.getArgumentTypes(method.desc)) {
            addDependencyIfNotExcluded(dependencies, paramType.getClassName());
        }
        method.exceptions.forEach(exception ->
                addDependencyIfNotExcluded(dependencies, Type.getObjectType(exception).getClassName()));
        method.instructions.forEach(instruction -> {
            if (instruction instanceof MethodInsnNode methodInsn) {
                addDependencyIfNotExcluded(dependencies, Type.getObjectType(methodInsn.owner).getClassName());
            } else if (instruction instanceof FieldInsnNode fieldInsn) {
                addDependencyIfNotExcluded(dependencies, Type.getObjectType(fieldInsn.owner).getClassName());
            } else if (instruction instanceof TypeInsnNode typeInsn) {
                addDependencyIfNotExcluded(dependencies, Type.getObjectType(typeInsn.desc).getClassName());
            }
        });
        if (method.localVariables != null) {
            for (LocalVariableNode localVar : method.localVariables) {
                addDependencyIfNotExcluded(dependencies, Type.getType(localVar.desc).getClassName());
            }
        }
    }

    private void analyzeClassSignature(ClassNode classNode, Set<String> dependencies) {
        if (classNode.signature == null) {
            return;
        }
        new SignatureReader(classNode.signature).accept(new SignatureVisitor(Opcodes.ASM9) {
            @Override
            public void visitClassType(String name) {
                addDependencyIfNotExcluded(dependencies, name.replace('/', '.'));
                super.visitClassType(name);
            }
        });
    }

    private void addDependencyIfNotExcluded(Set<String> dependencies, String dependency) {
        if (!exclusions.isExcluded(TypeNames.normalizeArrayClassName(dependency))) {
            dependencies.add(dependency);
        }
    }

    /** Collects every referenced type and method ({@code owner.name}) of a method, unfiltered. */
    private void collectRawReferences(MethodNode method, Set<String> typeRefs, Set<String> methodRefs) {
        typeRefs.add(TypeNames.stripArraySuffix(Type.getReturnType(method.desc).getClassName()));
        for (Type p : Type.getArgumentTypes(method.desc)) {
            typeRefs.add(TypeNames.stripArraySuffix(p.getClassName()));
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

    private void addAnnotationTypes(List<AnnotationNode> annotations, Set<String> typeRefs) {
        if (annotations == null) {
            return;
        }
        for (AnnotationNode a : annotations) {
            if (a.desc != null) {
                typeRefs.add(Type.getType(a.desc).getClassName());
            }
            addAnnotationValues(a.values, typeRefs);
        }
    }

    private void addAnnotationValues(List<Object> values, Set<String> typeRefs) {
        if (values == null) {
            return;
        }
        for (Object value : values) {
            addAnnotationValue(value, typeRefs);
        }
    }

    private void addAnnotationValue(Object value, Set<String> typeRefs) {
        if (value instanceof Type type) {                       // a class literal, e.g. Foo.class
            typeRefs.add(TypeNames.stripArraySuffix(type.getClassName()));
        } else if (value instanceof AnnotationNode nested) {     // a nested annotation
            if (nested.desc != null) {
                typeRefs.add(Type.getType(nested.desc).getClassName());
            }
            addAnnotationValues(nested.values, typeRefs);
        } else if (value instanceof List<?> list) {              // an array-valued member
            for (Object item : list) {
                addAnnotationValue(item, typeRefs);
            }
        } else if (value instanceof String[] enumValue && enumValue.length == 2) {  // {enumDesc, name}
            typeRefs.add(Type.getType(enumValue[0]).getClassName());
        }
    }

    private void addParameterAnnotationTypes(List<AnnotationNode>[] parameterAnnotations, Set<String> typeRefs) {
        if (parameterAnnotations == null) {
            return;
        }
        for (List<AnnotationNode> annotations : parameterAnnotations) {
            addAnnotationTypes(annotations, typeRefs);
        }
    }
}
