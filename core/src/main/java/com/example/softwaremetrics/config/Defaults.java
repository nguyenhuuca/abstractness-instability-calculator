package com.example.softwaremetrics.config;

import com.example.softwaremetrics.domain.GateConfig;
import com.example.softwaremetrics.domain.GateProperties;
import com.example.softwaremetrics.domain.InstabilityCalculatorProperties;
import com.example.softwaremetrics.domain.InstabilityCalculatorProperties.PackageListConfig;

import java.util.List;

/**
 * Code-level default configuration, the single source of default values for both the CLI (used
 * directly) and the web app (used as the seed object that {@code application.yaml} overrides via
 * {@code @Bean @ConfigurationProperties}).
 */
public final class Defaults {

    private Defaults() {
    }

    /** Default dependency-exclusion lists (JDK/native packages, common libraries, basic types). */
    public static InstabilityCalculatorProperties exclusions() {
        InstabilityCalculatorProperties props = new InstabilityCalculatorProperties();
        props.setNativePackages(list(
                "java.", "javax.", "jakarta.", "sun.", "com.sun.", "org.w3c.",
                "org.xml.", "org.omg.", "org.ietf.", "jdk.", "org.apache.xerces.", "org.relaxng."));
        props.setExternalPackages(list(
                "org.springframework.", "org.apache.", "com.google.", "org.junit.",
                "org.mockito.", "org.slf4j.", "org.logback.", "org.hibernate.",
                "com.fasterxml.", "org.assertj.", "org.aspectj.", "io.micrometer.",
                "io.swagger.", "io.jsonwebtoken.", "org.json."));
        props.setBasicTypes(list(
                "boolean", "byte", "char", "short", "int", "long", "float", "double", "void",
                "java.lang.Boolean", "java.lang.Byte", "java.lang.Character", "java.lang.Short",
                "java.lang.Integer", "java.lang.Long", "java.lang.Float", "java.lang.Double",
                "java.lang.Void", "java.lang.String", "java.lang.Object", "java.lang.Class"));
        return props;
    }

    /** Default quality gate: only {@code max-package-distance} (0.7) is enabled. */
    public static GateConfig gateConfig() {
        return new GateProperties().toConfig();
    }

    private static PackageListConfig list(String... values) {
        PackageListConfig cfg = new PackageListConfig();
        cfg.setDisabled(true); // inverted flag: true means the exclusion list is ACTIVE
        cfg.setValues(List.of(values));
        return cfg;
    }
}
