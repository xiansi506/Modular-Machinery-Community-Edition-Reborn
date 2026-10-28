# 气体和魔法资源

只在用户需要时添加相应依赖。安装模组不会自动生成机器。相应仓口必须列入结构并成型；配方 ID 正确不能替代实际供给。

## 气体

需要 Mekanism，只支持气体，不包括浆液、灌注物、颜料。一次性为 `gas`，逐 tick 为 `gas_pertick`；数量为 1～2147483647 的整数，使用已注册的完整气体 ID。

需求片段：

```json
{ "type": "gas_pertick", "io-type": "input", "gas": "mekanism:oxygen", "amount": 10 }
```

气体使用流体仓的气体侧，有流体时拒绝气体，排空后切换。ME 气体需要 AE2 + Mekanism + Applied Mekanistics 1.4.3；只用普通气体仓口不需要 AE2。不要把旧裸名 `oxygen` 当成已验证的 `mekanism:oxygen`。

## 魔法仓口及单位

以下仓口 ID 均加 `modular_machinery_reborn:` 前缀，输入/输出方向对应需求。

| 模组 / 需求类型 | 仓口 | 数量与资源来源 |
|---|---|---|
| Botania / `mana` | `mana_input_hatch`、`mana_output_hatch` | 非负 int 魔力；仓口本地缓存，默认容量 100000 |
| Nature’s Aura / `aura` | `aura_input_hatch`、`aura_output_hatch` | 非负 int 原始灵气单位；仓口处真实环境灵气 |
| Blood Magic 3 / `lifeessence` | `lifeessence_input_hatch`、`lifeessence_output_hatch` | 非负 int LP；有效绑定血球、灵魂网络和仓口缓存 |
| Blood Magic 3 / `will` | `will_input_hatch`、`will_output_hatch` | 非负有限小数；所在区块真实恶魔意志 |

`int` 上限 2147483647。四种需求均可写 `perTick: true`，缺省 false。魔力和 LP 没有资源变种字段。气动工艺与指定余烬复刻版另见下节，其他魔法体系当前不提供。

## 需求片段

```json
{ "type": "mana", "io-type": "input", "amount": 1000 }
```

```json
{ "type": "aura", "io-type": "input", "aura-type": "naturesaura:overworld", "amount": 100, "min-aura": 0 }
```

```json
{ "type": "lifeessence", "io-type": "input", "amount": 100 }
```

```json
{ "type": "will", "io-type": "output", "will-type": "default", "amount": 0.25, "max": 100 }
```

灵气必须写 `aura-type`，匹配环境类型，如 `naturesaura:overworld`、`naturesaura:nether`、`naturesaura:end`。`amount:100` 就是原始单位 100，不乘 100000。`min-aura` 默认 -2147483648，允许透支；要保留非负环境显式写 0。`max-aura` 默认 2147483647。输入检查扣除后的下限，输出检查生成后的上限；二者为 signed int 且 min ≤ max。

意志 `will-type` 默认 `default`，另有 `corrosive`、`destructive`、`vengeful`、`steadfast`。`min` 默认 0，`max` 默认 100，为有限数且 min ≤ max。不要把默认上限 100 说成所有意志配方的硬编码数量上限。

LP 仓要插入有效绑定血球。输入仓周期抽取网络 LP、输出仓周期回存，不能只看玩家在线或网络总量就认定当前缓存足够。灵气仓和意志仓没有独立资源罐。

JEI 灵气绿色条：一次性显示 amount，逐 tick 显示 amount × recipeTime；166666 是显示满格刻度，不是环境量或配方限制。显示不改变实际扣费。

## typed 魔法方法

在配方 `.machine(...)` 链上使用：

```text
.manaInput(amount[, perTick]) / .manaOutput(amount[, perTick])
.lifeEssenceInput(amount[, perTick]) / .lifeEssenceOutput(amount[, perTick])
.auraInput(type, amount, minimum)
.auraOutput(type, amount, maximum)
.auraPerTick(type, amount, io, minimum, maximum)
.willInput(type, amount, minimum)
.willOutput(type, amount, maximum)
.willPerTick(type, amount, io, minimum, maximum)
```

`io` 为 `'input'` 或 `'output'`，type 对应 JSON 的灵气类型/意志变种。单次 aura/will 方法没有额外 perTick 参数，逐 tick 使用专门方法。生成组合配方时逐项列出依赖及所有所需仓口，不把这些片段直接接到只有物品和能源仓口的模板上当作可运行机器。

## 气动工艺与余烬（0.119.0）

对接 PneumaticCraft: Repressurized 6.0.23，以及 RCXcrafter 的 Embers Rekindled 1.20.1-1.4.7（mod ID `embers`）。气动仓为 `compressed_air_input_hatch` / `compressed_air_output_hatch`，余烬为 `ember_input_hatch` / `ember_output_hatch`，均加本模组命名空间并列入结构。

```json
{ "type": "compressed_air", "io-type": "input", "pressure": 4.5, "air": 1000 }
```

输入在扣费前检查至少 4.5 bar，扣 1000 mL 空气。空气量 = bar × 有效容积；默认气动仓容积 16000 mL，使用原生 tier-two 处理器。输出同类型写 `io-type:"output"`、显式 `air` 数量，不要求 pressure。`compressed_air_pertick` 每 tick 检查和结算，不能用普通类型加 `perTick:true` 替代。空气用非负 int，接受 `amount` 别名。输入 `air:0` 或省略数量只检查压力；负 pressure 表示压力 ≤ 所写负值的真空条件。并行只放大 mL，不放大 bar。旧 `time` 不设置实际时长，使用根 `recipeTime`。

```json
{ "type": "ember", "io-type": "input", "amount": 100.5, "perTick": false }
```

余烬用非负有限小数，也接受旧 `ember` 数量字段，默认一次性；true 为每 tick。默认仓容量 256000 Ember，用复刻版的接收器贴住仓口供能，不转换 FE。输出需余烬输出仓。修改器目标为 `compressed_air` / `ember`。

```text
.compressedAirInput(pressure, air)
.compressedAirOutput(air)
.compressedAirPerTick(pressure, air, io)
.emberInput(amount[, perTick]) / .emberOutput(amount[, perTick])
```

`io` 为 input/output，气动输出的 pressure 传 0。JEI 条形为显示比例，不是当前库存。气动仓有原生超压行为，未接管道的输出仓会漏气；实际压力与容量看仓口界面。完整教学包在项目 `examples/pneumatic-embers/`，详细规则在 `docs/气动工艺与余烬.md`。
