# KubeJS TypeScript Compatibility

## What KubeJS Does Here

KubeJS stores user scripts under the game directory:

- `kubejs/startup_scripts`
- `kubejs/server_scripts`
- `kubejs/client_scripts`

In KubeJS `2001.6.5-build.14`, `KubeJS.loadScripts` and `ScriptManager.loadFromResources` already discover both `.js` and `.ts` files, while ignoring `.d.ts`. The missing piece is execution: KubeJS passes script text directly to Rhino, so TypeScript-only syntax still causes runtime syntax errors.

## What TS Modify Adds

TS Modify injects into `ScriptFileInfo.preload()` and rewrites `.ts` source before KubeJS evaluates it. Normal KubeJS behavior is preserved for `.js` files and `.d.ts` files stay ignored by KubeJS.

Each `.ts` file also gets isolated top-level names. KubeJS event callbacks are invoked later by KubeJS, so TS Modify avoids function-wrapper isolation and instead rewrites top-level declarations to file-unique names. KubeJS global bindings such as `ServerEvents`, `ItemEvents`, and `StartupEvents` remain visible, callbacks can still reference file-local helpers, and top-level declarations inside one TypeScript file no longer collide with other KubeJS script files.

The lightweight transpiler removes common TypeScript syntax that Rhino cannot parse:

- `interface`, `type`, and `declare` declarations
- variable, parameter, property, and return type annotations
- generic parameters on functions/classes and simple generic calls
- `as` and `satisfies` casts
- non-null assertions
- simple optional function calls such as `global.foo?.(value)`
- `export` statements
- basic `enum` declarations, converted to frozen JavaScript objects

Top-level assignments to explicit global objects, such as KubeJS `global`, are still shared intentionally. Undeclared assignments also follow normal JavaScript behavior, so prefer `let`, `const`, `var`, `function`, or `class` for file-local state.

## Supported Script Style

Good fit:

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

## IDE Duplicate Declaration Warnings

TypeScript treats files without any `import` or `export` as global script files. If multiple KubeJS `.ts` files all declare helpers such as `const g = global as any`, the IDE may report duplicate variable declarations even though TS Modify isolates them at runtime.

Add an empty export to each `.ts` file:

```ts
export {}

const g = global as any
```

The empty export makes the IDE treat the file as its own module scope. TS Modify strips `export {}` before Rhino evaluates the script, so it is safe for KubeJS runtime.

Not a full TypeScript compiler:

- no module bundling
- no type checking
- no decorator lowering
- no advanced TS emit transforms

For large script projects, run `tsc`, `esbuild`, or another real TypeScript tool before KubeJS loads the output. This mod is meant to make common inline TypeScript syntax work directly in KubeJS script folders.
