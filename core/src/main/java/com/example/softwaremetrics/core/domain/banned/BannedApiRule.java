package com.example.softwaremetrics.core.domain.banned;

import java.util.List;
import java.util.regex.Pattern;

/**
 * A forbidden-API rule. Matches references to a {@code class}, a specific {@code method}
 * ({@code owner.name}), or any class under a {@code package} prefix. Classes whose FQCN matches any
 * {@code allowedIn} pattern are exempt (e.g. allow {@code java.sql.*} only in the repository layer).
 */
public record BannedApiRule(Kind kind, String target, String message, List<Pattern> allowedIn) {

    public enum Kind {
        CLASS("class"), METHOD("method"), PACKAGE("package");

        private final String yamlKey;

        Kind(String yamlKey) {
            this.yamlKey = yamlKey;
        }

        /** The {@code aic-check.yaml} key that selects this rule kind (e.g. {@code "method"}). */
        public String yamlKey() {
            return yamlKey;
        }
    }

    public boolean isAllowedIn(String fqcn) {
        for (Pattern p : allowedIn) {
            if (p.matcher(fqcn).matches()) {
                return true;
            }
        }
        return false;
    }
}
