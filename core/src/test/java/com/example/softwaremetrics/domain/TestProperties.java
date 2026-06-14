package com.example.softwaremetrics.domain;

import java.util.List;

import com.example.softwaremetrics.domain.InstabilityCalculatorProperties.PackageListConfig;

/**
 * Test helper that builds an {@link InstabilityCalculatorProperties} mirroring the exclusion
 * lists in {@code application.yaml}, so unit tests exercise {@link JavaClassAnalyzer} with the
 * same filtering as production (JDK/native packages and basic types are excluded from coupling).
 */
final class TestProperties {

    private TestProperties() {
    }

    static InstabilityCalculatorProperties defaults() {
        InstabilityCalculatorProperties props = new InstabilityCalculatorProperties();
        props.setNativePackages(config(List.of(
                "java.", "javax.", "jakarta.", "sun.", "com.sun.", "org.w3c.",
                "org.xml.", "org.omg.", "org.ietf.", "jdk.", "org.apache.xerces.", "org.relaxng.")));
        props.setExternalPackages(config(List.of(
                "org.springframework.", "org.apache.", "com.google.", "org.junit.",
                "org.mockito.", "org.slf4j.", "org.logback.", "org.hibernate.",
                "com.fasterxml.", "org.assertj.", "org.aspectj.", "io.micrometer.",
                "io.swagger.", "io.jsonwebtoken.", "org.json.")));
        props.setBasicTypes(config(List.of(
                "boolean", "byte", "char", "short", "int", "long", "float", "double", "void",
                "java.lang.Boolean", "java.lang.Byte", "java.lang.Character", "java.lang.Short",
                "java.lang.Integer", "java.lang.Long", "java.lang.Float", "java.lang.Double",
                "java.lang.Void", "java.lang.String", "java.lang.Object", "java.lang.Class")));
        return props;
    }

    private static PackageListConfig config(List<String> values) {
        PackageListConfig cfg = new PackageListConfig();
        cfg.setDisabled(true); // inverted flag: true means the exclusion list is ACTIVE
        cfg.setValues(values);
        return cfg;
    }
}
