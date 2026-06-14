package com.example.softwaremetrics.domain.arch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArchSpecLoaderTest {

    @Test
    void builtInTemplatesLoadAndMatch() {
        for (String name : new String[]{"layered", "hexagonal", "onion"}) {
            ArchSpec spec = ArchSpecLoader.load(name);
            assertThat(spec.name()).isNotBlank();
        }
        ArchSpec layered = ArchSpecLoader.load("layered");
        assertThat(layered.componentOf("com.app.web.FooController")).isEqualTo("Web");
        assertThat(layered.componentOf("com.app.repository.FooRepository")).isEqualTo("Repository");
        assertThat(layered.componentOf("org.thirdparty.Whatever")).isNull();
    }

    @Test
    void customYamlFileParses(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("my-arch.yaml");
        Files.writeString(file, """
                name: Custom
                components:
                  - name: Api
                    matches: ['.*\\.api\\..*']
                  - name: Core
                    matches: ['.*\\.core\\..*']
                access:
                  Api: [Core]
                  Core: []
                options:
                  forbidCycles: true
                """);

        ArchSpec spec = ArchSpecLoader.load(file.toString());

        assertThat(spec.name()).isEqualTo("Custom");
        assertThat(spec.componentOf("x.api.Controller")).isEqualTo("Api");
        assertThat(spec.allowedTargets("Api")).containsExactly("Core");
        assertThat(spec.forbidCycles()).isTrue();
    }

    @Test
    void unknownTemplateThrows() {
        assertThatThrownBy(() -> ArchSpecLoader.load("nope"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown architecture template");
    }

    @Test
    void missingFileThrows() {
        assertThatThrownBy(() -> ArchSpecLoader.load("./does-not-exist.yaml"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
    }
}
