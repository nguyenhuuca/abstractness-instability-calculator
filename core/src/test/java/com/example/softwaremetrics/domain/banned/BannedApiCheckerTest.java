package com.example.softwaremetrics.domain.banned;

import com.example.softwaremetrics.domain.ClassInfo;
import com.example.softwaremetrics.domain.GateResult;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class BannedApiCheckerTest {

    private final BannedApiChecker checker = new BannedApiChecker();

    private ClassInfo clazz(String fqcn, Set<String> typeRefs, Set<String> methodRefs) {
        return new ClassInfo(fqcn, fqcn.substring(0, fqcn.lastIndexOf('.')), false, Set.of(), typeRefs, methodRefs);
    }

    @Test
    void flagsMethodClassAndPackageRules() {
        ClassInfo c = clazz("com.app.Foo",
                Set.of("java.util.Date", "java.sql.Connection"),
                Set.of("java.lang.System.exit"));

        List<BannedApiRule> rules = List.of(
                new BannedApiRule(BannedApiRule.Kind.METHOD, "java.lang.System.exit", "no exit", List.of()),
                new BannedApiRule(BannedApiRule.Kind.CLASS, "java.util.Date", "use java.time", List.of()),
                new BannedApiRule(BannedApiRule.Kind.PACKAGE, "java.sql", "repo only", List.of()));

        List<GateResult.Violation> v = checker.check(List.of(c), rules);

        assertThat(v).hasSize(3);
        assertThat(v).allSatisfy(x -> assertThat(x.type()).isEqualTo("bannedApi"));
    }

    @Test
    void allowedInExemptsMatchingClass() {
        ClassInfo repo = clazz("com.app.repository.UserRepository",
                Set.of("java.sql.Connection"), Set.of());
        ClassInfo service = clazz("com.app.service.UserService",
                Set.of("java.sql.Connection"), Set.of());

        BannedApiRule rule = new BannedApiRule(BannedApiRule.Kind.PACKAGE, "java.sql",
                "DB only in repository", List.of(Pattern.compile(".*\\.repository\\..*")));

        List<GateResult.Violation> v = checker.check(List.of(repo, service), List.of(rule));

        assertThat(v).hasSize(1);
        assertThat(v.get(0).packageName()).isEqualTo("com.app.service.UserService");
    }

    @Test
    void noViolationsWhenNothingMatches() {
        ClassInfo c = clazz("com.app.Foo", Set.of("java.util.List"), Set.of("java.util.List.add"));
        List<BannedApiRule> rules = List.of(
                new BannedApiRule(BannedApiRule.Kind.CLASS, "java.util.Date", null, List.of()));

        assertThat(checker.check(List.of(c), rules)).isEmpty();
    }
}
