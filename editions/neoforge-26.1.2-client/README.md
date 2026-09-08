# Neo-Voxy

[简体中文](#简体中文) | [English](#english)

Neo-Voxy is an unofficial NeoForge port and compatibility-focused fork of
[Voxy](https://github.com/MCRcortex/voxy). It currently has separate branches for Minecraft
**1.21.1** and **26.1.2**.

Maintained by **JohnSnow**.

> [!IMPORTANT]
> Build artifacts are intentionally removed. Fork the repository to download the GitHub Actions
> artifact, or build locally by following the build instructions below.
>
> Neo-Voxy is fully compatible with **Voxy Server Side**; server deployment is recommended for
> sharing LOD data.

> [!CAUTION]
> **Do not enable Neo-Voxy's circular LOD fade together with a shader pack that already implements
> its own LOD transition, such as Photon.** Disable **Circular LOD Fade** in Neo-Voxy's Sodium video
> settings page, or the two transitions may cause noise, repeated fading, lighting errors, or a
> visible shadow boundary.

## 简体中文

Neo-Voxy 为远距离地形提供高性能 LOD 渲染，并针对 NeoForge、Sodium、Iris 以及部分大型模组
进行兼容性维护，由 **JohnSnow** 维护。两个 Minecraft 分支分别构建，**JAR 与缓存不能跨版本混用**。

> [!IMPORTANT]
> 构建产物已被人为删除。请 Fork 仓库后从 GitHub Actions 下载，或按下方教程手动构建。
> Neo-Voxy 完全兼容 **Voxy Server Side**，推荐在服务器端部署以共享 LOD 数据。

### 版本与功能对比

| 功能 | 1.21.1 | 26.1.2 | 说明 |
|---|:---:|:---:|---|
| 远距离地形 LOD | ✅ | ✅ | 在原版区块范围外渲染简化地形 |
| NeoForge 原生运行 | ✅ | ✅ | 分别适配对应版本的 NeoForge |
| Sodium 渲染集成 | ✅ | ✅ | 接入区块渲染、深度与设置界面 |
| Iris 光影兼容 | ✅ | ✅ | 最终效果仍取决于具体光影包 |
| 圆形 LOD 淡入 | ✅ | ✅ | 以玩家为中心平滑交接原版区块与 LOD |
| 水体独立交接 | ✅ | ✅ | 水体不使用圆形抖动淡入，减少边界色差和缝隙 |
| Iris 阴影边界协同过渡 | ✅ | ✅ | 复用现有阴影绘制，不增加额外地形批次 |
| 父级 LOD、空洞与移动闪烁修复 | ✅ | ✅ | 改善重载、快速移动和层级转换后的缺块 |
| 保存、退出及存储关闭修复 | ✅ | ✅ | 等待待写数据并安全释放世界资源 |
| 自动关闭 Sodium 区块淡入 | — | ✅ | 避免 26.1.2 原生区块淡入与 LOD 交接叠加 |
| 无光影水体、水下雾和熔岩修复 | ✅ | — | 处理透明度、介质雾、液面高度和岸线衔接 |
| 失明与黑暗效果同步到 LOD | ✅ | — | LOD 使用原版实时限制性雾参数 |
| 树叶 LOD 质量模式 | ✅ | — | 快速、平衡、质量三档 |
| FakeSight 风格扩展区块请求 | ✅ | — | 移动时限流，静止时逐步扩展至目标距离 |
| 远距离玩家与乘骑物 | ✅ | — | 可选轻量快照、名称与位置共享 |
| 专项模组模型兼容 | ✅ | — | 详见下方兼容性表格 |
| 性能诊断命令 | ✅ | — | 包含帧阶段、GPU 标记和阻塞诊断 |

`—` 表示该分支尚未提供专项实现，不代表对应模组一定无法与基础地形 LOD 同时运行。

### 模组兼容性

| 模组或组件 | 1.21.1 | 26.1.2 | 兼容说明 |
|---|---|---|---|
| Sodium | ✅ 0.8.12 | ✅ 0.9.1 | 26.1.2 仅以 0.9.1 为兼容目标 |
| Iris | ✅ 1.8.12+ | ✅ 1.11.2+ | 光影包需要自行适配远景渲染语义 |
| Create | ✅ 6.0.10 专项 | 基础兼容 | 1.21.1 提供远景列车、轨道、动态结构及动力部件交接 |
| Sable | ⚠️ 2.0.3 专项 | 无专项实现 | 保留远景载具加载与距离扩展；高风险联合深度重定向默认关闭 |
| Domum Ornamentum | ✅ 专项 | 无专项实现 | 按方块实体材质数据缓存独立颜色与轻量模型 |
| Supplementaries / Lumisene | ✅ 专项 | 无专项实现 | 专用流体颜色、透明度及表面模型处理 |
| EclipticSeasons | ✅ 0.13.8.4.1 专项 | 无专项实现 | 季节积雪 LOD 与季节变化后的可选重载 |
| bits_n_bobs / Azimuth | ✅ 部分专项 | 无专项实现 | 参与 Create 动力部件的远景快照 |
| EntityCulling | ✅ 协同 | 无专项实现 | 避免远景 Create 实体在交接前被错误剔除 |
| Photon 等自带 LOD 淡入的光影 | ⚠️ | ⚠️ | 必须关闭 Neo-Voxy 的圆形 LOD 淡入 |

所有专项联动均按对应模组是否安装进行门控；未安装时不会注册相关监听器、渲染器或高频任务，
设置界面中的对应选项也会禁用。

### 安装

1. 安装对应版本的 Minecraft、NeoForge 和 Java。
2. 安装表格中对应版本的 Sodium；如需光影，再安装对应 Iris。
3. 将正确分支生成的 Neo-Voxy JAR 放入实例的 `mods` 文件夹。
4. 从其他版本迁移前备份世界与旧 Voxy 缓存。

| 分支 | Neo-Voxy 版本 | Java | NeoForge |
|---|---|---:|---|
| Minecraft 1.21.1 | 0.3.1 | 21 | 21.1.x |
| Minecraft 26.1.2 | 0.3.3 | 25 | 26.1.2.x |

### 开发说明

部分代码使用了 AI 辅助完成，发布前会由维护者审核并进行必要的小幅修改；源码注释保持简洁。

### 构建

```powershell
.\gradlew clean build
```

26.1.2 分支已将发布包裁剪整合为 Gradle 的 `slimJar` 任务。执行 `build` 会自动生成
`build/libs/neo-voxy-0.3.3-mc26.1.2-neoforge-client.jar`，不再需要 Python 或 `tools` 目录。该发布包仅保留
Windows/Linux x86_64 原生库；未带 `-slim` 的大体积 JAR 仅用于构建检查。

1.21.1 的 Create、Sable、EclipticSeasons 等源码联动需要在 `libs/aero-spike` 中提供
对应的编译期 API JAR。依赖只用于编译，不会让这些模组变成运行时强制前置；缺失时 Gradle
会列出所需的准确文件名。

## English

Neo-Voxy provides high-performance distant terrain LOD rendering with focused maintenance for
NeoForge, Sodium, Iris, and selected large mods. The two Minecraft branches are built separately;
**their JARs and caches are not interchangeable**.

### Version and feature comparison

| Feature | 1.21.1 | 26.1.2 | Notes |
|---|:---:|:---:|---|
| Distant terrain LOD | ✅ | ✅ | Simplified terrain outside the vanilla chunk radius |
| Native NeoForge support | ✅ | ✅ | Ported independently for each game version |
| Sodium renderer integration | ✅ | ✅ | Chunk rendering, depth, and settings integration |
| Iris shader support | ✅ | ✅ | Final compatibility still depends on the shader pack |
| Circular LOD fade | ✅ | ✅ | Camera-centred handoff between vanilla chunks and LOD |
| Separate water handoff | ✅ | ✅ | Water avoids the circular dither transition |
| Iris shadow-boundary transition | ✅ | ✅ | Reuses existing shadow draws without another terrain pass |
| Parent LOD, hole, and movement-flash fixes | ✅ | ✅ | Improves reloads, fast travel, and hierarchy refinement |
| Save, shutdown, and storage-close fixes | ✅ | ✅ | Flushes pending writes and releases world resources safely |
| Automatic Sodium chunk-fade disable | — | ✅ | Prevents the 26.1.2 vanilla fade from stacking with LOD handoff |
| No-shader water, underwater fog, and lava fixes | ✅ | — | Transparency, medium fog, fluid height, and shoreline handling |
| Blindness and Darkness on LOD | ✅ | — | Reuses vanilla's live restrictive fog parameters |
| Leaf LOD quality modes | ✅ | — | Fast, Balanced, and Quality modes |
| FakeSight-style extended chunk requests | ✅ | — | Throttled while moving and expanded gradually while stationary |
| Distant players and ridden vehicles | ✅ | — | Optional lightweight snapshots, names, and position sharing |
| Dedicated mod model integrations | ✅ | — | See the compatibility table below |
| Performance diagnostics | ✅ | — | Frame stages, GPU markers, and stall diagnostics |

`—` means that no dedicated implementation exists in that branch. It does not necessarily mean the
mod cannot run alongside basic terrain LOD rendering.

### Mod compatibility

| Mod or component | 1.21.1 | 26.1.2 | Compatibility notes |
|---|---|---|---|
| Sodium | ✅ 0.8.12 | ✅ 0.9.1 | 26.1.2 targets 0.9.1 only |
| Iris | ✅ 1.8.12+ | ✅ 1.11.2+ | Shader packs must understand distant-terrain rendering semantics |
| Create | ✅ 6.0.10 integration | Basic only | 1.21.1 adds distant trains, tracks, contraptions, and kinetic handoff |
| Sable | ⚠️ 2.0.3 integration | No dedicated support | Distant loading/range remains; unsafe combined-depth redirection is off by default |
| Domum Ornamentum | ✅ Integrated | No dedicated support | Cached block-entity material colours and lightweight independent models |
| Supplementaries / Lumisene | ✅ Integrated | No dedicated support | Dedicated fluid colour, transparency, and surface handling |
| EclipticSeasons | ✅ 0.13.8.4.1 integration | No dedicated support | Seasonal snow LOD and optional reload on season changes |
| bits_n_bobs / Azimuth | ✅ Partial integration | No dedicated support | Included in Create kinetic snapshots |
| EntityCulling | ✅ Coordinated | No dedicated support | Prevents premature culling during the Create handoff |
| Photon-like shaders with built-in LOD fade | ⚠️ | ⚠️ | Disable Neo-Voxy's Circular LOD Fade |

Every dedicated integration is gated by mod presence. When the matching mod is absent, Neo-Voxy does
not register its listeners, renderers, or recurring work, and the matching settings are disabled.

### Installation

1. Install the matching Minecraft, NeoForge, and Java versions.
2. Install the matching Sodium version from the table; add Iris if shaders are required.
3. Put the Neo-Voxy JAR from the correct branch into the instance `mods` directory.
4. Back up the world and old Voxy cache before migrating from another version.

| Branch | Neo-Voxy version | Java | NeoForge |
|---|---|---:|---|
| Minecraft 1.21.1 | 0.3.1 | 21 | 21.1.x |
| Minecraft 26.1.2 | 0.3.3 | 25 | 26.1.2.x |

### Development note

Some code was completed with AI assistance and will be audited and lightly edited by the maintainer
before release. Source comments are intentionally concise.

### Building

```powershell
.\gradlew clean build
```

The 26.1.2 branch implements release trimming as the Gradle `slimJar` task. `build` automatically
creates `build/libs/neo-voxy-0.3.3-mc26.1.2-neoforge-client.jar`; Python and the `tools` directory are no longer needed.
The release JAR keeps only Windows/Linux x86_64 natives. The large JAR without `-slim` is retained
only as an intermediate build artifact.

The 1.21.1 source integrations for Create, Sable, and EclipticSeasons require their compile-time API
JARs under `libs/aero-spike`. They remain optional at runtime. If a file is missing, Gradle reports
the exact expected filename.

## Credits and license

Voxy was created by [MCRcortex](https://github.com/MCRcortex). Thanks to the Voxy, Sodium, Iris,
NeoForge, and compatibility-mod communities and testers.

This is an unofficial port and is not affiliated with Mojang, Microsoft, NeoForge, Sodium, Iris, or
the original Voxy author. See [LICENSE.md](LICENSE.md) for the applicable license terms.
