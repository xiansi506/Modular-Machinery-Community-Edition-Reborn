# Modular Machinery: Community Edition Reborn

**Modular Machinery: Community Edition（1.12.2）在 Minecraft 1.20.1 / Forge 上的移植版。**

它是一个**框架**：JAR 不内置机器定义与机器加工配方，机器和配方由整合包作者或玩家通过数据包、配置目录或 KubeJS 提供。

## 当前状态（2026-10-27）

- **源码 0.118.6**，Minecraft 1.20.1 / Forge / Java 17。
- JEI 自然灵气需求改用 gugu-utils 风格的 6×80 绿色竖条，支持输入／输出、从下向上填充、超刻度“＋”及整条悬停提示。
- 新增 29 项专项（另含 200 组布局场景），相关既有 2951 项，共 2980 项零失败；生产 build/reobf、203 JSON 和中英各 427 键检查通过。
- 协议保持 8，配方格式与结算不变，不需要安装 gugu-utils；画面待实机验收。本次 Git 提交汇总 0.118.4–0.118.6 源码、教程和归档说明，未部署游戏或创建 GitHub Release。
- [显示说明](modular-machinery-reborn/docs/魔法资源.md) · [修复记录](modular-machinery-reborn/docs/更新记录-0.118.6.md) · [归档](releases/modular-machinery-reborn/v0.118.6/README.md) · [交接](modular-machinery-reborn/docs/交接-2026-10-23.md)。

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

正式产物是 **`modular-machinery-reborn/build/reobfJar/output.jar`**，不是 `build/libs` 下的开发期 jar。

`gradle.properties` 默认 `use_local_deps=false`，走 Maven 依赖。离线构建需要把本地依赖 jar 放进 `modular-machinery-reborn/libs/` 并设置 `use_local_deps=true`；这些 jar 不随本仓库分发。

## 验证边界

- 离线复现器位于 [`_audit/m6c-verify/`](_audit/m6c-verify/)，它不是构建的一部分，但改动逻辑时应连带运行。
- 0.118.0 的离线证据见 [本轮更新](_audit/magic-0.118.0/本轮更新.md)；完整套件与构建日志保留在本机，不随仓库提交。
- **未验证**：真实游戏内运行、真实 AE2 网络与样板供应器、多人联机与断线重连、JEI 页面与模型渲染、真实存档升级、工厂长期运行。

## 发布与归档

- 二进制 jar 通过 **GitHub Releases** 分发，**不入 Git 历史**。
- 版本索引、SHA-256 与各版本说明见 [releases/modular-machinery-reborn/README.md](releases/modular-machinery-reborn/README.md)。
- 0.108.0–0.118.0 已有本地归档目录；在创建 GitHub Release 之前，请不要把“仓库中存在版本目录”理解为“已公开发布”。

## 许可证与来源

**GPL-3.0**（见 [LICENSE](modular-machinery-reborn/LICENSE)）。

本模组是 **Modular Machinery: Community Edition**（上游为 GPL-3.0）的移植版；因上游采用 copyleft 许可证，本移植版同样以 GPL-3.0 发布。来源、修改声明与第三方依赖见 [NOTICE.md](modular-machinery-reborn/NOTICE.md)。
