# Neo Voxy · 多版本

[English](README_EN.md)

Neo Voxy 由 **JohnSnow** 维护，在 [NHblock714/voxy](https://github.com/NHblock714/voxy) 的基础上继续维护多版本、客户端优化与可选模组兼容。

> [!IMPORTANT]
> 更新 Neo Voxy 后若出现异常，请先删除 Neo Voxy 配置文件和对应存档中的 Voxy 缓存，再重新进入世界。
>
> **构建产物说明**：构建产物已被我人为删除。需要获得 JAR 时，请直接 Fork 本仓库，
> 在 GitHub Actions 中下载自动构建产物，或按构建教程手动构建。
>
> **服务器数据共享**：Neo-Voxy 与 **[voxy-server-side]([https://github.com/NHblock714/voxy](https://www.curseforge.com/minecraft/mc-mods/voxy-server-side-forge-neoforge))** 完全兼容，推荐在服务器端部署它
> 以共享 LOD 数据。
>
> **前置与兼容层**：Neo-Voxy 仅需要对应平台的 **Sodium**（NeoForge）或 **Embeddium**（Forge）
> 作为必要渲染前置；Sinytra Connector 与 **Forgified Fabric API** 不是本项目依赖，且不推荐安装，
> 以免其兼容层与原生 NeoForge/Forge 注入发生冲突。


## 支持版本

| 版本 | 安装位置 | 渲染依赖 | Java | 发布文件 |
|---|---|---|---:|---|
| 1.21.1 NeoForge 联动版 | 客户端与服务端 | Sodium 0.8 / Iris | 21 | `neo-voxy-0.4.6-mc1.21.1-neoforge-integrations.jar` |
| 1.21.1 NeoForge 纯客户端版 | 仅客户端 | Sodium 0.8 / Iris | 21 | `neo-voxy-0.3.0-mc1.21.1-neoforge-client.jar` |
| 1.20.1 Forge 纯客户端版 | 仅客户端 | Embeddium / Oculus | 17 | `neo-voxy-0.3.3-mc1.20.1-forge-client.jar` |
| 26.1.2 NeoForge 纯客户端版 | 仅客户端 | Sodium 0.9.1 / Iris 1.11.2+ | 25 | `neo-voxy-0.3.3-mc26.1.2-neoforge-client.jar` |

最终发布 JAR 会剔除不需要的平台原生库、重复模块描述符和构建中间文件；运行所需的着色器、语言、模型与存储依赖不会删除。

## 功能与兼容性对比

| 功能、模组或组件 | 1.21.1 联动版 | 1.21.1 客户端版 | 1.20.1 客户端版 | 26.1.2 客户端版 | 说明 |
|---|:---:|:---:|:---:|:---:|---|
| 地形 LOD、多级细节与持久缓存 | ✅ | ✅ | ✅ | ✅ | 基础远景功能 |
| Sodium / Embeddium 设置界面集成 | ✅ | ✅ | ✅ | ✅ | Neo Voxy 设置入口 |
| Iris / Oculus 光影管线 | ✅ | ✅ | ✅ | ✅ | 1.20.1 已支持光影；不同光影包的适配程度可能不同 |
| 环境雾、天空雾与流体修复 | ✅ | ✅ | ✅ | ✅ | 包含水下等必要介质遮罩 |
| 圆形 LOD 淡入 | ✅ | ✅ | ❌ | ✅ | 1.20.1 不考虑加入；其他版本在光影自带 LOD 过渡时建议关闭 |
| 地面植物交叉模型 | ✅ | ✅ | ✅ | ✅ | 居中的轻量交叉模型 |
| 树叶 LOD 模式 | ✅ | ✅ | ✅ | ✅ | 性能、平衡与质量模式 |
| 扩展区块请求（单人，上限 48） | ✅ | ✅ | ✅ | ✅ | 默认关闭 |
| LOD 群系水色混合 | ✅ | ✅ | ✅ | ✅ | 构建阶段完成 |
| LOD 构建压力控制 | ✅ | ✅ | ✅ | ✅ | 可侧重帧率或追赶速度 |
| 世界曲率 | ✅ | ✅ | ✅ | ✅ | GPU 顶点阶段实现 |
| 远距离信标光束 | ✅ | — | — | — | 无限距离连续宽度曲线、光影阴影与无缝交接 |
| 远距离玩家、乘骑物与动画 | ✅ | — | — | — | 联动版专项功能 |
| Sodium / Iris | ✅ | ✅ | — | ✅ | 26.1.2 仅以 Sodium 0.9.1 为兼容目标，Iris 使用 1.11.2+ |
| Embeddium / Oculus | — | — | ✅ | — | Embeddium 为渲染后端，Oculus 提供光影支持 |
| Create | ✅ | — | — | — | 远景列车、轨道、动态结构与动力部件 |
| Sable | ✅ | — | — | — | 远景物理结构及深度兼容 |
| Ecliptic Seasons | ✅ | — | — | — | 远景季节积雪 |
| Domum Ornamentum | ✅ | — | — | — | 完全兼容：精细独立模型、材质与持久缓存 |
| FramedBlocks | ✅ | — | — | — | 主/副伪装、特殊形状数据与远景材质持久缓存 |
| LittleTiles | 🧪 | — | — | — | 初步兼容：静态结构使用持久化的 1/8 格轻量 LOD 网格 |
| PowerGrid | ✅ | — | — | — | 完全兼容：悬垂电线使用持久化的简化悬链线 LOD 网格 |
| Simulated / Create Aeronautics | 🧪 | — | — | — | 激光指示器光束可在方块实体距离外继续渲染 |

`✅` 表示支持，`🧪` 表示初步兼容，`—` 表示未包含专项功能或不适用；后者不代表基础地形 LOD 一定与该模组冲突。专项联动仅在对应模组已安装时启用。Create、Sable 与节气兼容来自 **NHblock**。

### 主要可调功能

- 地面植物与树叶：四个版本都提供居中的交叉植物 LOD，以及性能、平衡、质量三种树叶模式。平衡模式剔除隐藏内部面并保留稳定、不对称的镂空；所有树叶均跳过透明淡入，直接在原版与 LOD 模型间交接，避免先消失再重现。
- 扩展区块请求：基于 [FakeSight](https://github.com/MoePus/fakesight) 的思路，让 Voxy 在单人游戏中请求原版距离外的区块。四个版本均默认关闭、上限 48 区块；移动时暂停扩张，静止后缓慢增加请求半径。高距离仍会显著增加 CPU、内存、世界生成与存档负载。
- 群系水色混合：四个版本都可在模型构建阶段平滑群系交界处的 LOD 水色；结果写入紧凑调色板。
- LOD 构建压力：可在“最高帧数”到“最高追赶”之间调节每帧节点处理与模型烘焙预算。
- 圆形 LOD 淡入：包含该功能的版本使用三维相机距离和世界坐标稳定抖动，在原版区块与 LOD 之间交接；水体与树叶采用各自的无透明渐变交接策略。若光影自身已有 LOD 过渡（例如 Photon），应关闭此功能，避免双重过渡、噪点或阴影边界。
- 雾气与效果：1.20.1 与 1.21.1 的无光影雾按 LOD 半径计算，并保留水下等必要介质遮罩；失明和黑暗生效时不会让远景 LOD 穿透效果范围。26.1.2 使用新版原生雾管线。
- 模型与水体质量：逐面独立生成 mip、按像素记录染色掩码，改善草侧面与含水植物错染；降采样使用就近取整，水体拥有独立边界和群系色调处理。
- 实验性 LOD 轻量光照：1.21.1 联动版可使用成对的 Lite 程序；默认关闭，加载或编译失败、版本不匹配及过渡条件不安全时自动整套回退。Eclipse Shader 482 由 NeoVoxy 内置补丁支持，无需修改光影包；Complementary Unbound r5.8.1 + Euphoria Patches 1.9.3 使用独立 overlay。
- 细分尺寸：控制屏幕空间触发更细 LOD 的阈值；数值越小画质越高，构建和渲染开销也越高。
- 世界曲率：四个版本均可在 GPU 顶点阶段弯曲原版距离外的 LOD；0 为关闭。
- 远景信标：联动版以缓存列生成光柱；靠近时确认原版光柱已就绪后再交接，极远距离使用平缓且无硬上限的宽度曲线，并在 Iris 阴影阶段提交轻量阴影模型。
- PowerGrid 远景电线：联动版会保存玩家见过的悬垂电线，在实体跟踪范围外按原端点和线长重建简化悬链线；已视为完全兼容，可单独开关并限制距离。
- FramedBlocks：联动版在区段摄取时把方块实体的主/副伪装、强化状态、可折叠偏移及其他模型数据登记为持久化变体；LOD 模型烘焙时恢复完整 `ModelData`，并使用伪装方块的染色来源。
- Simulated 远景激光：联动版捕获激光指示器的动态方向、长度、颜色与红石强度，在原版方块实体距离外以专用半透明阶段继续渲染；可单独开关并限制距离。
- 进服消息：每次进入服务器或单人世界显示版本提示，默认开启，可在 Sodium/Embeddium 的 Neo Voxy 配置中关闭。

## 构建

Windows 单独构建：

```powershell
.\scripts\build.ps1 integrations-1.21.1
.\scripts\build.ps1 client-1.21.1
.\scripts\build.ps1 client-1.20.1
.\scripts\build.ps1 client-26.1.2
```

构建全部版本：

```powershell
.\scripts\build-all.ps1
```

脚本优先读取 `JAVA_HOME_17`、`JAVA_HOME_21` 与 `JAVA_HOME_25`。Linux/macOS 可使用 `scripts/build.sh` 和 `scripts/build-all.sh`。最终产物统一复制到 `dist/`；GitHub Actions 会并行构建四个版本并发布一个 `neo-voxy-multiversion` 整合产物。

## 开发说明

部分代码使用了 AI 辅助完成，发布前会由维护者审核并进行必要的小幅修改；相较完全手写，
这能显著提高开发效率。源码注释保持简洁，打包的第三方库保留其原许可证。

## 许可证

请查看各版本随附的许可证文件。
