# 常用配方 JSON（0.119.0）

## 完整配方与 ID

```json
{
  "type": "modular_machinery_reborn:machine",
  "machine": "skill_demo:basic_processor",
  "registryName": "stone",
  "recipeTime": 100,
  "max-parallelism": 1,
  "requirements": [
    { "type": "item", "io-type": "input", "item": "minecraft:cobblestone", "amount": 1 },
    { "type": "energy", "io-type": "input", "energyPerTick": 20 },
    { "type": "item", "io-type": "output", "item": "minecraft:stone", "amount": 1 }
  ]
}
```

保存为 `data/skill_demo/recipes/stone.json` 时实际配方 ID 为 `skill_demo:stone`，不是 `registryName`。`machine` 指向机器 `registryname`，也可为非空机器 ID 数组。推荐完整 `namespace:path`；裸机器名按 `modular_machinery_reborn` 命名空间查找。

| 根字段 | 含义 |
|---|---|
| `type` | 必须是 `modular_machinery_reborn:machine` |
| `machine` | 机器 ID 字符串或非空字符串数组 |
| `registryName` | 可选名称，默认配方路径；不决定配方资源 ID |
| `recipeTime` | 正整数 tick，缺省 100；20 tick 约 1 秒 |
| `requirements` | 普通配方为非空需求对象数组 |
| `max-parallelism` | 非负整数；0/省略使用机器上限，1 限定单并行 |
| `priority` | 整数，数值小的层先检查，同层还会按输入权重排序 |
| `cancelIfPerTickFails` | 可选布尔；true 时逐 tick 支付失败会放弃任务，已付材料不会因此退款 |
| `tooltips` | 字符串或字符串数组；可为翻译键 |

`adapter` 声明是特殊情况：需求可省略，声明本身不参与普通匹配。内置只支持 `minecraft:furnace` 导入和机器间复用；请求普通配方时不要误生成适配器声明。根 `modifiers` 是适配器字段，不可当作任意普通配方的通用加速字段。

## 需求通用字段

每条有 `type`、`io-type: "input" / "output"`。常用可选项：

| 字段 | 行为 |
|---|---|
| `selector-tag` | 字符串，限制访问结构中具有同名标签的端口；不是物品标签 |
| `parallelize-unaffected` | 布尔，true 时该需求固定为一份，不参与并行放大和对应并行上限 |
| `script-tooltip` | 单个字符串，不是字符串数组 |
| `trigger-time` | 一次性需求的触发进度；能源和逐 tick 需求不能用，见进阶说明 |

## 物品

```json
{ "type": "item", "io-type": "input", "item": "#forge:ingots/iron", "amount": 2 }
```

```json
{ "type": "item", "io-type": "output", "item": "minecraft:flint", "minAmount": 1, "maxAmount": 3, "chance": 0.25 }
```

`amount` 默认 1，建议正整数；`minAmount`/`maxAmount` 默认各使用 `amount`。输入只写固定 `amount`，范围数量仅用于输出。标签输入匹配其成员，输出用具体 ID 避免标签首个成员随环境变化。旧矿辞只存在有限兼容映射，不据此生成新配方。

`chance` 默认 1，以 0～1 表示。输入未命中时不消耗，检查阶段仍需要可匹配原料；不是概率决定能否开工。并行时按需求整体掷点，不承诺每份独立掷点。输出空间检查可能按最大数量预留。

物品 `perTick: true` 不会把普通物品需求变成逐 tick 输入。不要仿照流体添加该字段。

## 流体与能源

```json
{ "type": "fluid", "io-type": "input", "fluid": "minecraft:water", "amount": 1000, "perTick": false }
```

流体单位 mB，1000 mB 为一桶，数量用正整数。`perTick: true` 才是逐 tick 流体，本版没有 `fluid_pertick` 类型。流体仓输入/输出方向必须匹配。

```json
{ "type": "energy", "io-type": "input", "energyPerTick": 40 }
```

能源恒为逐 tick，`energyPerTick` 用 0～9223372036854775807 的整数。不要写 `amount`、总能耗或试图用 `perTick:false` 变成一次性支付。缺少 `energyPerTick` 时引擎按 0，容易产生免费配方，作者检查工具会将漏写视作错误。

100 tick × 40 FE/t × 1 并行，在无修改器条件下为 4000 FE；N 并行通常需要 N 倍速率。内部使用 long，普通 Forge Energy 单次 API 仍受 int 限制。FluxNetworks、Draconic Evolution 可走相应 long 接口；不要凭空生成 GT/IC2 转换。

## 并行和运行条件

机器 `parallelizable`、`internal-parallelism`、机器 `max-parallelism`、结构并行控制器和配方上限一起约束并行，最终还受可见输入、输出空间和逐 tick 速率限制。仅把配方上限改成 64 不保证一次做 64 份。

普通输入开工付，普通输出完工付，逐 tick 资源按成功结算 tick 付。修改器可改变数量、时长和补偿，不宜只凭基础公式承诺最终总量。

已有机器不能开工时，核对成型、方向、红石、蓝图要求、仓口资源和输出空间，再检查标签和输入组。同 ID 的高优先级机器定义也可能覆盖预期结构。
