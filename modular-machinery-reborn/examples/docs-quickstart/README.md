# 0.107.3 文档入门示例

本目录对应 [使用说明](../../docs/使用说明.md) 和 [配方指南](../../docs/KJS-配方指南.md)。示例为本项目独立编写；先用创造模式测试。文件和接口经过离线核对，本轮未进游戏逐步验收。

## 安装：二选一

1. **数据包**：把整个 `datapack` 文件夹复制到 `<世界>/datapacks/`，可重命名为 `mm_quickstart`；确保 `pack.mcmeta` 直接位于这个文件夹内。执行 `/reload`。
2. **KubeJS**：安装 KubeJS 及其依赖，把 `kubejs/mm_quickstart.js` 复制到 `<实例>/kubejs/server_scripts/`，执行 `/reload`。不要同时安装上面的等价数据包。

两种方式都注册机器 `tutorial:stone_processor` 和配方 `tutorial:stone`。通用控制器朝北，西边物品输入仓、东边物品输出仓、下方能源输入仓；给输入仓圆石，给能源仓供 FE，输出留空。单并行每 100 tick 消耗 1 圆石和 2000 FE，产出 1 石头。

结构文件使用仓口的**方块 ID**，接受该家族任意等级；创造栏里的等级物品名称／ID可能不同。取得蓝图可用 `/mm-get_blueprint tutorial:stone_processor`。

需要专属控制器时，另外把 `claims/stone_processor.json` 复制到 `config/modular_machinery_reborn/machinery/`，在两端重启。该文件只声明控制器，不代替数据包或脚本的完整结构。

扩展时保持基础例子可运行，再加并行、催化剂和适配器；具体字段及限制见配套指南。
