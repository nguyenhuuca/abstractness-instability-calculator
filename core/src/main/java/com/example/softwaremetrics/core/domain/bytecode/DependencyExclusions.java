package com.example.softwaremetrics.core.domain.bytecode;

import com.example.softwaremetrics.core.domain.InstabilityCalculatorProperties;

/**
 * Decides whether a referenced type should be excluded from first-party coupling — JDK/native
 * packages, common third-party libraries, and basic types — based on the configured lists. Each list
 * filters only when its {@code enabled} flag is {@code true} (the default).
 */
public final class DependencyExclusions {

    private final InstabilityCalculatorProperties props;

    public DependencyExclusions(InstabilityCalculatorProperties props) {
        this.props = props;
    }

    /** True when {@code dependency} is a JDK/native package, a known external library, or a basic type. */
    public boolean isExcluded(String dependency) {
        return isJavaNativePackage(dependency) || isBasicType(dependency)
                || isJavaExternalPackage(dependency);
    }

    private boolean isJavaNativePackage(String packageName) {
        if (!props.getNativePackages().isEnabled()) {
            return false;
        }
        return props.getNativePackages().getValues().stream().anyMatch(packageName::startsWith);
    }

    private boolean isJavaExternalPackage(String packageName) {
        if (!props.getExternalPackages().isEnabled()) {
            return false;
        }
        return props.getExternalPackages().getValues().stream().anyMatch(packageName::startsWith);
    }

    private boolean isBasicType(String typeName) {
        if (!props.getBasicTypes().isEnabled()) {
            return false;
        }
        return props.getBasicTypes().getValues().contains(typeName)
                || props.getBasicTypes().getValues().contains(TypeNames.getPackageName(typeName));
    }
}
