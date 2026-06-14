package com.example.softwaremetrics.domain;

/**
 * Mutable per-package accumulator for method complexity, filled during the metrics pass and read into
 * {@link PackageMetrics}.
 */
class ComplexityStats {

    private int methodCount;
    private long complexitySum;
    private int maxComplexity;
    private String mostComplexMethod;

    void add(String methodName, int complexity) {
        methodCount++;
        complexitySum += complexity;
        if (complexity > maxComplexity) {
            maxComplexity = complexity;
            mostComplexMethod = methodName;
        }
    }

    int methodCount() {
        return methodCount;
    }

    double averageComplexity() {
        return methodCount == 0 ? 0.0 : Math.round((double) complexitySum / methodCount * 100.0) / 100.0;
    }

    int maxComplexity() {
        return maxComplexity;
    }

    String mostComplexMethod() {
        return mostComplexMethod;
    }
}
