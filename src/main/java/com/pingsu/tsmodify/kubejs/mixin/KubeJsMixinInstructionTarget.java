package com.pingsu.tsmodify.kubejs.mixin;

import org.objectweb.asm.Type;

import java.util.Locale;

/**
 * Exact bytecode location selected by an instruction-level script rule.
 */
sealed interface KubeJsMixinInstructionTarget
        permits KubeJsMixinInstructionTarget.Invoke, KubeJsMixinInstructionTarget.Variable {
    String describe();

    record Invoke(String owner, String methodName, String methodDescriptor, int ordinal, int argumentIndex)
            implements KubeJsMixinInstructionTarget {
        public Invoke {
            owner = owner == null ? null : owner.replace('.', '/');
        }

        Type[] argumentTypes() {
            return Type.getArgumentTypes(methodDescriptor);
        }

        Type returnType() {
            return Type.getReturnType(methodDescriptor);
        }

        @Override
        public String describe() {
            return owner.replace('/', '.') + "#" + methodName + methodDescriptor + " at ordinal " + ordinal;
        }
    }

    record Variable(int localIndex, String descriptor, VariableAccess access, int ordinal)
            implements KubeJsMixinInstructionTarget {
        Type type() {
            return Type.getType(descriptor);
        }

        @Override
        public String describe() {
            return access.displayName + " local " + localIndex + " (" + descriptor + ") at ordinal " + ordinal;
        }
    }

    enum VariableAccess {
        LOAD("load"),
        STORE("store");

        private final String displayName;

        VariableAccess(String displayName) {
            this.displayName = displayName;
        }

        static VariableAccess fromScriptValue(String value) {
            if (value == null) {
                throw new IllegalArgumentException("KubeJS modifyVariable access cannot be null");
            }
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(
                        "KubeJS modifyVariable access must be LOAD or STORE, not " + value,
                        exception);
            }
        }

        String displayName() {
            return displayName;
        }
    }
}
