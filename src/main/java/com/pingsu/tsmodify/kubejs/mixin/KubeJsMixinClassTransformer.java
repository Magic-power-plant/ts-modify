package com.pingsu.tsmodify.kubejs.mixin;

import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import cpw.mods.modlauncher.api.TransformerVoteResult;
import org.objectweb.asm.tree.ClassNode;

import java.util.Objects;
import java.util.Set;

/**
 * One class-level ModLauncher transformer for a script-selected target class.
 */
final class KubeJsMixinClassTransformer implements ITransformer<ClassNode> {
    private final String targetClass;
    private final Set<Target> targets;

    KubeJsMixinClassTransformer(String targetClass) {
        KubeJsMixinTargetPolicy.validateTargetClass(targetClass);
        this.targetClass = Objects.requireNonNull(targetClass, "KubeJS mixin target class cannot be null");
        this.targets = Set.of(Target.targetClass(targetClass));
    }

    @Override
    public ClassNode transform(ClassNode input, ITransformerVotingContext context) {
        if (input == null || input.name == null
                || !targetClass.equals(input.name.replace('/', '.'))) {
            throw new KubeJsMixinTransformationException(
                    "KubeJS mixin transformer received an unexpected class node for " + targetClass);
        }
        KubeJsMixinRuleRegistry.transform(targetClass, input);
        return input;
    }

    @Override
    public TransformerVoteResult castVote(ITransformerVotingContext context) {
        return KubeJsMixinRuleRegistry.hasActiveRules(targetClass)
                ? TransformerVoteResult.YES
                : TransformerVoteResult.NO;
    }

    @Override
    public Set<Target> targets() {
        return targets;
    }

    @Override
    public String[] labels() {
        return new String[]{"kubejs-mixin", targetClass};
    }
}
