# 进阶需求与行为边界

下列 JSON 均为需求或机器根片段，按说明放入对应对象。复杂脚本优先查当前项目 `docs/高级脚本指南.md`，不要把这里的片段当作完整脚本 API。

## NBT 的匹配、产出和显示

物品输入片段：

```json
{
  "type": "item", "io-type": "input", "item": "minecraft:diamond", "amount": 1,
  "nbt": { "charge": ">=1000" },
  "nbt-display": { "charge": "1000" }
}
```

需要物品确有相应 NBT，普通钻石没有 `charge`。NBT 模板数字使用字符串，`"1000"` 为 int，`"1000L"` 为 long，匹配区分实际 NBT 类型。`nbt-display` 仅改变显示，不能代替输入条件；原始 JSON 单写它而没有 `nbt` 会被忽略。typed `.previewNBT()` 会补空模板以支持仅显示 NBT。

输出 `nbt` 生成实际 NBT，不将 `">=1000"` 比较表达式当成期望产出的具体数值。结构方块的 `preview-nbt` 与物品需求的 `nbt-display` 不是同一字段。复杂对象、列表和脚本谓词先核对当前 `NbtPredicate` 和结构指南。

## 备选输入和催化剂

```json
{
  "type": "ingredient_array_input", "io-type": "input",
  "items": [
    { "item": "minecraft:coal", "amount": 1 },
    { "item": "minecraft:charcoal", "amount": 2 }
  ],
  "chance": 1
}
```

消费 1 煤或 2 木炭，不是两者都扣，也不跨备选拼不足数量。条目缺数量使用需求级 `amount`，再缺省为 1。`chance` 和 `nbt` 在需求层，只支持 input。

```json
{
  "type": "catalyst", "io-type": "input",
  "items": [{ "item": "minecraft:diamond", "amount": 1 }],
  "chance": 0.5,
  "modifiers": [{ "target": "duration", "io": "input", "operation": 1, "multiplier": 0.5 }],
  "tooltip": ["有钻石时加工时间减半；50% 概率消耗钻石"]
}
```

催化剂可选，没材料仍能按无增益配方运行；选中后需要独立可支付的一份。普通输入先占资源，多条催化剂不能重复使用同份材料获取增益。`chance:0` 仍需能选中材料，但免消耗。催化剂默认不随并行放大，增益在本次开工时确定。

## 数值接口与端口

机器根字段片段：

```json
{ "smart-interfaces": [{ "type": "mode", "default": 0, "priority": 1000 }] }
```

配方需求片段：

```json
{ "type": "interface_number_input", "io-type": "input", "interface": "mode", "value": 2 }
```

数值接口是控制器门槛，不消耗物品，无独立接口方块，也不支持字符串模式。范围使用 `minValue` / `maxValue`，包含端点。

结构 `parts` 的仓口写 `"selector-tag":"feed_a"`，需求写同名字段才会筛选至该端口。标签与工厂输入组同时限制可见性；一个组必须独立满足需求，不跨组拼料。不要把它与物品标签 `#forge:...` 混用。

## 修改器

修改器对象的 `target` 通常为 `item`、`fluid`、`energy`、`duration`、`*`；气体用兼容层通配目标，不写独立 `gas` 目标。魔法修改器需核对当前魔法实现。`io` 为 input/output（也接受 in/out），不是 `*`；`operation:0` 加法，1 乘法；`multiplier` 为加数/乘数；`affectChance` 决定改概率还是数值。

同类按 `(原值 + 加法和) × 乘法积` 合并。时长乘 0.5 才是减半。默认缩短时长不补偿 FE/t，总耗能随时长变；`general.enable-duration-multiplier` 配置会改变能源补偿。普通配方需要催化剂、结构或升级提供修改器，不是随便加根 `modifiers`。

## 延迟触发与脚本

一次性非能源需求可用 `"trigger-time":80`；在 100 tick 配方缩短至 50 tick 后，相应触发进度为 40。进度从 0 开始，超出末尾的点夹到最后一 tick。跳进度或提前完成也不能跳过尚未支付的需求。不能与能源或逐 tick 需求组合。

`trigger-repeatable:true` 是再次经过触发点时重触发，不是每隔 N tick 自动扣费。需求 `script-tooltip` 是字符串；配方 `tooltips` 与催化剂 `tooltip` 用途不同。

回调 `recipeCheck` 在付款前可能执行两次，不在其中扣 `customData`。`recipeStart` 在成功支付并启动后触发。`customData` 回调扣费不自动成为共享事务资源；如果按批次、线程或并行计费，必须明确生命周期和数量，不能把一份回调成本当作已支持全部组合的通用配方。

改配方前先让加工结束。当前已加载工厂的执行副本可继续当前批次，下次重开工解析当前定义；这不保证结构变化、区块加载或重启后继续旧任务，重载也不保证退款。
