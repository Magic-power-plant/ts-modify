# Third-Party Notices

This project is licensed under the Apache License, Version 2.0. That license
applies to TS Modify's original source code and project files unless a file or
directory carries a different copyright or license notice.

The Apache License, Version 2.0 is intended to grant broad rights to use, copy,
modify, distribute, sublicense, and commercially use this project. It does not
relicense third-party projects. Third-party code, libraries, assets, tools, and
Minecraft/Forge/KubeJS dependencies remain subject to their own licenses and
terms.

## Vendored or Adapted Source

- swc4j source code under `src/main/java/com/caoccao/javet/swc4j/`
  - Copyright: caoccao.com Sam Cao and contributors
  - License: Apache License, Version 2.0
  - Source: https://github.com/caoccao/swc4j
  - Notes: Source files keep their original Apache-2.0 copyright headers.

- swc4j reference checkout under `swc4j/`
  - Copyright: caoccao.com Sam Cao and contributors
  - License: Apache License, Version 2.0
  - Source: https://github.com/caoccao/swc4j

- Javet reference checkout under `Javet/`
  - Copyright: caoccao.com Sam Cao and contributors
  - License: Apache License, Version 2.0
  - Source: https://github.com/caoccao/Javet

## Bundled Runtime Libraries

The Gradle build uses `jarJar` to bundle the following Chicory modules:

- `com.dylibso.chicory:runtime:1.7.5`
- `com.dylibso.chicory:wasi:1.7.5`
- `com.dylibso.chicory:wasm:1.7.5`
- `com.dylibso.chicory:log:1.7.5`

These modules are third-party software from the Chicory project.

- License: Apache License, Version 2.0
- Source: https://github.com/dylibso/chicory

## Rust and WebAssembly Dependencies

The WebAssembly bridge in `wasm/swc4j-bridge/` depends on Rust crates recorded
in `wasm/swc4j-bridge/Cargo.lock`, including `deno_ast`, SWC crates, `anyhow`,
`base64`, `serde`, and `serde_json`. Those crates remain subject to their own
published crate licenses and notices.

## Minecraft Modding Dependencies

The project depends on Minecraft Forge, KubeJS, Rhino, Architectury, ProbeJS,
SpongePowered Mixin, Minecraft, and their transitive dependencies. Those
dependencies are not relicensed by TS Modify and remain subject to their
respective upstream licenses, terms, and distribution rules.

Local metadata currently identifies SpongePowered Mixin as MIT licensed and
JUnit test dependencies as Eclipse Public License 2.0 licensed. Runtime and
development dependencies should be reviewed from their upstream artifacts before
redistribution.

## Redistributor Responsibilities

When redistributing source or binary builds, keep this notice file, keep the
root `LICENSE`, and preserve copyright and license headers in third-party files.
If a distributed artifact includes additional third-party components or generated
outputs, include the notices required by those components as well.
