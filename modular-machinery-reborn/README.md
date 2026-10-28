# Modular Machinery: Community Edition Reborn

Minecraft **1.20.1 / Forge / Java 17** 的自定义多方块机械框架。当前源码 **0.121.0**，通信协议 **12**。功能介绍、联动模组清单和致谢见[项目主页](../README.md)。

## 教程入口

| 内容 | 文档 |
|---|---|
| 安装与文件目录 | [安装与重载](docs/安装与重载.md) |
| 从零创建机器 | [使用说明](docs/使用说明.md) · [完整入门示例](examples/docs-quickstart/README.md) |
| JSON／KubeJS 加工配方 | [配方指南](docs/KJS-配方指南.md) |
| 使用与升级注意事项 | **[注意事项与限制](docs/已知限制.md)** |
| 进阶、联动与英文资料 | [全部教程](docs/README.md) · [English](docs/en/README.md) |

## 新增创作工具 / New creator tools

| 功能 | 主要能力 | 入口与教程 |
|---|---|---|
| **结构构造工具增强 / Enhanced structure tool** | 框选、增减、撤销、材料与 NBT 筛选，完整机器 JSON／KubeJS 导出 | 手持工具潜行右键；[中文](docs/建造选择工具.md)／[English](docs/en/Construct-tool.md) |
| **可视化配方编辑器 / Visual recipe editor** | 资源表单、JEI 拖入、NBT／原文编辑、校验预览，JSON／KubeJS 导出 | `/mm-recipe_editor`；[中文](docs/可视化配方编辑器.md)／[English](docs/en/Visual-recipe-editor.md) |

两项首版均包含在 0.121.0，需要创造模式和权限等级 2。导出文件由服务端保存，安装后才会生效。离线回归与正式构建已通过，游戏内 GUI、拖拽和多人联机仍待验收。

Both creator tools are included in 0.121.0. Creative mode and permission level 2 are required. See the English guides above for server exports and installation; gameplay acceptance remains pending.

## 使用前

- JAR 不内置机器和机器加工配方，请使用教程或仓库示例自行定义。
- 客户端与服务端同时更新；新增专属控制器需要重启，定义修改按教程重载。
- 可选联动需要对应模组及其依赖。源码推送与二进制 GitHub Release 是两个步骤，下载以实际 Release 为准。

[GPL-3.0 许可证](LICENSE) · [来源、致谢与资源声明](NOTICE.md) · [开发与构建](docs/开发/README.md) · [历史归档](docs/归档/README.md)。
