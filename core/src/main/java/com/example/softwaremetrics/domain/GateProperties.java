package com.example.softwaremetrics.domain;

/**
 * Quality-gate configuration. In the web app it's bound from {@code instability-calculator.gate} in
 * application.yaml (via a {@code @Bean @ConfigurationProperties} method); in the CLI it's built from
 * code defaults. Each gate can be enabled/disabled independently. Use {@link #toConfig()} to obtain
 * the {@link GateConfig} consumed by {@link ThresholdEvaluator}.
 */
public class GateProperties {

    private ThresholdGate maxPackageDistance = new ThresholdGate(true, 0.7);
    private ToggleGate forbiddenZones = new ToggleGate(false);
    private ThresholdGate maxAverageDistance = new ThresholdGate(false, 0.5);
    private ToggleGate noCycles = new ToggleGate(false);

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

    public ToggleGate getNoCycles() {
        return noCycles;
    }

    public void setNoCycles(ToggleGate noCycles) {
        this.noCycles = noCycles;
    }

    public GateConfig toConfig() {
        return new GateConfig(
                maxPackageDistance.isEnabled(), maxPackageDistance.getThreshold(),
                forbiddenZones.isEnabled(),
                maxAverageDistance.isEnabled(), maxAverageDistance.getThreshold(),
                noCycles.isEnabled());
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
