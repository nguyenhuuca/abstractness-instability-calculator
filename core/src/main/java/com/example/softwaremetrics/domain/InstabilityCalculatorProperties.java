package com.example.softwaremetrics.domain;

import java.util.List;

/**
 * Holds the dependency-exclusion lists (native/external packages, basic types). In the web app it's
 * bound from {@code instability-calculator} in application.yaml (via a {@code @Bean
 * @ConfigurationProperties} method); in the CLI it's populated from code defaults.
 */
public class InstabilityCalculatorProperties {

    private PackageListConfig nativePackages = new PackageListConfig();
    private PackageListConfig externalPackages = new PackageListConfig();
    private PackageListConfig basicTypes = new PackageListConfig();

    public PackageListConfig getNativePackages() {
        return nativePackages;
    }

    public void setNativePackages(PackageListConfig nativePackages) {
        this.nativePackages = nativePackages;
    }

    public PackageListConfig getExternalPackages() {
        return externalPackages;
    }

    public void setExternalPackages(PackageListConfig externalPackages) {
        this.externalPackages = externalPackages;
    }

    public PackageListConfig getBasicTypes() {
        return basicTypes;
    }

    public void setBasicTypes(PackageListConfig basicTypes) {
        this.basicTypes = basicTypes;
    }

    public static class PackageListConfig {
        private boolean disabled = true;

        public List<String> getValues() {
            return values;
        }

        public void setValues(List<String> values) {
            this.values = values;
        }

        private List<String> values;

        public boolean isDisabled() {
            return disabled;
        }

        public void setDisabled(boolean disabled) {
            this.disabled = disabled;
        }
    }
}
