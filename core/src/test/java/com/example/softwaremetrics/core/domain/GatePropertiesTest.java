package com.example.softwaremetrics.core.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GatePropertiesTest {

    @Test
    void defaultValuesMatchCodeDefaults() {
        GateProperties props = new GateProperties();

        // Only maxPackageDistance is enabled by default with threshold 0.7
        assertThat(props.getMaxPackageDistance().isEnabled()).isTrue();
        assertThat(props.getMaxPackageDistance().getThreshold()).isEqualTo(0.7);
        assertThat(props.getForbiddenZones().isEnabled()).isFalse();
        assertThat(props.getMaxAverageDistance().isEnabled()).isFalse();
        assertThat(props.getMaxAverageDistance().getThreshold()).isEqualTo(0.5);
        assertThat(props.getNoCycles().isEnabled()).isFalse();
        assertThat(props.getMaxComplexity().isEnabled()).isFalse();
        assertThat(props.getMaxComplexity().getThreshold()).isEqualTo(15);
    }

    @Test
    void settersAreReflectedInToConfig() {
        GateProperties props = new GateProperties();
        props.getMaxPackageDistance().setThreshold(0.5);
        props.getMaxPackageDistance().setEnabled(true);
        props.getForbiddenZones().setEnabled(true);
        props.getMaxAverageDistance().setEnabled(true);
        props.getMaxAverageDistance().setThreshold(0.3);
        props.getNoCycles().setEnabled(true);
        props.getMaxComplexity().setEnabled(true);
        props.getMaxComplexity().setThreshold(10);

        GateConfig config = props.toConfig();

        assertThat(config.maxPackageDistanceEnabled()).isTrue();
        assertThat(config.maxPackageDistance()).isEqualTo(0.5);
        assertThat(config.forbiddenZonesEnabled()).isTrue();
        assertThat(config.maxAverageDistanceEnabled()).isTrue();
        assertThat(config.maxAverageDistance()).isEqualTo(0.3);
        assertThat(config.noCyclesEnabled()).isTrue();
        assertThat(config.maxComplexityEnabled()).isTrue();
        assertThat(config.maxComplexity()).isEqualTo(10);
    }

    @Test
    void setMaxPackageDistanceGateReplacesBothFieldAtOnce() {
        GateProperties props = new GateProperties();
        GateProperties.ThresholdGate replacement = new GateProperties.ThresholdGate(false, 0.9);
        props.setMaxPackageDistance(replacement);

        assertThat(props.getMaxPackageDistance()).isSameAs(replacement);
        assertThat(props.toConfig().maxPackageDistanceEnabled()).isFalse();
        assertThat(props.toConfig().maxPackageDistance()).isEqualTo(0.9);
    }

    @Test
    void setForbiddenZonesGateReplacesField() {
        GateProperties props = new GateProperties();
        GateProperties.ToggleGate replacement = new GateProperties.ToggleGate(true);
        props.setForbiddenZones(replacement);

        assertThat(props.getForbiddenZones()).isSameAs(replacement);
        assertThat(props.toConfig().forbiddenZonesEnabled()).isTrue();
    }

    @Test
    void setMaxAverageDistanceGateReplacesField() {
        GateProperties props = new GateProperties();
        GateProperties.ThresholdGate replacement = new GateProperties.ThresholdGate(true, 0.4);
        props.setMaxAverageDistance(replacement);

        assertThat(props.getMaxAverageDistance()).isSameAs(replacement);
        assertThat(props.toConfig().maxAverageDistanceEnabled()).isTrue();
        assertThat(props.toConfig().maxAverageDistance()).isEqualTo(0.4);
    }

    @Test
    void setNoCyclesGateReplacesField() {
        GateProperties props = new GateProperties();
        GateProperties.ToggleGate replacement = new GateProperties.ToggleGate(true);
        props.setNoCycles(replacement);

        assertThat(props.getNoCycles()).isSameAs(replacement);
        assertThat(props.toConfig().noCyclesEnabled()).isTrue();
    }

    @Test
    void setMaxComplexityGateReplacesField() {
        GateProperties props = new GateProperties();
        GateProperties.ThresholdGate replacement = new GateProperties.ThresholdGate(true, 20);
        props.setMaxComplexity(replacement);

        assertThat(props.getMaxComplexity()).isSameAs(replacement);
        assertThat(props.toConfig().maxComplexityEnabled()).isTrue();
        assertThat(props.toConfig().maxComplexity()).isEqualTo(20);
    }

    @Test
    void toggleGateNoArgConstructorDefaultsToDisabled() {
        GateProperties.ToggleGate gate = new GateProperties.ToggleGate();
        assertThat(gate.isEnabled()).isFalse();

        gate.setEnabled(true);
        assertThat(gate.isEnabled()).isTrue();
    }

    @Test
    void thresholdGateNoArgConstructorHasZeroThreshold() {
        GateProperties.ThresholdGate gate = new GateProperties.ThresholdGate();
        assertThat(gate.isEnabled()).isFalse();
        assertThat(gate.getThreshold()).isEqualTo(0.0);

        gate.setEnabled(true);
        gate.setThreshold(1.5);
        assertThat(gate.isEnabled()).isTrue();
        assertThat(gate.getThreshold()).isEqualTo(1.5);
    }
}
