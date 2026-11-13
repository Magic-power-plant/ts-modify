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

The main transpilation path uses the pinned pure-Java `Translanguage 2.0.6` library.
Runtime mode supports `.ts`, `.tsx`, `.mts`, and `.cts`, excluding declaration files.
The `KUBEJS` and `PROJECT` configurations remain separate.

KubeJS runtime processing is ordered as follows:

1. Guard ambiguous automatic-semicolon boundaries, then erase TypeScript with the
   engine's `PRESERVE` target and legacy decorators.
2. Convert static imports/exports with the existing CommonJS adapter. Relative
   imports stay inside the script domain or `shared_scripts`; shared sources still
   execute separately in server and client runtimes. Extensionless imports such as
   `./global` remain recommended for TypeScript language-service compatibility.
3. Lower JavaScript to ES5 using `KUBEJS_RHINO_2001_2_3_BUILD_6`, readable output,
   and `LexicalMode.LOOSE` (no full temporal-dead-zone emulation). The preset hoists
   loop declarations and respects Rhino's non-configurable function-name properties.
4. Apply Rhino-specific syntax repairs and adapt compiler-generated bound arrows
   to ordinary forwarding functions for Java interface conversion. Translanguage
   2.0.6 emits `.bind(this)`, whose Rhino `BoundFunction` cannot directly become
   a KubeJS `IEventHandler` (reported as `Cannot convert Unknown`). The adapter
   retains the bound receiver and preserves callback arity, return values and
   thrown exceptions, while continuing to reject construction of arrows. It
   parses generated JavaScript with the pinned engine, so comments, strings and
   regular expressions are not rewritten. User `.bind(...)` calls and project
   output are unchanged. The standalone `KUBEJS` facade
   also strips static module syntax and isolates file-local names; the module
   loader already supplies a separate scope, so it does not need these two steps.

Default parameters, arrows, classes/fields, destructuring, templates, object rest
and spread, optional chaining and loops are covered by regression tests. These
transforms do not install arbitrary JavaScript platform APIs: scripts must still
supply APIs such as `Promise`, and JSX uses `React.createElement` by default.
Rhino mode rejects BigInt rather than emitting a call to an unavailable global.

This callback repair changes the post-processing cache fingerprint. Previously
cached output is automatically regenerated after updating TS Modify; manually
deleting the transpile cache is unnecessary.

KubeJS global bindings such as `ServerEvents`, `ItemEvents`, and `StartupEvents` remain visible inside modules. Use `export` / `import` for TypeScript modules, and use explicit assignments to shared objects such as `global` only when you intentionally want normal KubeJS global behavior.

## Transpile Disk Cache

TS Modify caches emitted module bodies on disk. A cache hit avoids both pure-Java
compiler passes and the host compatibility processing; no WASM runtime is loaded.

Cache location:

```text
<game>/kubejs/tsmodify-cache/v1/<first two key characters>/<key>.js
```

One file per emitted module body, UTF-8, no source map, no index file. Key identity is content addressed:

- the exact transpiled source text (SHA-256), after normalising line endings and removing trailing line terminators, and
- the source media type extension (`.ts`, `.tsx`, `.mts`, `.cts`), and
- a pipeline fingerprint covering every transpiler option actually used for KubeJS module bodies, the Translanguage version and exact artifact SHA-256, the Java post-processing version, the mod version, and the cache format version.

The pipeline fingerprint makes cross-version reuse impossible: upgrading the mod, the engine, or the post-processing pipeline changes the fingerprint, so every entry misses instead of returning code produced by different software. Player edits change the content hash and are picked up on the next load. A key that ignores the source directory means one file has exactly one entry no matter which path named it, which is safe because the emitted body depends only on the extension and the content.

The cache is a performance feature, never a correctness dependency. Unreadable, truncated, empty, or non-UTF-8 entries are discarded and treated as a miss; every cache read and write failure falls back to transpiling. Writes go through a uniquely named temporary file and are published with an atomic move, so a concurrent reader can never observe a half-written body. Capacity defaults to 4096 entries and is trimmed least-recently-used first, where reads refresh an entry's recency.

Every reload starts a new measurement session. Three points emit the INFO summary: the KubeJS cache-clear hook, a Forge `ServerStartedEvent` listener (server scripts are transpiled long before any reload, so this is what makes a normal server run visible), and JVM shutdown. Individual files are reported at DEBUG:

```text
[TS Modify] transpiled <files> TypeScript file(s) in <compile time> (session <elapsed>, cache <hits>/<files> hit, Translanguage, <calls> compiler call(s), <bytes> source, trigger: server started)
```

The first duration is accumulated transpilation/cache lookup time. The `session`
span also includes unrelated startup work such as world loading. A normal cold
module uses two compiler calls; a cached module uses zero. `transpileCacheBenchmark`
compares these paths using the real script corpus. Old reports mentioning WASM
load time or bridge calls describe the pre-migration engine and are historical.

System properties:

- `-Dtsmodify.transpileCache=false` disables the disk cache; every file is transpiled by Translanguage.
- `-Dtsmodify.transpileCacheDir=<path>` relocates the cache root, replacing `<game>/kubejs/tsmodify-cache/v1`.
- `-Dtsmodify.transpileCacheMaxEntries=<n>` changes the capacity (default 4096).

The cache deliberately outlives `KubeJSPlugin.clearCaches()`; only the in-memory source map is cleared there, because that map is bound to a script domain and runtime.

The in-memory Rhino bytecode cache described above and this transpile disk cache are independent: the former avoids recompiling JavaScript on reload, the latter avoids re-running the TypeScript-to-JavaScript pipeline.

`TranspilePipelineFingerprint.POST_PROCESSING_VERSION` must change when host
rewrites or their ordering change. The engine identity is generated from
`gradle.properties` into `tsmodify/translanguage.properties`; updating the version
or artifact checksum invalidates existing cached bodies without loading the compiler.
The old engine's entries are not reused and will age out under the normal capacity limit.

## KubeJS Fluid Type Registration

For every fluid created by KubeJS through `StartupEvents.registry("fluid", ...)`, TS Modify also registers its Forge `FluidType` in `ForgeRegistries.FLUID_TYPES`. The fluid type uses the source fluid's resource location, so a source fluid named `pack:oil` has a fluid type named `pack:oil`.

The source fluid and its `pack:flowing_oil` variant return the exact same registered `FluidType` instance. The flowing-fluid ID remains an entry only in `minecraft:fluid`; TS Modify does not create a second `forge:fluid_type` entry for it.

## Error Diagnostics

TS Modify keeps the original exception as the cause and adds a structured diagnostic message at each TypeScript boundary. Runtime errors now identify:

- the failing phase, such as `Translanguage transpilation`, `TypeScript post-processing`, `script preload`, `module resolution`, or `module execution`;
- the source file and module ID when they are available;
- the requester and module chain for nested imports;
- the source line, column, and a short code frame when Translanguage or Rhino reports a location;
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
## Scope and Build Requirements

Translanguage handles syntax transpilation; TS Modify does not type-check or
bundle projects. Project mode preserves module syntax, rewrites relative
TypeScript extensions, and can request `SourceMapMode.EXTERNAL` or `INLINE` from
the Java configuration API. External maps are written beside the output; inline
maps stay in the code. Maps originate from the TypeScript-erasure pass and are
not composed with later host rewrites or ES5 lowering, so they are not precise
runtime maps for KubeJS (which disables maps by default).

The build requires Java 17 and the local `docs/Translanguage-2.0.6.jar`, with its
version and SHA-256 pinned in `gradle.properties`. There is no Cargo, Rust,
WASM target, JNI, or Chicory requirement. Run `./gradlew test` and `./gradlew build`
(or `.\gradlew.bat` on Windows). Distribute `build/libs/tsmodify-<version>-all.jar`,
which includes the unmodified library and its license/notices via Forge Jar-in-Jar.
The plain development JAR does not bundle the library.

Java integration configuration now uses Translanguage enums (`ModuleKind`,
`JavaScriptTarget`, `SourceMapMode`, `DecoratorMode`) in place of swc4j enums.
`ModuleKind.SCRIPT` expresses the former script-only parse mode; the redundant
`parseMode` setting and WASM merged-call switch are removed.
