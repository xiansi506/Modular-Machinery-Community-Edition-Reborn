# 魔法资源最小示例 / Minimal magic resource examples

适用于 Reborn **0.118.0 / Minecraft 1.20.1 / Forge**，客户端与服务端协议 **7**。这些是供作者测试的可选内容，不随模组 JAR 加载。

每个目录都是独立数据包，仅在安装相应魔法模组及其依赖后选用：

| 目录 | 机器 | 每次加工 |
|---|---|---|
| `botania-datapack` | `magic_demo:botania` | 1 圆石 + 1000 魔力 → 1 石头 |
| `naturesaura-datapack` | `magic_demo:naturesaura` | 1 圆石 + 100 主世界灵气 → 1 石头，扣除后不低于 0 |
| `bloodmagic-datapack` | `magic_demo:bloodmagic` | 1 圆石 + 100 LP → 1 石头 + 0.25 默认恶魔意志，生成后不高于 100 |

复制选中的整个 `*-datapack` 文件夹到世界 `datapacks/`，执行 `/reload`。使用通用机器控制器和对应蓝图搭建；三个示例都不强制蓝图，但蓝图可以帮助看清结构。不要把父目录当成一个数据包。

以控制器为 `(0,0,0)`，控制器朝北时：西侧 `(-1,0,0)` 放物品输入仓，东侧 `(1,0,0)` 放物品输出仓，下方 `(0,-1,0)` 放对应的魔力／灵气／LP 输入仓。血魔法示例额外在控制器上方 `(0,1,0)` 放意志输出仓。控制器旋转时结构跟随旋转。这些示例不消耗 FE。

向魔力输入仓发送魔力束；灵气示例在有主世界灵气的环境运行；LP 输入仓先插入绑定血球，并向其灵魂网络补充 LP。最后放入圆石。血魔法生成的意志进入输出仓所在的真实区块，不是存进物品槽。配方时间为 100 tick。

移除示例时，删除对应数据包并 `/reload`。不要在缺少相应模组时继续加载其示例。示例已按解析器离线核对，尚未在游戏中搭建验收。

Each `*-datapack` folder is a standalone optional data pack. Install its named magic mod and dependencies, copy that folder into the world’s `datapacks/`, then `/reload`. Do not install the parent folder as a pack.

With a north-facing generic controller at the origin, put item input at `(-1,0,0)`, item output at `(1,0,0)`, and the selected magic input hatch at `(0,-1,0)`. Blood Magic also needs a Will output hatch at `(0,1,0)`. Supply mana bursts, available overworld Aura, or a bound Blood Orb with LP in its owner’s SoulNetwork, then insert cobblestone. Each recipe takes 100 ticks and uses no FE. Blood Magic produces Will into the real chunk. Blueprints are optional for forming these examples.

These recipes are demonstration content, not conversions imported from third-party machines. Parser checks are offline; in-game construction and resource behavior remain to be verified. Remove the selected pack and reload to uninstall it.

[完整中文说明](../../docs/魔法资源.md) · [English guide](../../docs/Magic-resources.md)
