package com.example.softwaremetrics.domain;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ModuleResolverTest {

    @Test
    void depthOneTreatsDirectSubPackagesAsModules() {
        ModuleResolver r = ModuleResolver.topLevel("com.app");

        assertThat(r.moduleOf("com.app.dto.LoginDto")).isEqualTo("com.app.dto");
        assertThat(r.moduleOf("com.app.dto.admin.AdminDto")).isEqualTo("com.app.dto"); // rolls up
        assertThat(r.moduleOf("com.app.App")).isNull();        // directly in the main package
        assertThat(r.moduleOf("java.util.List")).isNull();     // external
    }

    @Test
    void globalDepthTwoSplitsEveryBranch() {
        ModuleResolver r = new ModuleResolver("com.app", 2, Set.of());

        assertThat(r.moduleOf("com.app.service.impl.FooImpl")).isEqualTo("com.app.service.impl");
        assertThat(r.moduleOf("com.app.service.Bar")).isEqualTo("com.app.service"); // shallower → capped, not dropped
    }

    @Test
    void expandSplitsOnlyTheNamedPackage() {
        ModuleResolver r = new ModuleResolver("com.app", 1, Set.of("com.app.dto"));

        assertThat(r.moduleOf("com.app.dto.admin.AdminDto")).isEqualTo("com.app.dto.admin");
        assertThat(r.moduleOf("com.app.dto.LoginDto")).isEqualTo("com.app.dto");        // directly in dto
        assertThat(r.moduleOf("com.app.service.impl.FooImpl")).isEqualTo("com.app.service"); // not expanded
    }
}
