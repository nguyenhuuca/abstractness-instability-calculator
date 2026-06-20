package com.example.softwaremetrics.core.domain.resolve;

import com.example.softwaremetrics.core.domain.bytecode.ProjectModelBuilder;
import com.example.softwaremetrics.core.domain.bytecode.TypeNames;

import java.nio.file.Path;
import java.util.Collection;

/**
 * Infers the root package as the longest package prefix shared by every compiled class — a framework-
 * agnostic fallback so a plain Java project (no {@code @SpringBootApplication}, no explicit setting)
 * can still be analyzed. For {@code com.foo.a.A} and {@code com.foo.b.B} the root is {@code com.foo}.
 */
public class CommonPrefixRootPackageResolver implements RootPackageResolver {

    private final ProjectModelBuilder modelBuilder;

    public CommonPrefixRootPackageResolver(ProjectModelBuilder modelBuilder) {
        this.modelBuilder = modelBuilder;
    }

    @Override
    public String resolve(Path projectPath) {
        try {
            return commonPackagePrefix(modelBuilder.build(projectPath).classNames());
        } catch (IllegalStateException e) {
            // I/O failure reading the project: this strategy can't determine a root — defer to the
            // chain by returning null (matching the other resolvers' "can't decide" contract).
            return null;
        }
    }

    /** The longest package prefix common to all class names, or {@code null} if there is none. */
    public static String commonPackagePrefix(Collection<String> classNames) {
        String[] common = null;
        for (String fqcn : classNames) {
            String pkg = TypeNames.getPackageName(fqcn);
            if (pkg.isEmpty()) {
                return null; // a class in the default package — no meaningful common root
            }
            String[] segments = pkg.split("\\.");
            if (common == null) {
                common = segments;
                continue;
            }
            int n = Math.min(common.length, segments.length);
            int i = 0;
            while (i < n && common[i].equals(segments[i])) {
                i++;
            }
            common = java.util.Arrays.copyOfRange(common, 0, i);
            if (common.length == 0) {
                return null;
            }
        }
        return (common == null || common.length == 0) ? null : String.join(".", common);
    }
}
