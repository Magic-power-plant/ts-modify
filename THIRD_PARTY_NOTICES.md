# Third-Party Notices

This project is licensed under the Apache License, Version 2.0. That license
applies to TS Modify's original source code and project files unless a file or
directory carries a different copyright or license notice.

The Apache License, Version 2.0 is intended to grant broad rights to use, copy,
modify, distribute, sublicense, and commercially use this project. It does not
relicense third-party projects. Third-party code, libraries, assets, tools, and
Minecraft/Forge/KubeJS dependencies remain subject to their own licenses and
terms.

## Bundled Runtime Library

TS Modify bundles the unmodified `docs/Translanguage-2.0.6.jar` through Forge
Jar-in-Jar. The version and SHA-256 are pinned in `gradle.properties` and checked
by `verifyTranslanguage` before compilation and packaging.

- Library: Translanguage 2.0.6
- Copyright: Copyright (c) 2026 gchdljsygch
- License: MIT License (the complete license is `META-INF/LICENSE` inside the JAR)
- SHA-256: `40ff977493cbaac411c09f5f263c5c2974159fff501f7dad3515491c358ca8c4`
- Notices: `META-INF/THIRD_PARTY_NOTICES.md` inside the JAR, preserved verbatim
- The JAR is a pure-Java library. Its notices distinguish reference/test-only
  ANTLR, Closure, Acorn and documentation assets from production dependencies;
  those components are not separately bundled by TS Modify.

The former vendored swc4j source, Rust/SWC WebAssembly bridge, and Chicory
runtime libraries have been removed from the product. Ignored personal reference
checkouts, if present, are not build inputs or distributed components and retain
their own upstream licenses.

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
