package com.example.softwaremetrics.core.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PackageMetricsTest {

    @Test
    void allGettersReturnValuesSetBySetter() {
        PackageMetrics m = new PackageMetrics();
        m.setPackageName("com.example.foo");
        m.setCe(5);
        m.setCa(3);
        m.setEfferentDependencies(List.of("com.example.bar.A", "com.example.baz.B"));
        m.setAfferentDependencies(List.of("com.example.web.C"));
        m.setAbstractClassCount(2);
        m.setTotalClassCount(8);
        m.setAbstractness(0.25);
        m.setInstability(0.625);
        m.setDistance(0.125);
        m.setMethodCount(12);
        m.setAvgComplexity(3.5);
        m.setMaxComplexity(9);
        m.setMostComplexMethod("com.example.foo.Service#process");

        assertThat(m.getPackageName()).isEqualTo("com.example.foo");
        assertThat(m.getCe()).isEqualTo(5);
        assertThat(m.getCa()).isEqualTo(3);
        assertThat(m.getEfferentDependencies()).containsExactly("com.example.bar.A", "com.example.baz.B");
        assertThat(m.getAfferentDependencies()).containsExactly("com.example.web.C");
        assertThat(m.getAbstractClassCount()).isEqualTo(2);
        assertThat(m.getTotalClassCount()).isEqualTo(8);
        assertThat(m.getAbstractness()).isEqualTo(0.25);
        assertThat(m.getInstability()).isEqualTo(0.625);
        assertThat(m.getDistance()).isEqualTo(0.125);
        assertThat(m.getMethodCount()).isEqualTo(12);
        assertThat(m.getAvgComplexity()).isEqualTo(3.5);
        assertThat(m.getMaxComplexity()).isEqualTo(9);
        assertThat(m.getMostComplexMethod()).isEqualTo("com.example.foo.Service#process");
    }

    @Test
    void defaultConstructorYieldsZeroValues() {
        PackageMetrics m = new PackageMetrics();

        assertThat(m.getCe()).isZero();
        assertThat(m.getCa()).isZero();
        assertThat(m.getMethodCount()).isZero();
        assertThat(m.getAfferentDependencies()).isNull();
        assertThat(m.getAbstractClassCount()).isZero();
        assertThat(m.getTotalClassCount()).isZero();
        assertThat(m.getAvgComplexity()).isZero();
    }
}
