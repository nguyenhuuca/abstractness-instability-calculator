package com.example.softwaremetrics.core.config;

import com.example.softwaremetrics.core.domain.GateConfig;
import com.example.softwaremetrics.core.domain.InstabilityCalculatorProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultsTest {

    @Test
    void gateConfigHasExpectedDefaults() {
        GateConfig config = Defaults.gateConfig();

        assertThat(config).isNotNull();
        // Only maxPackageDistance is on by default
        assertThat(config.maxPackageDistanceEnabled()).isTrue();
        assertThat(config.maxPackageDistance()).isEqualTo(0.7);
        // All other gates are disabled
        assertThat(config.forbiddenZonesEnabled()).isFalse();
        assertThat(config.maxAverageDistanceEnabled()).isFalse();
        assertThat(config.noCyclesEnabled()).isFalse();
        assertThat(config.maxComplexityEnabled()).isFalse();
    }

    @Test
    void exclusionsContainsStandardJavaNativePackages() {
        InstabilityCalculatorProperties props = Defaults.exclusions();

        assertThat(props).isNotNull();
        assertThat(props.getNativePackages()).isNotNull();
        assertThat(props.getNativePackages().getValues()).contains("java.", "javax.", "jakarta.");
        assertThat(props.getNativePackages().isEnabled()).isTrue(); // enabled=true means the filter is active
    }

    @Test
    void exclusionsContainsCommonExternalLibraries() {
        InstabilityCalculatorProperties props = Defaults.exclusions();

        assertThat(props.getExternalPackages().getValues())
                .contains("org.springframework.", "org.apache.", "com.fasterxml.");
        assertThat(props.getExternalPackages().isEnabled()).isTrue();
    }

    @Test
    void exclusionsContainsBasicJavaTypes() {
        InstabilityCalculatorProperties props = Defaults.exclusions();

        assertThat(props.getBasicTypes().getValues())
                .contains("java.lang.String", "java.lang.Object", "int", "boolean");
        assertThat(props.getBasicTypes().isEnabled()).isTrue();
    }
}
