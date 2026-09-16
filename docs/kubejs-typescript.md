# KubeJS TypeScript Compatibility

## What KubeJS Does Here

KubeJS stores user scripts under the game directory:

- `kubejs/startup_scripts`
- `kubejs/server_scripts`
- `kubejs/client_scripts`

In KubeJS `2001.6.5-build.14`, `KubeJS.loadScripts` and `ScriptManager.loadFromResources` already discover both `.js` and `.ts` files, while ignoring `.d.ts`. The missing piece is execution: KubeJS passes script text directly to Rhino, so TypeScript syntax must be emitted as JavaScript before runtime evaluation.

## Runtime Transpilation

TS Modify injects into `ScriptFileInfo.preload()` and rewrites TypeScript source before KubeJS evaluates it. Normal KubeJS behavior is preserved for `.js` files, and declaration files stay ignored.

TS Modify also caches compiled Rhino bytecode for unchanged KubeJS script
sources. The cache is used on later script reloads, including the small entry
script generated for a TypeScript module. Repeated ordinary
`Context.evaluateString` calls with a null security domain use the same cache.
It retains no JavaScript scope, module export, or function instance, so server
and client contexts remain separate. The first load still compiles normally;
this is a reload optimization, not a claim about every Rhino execution path.

The main transpilation path is backed by `swc4j`, using SWC's TypeScript parser and emitter instead of the old hand-written TypeScript stripper. Runtime mode supports the TypeScript-family script extensions `.ts`, `.tsx`, `.mts`, and `.cts`, excluding declaration files.

KubeJS runtime mode also applies KubeJS-specific compatibility steps after SWC emits JavaScript:

- static `import` and `export` declarations are transformed to TS Modify's runtime module loader
- every TypeScript file runs in a CommonJS-style module scope with its own `exports` object and module cache
- relative imports are supported inside the same script domain, such as `server_scripts` importing another `server_scripts` file
- extensionless relative imports are recommended, for example `./global` instead of `./global.ts`, because TypeScript and IDE language services reject `.ts` specifiers unless `allowImportingTsExtensions` is enabled
- `kubejs/shared_scripts` is available as shared source for server and client scripts; modules from that directory execute separately in each script context
- output targets ES5 for KubeJS Rhino; SWC's ES2015 compatibility pass lowers default parameters, arrow functions, block-scoped declarations, templates, destructuring, and related ES2015 syntax before the KubeJS-specific post-processing steps
- ES2018 object rest and object spread are lowered before the ES2015 pass, so patterns such as `const { x, ...rest } = value` and literals such as `{ ...rest, x }` do not leave Rhino-incompatible `...` syntax behind
- class fields are lowered before the ES2015 class transform, which deliberately ignores them: `static created: string[] = []` becomes a `_define_property(...)` assignment after the class and instance fields become constructor assignments. Private `#name` fields are emitted as mangled ordinary properties, because an ES5 target has no private names
- `var` declarations inside a `try` block are lifted to just before that block. KubeJS's Rhino loses the visibility of such a variable when an abrupt completion (`break`, `return`) runs the inlined `finally`, which is exactly the shape SWC's ES5 `for...of` lowering produces when it calls `iterator.return()` during cleanup
- the ES5 target is implemented in the local Rust/WASM bridge, not by the Java emitter flag alone: the bridge runs SWC's compatibility pass inside the same `GLOBALS` / helper context used by SWC transforms

KubeJS global bindings such as `ServerEvents`, `ItemEvents`, and `StartupEvents` remain visible inside modules. Use `export` / `import` for TypeScript modules, and use explicit assignments to shared objects such as `global` only when you intentionally want normal KubeJS global behavior.

## Transpile Disk Cache

Transpiling a TypeScript module body crosses the Rust/WASM bridge twice, which is the most expensive part of a KubeJS script load. TS Modify therefore keeps the emitted JavaScript on disk so a warm start can register modules without touching the WASM runtime at all.

Cache location:

```text
<game>/kubejs/tsmodify-cache/v1/<first two key characters>/<key>.js
```

One file per emitted module body, UTF-8, no source map, no index file. Key identity is content addressed:

- the exact transpiled source text (SHA-256), after CRLF normalisation, and
- the source media type extension (`.ts`, `.tsx`, `.mts`, `.cts`), and
- a pipeline fingerprint covering every transpiler option actually used for KubeJS module bodies, the WASM bridge ABI version (`2.2.0`), the Java post-processing version, the mod version, and the cache format version.

The pipeline fingerprint makes cross-version reuse impossible: upgrading the mod, the bridge, or the post-processing pipeline changes the fingerprint, so every entry misses instead of returning code produced by different software. Player edits change the content hash and are picked up on the next load. A key that ignores the source directory means one file has exactly one entry no matter which path named it, which is safe because the emitted body depends only on the extension and the content.

The cache is a performance feature, never a correctness dependency. Unreadable, truncated, empty, or non-UTF-8 entries are discarded and treated as a miss; every cache read and write failure falls back to transpiling. Writes go through a uniquely named temporary file and are published with an atomic move, so a concurrent reader can never observe a half-written body. Capacity defaults to 4096 entries and is trimmed least-recently-used first, where reads refresh an entry's recency.

Every reload starts a new measurement session. Three points emit the INFO summary: the KubeJS cache-clear hook, a Forge `ServerStartedEvent` listener (server scripts are transpiled long before any reload, so this is what makes a normal server run visible), and JVM shutdown. Individual files are reported at DEBUG:

```text
[TS Modify] transpiled 45 TypeScript file(s) in 5.18 s (session 12.90 s, cache 0/45 hit, wasm-load 1.80 s, 90 bridge call(s), 512.0 KiB source, trigger: server started)
[TS Modify] transpiled 45 TypeScript file(s) in 120 ms (session 9.77 s, cache 45/45 hit, wasm-load not needed, 0 bridge call(s), 512.0 KiB source, trigger: server started)
```

The first duration is the accumulated cost of the transpilation attempts themselves. The `session` span additionally covers everything between the first transpile and the flush, so in a game run it includes unrelated startup work such as world and datapack loading; compare `cache` hit counts, `wasm-load`, and bridge calls across runs instead of reading the session span as transpile time. Serving entries from the cache also means the WASM runtime is never initialized, which shows up as `wasm-load not needed` and as zero Chicory samples in a JFR recording.

System properties:

- `-Dtsmodify.transpileCache=false` disables the disk cache; every file is transpiled through the bridge.
- `-Dtsmodify.transpileCacheDir=<path>` relocates the cache root, replacing `<game>/kubejs/tsmodify-cache/v1`.
- `-Dtsmodify.transpileCacheMaxEntries=<n>` changes the capacity (default 4096).

The cache deliberately outlives `KubeJSPlugin.clearCaches()`; only the in-memory source map is cleared there, because that map is bound to a script domain and runtime.

The in-memory Rhino bytecode cache described above and this transpile disk cache are independent: the former avoids recompiling JavaScript on reload, the latter avoids re-running the TypeScript-to-JavaScript pipeline.

Java-side version constants that the fingerprint depends on must be bumped whenever the corresponding code changes: `TranspilePipelineFingerprint.POST_PROCESSING_VERSION` covers `typescript/syntax/*` and `TypeScriptCommonJsModuleTransformer`, and `TranspilePipelineFingerprint.SWC4J_BRIDGE_VERSION` mirrors the Rust `VERSION` constant in `wasm/swc4j-bridge/src/lib.rs` and is asserted against the loaded bridge by a unit test. The bridge version is mirrored rather than queried because asking the runtime for its version would initialize the WASM module, which is exactly the cost a cache hit is meant to avoid. Any change to the bridge crate — including a compatibility fix — must bump that version so bodies produced by the previous bridge cannot be reused.

## KubeJS Fluid Type Registration

For every fluid created by KubeJS through `StartupEvents.registry("fluid", ...)`, TS Modify also registers its Forge `FluidType` in `ForgeRegistries.FLUID_TYPES`. The fluid type uses the source fluid's resource location, so a source fluid named `pack:oil` has a fluid type named `pack:oil`.

The source fluid and its `pack:flowing_oil` variant return the exact same registered `FluidType` instance. The flowing-fluid ID remains an entry only in `minecraft:fluid`; TS Modify does not create a second `forge:fluid_type` entry for it.

## Error Diagnostics

TS Modify keeps the original exception as the cause and adds a structured diagnostic message at each TypeScript boundary. Runtime errors now identify:

- the failing phase, such as `SWC transpilation`, `TypeScript post-processing`, `script preload`, `module resolution`, or `module execution`;
- the source file and module ID when they are available;
- the requester and module chain for nested imports;
- the source line, column, and a short code frame when SWC or Rhino reports a location;
- the listener registration file and line for KubeJS event callback failures.

Typical output has this shape:

```text
TS Modify error during module execution [source=server_scripts/machine/crafter.ts]
  [module=pack:machine/crafter.ts] [module-chain=pack:machine/crafter.ts]
  at line 42, column 17: <original exception message>
    41 | const input = ...
    42 | const result = ...
       |                 ^
```

The original Java/Rhino stack trace is still available as the exception cause. For module resolution failures, inspect both the outer module-execution message and its `Caused by` entry; the latter contains the exact requester and unresolved specifier.

## Supported Script Style

```ts
export {}

interface RecipeEvent {
  server: any
}

enum Mode {
  Add,
  Remove
}

function addRecipe(event: RecipeEvent): void {
  event.server.tell(Mode.Add)
}

ServerEvents.recipes(event => addRecipe(event))
```

Exports are module values, not global variables:

```ts
// kubejs/server_scripts/global.ts
export const sharedItems = ["minecraft:stone"]

// kubejs/server_scripts/consumer.ts
import { sharedItems } from "./global"

ServerEvents.recipes(event => {
  console.info(sharedItems.length)
})
```

Shared source can be placed under `kubejs/shared_scripts` and imported from both server and client scripts:

```ts
// kubejs/shared_scripts/items.ts
export const hiddenItems = ["minecraft:stone"]

// kubejs/client_scripts/tooltips.ts
import { hiddenItems } from "../shared_scripts/items"
```

The shared module is read by both script domains but is not the same Rhino object across server and client contexts. Each side evaluates and caches its own copy.

## Java Primitive Helpers

Java exposes several numeric types that JavaScript does not distinguish. TS Modify
registers explicit factories in every KubeJS script scope so overloaded Java
methods can receive the intended boxed value:

```ts
const ticks = LONG.of("9223372036854775807")
const slots = INT.of(16)
const ratio = FLOAT.of(0.5)
const enabled = BOOLEAN.of(true)
const marker = CHAR.of("A")

someJavaMethod(ticks)
```

The available bindings are `LONG`, `INT`, `SHORT`, `BYTE`, `FLOAT`, `DOUBLE`,
`CHAR`, and `BOOLEAN`. `of`, `valueOf`, and `parse` return the corresponding
`java.lang.*` boxed value; when choosing a Java overload, TS Modify preserves
the primitive type selected by the factory before Rhino unboxes it. Integral
factories require a finite, whole value and enforce the Java range, so pass a
decimal string when a 64-bit value cannot be represented exactly by a
JavaScript number. `CHAR` accepts one UTF-16 code unit or a code unit number.
Boolean conversion accepts `true`/`false`, numeric `0`/`1`, and the same string
forms.

Each factory also provides `array(values)` and `arrayOf(...)`, which create a
real Java primitive array such as `long[]` or `int[]` from a JavaScript array,
Java collection, iterable, or (for `CHAR`) a string. `MIN_VALUE` and
`MAX_VALUE` are available on all numeric factories.

## Project Transpilation

For project-level TypeScript output, use `com.pingsu.tsmodify.typescript.project.TypeScriptProjectTranspiler` instead of the KubeJS runtime mode. It recursively reads a source root, writes JavaScript into a separate output root, rewrites relative TypeScript module specifiers, maps output extensions, and copies non-TypeScript files.

Extension mapping:

- `.ts` and `.tsx` -> `.js`
- `.mts` -> `.mjs`
- `.cts` -> `.cjs`
- `.d.ts`, `.d.mts`, and `.d.cts` are skipped

The output root must not be the source root or a child of it. This prevents repeated transpilation of generated output.

The CLI entry point accepts:

```text
TypeScriptProjectTranspiler <sourceRoot> <outputRoot> [--continue]
```

Without `--continue`, the first file error fails the run. With `--continue`, the result collects failures and returns a non-zero process exit after processing the rest of the project.

## Dedicated Server Notes

Verified on a production Forge server (1.20.1-47.4.10) with KubeJS `2001.6.5-build.14`, Rhino
`2001.2.3-build.6`, Architectury `9.1.12` and Mekanism `10.4.16.80`:

- KubeJS requires the separate **Rhino** mod at runtime. The copy embedded in the KubeJS jar does
  not satisfy ModLauncher's mandatory dependency check (`Mod ID: 'rhino', Requested by: 'kubejs'`),
  so a server install must ship `rhino-forge` alongside it.
- **ProbeJS is optional and is not server-safe** in its 7.x line: it fails during mod construction on
  a dedicated server (`Attempted to load class net/minecraft/client/multiplayer/MultiPlayerGameMode
  for invalid dist DEDICATED_SERVER`), which aborts startup. TS Modify itself needs no ProbeJS and
  starts normally without it.
## Scope

SWC handles TypeScript syntax transpilation. TS Modify does not perform type checking or bundling. Runtime KubeJS mode supports static module syntax through a CommonJS-style loader with same-domain and `shared_scripts` resolution; project mode preserves module syntax and rewrites relative `.ts`-family specifiers to their emitted JavaScript extensions.

The mod vendors the swc4j Java API subset that TS Modify uses, but the SWC execution path no longer loads swc4j JNI libraries. The build compiles the local `wasm/swc4j-bridge` Rust crate to `wasm32-wasip1`, packages `swc4j/swc4j_bridge.wasm` as a mod resource, and calls it through the pure Java Chicory WebAssembly runtime.

`compileSwc4jWasm` requires Cargo and the `wasm32-wasip1` target. `processResources` depends on that task so release jars contain the WASM module and no `libswc4j-*` platform native resources. Use `-PskipSwc4jWasm=true` only for Java-only compilation on machines without Rust; that path does not produce a runnable SWC WASM resource.
