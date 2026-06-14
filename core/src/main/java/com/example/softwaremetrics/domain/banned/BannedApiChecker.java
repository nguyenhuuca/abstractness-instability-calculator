package com.example.softwaremetrics.domain.banned;

import com.example.softwaremetrics.domain.ClassInfo;
import com.example.softwaremetrics.domain.GateResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Flags usages of forbidden APIs across the project model. Pure logic — unit-testable. Returns gate
 * violations ({@code type=bannedApi}) reusing {@link GateResult.Violation}.
 */
public class BannedApiChecker {

    public List<GateResult.Violation> check(List<ClassInfo> classes, List<BannedApiRule> rules) {
        List<GateResult.Violation> violations = new ArrayList<>();
        if (rules == null || rules.isEmpty()) {
            return violations;
        }
        for (ClassInfo info : classes) {
            for (BannedApiRule rule : rules) {
                if (rule.isAllowedIn(info.fqcn())) {
                    continue;
                }
                if (uses(info, rule)) {
                    String msg = (rule.message() != null && !rule.message().isBlank())
                            ? rule.message() : "Banned API used";
                    violations.add(new GateResult.Violation("bannedApi", info.fqcn(), 0.0, 0.0,
                            msg + " — '" + rule.target() + "' referenced in " + info.fqcn()));
                }
            }
        }
        return violations;
    }

    private boolean uses(ClassInfo info, BannedApiRule rule) {
        return switch (rule.kind()) {
            case CLASS -> info.typeRefs().contains(rule.target());
            case METHOD -> info.methodRefs().contains(rule.target());
            case PACKAGE -> info.typeRefs().stream()
                    .anyMatch(t -> t.equals(rule.target()) || t.startsWith(rule.target() + "."));
        };
    }
}
