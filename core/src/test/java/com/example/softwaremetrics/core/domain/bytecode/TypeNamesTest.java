package com.example.softwaremetrics.core.domain.bytecode;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TypeNamesTest {

    // ---- normalizeArrayType ----

    @Test
    void normalizeArrayType_objectArrayDescriptor_stripsBracketsAndLSemicolon() {
        // [Ljava/lang/String; → java.lang.String
        assertThat(TypeNames.normalizeArrayType("[Ljava/lang/String;")).isEqualTo("java.lang.String");
    }

    @Test
    void normalizeArrayType_multiDimensionalObjectArray_stripsAllBracketsAndLSemicolon() {
        // [[Ljava/util/List; → java.util.List
        assertThat(TypeNames.normalizeArrayType("[[Ljava/util/List;")).isEqualTo("java.util.List");
    }

    @Test
    void normalizeArrayType_primitiveArrayDescriptor_stripsBracketLeavingPrimitive() {
        // [B → B  (byte primitive — no L/; wrapping, returned as-is after stripping brackets)
        assertThat(TypeNames.normalizeArrayType("[B")).isEqualTo("B");
    }

    @Test
    void normalizeArrayType_multiDimensionalPrimitiveArray_stripsAllBrackets() {
        // [[I → I  (int[][])
        assertThat(TypeNames.normalizeArrayType("[[I")).isEqualTo("I");
    }

    @Test
    void normalizeArrayType_objectDescriptorNoLeadingBracket_stripsLAndSemicolon() {
        // Ljava/lang/Object; → java.lang.Object  (descriptor form, no leading '[')
        assertThat(TypeNames.normalizeArrayType("Ljava/lang/Object;")).isEqualTo("java.lang.Object");
    }

    @Test
    void normalizeArrayType_singleObjectArrayDescriptor_returnsDottedName() {
        // [Ljava/util/List; → java.util.List
        assertThat(TypeNames.normalizeArrayType("[Ljava/util/List;")).isEqualTo("java.util.List");
    }

    @Test
    void normalizeArrayType_slashSeparatedNameWithoutDescriptorWrap_convertsSlashesToDots() {
        // java/util/Map → java.util.Map  (internal name form without L/; wrapping)
        assertThat(TypeNames.normalizeArrayType("java/util/Map")).isEqualTo("java.util.Map");
    }

    // ---- normalizeArrayClassName ----

    @Test
    void normalizeArrayClassName_null_returnsEmptyString() {
        assertThat(TypeNames.normalizeArrayClassName(null)).isEqualTo("");
    }

    @Test
    void normalizeArrayClassName_descriptorFormArray_returnsNormalizedName() {
        // [Ljava/lang/String; → java.lang.String  (delegates to normalizeArrayType)
        assertThat(TypeNames.normalizeArrayClassName("[Ljava/lang/String;")).isEqualTo("java.lang.String");
    }

    @Test
    void normalizeArrayClassName_javaFormMultiArray_stripsArraySuffixes() {
        // java.lang.String[][] → java.lang.String
        assertThat(TypeNames.normalizeArrayClassName("java.lang.String[][]")).isEqualTo("java.lang.String");
    }

    @Test
    void normalizeArrayClassName_javaFormSingleArray_stripsSuffix() {
        // com.example.Foo[] → com.example.Foo
        assertThat(TypeNames.normalizeArrayClassName("com.example.Foo[]")).isEqualTo("com.example.Foo");
    }

    @Test
    void normalizeArrayClassName_noArraySuffix_returnsUnchanged() {
        // java.lang.String → java.lang.String
        assertThat(TypeNames.normalizeArrayClassName("java.lang.String")).isEqualTo("java.lang.String");
    }

    // ---- stripArraySuffix ----

    @Test
    void stripArraySuffix_multipleArrayBrackets_stripsAll() {
        assertThat(TypeNames.stripArraySuffix("Foo[][]")).isEqualTo("Foo");
    }

    @Test
    void stripArraySuffix_noArrayBrackets_returnsUnchanged() {
        assertThat(TypeNames.stripArraySuffix("Foo")).isEqualTo("Foo");
    }

    // ---- getPackageName ----

    @Test
    void getPackageName_fqcn_returnsPackage() {
        assertThat(TypeNames.getPackageName("com.example.Foo")).isEqualTo("com.example");
    }

    @Test
    void getPackageName_defaultPackage_returnsEmpty() {
        assertThat(TypeNames.getPackageName("Foo")).isEqualTo("");
    }
}
