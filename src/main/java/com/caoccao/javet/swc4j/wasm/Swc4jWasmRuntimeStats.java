/*
 * Copyright (c) 2024-2026. caoccao.com Sam Cao
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.caoccao.javet.swc4j.wasm;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Process-wide counters for the pure Java WASM runtime.
 *
 * <p>TS Modify addition: {@link Swc4jWasmRuntime} publishes its module load cost and bridge round
 * trip count here so startup diagnostics can read both <em>without</em> initializing
 * {@link Swc4jWasmRuntime}, whose static initializer is exactly the ~1.8 s WASM module parse that
 * must be avoided on a warm disk-cache startup.</p>
 */
public final class Swc4jWasmRuntimeStats {
    private static final AtomicLong INVOCATION_COUNT = new AtomicLong();
    private static volatile long loadDurationNanos;

    private Swc4jWasmRuntimeStats() {
    }

    /**
     * Wall-clock cost of parsing and instantiating the bundled WASM module, or {@code 0} when the
     * runtime has not been loaded yet. Reading this never triggers WASM loading.
     */
    public static long getLoadDurationNanos() {
        return loadDurationNanos;
    }

    /**
     * Number of request round trips performed across the WASM bridge since JVM start. Reading this
     * never triggers WASM loading.
     */
    public static long getInvocationCount() {
        return INVOCATION_COUNT.get();
    }

    static void recordLoadDuration(long durationNanos) {
        loadDurationNanos = Math.max(0L, durationNanos);
    }

    static void recordInvocation() {
        INVOCATION_COUNT.incrementAndGet();
    }
}
