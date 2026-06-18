package com.example.softwaremetrics.core.domain.arch;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArchSpecTest {

    private ArchSpec spec(boolean ignoreUnmatched, boolean forbidCycles) {
        return new ArchSpec(
                "test-spec",
                List.of(new ArchSpec.Component("Web", List.of(Pattern.compile(".*\\.web\\..*"))),
                        new ArchSpec.Component("Domain", List.of(Pattern.compile(".*\\.domain\\..*")))),
                Map.of("Web", Set.of("Domain")),
                Set.of(new ArchSpec.Edge("Domain", "Web")),
                Map.of("Web", Pattern.compile(".*Controller")),
                ignoreUnmatched,
                forbidCycles);
    }

    @Test
    void ignoreUnmatchedReturnsConfiguredValue() {
        assertThat(spec(true, false).ignoreUnmatched()).isTrue();
        assertThat(spec(false, false).ignoreUnmatched()).isFalse();
    }

    @Test
    void forbidCyclesReturnsConfiguredValue() {
        assertThat(spec(true, true).forbidCycles()).isTrue();
        assertThat(spec(true, false).forbidCycles()).isFalse();
    }

    @Test
    void nameReturnsSpecName() {
        assertThat(spec(true, false).name()).isEqualTo("test-spec");
    }

    @Test
    void componentOfMatchesPatternAndReturnsComponentName() {
        ArchSpec s = spec(true, false);

        assertThat(s.componentOf("com.example.web.FooController")).isEqualTo("Web");
        assertThat(s.componentOf("com.example.domain.Order")).isEqualTo("Domain");
        assertThat(s.componentOf("com.example.infra.Adapter")).isNull();
    }

    @Test
    void allowedTargetsReturnsAllowListOrNullWhenAbsent() {
        ArchSpec s = spec(true, false);

        assertThat(s.allowedTargets("Web")).containsExactly("Domain");
        assertThat(s.allowedTargets("Domain")).isNull(); // no allow-list for Domain
    }

    @Test
    void isForbiddenMatchesForbiddenEdge() {
        ArchSpec s = spec(true, false);

        assertThat(s.isForbidden("Domain", "Web")).isTrue();
        assertThat(s.isForbidden("Web", "Domain")).isFalse();
    }

    @Test
    void namingPatternReturnsPatternOrNullWhenAbsent() {
        ArchSpec s = spec(true, false);

        assertThat(s.namingPattern("Web")).isNotNull();
        assertThat(s.namingPattern("Web").pattern()).isEqualTo(".*Controller");
        assertThat(s.namingPattern("Domain")).isNull();
    }

    @Test
    void fromYamlThrowsWhenRootIsNull() {
        assertThatThrownBy(() -> ArchSpec.fromYaml(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void fromYamlThrowsWhenNoComponentsDefined() {
        assertThatThrownBy(() -> ArchSpec.fromYaml(Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("component");
    }
}
