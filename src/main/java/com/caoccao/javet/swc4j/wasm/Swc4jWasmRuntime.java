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

import com.caoccao.javet.swc4j.comments.Swc4jComments;
import com.caoccao.javet.swc4j.enums.Swc4jMediaType;
import com.caoccao.javet.swc4j.enums.Swc4jParseMode;
import com.caoccao.javet.swc4j.exceptions.Swc4jCoreException;
import com.caoccao.javet.swc4j.options.Swc4jDecoratorsTranspileOption;
import com.caoccao.javet.swc4j.options.Swc4jDecoratorsTranspileOptionEcma;
import com.caoccao.javet.swc4j.options.Swc4jDecoratorsTranspileOptionLegacyTypeScript;
import com.caoccao.javet.swc4j.options.Swc4jJsxRuntimeOption;
import com.caoccao.javet.swc4j.options.Swc4jJsxRuntimeOptionAutomatic;
import com.caoccao.javet.swc4j.options.Swc4jJsxRuntimeOptionClassic;
import com.caoccao.javet.swc4j.options.Swc4jJsxRuntimeOptionPrecompile;
import com.caoccao.javet.swc4j.options.Swc4jOptions;
import com.caoccao.javet.swc4j.options.Swc4jTransformOptions;
import com.caoccao.javet.swc4j.options.Swc4jTranspileOptions;
import com.caoccao.javet.swc4j.outputs.Swc4jTransformOutput;
import com.caoccao.javet.swc4j.outputs.Swc4jTranspileOutput;
import com.dylibso.chicory.runtime.ExportFunction;
import com.dylibso.chicory.runtime.ImportValues;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Memory;
import com.dylibso.chicory.wasi.WasiOptions;
import com.dylibso.chicory.wasi.WasiPreview1;
import com.dylibso.chicory.wasi.WasiPreview1_ModuleFactory;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Loads and invokes the SWC WebAssembly bridge with a pure Java WASM runtime.
 */
public final class Swc4jWasmRuntime {
    private static final Swc4jComments EMPTY_COMMENTS = new Swc4jComments(Map.of(), Map.of());
    private static final Gson GSON = new Gson();
    private static final Swc4jWasmRuntime INSTANCE = new Swc4jWasmRuntime();
    private static final String RESOURCE_NAME = "/swc4j/swc4j_bridge.wasm";

    private final ExportFunction allocFunction;
    private final ExportFunction freeFunction;
    private final Memory memory;
    private final ExportFunction transformFunction;
    private final ExportFunction transpileFunction;
    private final ExportFunction versionFunction;
    @SuppressWarnings("FieldCanBeLocal")
    private final WasiPreview1 wasiPreview1;

    private Swc4jWasmRuntime() {
        try (InputStream inputStream = Swc4jWasmRuntime.class.getResourceAsStream(RESOURCE_NAME)) {
            if (inputStream == null) {
                throw new IllegalStateException("Missing " + RESOURCE_NAME
                        + ". Run the Gradle compileSwc4jWasm task with Rust target wasm32-wasip1 installed.");
            }
            WasmModule module = Parser.parse(inputStream);
            wasiPreview1 = WasiPreview1.builder()
                    .withOptions(WasiOptions.builder().build())
                    .build();
            ImportValues importValues = ImportValues.builder()
                    .addFunction(WasiPreview1_ModuleFactory.toHostFunctions(wasiPreview1))
                    .build();
            Instance instance = Instance.builder(module)
                    .withImportValues(importValues)
                    .withStart(false)
                    .build();
            memory = instance.memory();
            allocFunction = instance.exports().function("swc4j_alloc");
            freeFunction = instance.exports().function("swc4j_free");
            versionFunction = instance.exports().function("swc4j_version");
            transformFunction = instance.exports().function("swc4j_transform");
            transpileFunction = instance.exports().function("swc4j_transpile");
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + RESOURCE_NAME, e);
        }
    }

    public static Swc4jWasmRuntime getInstance() {
        return INSTANCE;
    }

    public String getVersion() {
        JsonObject response = invoke(versionFunction);
        return response.get("version").getAsString();
    }

    public Swc4jTransformOutput transform(String code, Swc4jTransformOptions options) throws Swc4jCoreException {
        requireUnsupportedCallbacksAbsent(options, "transform");
        JsonObject response;
        try {
            response = invoke(transformFunction, createTransformRequest(code, options));
        } catch (RuntimeException e) {
            throw Swc4jCoreException.transformError("Failed to invoke SWC WASM transform", e);
        }
        if (!isOk(response)) {
            throw Swc4jCoreException.transformError(errorMessage(response));
        }
        return new Swc4jTransformOutput(
                response.get("code").getAsString(),
                mediaType(response),
                parseMode(response),
                optionalString(response.get("sourceMap")));
    }

    public Swc4jTranspileOutput transpile(String code, Swc4jTranspileOptions options) throws Swc4jCoreException {
        requireUnsupportedCallbacksAbsent(options, "transpile");
        if (options.isCaptureAst() || options.isCaptureComments() || options.isCaptureTokens()) {
            throw Swc4jCoreException.featureNotSupported("WASM transpile AST, comment, and token capture");
        }
        JsonObject response;
        try {
            response = invoke(transpileFunction, createTranspileRequest(code, options));
        } catch (RuntimeException e) {
            throw Swc4jCoreException.transpileError("Failed to invoke SWC WASM transpile", e);
        }
        if (!isOk(response)) {
            throw Swc4jCoreException.transpileError(errorMessage(response));
        }
        return new Swc4jTranspileOutput(
                null,
                response.get("code").getAsString(),
                mediaType(response),
                parseMode(response),
                optionalString(response.get("sourceMap")),
                code,
                List.of(),
                EMPTY_COMMENTS);
    }

    private static void addBaseOptions(JsonObject optionsJson, Swc4jOptions options) {
        optionsJson.addProperty("mediaType", options.getMediaType().getId());
        optionsJson.addProperty("parseMode", options.getParseMode().getId());
        optionsJson.addProperty("specifier", options.getSpecifier().toString());
    }

    private static void addDecorators(JsonObject optionsJson, Swc4jDecoratorsTranspileOption decorators) {
        JsonObject decoratorsJson = new JsonObject();
        if (decorators instanceof Swc4jDecoratorsTranspileOptionEcma) {
            decoratorsJson.addProperty("kind", "ecma");
        } else if (decorators instanceof Swc4jDecoratorsTranspileOptionLegacyTypeScript legacyTypeScript) {
            decoratorsJson.addProperty("kind", "legacyTypeScript");
            decoratorsJson.addProperty("emitMetadata", legacyTypeScript.isEmitMetadata());
        } else {
            decoratorsJson.addProperty("kind", "none");
        }
        optionsJson.add("decorators", decoratorsJson);
    }

    private static void addJsx(JsonObject optionsJson, Swc4jJsxRuntimeOption jsx) {
        if (jsx == null) {
            optionsJson.add("jsx", JsonNull.INSTANCE);
            return;
        }
        JsonObject jsxJson = new JsonObject();
        if (jsx instanceof Swc4jJsxRuntimeOptionAutomatic automatic) {
            jsxJson.addProperty("kind", "automatic");
            addJsxAutomatic(jsxJson, automatic);
        } else if (jsx instanceof Swc4jJsxRuntimeOptionClassic classic) {
            jsxJson.addProperty("kind", "classic");
            jsxJson.addProperty("factory", classic.getFactory());
            jsxJson.addProperty("fragmentFactory", classic.getFragmentFactory());
        } else if (jsx instanceof Swc4jJsxRuntimeOptionPrecompile precompile) {
            jsxJson.addProperty("kind", "precompile");
            JsonObject automaticJson = new JsonObject();
            addJsxAutomatic(automaticJson, precompile.getAutomatic());
            jsxJson.add("automatic", automaticJson);
            jsxJson.add("dynamicProps", GSON.toJsonTree(precompile.getDynamicProps()));
            jsxJson.add("skipElements", GSON.toJsonTree(precompile.getSkipElements()));
        } else {
            throw new IllegalArgumentException("Unsupported JSX option " + jsx.getClass().getName());
        }
        optionsJson.add("jsx", jsxJson);
    }

    private static void addJsxAutomatic(JsonObject jsonObject, Swc4jJsxRuntimeOptionAutomatic automatic) {
        jsonObject.addProperty("development", automatic.isDevelopment());
        if (automatic.getImportSource() == null) {
            jsonObject.add("importSource", JsonNull.INSTANCE);
        } else {
            jsonObject.addProperty("importSource", automatic.getImportSource());
        }
    }

    private static String errorMessage(JsonObject response) {
        JsonElement error = response.get("error");
        return optionalString(error) == null ? "Unknown SWC WASM error" : optionalString(error);
    }

    private static Swc4jMediaType mediaType(JsonObject response) {
        return Swc4jMediaType.parse(response.get("mediaType").getAsInt());
    }

    private static String optionalString(JsonElement element) {
        return element == null || element.isJsonNull() ? null : element.getAsString();
    }

    private static Swc4jParseMode parseMode(JsonObject response) {
        return Swc4jParseMode.parse(response.get("parseMode").getAsInt());
    }

    private static boolean isOk(JsonObject response) {
        return response.has("ok") && response.get("ok").getAsBoolean();
    }

    private static void requireUnsupportedCallbacksAbsent(Swc4jOptions options, String operation)
            throws Swc4jCoreException {
        if (options.getPluginHost() != null) {
            throw Swc4jCoreException.featureNotSupported("WASM " + operation + " plugin host callbacks");
        }
    }

    private JsonObject createTransformRequest(String code, Swc4jTransformOptions options) {
        JsonObject optionsJson = new JsonObject();
        addBaseOptions(optionsJson, options);
        optionsJson.addProperty("asciiOnly", options.isAsciiOnly());
        optionsJson.addProperty("emitAssertForImportAttributes", options.isEmitAssertForImportAttributes());
        optionsJson.addProperty("inlineSources", options.isInlineSources());
        optionsJson.addProperty("keepComments", options.isKeepComments());
        optionsJson.addProperty("minify", options.isMinify());
        optionsJson.addProperty("omitLastSemi", options.isOmitLastSemi());
        optionsJson.addProperty("sourceMap", options.getSourceMap().getId());
        optionsJson.addProperty("target", options.getTarget().getId());
        return createRequest(code, optionsJson);
    }

    private JsonObject createTranspileRequest(String code, Swc4jTranspileOptions options) {
        JsonObject optionsJson = new JsonObject();
        addBaseOptions(optionsJson, options);
        optionsJson.addProperty("captureAst", options.isCaptureAst());
        optionsJson.addProperty("captureComments", options.isCaptureComments());
        optionsJson.addProperty("captureTokens", options.isCaptureTokens());
        addDecorators(optionsJson, options.getDecorators());
        optionsJson.addProperty("importsNotUsedAsValues", options.getImportsNotUsedAsValues().getId());
        optionsJson.addProperty("inlineSources", options.isInlineSources());
        addJsx(optionsJson, options.getJsx());
        optionsJson.addProperty("keepComments", options.isKeepComments());
        optionsJson.addProperty("moduleKind", options.getModuleKind().getId());
        optionsJson.addProperty("scopeAnalysis", options.isScopeAnalysis());
        optionsJson.addProperty("sourceMap", options.getSourceMap().getId());
        optionsJson.addProperty("varDeclImports", options.isVarDeclImports());
        optionsJson.addProperty("verbatimModuleSyntax", options.isVerbatimModuleSyntax());
        return createRequest(code, optionsJson);
    }

    private JsonObject createRequest(String code, JsonObject optionsJson) {
        JsonObject request = new JsonObject();
        request.addProperty("code", code);
        request.add("options", optionsJson);
        return request;
    }

    private synchronized JsonObject invoke(ExportFunction function) {
        long packed = function.apply()[0];
        return readAndFreeResponse(packed);
    }

    private synchronized JsonObject invoke(ExportFunction function, JsonObject request) {
        byte[] requestBytes = GSON.toJson(request).getBytes(StandardCharsets.UTF_8);
        int requestPointer = allocate(requestBytes.length);
        try {
            memory.write(requestPointer, requestBytes);
            long packed = function.apply(requestPointer, requestBytes.length)[0];
            return readAndFreeResponse(packed);
        } finally {
            free(requestPointer, requestBytes.length);
        }
    }

    private int allocate(int byteLength) {
        return (int) allocFunction.apply(byteLength)[0];
    }

    private void free(int pointer, int byteLength) {
        freeFunction.apply(pointer, byteLength);
    }

    private JsonObject readAndFreeResponse(long packed) {
        int pointer = (int) (packed & 0xFFFF_FFFFL);
        int byteLength = (int) (packed >>> 32);
        try {
            String response = memory.readString(pointer, byteLength, StandardCharsets.UTF_8);
            return JsonParser.parseString(response).getAsJsonObject();
        } finally {
            free(pointer, byteLength);
        }
    }
}
