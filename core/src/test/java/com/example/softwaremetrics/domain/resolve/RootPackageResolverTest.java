package com.example.softwaremetrics.domain.resolve;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Unit tests for the framework-agnostic root-package resolvers (no filesystem needed). */
class RootPackageResolverTest {

    private static final Path ANY = Path.of(".");

    @Test
    void explicitReturnsConfiguredPrefixOrNull() {
        assertEquals("com.foo", new ExplicitRootPackageResolver("com.foo").resolve(ANY));
        assertEquals("com.foo", new ExplicitRootPackageResolver("  com.foo  ").resolve(ANY));
        assertNull(new ExplicitRootPackageResolver("").resolve(ANY));
        assertNull(new ExplicitRootPackageResolver(null).resolve(ANY));
    }

    @Test
    void commonPrefixIsTheLongestSharedPackage() {
        assertEquals("com.foo",
                CommonPrefixRootPackageResolver.commonPackagePrefix(List.of("com.foo.a.A", "com.foo.b.B")));
        assertEquals("com.foo",
                CommonPrefixRootPackageResolver.commonPackagePrefix(List.of("com.foo.A", "com.foo.B")));
    }

    @Test
    void commonPrefixIsNullWhenNothingShared() {
        assertNull(CommonPrefixRootPackageResolver.commonPackagePrefix(List.of("com.foo.A", "org.bar.B")));
        assertNull(CommonPrefixRootPackageResolver.commonPackagePrefix(List.of()));
        assertNull(CommonPrefixRootPackageResolver.commonPackagePrefix(List.of("NoPackage")));
    }

    @Test
    void chainReturnsFirstNonBlankResult() {
        RootPackageResolver chain = new ChainedRootPackageResolver(List.of(
                new ExplicitRootPackageResolver(null),   // skipped
                p -> "  ",                                // blank, skipped
                p -> "com.winner",
                p -> "com.never"));
        assertEquals("com.winner", chain.resolve(ANY));
    }

    @Test
    void chainReturnsNullWhenAllDelegatesYieldNothing() {
        RootPackageResolver chain = new ChainedRootPackageResolver(List.of(
                new ExplicitRootPackageResolver(null),
                p -> null));
        assertNull(chain.resolve(ANY));
    }
}
