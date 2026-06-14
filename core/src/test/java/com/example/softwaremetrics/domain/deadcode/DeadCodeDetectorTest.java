package com.example.softwaremetrics.domain.deadcode;

import com.example.softwaremetrics.domain.ClassInfo;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DeadCodeDetectorTest {

    private final DeadCodeDetector detector = new DeadCodeDetector();

    private ClassInfo clazz(String fqcn, boolean entryPoint, Set<String> refs) {
        return new ClassInfo(fqcn, fqcn.substring(0, fqcn.lastIndexOf('.')), entryPoint, refs, refs, Set.of());
    }

    @Test
    void flagsUnreferencedNonEntryClass() {
        ClassInfo app = clazz("com.app.App", true, Set.of("com.app.Used"));   // entry point
        ClassInfo used = clazz("com.app.Used", false, Set.of());              // referenced by App
        ClassInfo orphan = clazz("com.app.Orphan", false, Set.of());          // nobody references it

        DeadCodeResult result = detector.detect(List.of(app, used, orphan));

        assertThat(result.unusedClasses()).containsExactly("com.app.Orphan");
    }

    @Test
    void entryPointsAreNeverDead() {
        ClassInfo controller = clazz("com.app.web.FooController", true, Set.of()); // bean, unreferenced
        DeadCodeResult result = detector.detect(List.of(controller));
        assertThat(result.unusedClasses()).isEmpty();
    }
}
