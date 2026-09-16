package com.pingsu.tsmodify.kubejs.mixin;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class KubeJsMixinBytecodeInjector implements Opcodes {
    private static final String RUNTIME_OWNER = "com/pingsu/tsmodify/kubejs/mixin/KubeJsMixinRuntime";
    private static final String RUNTIME_RESULT_OWNER = "com/pingsu/tsmodify/kubejs/mixin/KubeJsMixinRuntimeResult";
    private static final String RUNTIME_RESULT_DESCRIPTOR = "L" + RUNTIME_RESULT_OWNER + ";";
    private static final String BEFORE_DESCRIPTOR = "(Ljava/lang/String;Ljava/lang/Object;[Ljava/lang/Object;)V";
    private static final String CANCELLABLE_BEFORE_DESCRIPTOR = "(Ljava/lang/String;Ljava/lang/Object;[Ljava/lang/Object;)"
            + RUNTIME_RESULT_DESCRIPTOR;
    private static final String MODIFY_ARGUMENTS_DESCRIPTOR = "(Ljava/lang/String;Ljava/lang/Object;[Ljava/lang/Object;)[Ljava/lang/Object;";
    private static final String AFTER_DESCRIPTOR = "(Ljava/lang/String;Ljava/lang/Object;[Ljava/lang/Object;Ljava/lang/Object;)V";
    private static final String MODIFY_RETURN_VALUE_DESCRIPTOR = "(Ljava/lang/String;Ljava/lang/Object;[Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";
    private static final String REPLACE_DESCRIPTOR = "(Ljava/lang/String;Ljava/lang/Object;[Ljava/lang/Object;)"
            + RUNTIME_RESULT_DESCRIPTOR;
    private static final String REDIRECT_DESCRIPTOR = "(Ljava/lang/String;Ljava/lang/Object;[Ljava/lang/Object;Ljava/lang/Object;[Ljava/lang/Object;)"
            + RUNTIME_RESULT_DESCRIPTOR;
    private static final String MODIFY_ARG_DESCRIPTOR = "(Ljava/lang/String;Ljava/lang/Object;[Ljava/lang/Object;Ljava/lang/Object;[Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";
    private static final String MODIFY_VARIABLE_DESCRIPTOR = "(Ljava/lang/String;Ljava/lang/Object;[Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";

    private KubeJsMixinBytecodeInjector() {
    }

    /**
     * Injects the active rules into the given class node.
     *
     * @return the number of rules that were actually injected; already-present rule markers are
     *         skipped, which keeps a repeated retransformation of the same class idempotent
     */
    static int inject(ClassNode targetClass, List<? extends AbstractKubeJsMixinRule> rules) {
        Map<MethodKey, List<AbstractKubeJsMixinRule>> rulesByMethod = new LinkedHashMap<>();
        for (AbstractKubeJsMixinRule rule : rules) {
            if (rule.isActive()) {
                rulesByMethod.computeIfAbsent(
                        new MethodKey(rule.methodName(), rule.methodDescriptor()),
                        ignored -> new ArrayList<>()).add(rule);
            }
        }

        int injectedRules = 0;
        for (Map.Entry<MethodKey, List<AbstractKubeJsMixinRule>> entry : rulesByMethod.entrySet()) {
            MethodNode method = findMethod(targetClass, entry.getKey());
            if (method == null) {
                throw new KubeJsMixinTransformationException(
                        "KubeJS mixin could not find " + targetClass.name.replace('/', '.') + "#"
                                + entry.getKey().name + entry.getKey().descriptor);
            }
            validateMethod(targetClass, method);
            injectedRules += injectMethod(method, entry.getValue());
        }
        return injectedRules;
    }

    private static MethodNode findMethod(ClassNode targetClass, MethodKey key) {
        for (MethodNode method : targetClass.methods) {
            if (method.name.equals(key.name) && method.desc.equals(key.descriptor)) {
                return method;
            }
        }
        return null;
    }

    private static void validateMethod(ClassNode targetClass, MethodNode method) {
        if (method.name.equals("<init>") || method.name.equals("<clinit>")) {
            throw new KubeJsMixinTransformationException(
                    "KubeJS mixin hooks do not support initializers: " + targetClass.name.replace('/', '.') + "#" + method.name);
        }
        if ((method.access & (ACC_ABSTRACT | ACC_NATIVE)) != 0 || method.instructions == null || method.instructions.size() == 0) {
            throw new KubeJsMixinTransformationException(
                    "KubeJS mixin requires a concrete method body: " + targetClass.name.replace('/', '.') + "#" + method.name + method.desc);
        }
    }

    private static int injectMethod(MethodNode method, List<AbstractKubeJsMixinRule> rules) {
        List<AbstractKubeJsMixinRule> freshRules = new ArrayList<>();
        for (AbstractKubeJsMixinRule rule : rules) {
            if (!containsRuleMarker(method, rule.id())) {
                freshRules.add(rule);
            }
        }
        if (freshRules.isEmpty()) {
            return 0;
        }

        Map<AbstractInsnNode, List<AbstractKubeJsMixinRule>> instructionPlans = resolveInstructionPlans(method, freshRules);
        applyInstructionPlans(method, instructionPlans);

        List<AbstractKubeJsMixinRule> entryRules = new ArrayList<>();
        List<AbstractKubeJsMixinRule> returnRules = new ArrayList<>();
        for (AbstractKubeJsMixinRule rule : freshRules) {
            if (rule.hook().isMethodEntryHook()) {
                entryRules.add(rule);
            } else if (rule.hook().isMethodReturnHook()) {
                returnRules.add(rule);
            }
        }

        long replacementCount = entryRules.stream().filter(rule -> rule.hook() == KubeJsMixinHook.REPLACE).count();
        if (replacementCount > 1) {
            throw new KubeJsMixinTransformationException(
                    "KubeJS mixin allows only one replace rule for " + method.name + method.desc);
        }
        if (!entryRules.isEmpty()) {
            appendEntryHooks(method, entryRules);
        }
        if (!returnRules.isEmpty()) {
            appendReturnHooks(method, returnRules);
        }
        return freshRules.size();
    }

    private static boolean containsRuleMarker(MethodNode method, String ruleId) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof LdcInsnNode constant && ruleId.equals(constant.cst)) {
                return true;
            }
        }
        return false;
    }

    private static Map<AbstractInsnNode, List<AbstractKubeJsMixinRule>> resolveInstructionPlans(
            MethodNode method,
            List<AbstractKubeJsMixinRule> rules) {
        Map<AbstractInsnNode, List<AbstractKubeJsMixinRule>> plans = new LinkedHashMap<>();
        for (AbstractKubeJsMixinRule rule : rules) {
            if (!rule.hook().isInstructionHook()) {
                continue;
            }
            AbstractInsnNode instruction = findInstruction(method, rule.instructionTarget());
            plans.computeIfAbsent(instruction, ignored -> new ArrayList<>()).add(rule);
        }
        return plans;
    }

    private static AbstractInsnNode findInstruction(MethodNode method, KubeJsMixinInstructionTarget target) {
        List<AbstractInsnNode> matches = new ArrayList<>();
        if (target instanceof KubeJsMixinInstructionTarget.Invoke invoke) {
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (instruction instanceof MethodInsnNode methodInstruction
                        && methodInstruction.owner.equals(invoke.owner())
                        && methodInstruction.name.equals(invoke.methodName())
                        && methodInstruction.desc.equals(invoke.methodDescriptor())) {
                    matches.add(instruction);
                }
            }
            return selectInstruction(target.describe(), invoke.ordinal(), matches);
        }
        if (target instanceof KubeJsMixinInstructionTarget.Variable variable) {
            Type variableType = variable.type();
            int expectedOpcode = variableType.getOpcode(
                    variable.access() == KubeJsMixinInstructionTarget.VariableAccess.LOAD ? ILOAD : ISTORE);
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (instruction instanceof VarInsnNode variableInstruction
                        && variableInstruction.var == variable.localIndex()
                        && variableInstruction.getOpcode() == expectedOpcode) {
                    matches.add(instruction);
                }
            }
            return selectInstruction(target.describe(), variable.ordinal(), matches);
        }
        throw new KubeJsMixinTransformationException("KubeJS mixin has an unsupported instruction target");
    }

    private static AbstractInsnNode selectInstruction(
            String targetDescription,
            int ordinal,
            List<AbstractInsnNode> matches) {
        if (ordinal >= matches.size()) {
            throw new KubeJsMixinTransformationException(
                    "KubeJS mixin could not find " + targetDescription + "; found " + matches.size() + " matching instructions");
        }
        return matches.get(ordinal);
    }

    private static void applyInstructionPlans(
            MethodNode method,
            Map<AbstractInsnNode, List<AbstractKubeJsMixinRule>> plans) {
        for (Map.Entry<AbstractInsnNode, List<AbstractKubeJsMixinRule>> entry : plans.entrySet()) {
            AbstractInsnNode instruction = entry.getKey();
            List<AbstractKubeJsMixinRule> rules = entry.getValue();
            if (instruction instanceof MethodInsnNode methodInstruction) {
                applyInvocationPlan(method, methodInstruction, rules);
            } else if (instruction instanceof VarInsnNode variableInstruction) {
                applyVariablePlan(method, variableInstruction, rules);
            } else {
                throw new KubeJsMixinTransformationException("KubeJS mixin selected a non-injectable instruction");
            }
        }
    }

    private static void applyInvocationPlan(
            MethodNode method,
            MethodInsnNode instruction,
            List<AbstractKubeJsMixinRule> rules) {
        List<AbstractKubeJsMixinRule> redirectRules = rules.stream()
                .filter(rule -> rule.hook() == KubeJsMixinHook.REDIRECT)
                .toList();
        if (redirectRules.size() > 1) {
            throw new KubeJsMixinTransformationException(
                    "KubeJS mixin allows only one redirect rule for " + instruction.owner.replace('/', '.')
                            + "#" + instruction.name + instruction.desc);
        }
        if (!redirectRules.isEmpty() && rules.size() != 1) {
            throw new KubeJsMixinTransformationException(
                    "KubeJS redirect cannot share an invocation instruction with modifyArg rules: "
                            + instruction.owner.replace('/', '.') + "#" + instruction.name + instruction.desc);
        }
        if (!redirectRules.isEmpty()) {
            appendRedirect(method, instruction, redirectRules.get(0));
            return;
        }
        for (AbstractKubeJsMixinRule rule : rules) {
            if (rule.hook() != KubeJsMixinHook.MODIFY_ARG) {
                throw new KubeJsMixinTransformationException(
                        "KubeJS mixin invocation plan contains an invalid " + rule.hook().displayName() + " rule");
            }
        }
        appendModifyArgsAtInvocation(method, instruction, rules);
    }

    private static void applyVariablePlan(
            MethodNode method,
            VarInsnNode instruction,
            List<AbstractKubeJsMixinRule> rules) {
        KubeJsMixinInstructionTarget.Variable firstTarget = requireVariableTarget(rules.get(0));
        for (AbstractKubeJsMixinRule rule : rules) {
            KubeJsMixinInstructionTarget.Variable target = requireVariableTarget(rule);
            if (!target.descriptor().equals(firstTarget.descriptor()) || target.access() != firstTarget.access()) {
                throw new KubeJsMixinTransformationException(
                        "KubeJS modifyVariable rules at one instruction must use the same descriptor and access");
            }
        }
        if ((method.access & ACC_STATIC) == 0 && instruction.var == 0) {
            throw new KubeJsMixinTransformationException(
                    "KubeJS modifyVariable cannot change the target method receiver in " + method.name + method.desc);
        }
        appendModifyVariable(method, instruction, firstTarget, rules);
    }

    private static KubeJsMixinInstructionTarget.Variable requireVariableTarget(AbstractKubeJsMixinRule rule) {
        if (rule.hook() != KubeJsMixinHook.MODIFY_VARIABLE
                || !(rule.instructionTarget() instanceof KubeJsMixinInstructionTarget.Variable target)) {
            throw new KubeJsMixinTransformationException(
                    "KubeJS mixin local-variable plan contains an invalid " + rule.hook().displayName() + " rule");
        }
        return target;
    }

    private static void appendEntryHooks(MethodNode method, List<AbstractKubeJsMixinRule> rules) {
        InsnList instructions = new InsnList();
        for (AbstractKubeJsMixinRule rule : rules) {
            switch (rule.hook()) {
                case BEFORE -> appendBeforeHook(instructions, method, rule);
                case MODIFY_ARGS -> appendModifyArguments(instructions, method, rule);
                case REPLACE -> appendReplace(instructions, method, rule);
                default -> throw new KubeJsMixinTransformationException(
                        "KubeJS mixin method-entry plan contains an invalid " + rule.hook().displayName() + " rule");
            }
        }
        method.instructions.insert(instructions);
    }

    private static void appendBeforeHook(InsnList instructions, MethodNode method, AbstractKubeJsMixinRule rule) {
        instructions.add(new LdcInsnNode(rule.id()));
        appendReceiver(instructions, method);
        appendArguments(instructions, method);
        if (!rule.cancellable()) {
            instructions.add(new MethodInsnNode(INVOKESTATIC, RUNTIME_OWNER, "before", BEFORE_DESCRIPTOR, false));
            return;
        }

        int resultLocal = allocateLocal(method, Type.getObjectType(RUNTIME_RESULT_OWNER));
        LabelNode continueTarget = new LabelNode();
        instructions.add(new MethodInsnNode(
                INVOKESTATIC,
                RUNTIME_OWNER,
                "cancellableBefore",
                CANCELLABLE_BEFORE_DESCRIPTOR,
                false));
        instructions.add(new VarInsnNode(ASTORE, resultLocal));
        appendBranchOnResult(instructions, method, resultLocal, continueTarget);
        instructions.add(continueTarget);
    }

    private static void appendModifyArguments(InsnList instructions, MethodNode method, AbstractKubeJsMixinRule rule) {
        int argumentsLocal = allocateLocal(method, Type.getType("[Ljava/lang/Object;"));
        instructions.add(new LdcInsnNode(rule.id()));
        appendReceiver(instructions, method);
        appendArguments(instructions, method);
        instructions.add(new MethodInsnNode(
                INVOKESTATIC,
                RUNTIME_OWNER,
                "modifyArguments",
                MODIFY_ARGUMENTS_DESCRIPTOR,
                false));
        instructions.add(new VarInsnNode(ASTORE, argumentsLocal));
        appendStoreMethodArguments(instructions, method, argumentsLocal);
    }

    private static void appendReplace(InsnList instructions, MethodNode method, AbstractKubeJsMixinRule rule) {
        int resultLocal = allocateLocal(method, Type.getObjectType(RUNTIME_RESULT_OWNER));
        LabelNode continueTarget = new LabelNode();
        instructions.add(new LdcInsnNode(rule.id()));
        appendReceiver(instructions, method);
        appendArguments(instructions, method);
        instructions.add(new MethodInsnNode(INVOKESTATIC, RUNTIME_OWNER, "replace", REPLACE_DESCRIPTOR, false));
        instructions.add(new VarInsnNode(ASTORE, resultLocal));
        appendBranchOnResult(instructions, method, resultLocal, continueTarget);
        instructions.add(continueTarget);
    }

    private static void appendBranchOnResult(
            InsnList instructions,
            MethodNode method,
            int resultLocal,
            LabelNode continueTarget) {
        instructions.add(new VarInsnNode(ALOAD, resultLocal));
        instructions.add(new MethodInsnNode(
                INVOKEVIRTUAL,
                RUNTIME_RESULT_OWNER,
                "isApplied",
                "()Z",
                false));
        instructions.add(new JumpInsnNode(IFEQ, continueTarget));

        Type returnType = Type.getReturnType(method.desc);
        if (returnType.getSort() != Type.VOID) {
            instructions.add(new VarInsnNode(ALOAD, resultLocal));
            instructions.add(new MethodInsnNode(
                    INVOKEVIRTUAL,
                    RUNTIME_RESULT_OWNER,
                    "getValue",
                    "()Ljava/lang/Object;",
                    false));
            appendUnbox(instructions, returnType);
        }
        instructions.add(new InsnNode(returnType.getSort() == Type.VOID ? RETURN : returnType.getOpcode(IRETURN)));
    }

    private static void appendReturnHooks(MethodNode method, List<AbstractKubeJsMixinRule> returnRules) {
        Type returnType = Type.getReturnType(method.desc);
        int returnOpcode = returnType.getSort() == Type.VOID ? RETURN : returnType.getOpcode(IRETURN);
        List<AbstractInsnNode> returnInstructions = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction.getOpcode() == returnOpcode) {
                returnInstructions.add(instruction);
            }
        }

        if (returnInstructions.isEmpty()) {
            throw new KubeJsMixinTransformationException(
                    "KubeJS return hook could not find a normal return instruction in " + method.name + method.desc);
        }

        int returnLocal = returnType.getSort() == Type.VOID ? -1 : allocateLocal(method, returnType);
        for (AbstractInsnNode returnInstruction : returnInstructions) {
            InsnList instructions = new InsnList();
            if (returnLocal >= 0) {
                instructions.add(new VarInsnNode(returnType.getOpcode(ISTORE), returnLocal));
            }
            for (AbstractKubeJsMixinRule rule : returnRules) {
                instructions.add(new LdcInsnNode(rule.id()));
                appendReceiver(instructions, method);
                appendArguments(instructions, method);
                if (returnLocal >= 0) {
                    instructions.add(new VarInsnNode(returnType.getOpcode(ILOAD), returnLocal));
                    appendBox(instructions, returnType);
                } else {
                    instructions.add(new InsnNode(ACONST_NULL));
                }
                if (rule.hook() == KubeJsMixinHook.AFTER) {
                    instructions.add(new MethodInsnNode(INVOKESTATIC, RUNTIME_OWNER, "after", AFTER_DESCRIPTOR, false));
                } else if (rule.hook() == KubeJsMixinHook.MODIFY_RETURN_VALUE) {
                    instructions.add(new MethodInsnNode(
                            INVOKESTATIC,
                            RUNTIME_OWNER,
                            "modifyReturnValue",
                            MODIFY_RETURN_VALUE_DESCRIPTOR,
                            false));
                    appendUnbox(instructions, returnType);
                    instructions.add(new VarInsnNode(returnType.getOpcode(ISTORE), returnLocal));
                } else {
                    throw new KubeJsMixinTransformationException(
                            "KubeJS return plan contains an invalid " + rule.hook().displayName() + " rule");
                }
            }
            if (returnLocal >= 0) {
                instructions.add(new VarInsnNode(returnType.getOpcode(ILOAD), returnLocal));
            }
            method.instructions.insertBefore(returnInstruction, instructions);
        }
    }

    private static void appendRedirect(
            MethodNode method,
            MethodInsnNode instruction,
            AbstractKubeJsMixinRule rule) {
        InvocationOperands operands = captureInvocationOperands(method, instruction);
        Type returnType = Type.getReturnType(instruction.desc);
        int resultLocal = allocateLocal(method, Type.getObjectType(RUNTIME_RESULT_OWNER));
        LabelNode invokeOriginal = new LabelNode();
        LabelNode afterInvocation = new LabelNode();

        InsnList instructions = new InsnList();
        appendStoreInvocationOperands(instructions, instruction, operands);
        instructions.add(new LdcInsnNode(rule.id()));
        appendReceiver(instructions, method);
        appendArguments(instructions, method);
        appendInvocationReceiver(instructions, instruction, operands);
        appendInvocationArguments(instructions, operands);
        instructions.add(new MethodInsnNode(INVOKESTATIC, RUNTIME_OWNER, "redirect", REDIRECT_DESCRIPTOR, false));
        instructions.add(new VarInsnNode(ASTORE, resultLocal));
        instructions.add(new VarInsnNode(ALOAD, resultLocal));
        instructions.add(new MethodInsnNode(
                INVOKEVIRTUAL,
                RUNTIME_RESULT_OWNER,
                "isApplied",
                "()Z",
                false));
        instructions.add(new JumpInsnNode(IFEQ, invokeOriginal));
        if (returnType.getSort() != Type.VOID) {
            instructions.add(new VarInsnNode(ALOAD, resultLocal));
            instructions.add(new MethodInsnNode(
                    INVOKEVIRTUAL,
                    RUNTIME_RESULT_OWNER,
                    "getValue",
                    "()Ljava/lang/Object;",
                    false));
            appendUnbox(instructions, returnType);
        }
        instructions.add(new JumpInsnNode(GOTO, afterInvocation));
        instructions.add(invokeOriginal);
        appendLoadInvocationOperands(instructions, instruction, operands);
        method.instructions.insertBefore(instruction, instructions);

        InsnList continuation = new InsnList();
        continuation.add(afterInvocation);
        method.instructions.insert(instruction, continuation);
    }

    private static void appendModifyArgsAtInvocation(
            MethodNode method,
            MethodInsnNode instruction,
            List<AbstractKubeJsMixinRule> rules) {
        InvocationOperands operands = captureInvocationOperands(method, instruction);
        InsnList instructions = new InsnList();
        appendStoreInvocationOperands(instructions, instruction, operands);
        for (AbstractKubeJsMixinRule rule : rules) {
            KubeJsMixinInstructionTarget.Invoke target = (KubeJsMixinInstructionTarget.Invoke) rule.instructionTarget();
            int argumentIndex = target.argumentIndex();
            Type argumentType = operands.argumentTypes[argumentIndex];
            instructions.add(new LdcInsnNode(rule.id()));
            appendReceiver(instructions, method);
            appendArguments(instructions, method);
            appendInvocationReceiver(instructions, instruction, operands);
            appendInvocationArguments(instructions, operands);
            instructions.add(new VarInsnNode(argumentType.getOpcode(ILOAD), operands.argumentLocals[argumentIndex]));
            appendBox(instructions, argumentType);
            instructions.add(new MethodInsnNode(INVOKESTATIC, RUNTIME_OWNER, "modifyArg", MODIFY_ARG_DESCRIPTOR, false));
            appendUnbox(instructions, argumentType);
            instructions.add(new VarInsnNode(argumentType.getOpcode(ISTORE), operands.argumentLocals[argumentIndex]));
        }
        appendLoadInvocationOperands(instructions, instruction, operands);
        method.instructions.insertBefore(instruction, instructions);
    }

    private static void appendModifyVariable(
            MethodNode method,
            VarInsnNode instruction,
            KubeJsMixinInstructionTarget.Variable target,
            List<AbstractKubeJsMixinRule> rules) {
        Type valueType = target.type();
        int temporaryLocal = allocateLocal(method, valueType);
        InsnList instructions = new InsnList();
        instructions.add(new VarInsnNode(valueType.getOpcode(ISTORE), temporaryLocal));
        for (AbstractKubeJsMixinRule rule : rules) {
            instructions.add(new LdcInsnNode(rule.id()));
            appendReceiver(instructions, method);
            appendArguments(instructions, method);
            instructions.add(new VarInsnNode(valueType.getOpcode(ILOAD), temporaryLocal));
            appendBox(instructions, valueType);
            instructions.add(new MethodInsnNode(
                    INVOKESTATIC,
                    RUNTIME_OWNER,
                    "modifyVariable",
                    MODIFY_VARIABLE_DESCRIPTOR,
                    false));
            appendUnbox(instructions, valueType);
            instructions.add(new VarInsnNode(valueType.getOpcode(ISTORE), temporaryLocal));
        }
        instructions.add(new VarInsnNode(valueType.getOpcode(ILOAD), temporaryLocal));
        if (target.access() == KubeJsMixinInstructionTarget.VariableAccess.STORE) {
            method.instructions.insertBefore(instruction, instructions);
        } else {
            method.instructions.insert(instruction, instructions);
        }
    }

    private static InvocationOperands captureInvocationOperands(MethodNode method, MethodInsnNode instruction) {
        Type[] argumentTypes = Type.getArgumentTypes(instruction.desc);
        int[] argumentLocals = new int[argumentTypes.length];
        for (int index = 0; index < argumentTypes.length; index++) {
            argumentLocals[index] = allocateLocal(method, argumentTypes[index]);
        }
        int receiverLocal = instruction.getOpcode() == INVOKESTATIC
                ? -1
                : allocateLocal(method, Type.getObjectType(instruction.owner));
        return new InvocationOperands(argumentTypes, argumentLocals, receiverLocal);
    }

    private static void appendStoreInvocationOperands(
            InsnList instructions,
            MethodInsnNode instruction,
            InvocationOperands operands) {
        for (int index = operands.argumentTypes.length - 1; index >= 0; index--) {
            Type argumentType = operands.argumentTypes[index];
            instructions.add(new VarInsnNode(argumentType.getOpcode(ISTORE), operands.argumentLocals[index]));
        }
        if (instruction.getOpcode() != INVOKESTATIC) {
            instructions.add(new VarInsnNode(ASTORE, operands.receiverLocal));
        }
    }

    private static void appendLoadInvocationOperands(
            InsnList instructions,
            MethodInsnNode instruction,
            InvocationOperands operands) {
        if (instruction.getOpcode() != INVOKESTATIC) {
            instructions.add(new VarInsnNode(ALOAD, operands.receiverLocal));
        }
        for (int index = 0; index < operands.argumentTypes.length; index++) {
            Type argumentType = operands.argumentTypes[index];
            instructions.add(new VarInsnNode(argumentType.getOpcode(ILOAD), operands.argumentLocals[index]));
        }
    }

    private static void appendInvocationReceiver(
            InsnList instructions,
            MethodInsnNode instruction,
            InvocationOperands operands) {
        if (instruction.getOpcode() == INVOKESTATIC) {
            instructions.add(new InsnNode(ACONST_NULL));
        } else {
            instructions.add(new VarInsnNode(ALOAD, operands.receiverLocal));
        }
    }

    private static void appendInvocationArguments(InsnList instructions, InvocationOperands operands) {
        pushInt(instructions, operands.argumentTypes.length);
        instructions.add(new TypeInsnNode(ANEWARRAY, "java/lang/Object"));
        for (int index = 0; index < operands.argumentTypes.length; index++) {
            Type argumentType = operands.argumentTypes[index];
            instructions.add(new InsnNode(DUP));
            pushInt(instructions, index);
            instructions.add(new VarInsnNode(argumentType.getOpcode(ILOAD), operands.argumentLocals[index]));
            appendBox(instructions, argumentType);
            instructions.add(new InsnNode(AASTORE));
        }
    }

    private static void appendReceiver(InsnList instructions, MethodNode method) {
        if ((method.access & ACC_STATIC) == 0) {
            instructions.add(new VarInsnNode(ALOAD, 0));
        } else {
            instructions.add(new InsnNode(ACONST_NULL));
        }
    }

    private static void appendArguments(InsnList instructions, MethodNode method) {
        Type[] argumentTypes = Type.getArgumentTypes(method.desc);
        pushInt(instructions, argumentTypes.length);
        instructions.add(new TypeInsnNode(ANEWARRAY, "java/lang/Object"));
        int local = (method.access & ACC_STATIC) == 0 ? 1 : 0;
        for (int index = 0; index < argumentTypes.length; index++) {
            Type argumentType = argumentTypes[index];
            instructions.add(new InsnNode(DUP));
            pushInt(instructions, index);
            instructions.add(new VarInsnNode(argumentType.getOpcode(ILOAD), local));
            appendBox(instructions, argumentType);
            instructions.add(new InsnNode(AASTORE));
            local += argumentType.getSize();
        }
    }

    private static void appendStoreMethodArguments(InsnList instructions, MethodNode method, int argumentsLocal) {
        Type[] argumentTypes = Type.getArgumentTypes(method.desc);
        int local = (method.access & ACC_STATIC) == 0 ? 1 : 0;
        for (int index = 0; index < argumentTypes.length; index++) {
            Type argumentType = argumentTypes[index];
            instructions.add(new VarInsnNode(ALOAD, argumentsLocal));
            pushInt(instructions, index);
            instructions.add(new InsnNode(AALOAD));
            appendUnbox(instructions, argumentType);
            instructions.add(new VarInsnNode(argumentType.getOpcode(ISTORE), local));
            local += argumentType.getSize();
        }
    }

    private static int allocateLocal(MethodNode method, Type type) {
        int local = method.maxLocals;
        method.maxLocals += type.getSize();
        return local;
    }

    private static void appendBox(InsnList instructions, Type type) {
        switch (type.getSort()) {
            case Type.BOOLEAN -> instructions.add(new MethodInsnNode(
                    INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false));
            case Type.BYTE -> instructions.add(new MethodInsnNode(
                    INVOKESTATIC, "java/lang/Byte", "valueOf", "(B)Ljava/lang/Byte;", false));
            case Type.CHAR -> instructions.add(new MethodInsnNode(
                    INVOKESTATIC, "java/lang/Character", "valueOf", "(C)Ljava/lang/Character;", false));
            case Type.SHORT -> instructions.add(new MethodInsnNode(
                    INVOKESTATIC, "java/lang/Short", "valueOf", "(S)Ljava/lang/Short;", false));
            case Type.INT -> instructions.add(new MethodInsnNode(
                    INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false));
            case Type.FLOAT -> instructions.add(new MethodInsnNode(
                    INVOKESTATIC, "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", false));
            case Type.LONG -> instructions.add(new MethodInsnNode(
                    INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false));
            case Type.DOUBLE -> instructions.add(new MethodInsnNode(
                    INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false));
            default -> {
                // Object and array values are already compatible with Object[] storage.
            }
        }
    }

    private static void appendUnbox(InsnList instructions, Type type) {
        switch (type.getSort()) {
            case Type.BOOLEAN -> appendPrimitiveUnbox(instructions, "java/lang/Boolean", "booleanValue", "()Z");
            case Type.BYTE -> appendPrimitiveUnbox(instructions, "java/lang/Byte", "byteValue", "()B");
            case Type.CHAR -> appendPrimitiveUnbox(instructions, "java/lang/Character", "charValue", "()C");
            case Type.SHORT -> appendPrimitiveUnbox(instructions, "java/lang/Short", "shortValue", "()S");
            case Type.INT -> appendPrimitiveUnbox(instructions, "java/lang/Integer", "intValue", "()I");
            case Type.FLOAT -> appendPrimitiveUnbox(instructions, "java/lang/Float", "floatValue", "()F");
            case Type.LONG -> appendPrimitiveUnbox(instructions, "java/lang/Long", "longValue", "()J");
            case Type.DOUBLE -> appendPrimitiveUnbox(instructions, "java/lang/Double", "doubleValue", "()D");
            case Type.OBJECT, Type.ARRAY -> instructions.add(new TypeInsnNode(CHECKCAST, type.getInternalName()));
            default -> throw new KubeJsMixinTransformationException(
                    "KubeJS mixin cannot unbox " + type.getDescriptor());
        }
    }

    private static void appendPrimitiveUnbox(
            InsnList instructions,
            String owner,
            String methodName,
            String descriptor) {
        instructions.add(new TypeInsnNode(CHECKCAST, owner));
        instructions.add(new MethodInsnNode(INVOKEVIRTUAL, owner, methodName, descriptor, false));
    }

    private static void pushInt(InsnList instructions, int value) {
        if (value >= -1 && value <= 5) {
            instructions.add(new InsnNode(ICONST_0 + value));
        } else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
            instructions.add(new IntInsnNode(BIPUSH, value));
        } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
            instructions.add(new IntInsnNode(SIPUSH, value));
        } else {
            instructions.add(new LdcInsnNode(value));
        }
    }

    private record MethodKey(String name, String descriptor) {
    }

    private record InvocationOperands(Type[] argumentTypes, int[] argumentLocals, int receiverLocal) {
    }
}
