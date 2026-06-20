package com.example.softwaremetrics.core.domain.arch;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class ArchCheckerTest {

    private final ArchChecker checker = new ArchChecker();

    private ArchSpec.Component comp(String name, String... regex) {
        return new ArchSpec.Component(name, java.util.Arrays.stream(regex).map(Pattern::compile).toList());
    }

    private List<ArchSpec.Component> layers() {
        return List.of(
                comp("Web", ".*\\.web\\..*"),
                comp("Service", ".*\\.service\\..*"),
                comp("Repository", ".*\\.repo\\..*"),
                comp("Domain", ".*\\.domain\\..*"));
    }

    private Map<String, Set<String>> graph(Object... pairs) {
        Map<String, Set<String>> g = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            String from = (String) pairs[i];
            @SuppressWarnings("unchecked")
            List<String> tos = (List<String>) pairs[i + 1];
            g.put(from, new LinkedHashSet<>(tos));
        }
        return g;
    }

    @Test
    void accessAllowListFlagsForbiddenDependency() {
        ArchSpec spec = new ArchSpec("Layered", layers(),
                Map.of("Web", Set.of("Service", "Domain"),
                        "Service", Set.of("Repository", "Domain"),
                        "Repository", Set.of("Domain"),
                        "Domain", Set.of()),
                Set.of(), Map.of(), true, false);

        // Repository -> Web is not allowed
        ArchResult result = checker.check(spec, graph("a.repo.UserRepository", List.of("a.web.UserController")));

        assertThat(result.compliant()).isFalse();
        assertThat(result.violations()).hasSize(1);
        ArchResult.Violation v = result.violations().get(0);
        assertThat(v.type()).isEqualTo("forbiddenDependency");
        assertThat(v.from()).isEqualTo("Repository");
        assertThat(v.to()).isEqualTo("Web");
    }

    @Test
    void compliantGraphPasses() {
        ArchSpec spec = new ArchSpec("Layered", layers(),
                Map.of("Web", Set.of("Service", "Domain"),
                        "Service", Set.of("Repository", "Domain"),
                        "Repository", Set.of("Domain"),
                        "Domain", Set.of()),
                Set.of(), Map.of(), true, true);

        ArchResult result = checker.check(spec, graph(
                "a.web.UserController", List.of("a.service.UserService"),
                "a.service.UserService", List.of("a.repo.UserRepository"),
                "a.repo.UserRepository", List.of("a.domain.User"),
                "a.domain.User", List.of()));

        assertThat(result.compliant()).isTrue();
        assertThat(result.violations()).isEmpty();
    }

    @Test
    void namingConventionEnforcedPerComponent() {
        ArchSpec spec = new ArchSpec("Naming", layers(),
                Map.of(), Set.of(),
                Map.of("Repository", Pattern.compile(".*Repository")),
                true, false);

        ArchResult result = checker.check(spec, graph(
                "a.repo.UserRepository", List.of(),  // ok
                "a.repo.UserDao", List.of()));       // bad name

        assertThat(result.violations())
                .filteredOn(v -> v.type().equals("naming"))
                .singleElement()
                .satisfies(v -> assertThat(v.message()).contains("a.repo.UserDao"));
    }

    @Test
    void explicitForbiddenEdgeFlagged() {
        ArchSpec spec = new ArchSpec("Forbidden", layers(),
                Map.of(),                                  // no allow-list
                Set.of(new ArchSpec.Edge("Domain", "Web")),
                Map.of(), true, false);

        ArchResult result = checker.check(spec, graph(
                "a.domain.User", List.of("a.web.UserController"),  // forbidden
                "a.web.UserController", List.of("a.service.X")));  // not forbidden

        assertThat(result.violations()).singleElement().satisfies(v -> {
            assertThat(v.type()).isEqualTo("forbiddenDependency");
            assertThat(v.from()).isEqualTo("Domain");
            assertThat(v.to()).isEqualTo("Web");
        });
    }

    @Test
    void unmatchedClassIgnoredByDefault() {
        // ignoreUnmatched = true → a class matching no component produces no violation.
        ArchSpec spec = new ArchSpec("Layered", layers(),
                Map.of(), Set.of(), Map.of(), true, false);

        ArchResult result = checker.check(spec, graph("a.other.Mystery", List.of()));

        assertThat(result.compliant()).isTrue();
        assertThat(result.violations()).isEmpty();
    }

    @Test
    void unmatchedClassFlaggedWhenIgnoreUnmatchedFalse() {
        // ignoreUnmatched = false → a class matching no component is reported.
        ArchSpec spec = new ArchSpec("Layered", layers(),
                Map.of(), Set.of(), Map.of(), false, false);

        ArchResult result = checker.check(spec, graph(
                "a.other.Mystery", List.of(),       // unmatched → violation
                "a.web.UserController", List.of())); // matched → fine

        assertThat(result.compliant()).isFalse();
        assertThat(result.violations())
                .filteredOn(v -> v.type().equals("unmatched"))
                .singleElement()
                .satisfies(v -> assertThat(v.from()).isEqualTo("a.other.Mystery"));
    }

    @Test
    void componentCycleDetectedWhenForbidden() {
        ArchSpec spec = new ArchSpec("Cycle",
                List.of(comp("A", ".*\\.a\\..*"), comp("B", ".*\\.b\\..*")),
                Map.of(), Set.of(), Map.of(), true, true);

        ArchResult result = checker.check(spec, graph(
                "x.a.Foo", List.of("x.b.Bar"),
                "x.b.Bar", List.of("x.a.Baz")));

        assertThat(result.compliant()).isFalse();
        assertThat(result.violations()).anySatisfy(v -> assertThat(v.type()).isEqualTo("cycle"));
    }
}
