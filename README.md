# Modular Machinery: Community Edition Reborn

**Modular Machinery: Community Edition（1.12.2）在 Minecraft 1.20.1 / Forge 上的移植版。**

它是一个**框架**：JAR 不内置机器定义与机器加工配方，机器和配方由整合包作者或玩家通过数据包、配置目录或 KubeJS 提供。

## 可选联动模组

按需要选装对应的 **Minecraft 1.20.1 / Forge** 版本，并满足各模组自身的依赖。气动工艺与余烬是 **0.119.0** 新增联动；请核对所用版本，实际验证范围见下方[验证边界](#验证边界)。

| 模组 | 联动内容 | 接入状态 |
|---|---|---|
| JEI（Just Enough Items） | 机器配方浏览、多方块结构预览、材料表和资源需求显示 | 已接入 |
| KubeJS 6 | 定义机器与配方，提供服务端生命周期事件和客户端 GUI／模型／动画脚本 | 已接入 |
| Applied Energistics 2（AE2） | 统一 ME 输入／输出总线、ME机械样板供应器、ME机械样板镜像及物品／流体自动化 | 已接入 |
| Mekanism | 气体配方、仓口气体存储／传输和 JEI 气体贴图 | 已接入 |
| Applied Mekanistics 1.4.3 | 配合 AE2 与 Mekanism，为 ME 总线和加工样板接入气体 | 已接入 |
| Botania（植物魔法） | 魔力输入／输出仓及魔力网络接口 | 已接入 |
| Nature’s Aura（自然灵气） | 真实环境灵气、类型与阈值控制，以及 JEI 绿色灵气条 | 已接入 |
| Blood Magic 3（血魔法 3） | 绑定血球与 SoulNetwork 的 LP，以及区块恶魔意志资源 | 已接入 |
| Flux Networks（通量网络） | 通过 long 能源接口连接能量仓与 Flux 网络 | 已接入 |
| Draconic Evolution（龙之研究） | 通过 Brandon’s Core 的 OP／long 能源接口连接能量仓 | 已接入 |
| Jade | 仓口能量／流体信息、控制器与工厂线程能量读数 | 已接入 |
| GeckoLib | 自定义控制器模型与动画，全亮、透明及光晕效果 | 已接入 |
| PneumaticCraft: Repressurized（气动工艺） | 压缩空气输入／输出仓、压力／真空条件、一次性及逐 tick 空气需求 | 已接入（0.119.0） |
| [Embers Rekindled（余烬复刻版）](https://github.com/RCXcrafter/EmbersRekindled) | Ember 输入／输出仓、小数能量结算与余烬传输接口 | 已接入（0.119.0） |

## 当前状态（2026-10-28）

- **源码 0.119.1**，Minecraft 1.20.1 / Forge / Java 17，协议 **10**。
- 修复建造选择工具的 NBT 往返、方块状态旋转、右键交互、跨维度选区、失败后丢失选区、区块漏导出和文件名问题。片段改存 `config/modular_machinery_reborn/exports/`。专项 50 项、完整主套件 2668 项及既有集成专项合计 **3003 项零失败**，另有 200 组 JEI 几何场景；Java 17 `clean build --offline --no-daemon`／reobf **45s** 成功，JAR 含 215 个合法 JSON、中英各 441 个键。
- 未启动／部署游戏，真实点击、高亮、多人及跨维度传送仍待实机验收；本次源码提交包含 0.119.1 修复、文档和两项创作者工具计划，未创建 Release。
- 建造选择工具：[中文](modular-machinery-reborn/docs/建造选择工具.md)／[English](modular-machinery-reborn/docs/Construct-tool.md) · [修复记录](modular-machinery-reborn/docs/修复记录-0.119.1.md) · [本地归档](releases/modular-machinery-reborn/v0.119.1/README.md)。

后续开发：[结构构造工具增强与可视化配方编辑器](modular-machinery-reborn/docs/创作者工具计划.md)已列入计划，均待实施。

## 从哪开始

| 你是 | 先读 |
|---|---|
| **想安装并使用** | [使用说明](modular-machinery-reborn/docs/使用说明.md) —— 从空实例到一台能跑的机器 |
| **想知道移植了什么** | [中文简介](modular-machinery-reborn/docs/简介.md) / [English introduction](modular-machinery-reborn/docs/Introduction.md) |
| **要写 KubeJS 机器或配方** | [KJS 配方指南](modular-machinery-reborn/docs/KJS-配方指南.md) |
| **想知道刻意不做什么** | [已知限制](modular-machinery-reborn/docs/已知限制.md) |
| **想改代码 / 看当前状态** | [现行交接](modular-machinery-reborn/docs/交接-2026-10-23.md) |
| **找文档入口** | [文档索引](modular-machinery-reborn/docs/README.md) |
| **看可复制的示例** | [docs-quickstart 示例](modular-machinery-reborn/examples/docs-quickstart/) |
| **看逐版本变更** | [迁移日志](modular-machinery-reborn/迁移日志.md) |

## 三条定义路径

- **数据包 JSON**：`/reload` 即生效。
- **配置目录**：`config/modular_machinery_reborn/machinery/`，重启后机器可获得专属控制器方块。
- **KubeJS 服务器脚本**：`/reload` 即生效。

## 构建

```bash
cd modular-machinery-reborn
./gradlew build
```

`build` 会执行 `reobfJar`；0.119.1 的正式构建产物为 **`modular-machinery-reborn/build/libs/modular_machinery_reborn-0.119.1.jar`**。本地归档使用 `modular-machinery-reborn-0.119.1.jar` 文件名。

`gradle.properties` 默认 `use_local_deps=false`，走 Maven 依赖。离线构建需要把本地依赖 jar 放进 `modular-machinery-reborn/libs/` 并设置 `use_local_deps=true`；这些 jar 不随本仓库分发。

## 验证边界

- 离线复现器位于 [`_audit/m6c-verify/`](_audit/m6c-verify/)，它不是构建的一部分，但改动逻辑时应连带运行。
- 0.119.0 的验证摘要见 [更新记录](modular-machinery-reborn/docs/更新记录-0.119.0.md)；详细 API、回归与构建日志保留在本机 `_audit/pneumatic-embers-0.119.0/`，不随仓库提交。
- **未验证**：真实游戏内运行、真实 AE2 网络与样板供应器、多人联机与断线重连、JEI 页面与模型渲染、真实存档升级、工厂长期运行。

## 发布与归档

- 二进制 jar 通过 **GitHub Releases** 分发，**不入 Git 历史**。
- 版本索引、SHA-256 与各版本说明见 [releases/modular-machinery-reborn/README.md](releases/modular-machinery-reborn/README.md)。
- 已有至 0.119.1 的本地构建归档；Git 中保留版本说明和 SHA-256，二进制下载以实际创建的 GitHub Release 为准。

## 致谢

- 感谢 [Modular Machinery: Community Edition（MMCE）](https://github.com/NovaEngineering-Source/ModularMachinery-Community-Edition) 的 **KasumiNova、youyihj、ikexing** 及所有社区贡献者。社区版的开源代码、资源与功能设计，是本项目进行 1.20.1 移植的基础。
- 感谢原版 Modular Machinery 的 **HellFirePvP** 与美术作者 **wiiv**，为模块化机械提供最初的框架与美术资源。
- 感谢 [咕咕工具（GuGu Utils）](https://github.com/ParaParty/gugu-utils) 的 **WarmthDawn、yuedu233_、Month_Hare** 及派瑞派对团队，提供资源联动与 JEI 显示的开源实现。本项目自然灵气条的绘制／贴图，以及气动工艺、余烬联动的适配，受益于这些工作。

来源、资源使用与许可证说明见 [NOTICE.md](modular-machinery-reborn/NOTICE.md)。

## 许可证与来源

**GPL-3.0**（见 [LICENSE](modular-machinery-reborn/LICENSE)）。

本模组是 **Modular Machinery: Community Edition**（上游为 GPL-3.0）的移植版；因上游采用 copyleft 许可证，本移植版同样以 GPL-3.0 发布。来源、修改声明与第三方依赖见 [NOTICE.md](modular-machinery-reborn/NOTICE.md)。
