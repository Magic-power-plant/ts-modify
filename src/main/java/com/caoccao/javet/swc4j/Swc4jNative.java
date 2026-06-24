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

package com.caoccao.javet.swc4j;

import com.caoccao.javet.swc4j.exceptions.Swc4jCoreException;
import com.caoccao.javet.swc4j.options.Swc4jParseOptions;
import com.caoccao.javet.swc4j.options.Swc4jTransformOptions;
import com.caoccao.javet.swc4j.options.Swc4jTranspileOptions;
import com.caoccao.javet.swc4j.wasm.Swc4jWasmRuntime;

/**
 * The type swc4j native.
 */
final class Swc4jNative {
    private static final Swc4jWasmRuntime RUNTIME = Swc4jWasmRuntime.getInstance();

    private Swc4jNative() {
    }

    /**
     * Core get version string.
     *
     * @return the string
     */
    static String coreGetVersion() {
        return RUNTIME.getVersion();
    }

    /**
     * Core parse object.
     *
     * @param code    the code
     * @param options the options
     * @return the object
     */
    static Object coreParse(String code, Object options) throws Swc4jCoreException {
        if (!(options instanceof Swc4jParseOptions)) {
            throw Swc4jCoreException.parseError("Swc4j WASM parse options must be Swc4jParseOptions");
        }
        throw Swc4jCoreException.featureNotSupported("WASM AST parse output");
    }

    /**
     * Core transform object.
     *
     * @param code    the code
     * @param options the options
     * @return the object
     */
    static Object coreTransform(String code, Object options) throws Swc4jCoreException {
        if (!(options instanceof Swc4jTransformOptions transformOptions)) {
            throw Swc4jCoreException.transformError("Swc4j WASM transform options must be Swc4jTransformOptions");
        }
        return RUNTIME.transform(code, transformOptions);
    }

    /**
     * Core transpile object.
     *
     * @param code    the code
     * @param options the options
     * @return the object
     */
    static Object coreTranspile(String code, Object options) throws Swc4jCoreException {
        if (!(options instanceof Swc4jTranspileOptions transpileOptions)) {
            throw Swc4jCoreException.transpileError("Swc4j WASM transpile options must be Swc4jTranspileOptions");
        }
        return RUNTIME.transpile(code, transpileOptions);
    }
}
