# 配方指南：JSON、KubeJS 与高级加工

适用于 **Reborn 0.107.3 / Minecraft 1.20.1 / Forge**，更新于 2026-10-25。保留原文件名，本文同时介绍 JSON 和 KubeJS；先完成 [使用说明](使用说明.md) 的圆石加工机，再添加高级需求。

本文从基础配方开始，依次介绍高级需求、修改器、并行、配方适配器与机器事件。示例使用 Reborn 当前的解析规则和 JSON／KubeJS 接口。

**验证口径**：示例按当前实现核对；本轮未在游戏中逐段执行脚本。代码块标作“片段”时，需要放入指定数组／对象，不能单独当完整配方安装。

## 一、选一种配方入口

| 入口 | 位置 | 特点 |
|---|---|---|
| JSON 数据包 | `data/<namespace>/recipes/<path>.json` | 不需要脚本模组，必须有根 `type` |
| KubeJS raw JSON | `kubejs/server_scripts/*.js` 的 `ServerEvents.recipes` | `event.custom(...)` 接受相同 JSON |
| KubeJS typed 链 | 同上 | 使用本模组 `.machine(id)` 和需求方法 |

修改后 `/reload`，先查 KubeJS 错误和 `logs/latest.log`，再看 JEI 与控制器。每份配方用唯一资源 ID。以下三种示例内容等价，**只选一种**，不要重复安装。

### 完整 JSON

文件 `data/tutorial/recipes/stone.json`，配方 ID 为 `tutorial:stone`：

```json
{
  "type": "modular_machinery_reborn:machine",
  "machine": "tutorial:stone_processor",
  "registryName": "stone",
  "recipeTime": 100,
  "max-parallelism": 64,
  "requirements": [
    { "type": "item", "io-type": "input", "item": "minecraft:cobblestone", "amount": 1 },
    { "type": "energy", "io-type": "input", "energyPerTick": 20 },
    { "type": "item", "io-type": "output", "item": "minecraft:stone", "amount": 1 }
  ]
}
```

### KubeJS raw 写法

```js
ServerEvents.recipes(event => {
  event.custom({
    type: 'modular_machinery_reborn:machine',
    machine: 'tutorial:stone_processor',
    registryName: 'stone',
    recipeTime: 100,
    'max-parallelism': 64,
    requirements: [
      { type: 'item', 'io-type': 'input', item: 'minecraft:cobblestone', amount: 1 },
      { type: 'energy', 'io-type': 'input', energyPerTick: 20 },
      { type: 'item', 'io-type': 'output', item: 'minecraft:stone', amount: 1 }
    ]
  }).id('tutorial:stone')
})
```

### KubeJS typed 写法

```js
ServerEvents.recipes(event => {
  event.recipes.modular_machinery_reborn.machine('tutorial:stone_processor')
    .registryName('stone')
    .time(100)
    .parallelism(64)
    .itemInput('minecraft:cobblestone', 1)
    .energyInput(20)
    .itemOutput('minecraft:stone', 1)
    .id('tutorial:stone')
})
```

typed API 会生成根 `type`；`event.custom` 和手写 JSON 需要自己写。`.id(...)` 设置实际配方资源 ID，`.registryName(...)` 设置对象的名称字段；不要把机器的 `registryname` 写成配方字段。

## 二、根字段和结算时机

| 字段 | 含义 |
|---|---|
| `type` | 固定为 `modular_machinery_reborn:machine` |
| `machine` | 完整机器 ID，或非空 ID 数组；数组表示多台机器共享一份配方 |
| `registryName` | 可选名称，默认使用配方资源路径 |
| `recipeTime` | 基础 tick 数，默认 100，至少 1 |
| `requirements` | 普通配方必须是非空需求数组；适配器声明例外 |
| `max-parallelism` | 本配方并行上限；正整数为明确上限，0／省略交由机器上限决定；只跑单份写 1 |
| `priority` | 整数，默认 0；越小越先尝试，同层再按输入权重排序 |
| `cancelIfPerTickFails` | 默认 false；true 时逐 tick 结算失败放弃本次合成，已付一次性输入不返还 |
| `tooltips` | 作者说明字符串数组，供配方显示 |
| `startCommands` / `processingCommands` / `finishCommands` | 三阶段命令数组，详见第八节 |
| `adapter` / `modifiers` | 配方导入声明专用，详见第七节；不是普通配方的通用根级加速入口 |

**开工**：预留并支付一次性输入、选中的催化剂，冻结本次有效修改器。**运行**：逐 tick 结算能源以及标为逐 tick 的流体／气体。**完工**：结算一次性输出。结构、端口、输出空间和概率共同影响是否推进。

默认逐 tick 缺料／缺能按机器 `failure-action` 处理；设置 `cancelIfPerTickFails: true` 会丢弃这次合成。这个字段不把“输出放不下”或“结构损坏”统称成逐 tick 失败。已经支付输入的合成等待输出时，不应靠反复启动来重复扣料。

机器 `failure-action` 可写 `still`（保留进度）、`reset`（进度归零）、`decrease`（逐次减 1）。它控制失败时进度，不是“退还输入”选项；配方取消本次合成后也不会重新发还原料。

根配方 `type` 必须是当前序列化器 ID。需求内部的 `type` 可以简写为 `item`，内置种类也兼容旧 `modularmachinery:item` 等命名空间，但**这不意味着旧版整个 JSON 或 ZenScript 可以原样加载**。旧物品 ID、元数据、气体名和脚本 API 仍需迁移。

## 三、基础需求

每条需求都要有 `type` 和 `io-type`。`input` / `output` 表示方向；`selector-tag` 可限定结构端口；`parallelize-unaffected: true` 表示该需求不随并行倍增。

### 物品与标签

下面是添加到 `requirements` 的片段：

```json
{
  "type": "item",
  "io-type": "input",
  "item": "#forge:ingots/iron",
  "amount": 2,
  "chance": 1.0
}
```

`#forge:ingots/iron` 匹配物品标签成员，不是一个具体物品。旧 `ore:ingotIron` 等已知矿辞名会转换成标签，但新内容建议直接使用现代标签；不要依赖所有旧矿辞都有等价映射。输出建议写具体物品 ID，避免标签解析出的首个物品随环境变化。

范围产出只用于物品输出，片段如下：

```json
{
  "type": "item",
  "io-type": "output",
  "item": "minecraft:flint",
  "minAmount": 1,
  "maxAmount": 3,
  "chance": 0.25
}
```

数量和概率是不同概念：命中概率后才产生范围数量。输出空间的检查可能比实际掷出的产量保守。输入数量必须固定，不支持用输入的 `minAmount`／`maxAmount` 表示随机扣料。

`chance` 建议明确写 0～1，默认 1。输入未命中时不消耗，但不意味着检查阶段可省略原料；尤其催化剂必须可被选中和预留。结算按需求整体掷点，**不要假定 N 并行会独立掷 N 次**。

### 流体与逐 tick 流体

一次性流体输入片段：

```json
{
  "type": "fluid",
  "io-type": "input",
  "fluid": "minecraft:water",
  "amount": 1000,
  "perTick": false
}
```

`amount` 单位为 mB，1000 mB 为一桶。`perTick: true` 表示每个成功结算 tick 消耗／产出该数量，输出方向改成 `output`。本版不使用 `fluid_pertick` 需求类型；连续供液必须考虑“mB/t × 并行”，而非只准备一份总量。

### 能源

```json
{
  "type": "energy",
  "io-type": "input",
  "energyPerTick": 40
}
```

这是 **FE/t**，不是整次耗能。100 tick、40 FE/t、单并行且没有修改器时，总耗能为 4000 FE；输出方向同理需要能源输出仓。声明可使用长整数，但实际 Forge Energy 端口和仓储仍受 int 能力范围限制，不提供 GT／IC2 能源转换。

### Mekanism 气体

安装 Mekanism 后才有 `gas` / `gas_pertick` 需求，片段如下：

```json
{
  "type": "gas_pertick",
  "io-type": "input",
  "gas": "mekanism:oxygen",
  "amount": 10
}
```

`gas` 是一次性气体需求，`gas_pertick` 是逐 tick。使用完整、已注册的气体 ID；旧 `oxygen` 不直接等于 `mekanism:oxygen`。气体使用流体仓的气体存储侧，正常气液传输互斥：仓内有流体时拒绝气体，排空后才能切换。JEI 气体格目前以文本显示，不能据此判断配方未加载。

## 四、备选原料、催化剂、NBT 与端口选择

### 备选输入组：消费其中一项

```json
{
  "type": "ingredient_array_input",
  "io-type": "input",
  "items": [
    { "item": "minecraft:coal", "amount": 1 },
    { "item": "minecraft:charcoal", "amount": 2 }
  ],
  "chance": 1.0
}
```

这是“1 煤 **或** 2 木炭”，不是两项都扣，也不是把不同备选的不足数量拼起来。条目可以写标签；条目缺少 `amount` 时使用需求级 `amount`，再缺省为 1。`chance` 和 `nbt` 放需求层，不放在每个条目内部。仅支持输入方向。

### 催化剂：可选材料与本次增益

```json
{
  "type": "catalyst",
  "io-type": "input",
  "items": [ { "item": "minecraft:diamond", "amount": 1 } ],
  "chance": 0.5,
  "modifiers": [
    { "target": "duration", "io": "input", "operation": 1, "multiplier": 0.5 }
  ],
  "tooltip": ["使用钻石时，加工时间减半；50% 概率消耗钻石"]
}
```

材料不足时可以不用这组催化剂开工，也不会获得其增益。一次选择一个备选，催化剂默认不随并行放大；增益在本次合成开始时确定，持续到结束。

**0.107.3 的共享支付规则**：普通输入优先占用资源，选中的催化剂还必须有自己可支付的一份；多条催化剂不能拿同一份材料分别获得增益。相互冲突的可选项按定义顺序分配；此机制不保证寻找收益最高的所有组合。计划选中后库存变化导致缺料，会拒绝开工，不能“省略支付、照拿增益”。

概率免消耗仍存在：`chance: 0` 要有可选中的材料，但不扣除；`chance: 1` 必须扣除。不要把“概率未消耗”与“检查时根本没有材料”混为一谈。

### NBT 条件

物品需求的 `nbt` 为 JSON 对象，输入按条件比较，输出按对象生成实际 NBT。以下为输入需求片段：

```json
{
  "type": "item",
  "io-type": "input",
  "item": "minecraft:diamond",
  "amount": 1,
  "nbt": { "charge": ">=1000" },
  "nbt-display": { "charge": "1000" }
}
```

这里需要物品确实携带相应自定义 NBT，普通钻石没有。本版 NBT 模板中的数字使用字符串，例如 `"1000"` 为 int、`"1000L"` 为 long；比较还区分实际 NBT 数值类型。`nbt-display` 是显示模板，不替代真实输入条件；单独写而不写 `nbt` 不生效。不要把比较表达式用作期望生成的输出数值。独立 `item_durability` 需求类型没有实现，不能照搬旧脚本耐久 API。

### 只访问指定端口

先在机器 `parts` 中给对应仓口写 `"selector-tag": "feed_a"`，再在需求中写相同字段。该需求只看得到这个结构标签对应的端口。它与 `#forge:ingots/iron` 的物品标签不同。

工厂端口分组还会进一步限制可见范围；一个组需要能独立满足本次需求，不会自动跨所有组拼料。排错时先移除筛选建立最小例子，再逐项加回。

## 五、修改器、并行和机器数值模式

### 修改器的字段

```json
{
  "target": "item",
  "io": "output",
  "operation": 1,
  "multiplier": 2.0,
  "affectChance": false
}
```

| 字段 | 取值 |
|---|---|
| `target` | `item`、`fluid`、`energy`、`duration`、`*`；气体修改器使用兼容层的通配目标，不另写 `gas` 目标 |
| `io` | `input`、`output`（也接受 `in`、`out`）；当前 JSON 不接受 `*` |
| `operation` | 0 为加法，1 为乘法 |
| `multiplier` | 加数或乘数，由 operation 决定 |
| `affectChance` | true 改概率，false 改数量／时长等数值 |

同类修改器按 **`(原值 + 加法和) × 乘法积`** 合并，再按对应数量规则取整与限制。`duration × 0.5` 才是时长减半；不要写“乘 2 表示速度翻倍”。默认缩短时间不补偿每 tick 能耗，总耗能随时长变化；配置 `general.enable-duration-multiplier` 会改变能源补偿行为。

修改器来自结构、升级和催化剂。结构修改器是机器根字段，例子为**根对象片段**：

```json
{
  "modifiers": [
    {
      "x": 0, "y": 1, "z": 0,
      "elements": "minecraft:gold_block",
      "description": "上方放金块时，物品输出翻倍",
      "modifier": { "target": "item", "io": "output", "operation": 1, "multiplier": 2.0 }
    }
  ]
}
```

普通 `parts` 条件也必须允许这个位置放金块，否则换块可能破坏结构。多方块条件使用 `multi-block-modifiers`。升级定义和物品关联示例另见 [examples/pack-author](../examples/pack-author/README.md)，不要把升级物品仅放进总线就当成已声明升级。

### 并行参数怎样配合

机器根字段 `parallelizable` 控制是否计算并行，`internal-parallelism` 给内置额度，`max-parallelism` 封顶；结构内并行控制器贡献可调额度。配方根 `max-parallelism` 再限制这份配方。需求级 `parallelize-unaffected: true` 可让固定成本不随 N 放大。

例如机器额度 64，配方上限 8，只够 3 份输入且有 3 份输出空间，最终不能超过 3；若每 tick 能量只能供 2 份，还会进一步限制。工厂新开工和重开工也重新计算，不直接继承上一批并行数。

### 数值接口

机器根对象片段：

```json
{
  "smart-interfaces": [ { "type": "mode", "default": 0, "priority": 1000 } ]
}
```

配方 `requirements` 片段：

```json
{
  "type": "interface_number_input",
  "io-type": "input",
  "interface": "mode",
  "value": 2
}
```

玩家在控制器数值界面把 `mode` 设置为 2 后匹配。区间可改用 `minValue` / `maxValue`，两端包含。这里的 `type` 是需求种类，接口名称用 `interface`；只支持数值，不支持字符串接口，也没有独立接口方块。

## 六、KubeJS 方法速查和机器字段

以下配方方法接在 `.machine('机器 ID')` 后：

| 方法 | 作用 |
|---|---|
| `.time(ticks)` / `.parallelism(max)` / `.priority(n)` | 时长、并行上限、优先级 |
| `.registryName(name)` / `.machines(id...)` | 名称；覆盖为多机器归属 |
| `.itemInput(id, amount)` / `.itemOutput(id, amount)` | 固定物品数量 |
| `.itemOutput(id, min, max, chance)` | 物品范围产出 |
| `.fluidInput(id, amount[, perTick])` / `.fluidOutput(...)` | 一次性或逐 tick 流体 |
| `.energyInput(perTick)` / `.energyOutput(perTick)` | 每 tick 能源 |
| `.catalyst(id, amount[, chance])` | 新建一组催化剂 |
| `.catalystEntry(id, amount)` | 为最近一组催化剂增加备选 |
| `.catalystChance(chance)` / `.catalystModifier(target, io, operation, value)` / `.catalystTooltip(text)` | 修改最近一组催化剂 |
| `.requirement(jsonObject)` | 追加原始需求；气体、NBT、接口、筛选标签等可走这里 |
| `.cancelIfPerTickFails([boolean])` | 无参数表示 true |
| `.startCommand(cmd[, interval])` / `.processingCommand(...)` / `.finishCommand(...)` | 追加阶段命令 |
| `.id('namespace:path')` | KubeJS 配方 ID |

typed API 未列出的字段可用 raw JSON；不要猜 `.gasInput()`、`.tooltips()` 等未提供方法。原始需求例子（完整脚本）：

```js
ServerEvents.recipes(event => {
  event.recipes.modular_machinery_reborn.machine('tutorial:stone_processor')
    .time(100)
    .energyInput(20)
    .itemOutput('minecraft:stone', 1)
    .requirement({
      type: 'ingredient_array_input',
      'io-type': 'input',
      items: [
        { item: 'minecraft:cobblestone', amount: 1 },
        { item: 'minecraft:cobbled_deepslate', amount: 1 }
      ]
    })
    .id('tutorial:stone_from_either')
})
```

机器定义放 `MachineRegistryEvents.registry`，不是配方事件；使用 `event.machine(id)`，末尾 **`.register()` 必需**。完整最小结构见 [使用说明](使用说明.md)。常用字段对应如下：

| JSON 根字段 | 机器构建器方法 |
|---|---|
| `localizedname` / `prefix` | `.localizedName(text)` / `.prefix(text)` |
| `parts` | `.part(x,y,z,...elements)`；坐标列表用 `.parts(xs,ys,zs,...elements)` |
| `failure-action` / `requires-blueprint` | `.failureAction(value)` / `.requiresBlueprint(boolean)` |
| `parallelizable` / `max-parallelism` / `internal-parallelism` | `.parallelizable(boolean)` / `.maxParallelism(n)` / `.internalParallelism(n)` |
| `has-factory` / `factory-only` / `max-threads` | `.hasFactory(boolean)` / `.factoryOnly(boolean)` / `.maxThreads(n)` |
| `core-threads` | `.coreThread(name, ...recipeIds)` |
| `modifiers` | `.modifier(x,y,z,element,modifier[,description])` |
| `multi-block-modifiers` | `.multiBlockModifier(entry)` |
| `smart-interfaces` | `.smartInterface(type,defaultValue,priority[,extra])` |
| `geo-model` | `.geoModel(model,texture[,animation,idle,working])` |

构建器没有通用方法覆盖所有 JSON 字段。复杂的动态结构、根颜色等建议使用完整 JSON；不要因 JSON 支持就假设有同名链式方法。KubeJS 机器与数据包冲突时脚本优先；删除脚本后 `/reload` 不会继续保留那份脚本定义。

## 七、配方适配器：导入现成配方

配方适配器将已有配方导入目标机器。目前提供**熔炉**和**机器间配方复用**两个入口，尚未提供 IC2、GT、匠魂、核电工艺等第三方适配器。

### 熔炉导入声明

保存为 `data/tutorial/recipes/import_smelting.json`，目标机器须另行定义：

```json
{
  "type": "modular_machinery_reborn:machine",
  "machine": "tutorial:stone_processor",
  "adapter": "minecraft:furnace",
  "modifiers": [
    { "target": "duration", "io": "input", "operation": 1, "multiplier": 0.5 }
  ]
}
```

它在重载时导入当前熔炼配方，生成普通机器配方；不是让机器烧燃料。基础值为 **120 tick、20 FE/t**，示例修改后为 60 tick，输入输出继承熔炼内容；不是继承原熔炉的 cookingTime，也不生成熔炼经验。

这是可选扩展练习，不包含在最小示例包中。导入后可能与手写圆石配方重叠，做实验时先移除手写版本，便于确认实际跑的是哪一条。

### 复用另一台机器的配方

```json
{
  "type": "modular_machinery_reborn:machine",
  "machine": "tutorial:advanced_processor",
  "adapter": "tutorial:stone_processor",
  "modifiers": [
    { "target": "energy", "io": "input", "operation": 1, "multiplier": 2.0 }
  ]
}
```

目标机器为 `advanced_processor`，来源机器为 `stone_processor`，两者 ID 不要写反。给 adapter 写完整命名空间，避免裸名落到 `minecraft`。

声明本身会被生成配方替换。**当前适配器不应用声明里的附加 `requirements`**，会警告；不要把额外输入写进去后以为已经扣料。需要额外成本就显式编写普通配方。生成 ID 根据声明、目标和来源稳定生成；从旧流水号 ID 版本升级前先完成在途合成。

## 八、命令与机器事件

命令适合简单通知；事件适合按机器、位置和阶段做判断。Reborn 使用下面的 `MachineLifecycleEvents` 事件组。

配方根对象片段：

```json
{
  "startCommands": ["say processor started"],
  "processingCommands": [ { "command": "say processor running", "interval": 20 } ],
  "finishCommands": ["say processor finished"]
}
```

命令在服务端以权限 2、控制器位置执行，建议不加前导 `/`。processing 使用成功结算前进度，首次为 0；start / finish 的进度参数为 0。interval 按 `tick % N == 0` 触发，省略或 0 表示每次触发，命令结果不控制合成成功与否。避免给所有机器每 tick 广播消息。

KubeJS 事件完整脚本例子：

```js
MachineLifecycleEvents.recipeStart(event => {
  if (String(event.machine) === 'tutorial:stone_processor') {
    console.info('圆石加工机开始配方：' + event.recipe)
  }
})
```

| 事件名 | 可观察／修改 |
|---|---|
| `structureFormed` / `structureUpdated` | 机器 ID、控制器坐标 |
| `machineTick` | START / END 阶段与控制器信息 |
| `recipeCheck` | `phase`，可下调 `parallelism`，可 `setFailed(reason)` 否决开工 |
| `recipeStart` / `recipeFinish` | 配方 ID、进度、时长、并行等只读载荷 |
| `recipeTick` | START / END，可 `cancel()`；两阶段的资源语义不同 |
| `recipeFailure` | 原因与 `destructRecipe` |
| `factoryRecipeStart` / `factoryRecipeTick` / `factoryRecipeFinish` / `factoryRecipeFailure` | 工厂独立事件，另含线程序号 |

工厂配方使用对应 factory 事件，不要只监听普通 recipeStart 就认为覆盖所有合成。取消 recipeTick 的 START 会跳过该次结算；取消 END 时本 tick 已结算资源，进度不加，不能当作事务撤销。失败事件也不自动退还已经支付的开工输入。

这不是旧版脚本全部控制器接口的等量替代；例如任意 customData 与旧 RecipePrimer 回调不能按原代码复制。附属模组 Java 事件与具体时序见 [事件 API](事件API.md)，其中旧阶段描述需要结合当前迁移日志阅读。

## 九、常见错误与验收清单

| 错误 | 修正 |
|---|---|
| 写了配方却没机器 | 先定义完整机器结构，`machine` 必须等于机器 `registryname` |
| 把 `registryName` 当实际配方 ID | JSON 由文件路径决定；KubeJS 用 `.id(...)` |
| 给 input 写随机数量 | 输入用固定 `amount`，范围数量仅用于物品输出 |
| 使用 `.gasInput()` 或旧 CrT 方法 | 用当前 typed 表；未提供字段走 `.requirement(...)` 或 raw JSON |
| 一个材料被普通输入和催化剂重复使用 | 准备两份，或接受没有催化剂增益；不能靠重排骗过共享预留 |
| 加 `tooltips` 就以为改了数值 | 文案只是展示，数量和效果由需求与修改器决定 |
| 配方“注册成功”但不运行 | 再查结构、端口、标签／分组、能源速率和输出空间；执行 `/mm-diagnose` |
| 照搬旧脚本“全部兼容模块” | 核对本版范围；不支持的需求类型会报错，不应删掉需求让配方变成免费 |

每次先保留一条确定配方：记录输入、运行 tick、FE/t、输出、并行数；再逐一加入概率、催化剂、标签、分组、适配器与事件。最后实际检查重载、重登、存档重启、输出堵塞与连续合成。脚本没有语法错误、JEI 能显示、离线测试通过，分别只证明各自覆盖的部分。
