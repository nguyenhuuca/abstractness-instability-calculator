package com.example.softwaremetrics.domain.deadcode;

import com.example.softwaremetrics.domain.ClassInfo;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Class-level dead-code detection: a class is "potentially unused" when no other first-party class
 * references it and it is not an entry point (no {@code main}, no framework stereotype/entity
 * annotation). Report-only — treat results as candidates, not certainties.
 */
public class DeadCodeDetector {

    public DeadCodeResult detect(List<ClassInfo> classes) {
        Set<String> referenced = new HashSet<>();
        for (ClassInfo info : classes) {
            referenced.addAll(info.firstPartyClassRefs());
        }
        List<String> unused = classes.stream()
                .filter(info -> !info.entryPoint())
                .map(ClassInfo::fqcn)
                .filter(fqcn -> !referenced.contains(fqcn))
                .sorted()
                .toList();
        return new DeadCodeResult(unused);
    }
}
