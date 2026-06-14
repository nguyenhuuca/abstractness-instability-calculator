package com.example.softwaremetrics.domain.arch;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Loads an {@link ArchSpec} from either a built-in template (bundled under {@code /arch/<name>.yaml})
 * or a user-supplied YAML file path.
 */
public final class ArchSpecLoader {

    private ArchSpecLoader() {
    }

    /**
     * @param ref a built-in template name (e.g. {@code layered}) or a path to a {@code .yaml} file
     * @return the parsed spec
     * @throws IllegalArgumentException if the reference cannot be resolved or parsed
     */
    public static ArchSpec load(String ref) {
        if (ref == null || ref.isBlank()) {
            throw new IllegalArgumentException("No architecture spec provided");
        }
        return looksLikeFile(ref) ? loadFromFile(ref.trim()) : loadBuiltIn(ref.trim());
    }

    private static boolean looksLikeFile(String ref) {
        return ref.endsWith(".yaml") || ref.endsWith(".yml")
                || ref.contains("/") || ref.contains("\\");
    }

    private static ArchSpec loadBuiltIn(String name) {
        String resource = "/arch/" + name + ".yaml";
        try (InputStream in = ArchSpecLoader.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("Unknown architecture template '" + name
                        + "'. Built-in templates: layered, hexagonal, onion. "
                        + "Or pass a path to a .yaml file.");
            }
            return parse(in);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read architecture template '" + name + "'", e);
        }
    }

    private static ArchSpec loadFromFile(String path) {
        Path file = Path.of(path);
        if (!Files.exists(file)) {
            throw new IllegalArgumentException("Architecture spec file not found: " + path);
        }
        try (InputStream in = Files.newInputStream(file)) {
            return parse(in);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read architecture spec file: " + path, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static ArchSpec parse(InputStream in) {
        Object root = new Yaml().load(in);
        if (!(root instanceof Map)) {
            throw new IllegalArgumentException("Architecture spec must be a YAML mapping");
        }
        return ArchSpec.fromYaml((Map<String, Object>) root);
    }
}
