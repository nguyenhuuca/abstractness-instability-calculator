package com.example.softwaremetrics.config;

import com.example.softwaremetrics.config.CheckConfigLoader.Overrides;
import com.example.softwaremetrics.domain.banned.BannedApiRule;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CheckConfigLoaderTest {

    private void writeFile(Path dir, String relative, String content) throws IOException {
        Path file = dir.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @Test
    void defaultsWhenNoFileAndNoFlags(@TempDir Path proj) {
        CheckConfig cfg = CheckConfigLoader.resolve(proj, Overrides.none());

        assertThat(cfg.gate().maxPackageDistanceEnabled()).isTrue();
        assertThat(cfg.gate().maxPackageDistance()).isEqualTo(0.7);
        assertThat(cfg.gate().noCyclesEnabled()).isFalse();
        assertThat(cfg.architecture()).isNull();
    }

    @Test
    void projectFileOverridesDefaults(@TempDir Path proj) throws IOException {
        writeFile(proj, "src/main/resources/aic-check.yaml", """
                gates:
                  no-cycles: { enabled: true }
                  forbidden-zones: { enabled: true }
                  max-package-distance: { enabled: true, threshold: 0.5 }
                architecture:
                  enabled: true
                  template: layered
                """);

        CheckConfig cfg = CheckConfigLoader.resolve(proj, Overrides.none());

        assertThat(cfg.gate().noCyclesEnabled()).isTrue();
        assertThat(cfg.gate().forbiddenZonesEnabled()).isTrue();
        assertThat(cfg.gate().maxPackageDistance()).isEqualTo(0.5);
        assertThat(cfg.architecture()).isNotNull();
        assertThat(cfg.architecture().name()).isEqualTo("Layered");
    }

    @Test
    void discoversFileAtProjectRoot(@TempDir Path proj) throws IOException {
        writeFile(proj, "aic-check.yaml", """
                gates:
                  no-cycles: { enabled: true }
                """);

        CheckConfig cfg = CheckConfigLoader.resolve(proj, Overrides.none());

        assertThat(cfg.gate().noCyclesEnabled()).isTrue();
    }

    @Test
    void inlineArchitectureSpecIsParsed(@TempDir Path proj) throws IOException {
        writeFile(proj, "aic-check.yaml", """
                architecture:
                  enabled: true
                  spec:
                    name: Custom
                    components:
                      - name: Api
                        matches: ['.*\\.api\\..*']
                      - name: Core
                        matches: ['.*\\.core\\..*']
                    access:
                      Api: [Core]
                      Core: []
                """);

        CheckConfig cfg = CheckConfigLoader.resolve(proj, Overrides.none());

        assertThat(cfg.architecture()).isNotNull();
        assertThat(cfg.architecture().name()).isEqualTo("Custom");
        assertThat(cfg.architecture().componentOf("x.api.Controller")).isEqualTo("Api");
    }

    @Test
    void cliFlagsOverrideProjectFile(@TempDir Path proj) throws IOException {
        writeFile(proj, "aic-check.yaml", """
                gates:
                  max-package-distance: { enabled: true, threshold: 0.5 }
                architecture:
                  enabled: true
                  template: layered
                """);

        CheckConfig cfg = CheckConfigLoader.resolve(proj, new Overrides(0.9, true, "onion"));

        assertThat(cfg.gate().maxPackageDistance()).isEqualTo(0.9);   // flag wins over file's 0.5
        assertThat(cfg.gate().noCyclesEnabled()).isTrue();            // flag enabled it
        assertThat(cfg.architecture().name()).isEqualTo("Onion / Clean"); // --arch overrides template
    }

    @Test
    void invalidArchitectureTemplateThrows(@TempDir Path proj) throws IOException {
        writeFile(proj, "aic-check.yaml", """
                architecture:
                  enabled: true
                  template: nope
                """);

        assertThatThrownBy(() -> CheckConfigLoader.resolve(proj, Overrides.none()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown architecture template");
    }

    @Test
    void parsesMaxComplexityGate(@TempDir Path proj) throws IOException {
        writeFile(proj, "aic-check.yaml", """
                gates:
                  max-complexity:
                    enabled: true
                    threshold: 12
                """);

        CheckConfig cfg = CheckConfigLoader.resolve(proj, Overrides.none());

        assertThat(cfg.gate().maxComplexityEnabled()).isTrue();
        assertThat(cfg.gate().maxComplexity()).isEqualTo(12);
    }

    @Test
    void parsesBannedApisWithAllowedIn(@TempDir Path proj) throws IOException {
        writeFile(proj, "aic-check.yaml", """
                banned-apis:
                  enabled: true
                  rules:
                    - method: java.lang.System.exit
                      message: "no exit"
                    - package: java.sql
                      allowedIn: ['.*\\.repository\\..*']
                """);

        CheckConfig cfg = CheckConfigLoader.resolve(proj, Overrides.none());

        assertThat(cfg.bannedApis()).hasSize(2);
        assertThat(cfg.bannedApis().get(0).kind()).isEqualTo(BannedApiRule.Kind.METHOD);
        assertThat(cfg.bannedApis().get(0).target()).isEqualTo("java.lang.System.exit");
        assertThat(cfg.bannedApis().get(1).kind()).isEqualTo(BannedApiRule.Kind.PACKAGE);
        assertThat(cfg.bannedApis().get(1).isAllowedIn("com.app.repository.UserRepository")).isTrue();
        assertThat(cfg.bannedApis().get(1).isAllowedIn("com.app.service.UserService")).isFalse();
    }

    @Test
    void parsesDeadCodeFlag(@TempDir Path proj) throws IOException {
        writeFile(proj, "aic-check.yaml", """
                dead-code:
                  enabled: true
                """);

        assertThat(CheckConfigLoader.resolve(proj, Overrides.none()).deadCodeEnabled()).isTrue();
        assertThat(CheckConfigLoader.resolve(proj, Overrides.none()).bannedApis()).isEmpty();
    }

    @Test
    void parsesAnalyzeDepthAndExpand(@TempDir Path proj) throws IOException {
        writeFile(proj, "aic-check.yaml", """
                analyze:
                  depth: 2
                  expand:
                    - dto
                    - service
                """);

        CheckConfig cfg = CheckConfigLoader.resolve(proj, Overrides.none());

        assertThat(cfg.analyze().depth()).isEqualTo(2);
        assertThat(cfg.analyze().expand()).containsExactly("dto", "service");
    }

    @Test
    void analyzeDefaultsWhenAbsent(@TempDir Path proj) throws IOException {
        writeFile(proj, "aic-check.yaml", "dead-code:\n  enabled: true\n");
        CheckConfig cfg = CheckConfigLoader.resolve(proj, Overrides.none());
        assertThat(cfg.analyze().depth()).isEqualTo(1);
        assertThat(cfg.analyze().expand()).isEmpty();
    }
}
