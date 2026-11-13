package com.pingsu.tsmodify.typescript;

import com.pingsu.transpiler.DecoratorMode;
import com.pingsu.transpiler.JavaScriptTarget;
import com.pingsu.transpiler.ModuleKind;
import com.pingsu.transpiler.SourceMapMode;

public final class TypeScriptTranspileConfig {
    public static final TypeScriptTranspileConfig KUBEJS = builder()
            .moduleKind(ModuleKind.PRESERVE)
            .sourceMap(SourceMapMode.NONE)
            .keepComments(true)
            .stripStaticModuleSyntax(true)
            .rewriteTypeScriptModuleSpecifiers(false)
            .transformTarget(JavaScriptTarget.ES5)
            .lowerRuntimeSyntax(true)
            .guardAutomaticSemicolonHazards(true)
            .isolateTopLevelNames(true)
            .legacyFallback(false)
            .copyNonTypeScriptFiles(false)
            .failFast(true)
            .build();

    public static final TypeScriptTranspileConfig PROJECT = builder()
            .moduleKind(ModuleKind.PRESERVE)
            .sourceMap(SourceMapMode.NONE)
            .keepComments(true)
            .stripStaticModuleSyntax(false)
            .rewriteTypeScriptModuleSpecifiers(true)
            .isolateTopLevelNames(false)
            .legacyFallback(false)
            .copyNonTypeScriptFiles(true)
            .failFast(true)
            .build();

    private final ModuleKind moduleKind;
    private final SourceMapMode sourceMap;
    private final DecoratorMode decorators;
    private final JavaScriptTarget transformTarget;
    private final boolean keepComments;
    private final boolean stripStaticModuleSyntax;
    private final boolean rewriteTypeScriptModuleSpecifiers;
    private final boolean lowerRuntimeSyntax;
    private final boolean guardAutomaticSemicolonHazards;
    private final boolean isolateTopLevelNames;
    private final boolean legacyFallback;
    private final boolean copyNonTypeScriptFiles;
    private final boolean failFast;

    private TypeScriptTranspileConfig(Builder builder) {
        moduleKind = builder.moduleKind;
        sourceMap = builder.sourceMap;
        decorators = builder.decorators;
        transformTarget = builder.transformTarget;
        keepComments = builder.keepComments;
        stripStaticModuleSyntax = builder.stripStaticModuleSyntax;
        rewriteTypeScriptModuleSpecifiers = builder.rewriteTypeScriptModuleSpecifiers;
        lowerRuntimeSyntax = builder.lowerRuntimeSyntax;
        guardAutomaticSemicolonHazards = builder.guardAutomaticSemicolonHazards;
        isolateTopLevelNames = builder.isolateTopLevelNames;
        legacyFallback = builder.legacyFallback;
        copyNonTypeScriptFiles = builder.copyNonTypeScriptFiles;
        failFast = builder.failFast;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    public ModuleKind moduleKind() {
        return moduleKind;
    }

    public SourceMapMode sourceMap() {
        return sourceMap;
    }

    public DecoratorMode decorators() {
        return decorators;
    }

    public JavaScriptTarget transformTarget() {
        return transformTarget;
    }

    public boolean keepComments() {
        return keepComments;
    }

    public boolean stripStaticModuleSyntax() {
        return stripStaticModuleSyntax;
    }

    public boolean rewriteTypeScriptModuleSpecifiers() {
        return rewriteTypeScriptModuleSpecifiers;
    }

    public boolean lowerRuntimeSyntax() {
        return lowerRuntimeSyntax;
    }

    public boolean guardAutomaticSemicolonHazards() {
        return guardAutomaticSemicolonHazards;
    }

    public boolean isolateTopLevelNames() {
        return isolateTopLevelNames;
    }

    public boolean legacyFallback() {
        return legacyFallback;
    }

    public boolean copyNonTypeScriptFiles() {
        return copyNonTypeScriptFiles;
    }

    public boolean failFast() {
        return failFast;
    }

    public static final class Builder {
        private ModuleKind moduleKind = ModuleKind.PRESERVE;
        private SourceMapMode sourceMap = SourceMapMode.NONE;
        private DecoratorMode decorators = DecoratorMode.LEGACY;
        private JavaScriptTarget transformTarget;
        private boolean keepComments = true;
        private boolean stripStaticModuleSyntax;
        private boolean rewriteTypeScriptModuleSpecifiers = true;
        private boolean lowerRuntimeSyntax;
        private boolean guardAutomaticSemicolonHazards;
        private boolean isolateTopLevelNames;
        private boolean legacyFallback;
        private boolean copyNonTypeScriptFiles = true;
        private boolean failFast = true;

        private Builder() {
        }

        private Builder(TypeScriptTranspileConfig config) {
            moduleKind = config.moduleKind;
            sourceMap = config.sourceMap;
            decorators = config.decorators;
            transformTarget = config.transformTarget;
            keepComments = config.keepComments;
            stripStaticModuleSyntax = config.stripStaticModuleSyntax;
            rewriteTypeScriptModuleSpecifiers = config.rewriteTypeScriptModuleSpecifiers;
            lowerRuntimeSyntax = config.lowerRuntimeSyntax;
            guardAutomaticSemicolonHazards = config.guardAutomaticSemicolonHazards;
            isolateTopLevelNames = config.isolateTopLevelNames;
            legacyFallback = config.legacyFallback;
            copyNonTypeScriptFiles = config.copyNonTypeScriptFiles;
            failFast = config.failFast;
        }

        public Builder moduleKind(ModuleKind moduleKind) {
            this.moduleKind = requireNonNull(moduleKind, "moduleKind");
            return this;
        }

        public Builder sourceMap(SourceMapMode sourceMap) {
            this.sourceMap = requireNonNull(sourceMap, "sourceMap");
            return this;
        }

        public Builder decorators(DecoratorMode decorators) {
            this.decorators = requireNonNull(decorators, "decorators");
            return this;
        }

        public Builder transformTarget(JavaScriptTarget transformTarget) {
            this.transformTarget = transformTarget;
            return this;
        }

        public Builder keepComments(boolean keepComments) {
            this.keepComments = keepComments;
            return this;
        }

        public Builder stripStaticModuleSyntax(boolean stripStaticModuleSyntax) {
            this.stripStaticModuleSyntax = stripStaticModuleSyntax;
            return this;
        }

        public Builder rewriteTypeScriptModuleSpecifiers(boolean rewriteTypeScriptModuleSpecifiers) {
            this.rewriteTypeScriptModuleSpecifiers = rewriteTypeScriptModuleSpecifiers;
            return this;
        }

        public Builder lowerRuntimeSyntax(boolean lowerRuntimeSyntax) {
            this.lowerRuntimeSyntax = lowerRuntimeSyntax;
            return this;
        }

        public Builder guardAutomaticSemicolonHazards(boolean guardAutomaticSemicolonHazards) {
            this.guardAutomaticSemicolonHazards = guardAutomaticSemicolonHazards;
            return this;
        }

        public Builder isolateTopLevelNames(boolean isolateTopLevelNames) {
            this.isolateTopLevelNames = isolateTopLevelNames;
            return this;
        }

        public Builder legacyFallback(boolean legacyFallback) {
            this.legacyFallback = legacyFallback;
            return this;
        }

        public Builder copyNonTypeScriptFiles(boolean copyNonTypeScriptFiles) {
            this.copyNonTypeScriptFiles = copyNonTypeScriptFiles;
            return this;
        }

        public Builder failFast(boolean failFast) {
            this.failFast = failFast;
            return this;
        }

        public TypeScriptTranspileConfig build() {
            return new TypeScriptTranspileConfig(this);
        }

        private static <T> T requireNonNull(T value, String name) {
            if (value == null) {
                throw new IllegalArgumentException(name + " cannot be null");
            }
            return value;
        }
    }
}
