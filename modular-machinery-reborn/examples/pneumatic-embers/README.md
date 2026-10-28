# 气动工艺与余烬教学包

适用 Reborn 0.119.0 / Minecraft 1.20.1 / Forge，协议 9。两个示例可独立安装：

- `pneumatic-datapack/`：需要 PneumaticCraft: Repressurized 6.0.23 及依赖。包含一次性耗气的圆石→石头，以及持续耗气的石头→平滑石。
- `embers-datapack/`：需要 RCXcrafter 的 Embers Rekindled 1.20.1-1.4.7 及依赖。圆石→石头消耗 100.5 Ember。
- `kubejs/`：同等内容的替代服务器脚本。需要 KubeJS 6；只选数据包或相应脚本，避免同 ID 重复。

数据包整个目录复制到 `<世界>/datapacks/`，确保包根直接有 `pack.mcmeta` 和 `data/`。KubeJS 脚本放实例的 `kubejs/server_scripts/`。`/reload` 后检查日志，使用机器蓝图预览搭建；相对控制器位置为：左侧物品输入仓、右侧物品输出仓、下方对应资源输入仓。仓口必须成型。

气动示例需要先连接管道供压；第一条配方扣费前至少 4.5 bar，第二条每 tick 都要求至少 4.5 bar。余烬示例需要接收器连接真正的 Ember 传输网络。两者都不能只放仓口而不供资源。

这些是配方格式示例，未随 JAR 自动加载。离线解析／接口验证不代表完整实机通过。完整字段、输出方法和验证边界见 [中文说明](../../docs/气动工艺与余烬.md)／[English](../../docs/PneumaticCraft-and-Embers.md)。
