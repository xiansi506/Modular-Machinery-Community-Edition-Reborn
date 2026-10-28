# KubeJS 6 写法

服务器脚本放 `kubejs/server_scripts/*.js`。机器在 `MachineRegistryEvents.registry` 中定义，结尾 `.register()`；配方在 `ServerEvents.recipes` 中定义。客户端事件不能代替这两者。完整可复制版本在 `assets/kubejs/basic_processor.js`。

## typed 和原始 JSON 是替代写法

```js
ServerEvents.recipes(event => {
  event.recipes.modular_machinery_reborn.machine('skill_demo:basic_processor')
    .registryName('stone')
    .time(100)
    .parallelism(1)
    .itemInput('minecraft:cobblestone', 1)
    .energyInput(20)
    .itemOutput('minecraft:stone', 1)
    .id('skill_demo:stone')
})
```

```js
ServerEvents.recipes(event => {
  event.custom({
    type: 'modular_machinery_reborn:machine',
    machine: 'skill_demo:basic_processor',
    registryName: 'stone',
    recipeTime: 100,
    'max-parallelism': 1,
    requirements: [
      { type: 'item', 'io-type': 'input', item: 'minecraft:cobblestone', amount: 1 },
      { type: 'energy', 'io-type': 'input', energyPerTick: 20 },
      { type: 'item', 'io-type': 'output', item: 'minecraft:stone', amount: 1 }
    ]
  }).id('skill_demo:stone')
})
```

typed 会生成根 `type`；`event.custom` 必须显式写它。不要同时安装以上同 ID 配方。`.id()` 和 `.registryName()` 作用不同。

## 已核对的配方方法

| 方法 | 含义 |
|---|---|
| `.time(ticks)`、`.parallelism(max)`、`.priority(n)` | 时长、配方并行上限、优先级 |
| `.registryName(name)`、`.machines(id...)` | 名称、多机器归属 |
| `.itemInput(id, amount)`、`.itemOutput(id, amount)` | 固定物品 |
| `.itemOutput(id, min, max, chance)` | 范围物品输出 |
| `.fluidInput(id, amount[, perTick])`、`.fluidOutput(...)` | 流体 |
| `.energyInput(perTick)`、`.energyOutput(perTick)` | FE/t |
| `.gasInput(id, amount)`、`.gasOutput(id, amount)`、`.gasPerTick(id, amount, io)` | 气体，需要 Mekanism |
| `.ingredientArray(entries)` | 备选输入数组 |
| `.smartInterface(name, value)` | 数值接口门槛 |
| `.catalyst(id, amount[, chance])` | 新建可选催化剂组 |
| `.catalystEntry(id, amount)`、`.catalystChance(n)` | 增加备选、设置概率 |
| `.catalystModifier(target, io, operation, value)`、`.catalystTooltip(text)` | 催化剂增益与说明 |
| `.chance(n)`、`.nbt(object)`、`.previewNBT(object)` | 修饰最近一条需求 |
| `.selectorTag(tag)`、`.parallelizeUnaffected(bool)` | 最近需求的端口与固定成本 |
| `.consumeDurability()`、`.fuelInput(burnTicks)` | 物品耐久消耗、燃料时间需求 |
| `.triggerTime(tick)`、`.triggerRepeatable(bool)` | 最近需求的延迟触发 |
| `.requirementTooltip(text)`、`.tooltip(text...)` | 需求说明、配方说明；没有 `.tooltips()` |
| `.requirement(object)`、`.requirementOption(key,value)`、`.field(key,value)` | 追加原始需求、最近需求字段、配方根字段；仍须符合 schema |
| `.cancelIfPerTickFails([bool])` | 无参数表示 true；不是退款 |
| `.on(event, callback)`、`.itemChecker(fn)`、`.itemModifier(fn)` | 高级回调，先核对项目高级脚本指南 |
| `.adapter(id)`、`.adapterModifiers(array)` | 适配器声明；不用于普通配方的通用修改器 |

魔法方法见 [可选资源](optional-resources.md)。遇到未列出的方法，查看当前 `MachineRecipeJS.java`；不要根据中文字段名拼造 API。JavaScript 的语法解析不检查上述 API 是否存在。

## 最小机器事件

```js
MachineRegistryEvents.registry(event => {
  event.machine('skill_demo:basic_processor')
    .localizedName('基础加工机')
    .requiresBlueprint(false)
    .parallelizable(false)
    .part(-1, 0, 0, 'modular_machinery_reborn:item_input_hatch')
    .part(1, 0, 0, 'modular_machinery_reborn:item_output_hatch')
    .part(0, -1, 0, 'modular_machinery_reborn:energy_input_hatch')
    .register()
})
```

相对坐标以控制器为原点，按实际预览和朝向搭建。修改现有机器时不要为了添加一条配方用最小模板覆盖其结构。新增专属控制器需要配置预声明和重启；上述事件本身不凭空增加一个新的注册方块。
