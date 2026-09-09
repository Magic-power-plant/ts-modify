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
- the ES5 target is implemented in the local Rust/WASM bridge, not by the Java emitter flag alone: the bridge runs SWC's compatibility pass inside the same `GLOBALS` / helper context used by SWC transforms

KubeJS global bindings such as `ServerEvents`, `ItemEvents`, and `StartupEvents` remain visible inside modules. Use `export` / `import` for TypeScript modules, and use explicit assignments to shared objects such as `global` only when you intentionally want normal KubeJS global behavior.

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

## Scope

SWC handles TypeScript syntax transpilation. TS Modify does not perform type checking or bundling. Runtime KubeJS mode supports static module syntax through a CommonJS-style loader with same-domain and `shared_scripts` resolution; project mode preserves module syntax and rewrites relative `.ts`-family specifiers to their emitted JavaScript extensions.

The mod vendors the swc4j Java API subset that TS Modify uses, but the SWC execution path no longer loads swc4j JNI libraries. The build compiles the local `wasm/swc4j-bridge` Rust crate to `wasm32-wasip1`, packages `swc4j/swc4j_bridge.wasm` as a mod resource, and calls it through the pure Java Chicory WebAssembly runtime.

`compileSwc4jWasm` requires Cargo and the `wasm32-wasip1` target. `processResources` depends on that task so release jars contain the WASM module and no `libswc4j-*` platform native resources. Use `-PskipSwc4jWasm=true` only for Java-only compilation on machines without Rust; that path does not produce a runnable SWC WASM resource.
