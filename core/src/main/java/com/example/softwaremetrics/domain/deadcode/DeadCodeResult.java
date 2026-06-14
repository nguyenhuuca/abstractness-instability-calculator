package com.example.softwaremetrics.domain.deadcode;

import java.util.List;

/**
 * Report of potentially-dead classes: first-party classes that no other first-party class references
 * and that are not entry points. Heuristic / report-only — DI and reflection cause false positives.
 */
public record DeadCodeResult(List<String> unusedClasses) {
}
