package com.pingsu.tsmodify.kubejs.mixin;

enum KubeJsMixinHook {
    BEFORE("before"),
    AFTER("after"),
    MODIFY_ARGS("modifyArgs"),
    MODIFY_RETURN_VALUE("modifyReturnValue"),
    REPLACE("replace"),
    REDIRECT("redirect"),
    MODIFY_ARG("modifyArg"),
    MODIFY_VARIABLE("modifyVariable");

    private final String displayName;

    KubeJsMixinHook(String displayName) {
        this.displayName = displayName;
    }

    String displayName() {
        return displayName;
    }

    boolean isMethodEntryHook() {
        return this == BEFORE || this == MODIFY_ARGS || this == REPLACE;
    }

    boolean isMethodReturnHook() {
        return this == AFTER || this == MODIFY_RETURN_VALUE;
    }

    boolean isInstructionHook() {
        return this == REDIRECT || this == MODIFY_ARG || this == MODIFY_VARIABLE;
    }

    boolean requiresInstructionTarget() {
        return isInstructionHook();
    }

    boolean supportsArgumentMutation() {
        return this == MODIFY_ARGS;
    }

    boolean supportsReturnValueMutation(boolean cancellable) {
        return this == MODIFY_RETURN_VALUE
                || this == REPLACE
                || this == REDIRECT
                || (this == BEFORE && cancellable);
    }

    boolean supportsValueMutation() {
        return this == MODIFY_ARG || this == MODIFY_VARIABLE;
    }
}
