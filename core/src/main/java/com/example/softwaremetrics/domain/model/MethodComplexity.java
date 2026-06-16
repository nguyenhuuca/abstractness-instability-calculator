package com.example.softwaremetrics.domain.model;

/**
 * The cyclomatic complexity of one concrete method, captured during the single bytecode pass.
 *
 * @param name       {@code SimpleClassName#methodName}
 * @param complexity cyclomatic complexity (1 + conditional branches + switch cases)
 */
public record MethodComplexity(String name, int complexity) {
}
