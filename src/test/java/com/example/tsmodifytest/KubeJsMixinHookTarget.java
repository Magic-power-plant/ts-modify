package com.example.tsmodifytest;

public class KubeJsMixinHookTarget {
    public int hits;
    public static int staticHelperCalls;

    public void add(int amount) {
        hits += amount;
    }

    public void mark() {
        hits++;
    }

    public int sum(int left, int right) {
        return left + right;
    }

    public static String staticDescribe(long value, double factor, Object marker) {
        return marker + ":" + (value * factor);
    }

    public long wideSum(long left, long right) {
        return left + right;
    }

    public Object echo(Object value) {
        return value;
    }

    public void multipleReturns(boolean skip) {
        if (skip) {
            return;
        }
        hits++;
    }

    public void afterVoid() {
        hits++;
    }

    public void commitBatch(String channel, int amount, boolean dryRun) {
        if (!dryRun) {
            hits += amount;
        }
    }

    public int processBatch(String channel, int amount, boolean priority) {
        int normalizedAmount = Math.max(amount, 0);
        hits += normalizedAmount;
        if (priority) {
            return normalizedAmount * 2;
        }
        return normalizedAmount + channel.length();
    }

    public int redirectTarget(int value) {
        return staticDouble(value);
    }

    public static int staticDouble(int value) {
        staticHelperCalls++;
        return value * 2;
    }

    public int modifyInvocationArgument(int left, int right) {
        return Math.max(left, right);
    }

    public int variableFlow(int input) {
        int local = input + 1;
        return local * 2;
    }

    public String objectPipeline(String input) {
        String local = input + "!";
        return local.toUpperCase();
    }
}
