# TS Modify

TS Modify 是一个面向 Minecraft Forge 1.20.1 / KubeJS 的辅助 Mod，目标是在不改变 KubeJS 常规脚本目录和加载流程的前提下，让 KubeJS 脚本能够使用 TypeScript 编写，并补充 KubeJS 流体配方处理中对流体标签输入的支持。

本项目当前的 Mod ID 为 `tsmodify`，版本见 `gradle.properties`（当前 `0.94`），许可证为 Apache License 2.0。

## 项目目的

KubeJS 已经能够在 `kubejs/startup_scripts`、`kubejs/server_scripts`、`kubejs/client_scripts` 中发现 `.ts` 文件，但这些文件最终会被交给 Rhino 执行。Rhino 不能直接执行 TypeScript 类型标注、接口、枚举、`satisfies`、静态 `import` / `export` 等语法，因此 TypeScript 脚本必须先转换为 Rhino 可接受的 JavaScript。

TS Modify 解决的是这个运行时兼容问题：让整合包或脚本开发者可以在 KubeJS 脚本中使用 TypeScript 的类型辅助和部分现代语法，同时在运行游戏时自动转译为 KubeJS/Rhino 可以执行的 JavaScript。

## 功能实现

### KubeJS TypeScript 运行时转译

项目通过 Mixin 注入 `dev.latvian.mods.kubejs.script.ScriptFileInfo#preload()`。当 KubeJS 读取脚本文本时，`ScriptFileInfoMixin` 会把脚本内容交给 `TypeScriptTranspiler`：

- `.js` 文件保持原样；
- `.d.ts`、`.d.mts`、`.d.cts` 声明文件不参与执行；
- `.ts`、`.tsx`、`.mts`、`.cts` 会被转译。

运行时转译使用本地 `docs/Translanguage-2.0.6.jar`。Translanguage 是纯 Java 17 引擎，由 Forge Jar-in-Jar 随 Mod 一起分发；构建会校验 `gradle.properties` 中锁定的 SHA-256。无需 Rust、Cargo、WebAssembly 或平台相关 native library。

针对 KubeJS 运行环境，转译后还会执行以下兼容处理：

- 将静态 `import` / `export` 转换为 TS Modify 的运行时模块加载器调用；
- 为每个 `.ts` 文件建立独立模块作用域，减少不同脚本文件之间的辅助变量和函数名冲突；
- 在同一脚本域内支持相对路径模块引用，例如 `server_scripts` 内 import `server_scripts` 内的文件；
- 支持 `kubejs/shared_scripts` 作为 server/client 都可读取的共享源码目录，模块会在各自脚本上下文中分别执行和缓存；
- 输出目标为 ES5，使用 Translanguage 的 KubeJS/Rhino 预设处理循环初始化、函数名属性和类降级，并保留现有兼容性后处理；
- 在链式调用后接 TypeScript 类型断言的场景中补充分号保护，避免转译前后语义被错误拼接。
- ProbeJS 生成声明时，如果某个 Mod 的泛型父类或接口引用了当前环境缺失的可选 API，会退回原始类型元数据继续生成，避免整次 dump 中断并保留 `Java.loadClass(...)` 已捕获类的声明。

更详细的 TypeScript 兼容说明见 [docs/kubejs-typescript.md](docs/kubejs-typescript.md)。有关 SpongePowered Mixin 的通用工作原理、校验机制和安全边界见 [docs/mixin.md](docs/mixin.md)，KubeJS 方法钩子的用法和安全限制见 [docs/kubejs-mixin.md](docs/kubejs-mixin.md)。

### Rhino 脚本加载优化

TS Modify 会缓存未变更 KubeJS 脚本编译后的 Rhino `Script` 字节码，减少脚本重载时重复解析和编译 JavaScript 的开销。`ScriptFileMixin` 将 KubeJS `ScriptFile.load()` 的 `Context.evaluateString(...)` 调用重定向到 `RhinoScriptCompilationCache`；`ContextEvaluateStringMixin` 也会将普通、无安全域的 `Context.evaluateString(...)` 内部编译调用接入同一缓存。

- 缓存命中时仅复用已编译的 `Script`，仍在当前 Rhino 上下文和作用域中执行；不会缓存或共享 KubeJS 作用域、模块导出对象及 JavaScript 函数实例，因此 server/client 脚本上下文保持隔离；
- 缓存按脚本位置、起始行号、严格模式和观察者计数模式索引，并再次校验源码内容；源码变更会替换原有条目；
- 默认保留 512 个最近使用的条目。可通过 JVM 属性 `tsmodify.rhino.scriptCache.maxEntries` 调整容量，设置为 `0` 可禁用缓存保留。

在 Java 17 的独立基准中，128 个普通 JavaScript 脚本的预热重载中位数由 `46.624 ms` 降至 `7.672 ms`，延迟降低 `83.54%`，相对速度提升 `507.69%`，超过“至少 50%”的验收门槛。该结论只适用于已预热的 KubeJS 风格 Rhino 脚本重载；首次冷加载、任意解释器循环、事件回调、游戏刻、磁盘 I/O 与 TypeScript 转译不在此性能承诺范围内。

测试方法和缓存行为见 [docs/rhino-performance.md](docs/rhino-performance.md)，详细执行方案见 [docs/rhino-performance-execution.md](docs/rhino-performance-execution.md)，实际 Java 17 测量报告见 [docs/rhino-performance-report.md](docs/rhino-performance-report.md)。

### 项目级 TypeScript 转译工具

除了 KubeJS 运行时模式，项目还提供 `com.pingsu.tsmodify.typescript.project.TypeScriptProjectTranspiler`。它可以递归读取一个 TypeScript 源目录，将结果输出到另一个目录：

- `.ts` / `.tsx` 输出为 `.js`；
- `.mts` 输出为 `.mjs`；
- `.cts` 输出为 `.cjs`；
- `.d.ts`、`.d.mts`、`.d.cts` 会被跳过；
- 非 TypeScript 文件会复制到输出目录；
- 相对 TypeScript 模块引用会改写为对应的 JavaScript 输出扩展名；
- 输出目录不能等于源目录，也不能位于源目录内部。

命令行入口格式：

```text
TypeScriptProjectTranspiler <sourceRoot> <outputRoot> [--continue]
```

### 流体标签输入支持

项目注册了 KubeJS 插件 `TsmodifyKubeJSPlugin`，向脚本绑定 `FluidIngredient`。`FluidIngredientJS` 支持把流体标签、流体栈、JSON、Map 等输入统一包装为 KubeJS `InputFluid`。

`RecipeJSMixin` 注入 KubeJS `RecipeJS` 的流体输入读写逻辑，使配方脚本可以优先识别并序列化 `FluidIngredientJS`。这使脚本可以表达基于流体标签的输入，而不只局限于具体流体栈。

示例风格：

```js
const waterLike = FluidIngredient.tag("#minecraft:water", 1000)
```

### Java 基本类型工具

JavaScript 只有一个 `number` 类型，调用存在重载的 Java API 时无法主动
区分 `long`、`int`、`float` 等类型。TS Modify 在每个 KubeJS 脚本作用域中
注册了 `LONG`、`INT`、`SHORT`、`BYTE`、`FLOAT`、`DOUBLE`、`CHAR` 和
`BOOLEAN` 工具：

```ts
const amount = LONG.of("9223372036854775807")
const index = INT.of(3)
const flags = BYTE.array([1, 0, 1])
```

这些工具返回对应的 Java boxed value；调用 Java 重载方法时，TS Modify 会保留
工厂指定的 primitive 类型，Rhino 随后会自动拆箱。
整数转换会检查是否为整数及是否越界；需要精确表达超过 JavaScript 安全整数范围
的 `long` 时请传入十进制字符串。`BOOLEAN` 接受 `true` / `false`、`0` / `1`
及对应的字符串形式。`array` / `arrayOf` 会创建真正的 Java 原生数组，便于传给
`long[]`、`int[]` 等参数。

## 构建与运行

项目使用 Gradle ForgeGradle，目标 Java 版本为 17。

常用命令：

```powershell
.\gradlew.bat test
.\gradlew.bat build
.\gradlew.bat runClient
```

构建只需 JDK 17、Gradle Wrapper 和项目的 Forge/KubeJS 依赖。请保留本地引擎
`docs/Translanguage-2.0.6.jar`；版本和 SHA-256 由 `gradle.properties` 管理。
`verifyTranslanguage` 在编译和资源处理前验证该文件，防止同版本二进制被意外替换。

```powershell
.\gradlew.bat --no-daemon compileJava
.\gradlew.bat --no-daemon build
```

发行时使用 `build/libs/tsmodify-<版本>-all.jar`，其中包含完整的 Translanguage JAR
及其原始许可证和第三方声明。无 `-all` 后缀的开发 JAR 不内嵌引擎。
旧 `compileSwc4jWasm`、`transpileBridgeBenchmark` 任务及 `skipSwc4jWasm` 属性已移除。

构建仅使用当前引擎，历史文档和参考文件继续保留。`build/` 可通过 `clean` 再生成；
大规模压力语料 `test/rhino-stress-scripts/` 可通过
`scripts/generate-rhino-stress-corpus.ps1` 再生成，不纳入版本控制。
`run/` 中的世界、配置和脚本以及个人 `.idea/` 配置不属于清理目标。

## 使用到的其他项目代码

完整第三方说明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。当前仓库中与第三方代码直接相关的部分包括：

- `docs/Translanguage-2.0.6.jar`：Translanguage 2.0.6，MIT License，Copyright (c) 2026 gchdljsygch。原始 `META-INF/LICENSE` 和 `META-INF/THIRD_PARTY_NOTICES.md` 保留在嵌套 JAR 中。
- 本次迁移已移除 vendored swc4j 源码、Rust/WASM 桥和 Chicory 打包依赖。
- 个人 `Javet/` 等参考 checkout 不参与构建或分发。
项目还依赖 Minecraft Forge、KubeJS、Rhino、Architectury、ProbeJS、SpongePowered Mixin、JUnit 以及它们的传递依赖。这些依赖不因本项目的 Apache License 2.0 而被重新授权，重新分发时应遵守各自上游项目的许可证、条款和 Minecraft/Forge/KubeJS 生态的分发规则。

## 许可证

TS Modify 的原创源码和项目文件默认按 Apache License 2.0 授权，除非具体文件或目录带有不同版权或许可证声明。第三方源码、参考 checkout、依赖库和生成产物仍受各自上游许可证约束。
