package com.example.softwaremetrics.domain;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Quality-gate configuration, bound from {@code instability-calculator.gate} in application.yaml.
 * Each gate can be enabled/disabled independently. Use {@link #toConfig()} to obtain the
 * Spring-free {@link GateConfig} consumed by {@link ThresholdEvaluator}.
 */
@Component
@ConfigurationProperties(prefix = "instability-calculator.gate")
public class GateProperties {

    private ThresholdGate maxPackageDistance = new ThresholdGate(true, 0.7);
    private ToggleGate forbiddenZones = new ToggleGate(false);
    private ThresholdGate maxAverageDistance = new ThresholdGate(false, 0.5);

    public ThresholdGate getMaxPackageDistance() {
        return maxPackageDistance;
    }

    public void setMaxPackageDistance(ThresholdGate maxPackageDistance) {
        this.maxPackageDistance = maxPackageDistance;
    }

    public ToggleGate getForbiddenZones() {
        return forbiddenZones;
    }

    public void setForbiddenZones(ToggleGate forbiddenZones) {
        this.forbiddenZones = forbiddenZones;
    }

    public ThresholdGate getMaxAverageDistance() {
        return maxAverageDistance;
    }

    public void setMaxAverageDistance(ThresholdGate maxAverageDistance) {
        this.maxAverageDistance = maxAverageDistance;
    }

    public GateConfig toConfig() {
        return new GateConfig(
                maxPackageDistance.isEnabled(), maxPackageDistance.getThreshold(),
                forbiddenZones.isEnabled(),
                maxAverageDistance.isEnabled(), maxAverageDistance.getThreshold());
    }

    public static class ToggleGate {
        private boolean enabled;

        public ToggleGate() {
        }

        public ToggleGate(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class ThresholdGate extends ToggleGate {
        private double threshold;

        public ThresholdGate() {
        }

        public ThresholdGate(boolean enabled, double threshold) {
            super(enabled);
            this.threshold = threshold;
        }

        public double getThreshold() {
            return threshold;
        }

        public void setThreshold(double threshold) {
            this.threshold = threshold;
        }
    }
}
