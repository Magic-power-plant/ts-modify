# KubeJS TypeScript Compatibility

## What KubeJS Does Here

KubeJS stores user scripts under the game directory:

- `kubejs/startup_scripts`
- `kubejs/server_scripts`
- `kubejs/client_scripts`

In KubeJS `2001.6.5-build.14`, `KubeJS.loadScripts` and `ScriptManager.loadFromResources` already discover both `.js` and `.ts` files, while ignoring `.d.ts`. The missing piece is execution: KubeJS passes script text directly to Rhino, so TypeScript syntax must be emitted as JavaScript before runtime evaluation.

## Runtime Transpilation

TS Modify injects into `ScriptFileInfo.preload()` and rewrites TypeScript source before KubeJS evaluates it. Normal KubeJS behavior is preserved for `.js` files, and declaration files stay ignored.

The main transpilation path is backed by `swc4j`, using SWC's TypeScript parser and emitter instead of the old hand-written TypeScript stripper. Runtime mode supports the TypeScript-family script extensions `.ts`, `.tsx`, `.mts`, and `.cts`, excluding declaration files.

KubeJS runtime mode also applies KubeJS-specific compatibility steps after SWC emits JavaScript:

- static `import` and `export` declarations are stripped because KubeJS script folders are not loaded as an ES module graph
- top-level declarations are renamed to file-unique names so helpers from separate script files do not collide
- selected JavaScript syntax that Rhino commonly rejects, such as arrow functions and optional calls, is lowered for runtime compatibility

KubeJS global bindings such as `ServerEvents`, `ItemEvents`, `StartupEvents`, and explicit assignments to shared objects such as `global` remain visible. Undeclared assignments also follow normal JavaScript behavior, so prefer `let`, `const`, `var`, `function`, or `class` for file-local state.

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

The `export {}` marker is useful for IDEs because it makes each TypeScript file a module for type-analysis purposes. TS Modify removes that marker before Rhino evaluates the script.

## Project Transpilation

For project-level TypeScript output, use `TypeScriptProjectTranspiler` instead of the KubeJS runtime mode. It recursively reads a source root, writes JavaScript into a separate output root, rewrites relative TypeScript module specifiers, maps output extensions, and copies non-TypeScript files.

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

SWC handles TypeScript syntax transpilation. TS Modify does not perform type checking or module bundling. Runtime KubeJS mode intentionally strips static module declarations; project mode preserves module syntax and rewrites relative `.ts`-family specifiers to their emitted JavaScript extensions.

The mod packages `swc4j` through Forge Jar-in-Jar. By default, the build only embeds the native artifact for the build machine's current platform, which keeps the mod jar small for local use. Use `-Pswc4jPlatforms=all` to build a cross-platform jar, or pass a comma-separated list such as `-Pswc4jPlatforms=windows-x86_64,linux-x86_64` for a targeted release.
