package com.example.softwaremetrics.domain.bytecode;

/**
 * Small helpers for normalizing type names extracted from bytecode — stripping array suffixes
 * (both Java {@code Foo[]} and ASM descriptor {@code [Lcom/foo/Foo;} forms) and deriving package
 * names. Extracted verbatim from the former {@code JavaClassAnalyzer} so the extraction is one
 * single-responsibility unit.
 */
public final class TypeNames {

    private TypeNames() {
    }

    /** Strips trailing {@code []} array markers from a Java type name (e.g. {@code Foo[][]} → {@code Foo}). */
    public static String stripArraySuffix(String type) {
        while (type.endsWith("[]")) {
            type = type.substring(0, type.length() - 2);
        }
        return type;
    }

    /** Normalizes an ASM descriptor array type ({@code [B}, {@code [Ljava/lang/String;}) to a dotted name. */
    public static String normalizeArrayType(String rawType) {
        while (rawType.startsWith("[")) {
            rawType = rawType.substring(1);
        }
        if (rawType.startsWith("L") && rawType.endsWith(";")) {
            rawType = rawType.substring(1, rawType.length() - 1);
        }
        return rawType.replace('/', '.');
    }

    /** Handles either descriptor form ({@code [Ljava/lang/String;}) or Java form ({@code String[]}). */
    public static String normalizeArrayClassName(String rawType) {
        if (rawType == null) {
            return "";
        }
        if (rawType.startsWith("[")) {
            return normalizeArrayType(rawType);
        }
        while (rawType.endsWith("[]")) {
            rawType = rawType.substring(0, rawType.length() - 2);
        }
        return rawType;
    }

    /** The package of a fully-qualified class name, or {@code ""} for the default package. */
    public static String getPackageName(String className) {
        int lastDotIndex = className.lastIndexOf('.');
        return (lastDotIndex == -1) ? "" : className.substring(0, lastDotIndex);
    }
}
