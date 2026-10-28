---
name: mmr-recipe-author
description: 为 Modular Machinery Reborn（Minecraft 1.20.1 / Forge）编写、修改和排查机器加工配方，提供 JSON 数据包、KubeJS 6、NBT、并行及魔法资源的准确写法。用于该项目的整合包配方制作；普通工作台配方及其他同名模组不适用。
---

# MMR 配方编写

基线：Reborn **0.119.0**，Minecraft **1.20.1 / Forge / Java 17**。本技能可独立阅读；有项目源码时以当前实现为准，尤其版本不同或涉及未列出的接口时。通信协议 9 不是配方字段。

## 先确定交付对象

从用户请求和现有文件确定机器 ID、配方 ID、输入输出、时长、供能、可选模组及希望使用 JSON 还是 KubeJS。缺少机器时说明这一点，并在请求范围内提供最小机器定义。不要用显示名猜注册 ID；已有工程优先沿用其命名空间和组织方式。

普通配方先读 [配方格式](references/recipe-schema.md)。按任务需要再读：

- KubeJS 脚本、机器事件和精确方法签名：[KubeJS](references/kubejs.md)。
- NBT、备选输入、催化剂、延迟触发、修改器：[进阶需求](references/advanced.md)。
- 气体、植物魔法、自然灵气、血魔法 3、气动工艺和余烬：[可选资源](references/optional-resources.md)。

## 快速制作

1. 机器已存在时，只生成指向该机器的配方；核对结构中确有对应方向的仓口，以及 `selector-tag` 和工厂输入组的可见范围。
2. 新建最小示例时，选择一种模板改写：
   - [JSON 数据包](assets/datapack/pack.mcmeta)：复制整个 `assets/datapack` 为数据包，内含机器和配方。
   - [KubeJS 脚本](assets/kubejs/basic_processor.js)：包含等价的机器定义和 typed 配方，放进实例的 `kubejs/server_scripts/`。
   两者是替代方案。不要把同 ID 的等价定义同时安装。模板命名空间为 `skill_demo`，改名时同步修改路径及所有引用。
3. 给出完整可保存的文件、目标路径和依赖；需求片段明确标成片段。JSON 不带注释。配方不自动创建结构、能源或资源供应。
4. 对生成的 JSON 运行本技能的静态检查，随后按下文安排引擎验证。KubeJS 的 JS 语法检查不能证明事件及 typed API 在运行时存在。

## 最容易写错的规则

- 机器 ID 用机器根 `registryname`；配方根 `registryName` 是名称。数据包配方 ID 由 `data/<namespace>/recipes/<path>.json` 决定；KubeJS 用 `.id(...)`。
- 根 `type` 固定为 `modular_machinery_reborn:machine`。需求另有 `type` 和 `io-type`。机器和配方是两个对象。
- 时长用 `recipeTime`，单位 tick，正整数。能源用 `energyPerTick`，单位 FE/t；不把整次耗能写成每 tick 耗能。
- `chance` 用 0～1。输出范围用 `minAmount` / `maxAmount`，输入用固定数量。标签原料写 `#forge:...`，输出通常用具体物品。
- 普通输入开工扣除、普通输出完工结算；流体与魔法的 `perTick: true`、`gas_pertick` 和能源每个成功结算 tick 支付。
- 配方并行上限不能凭空给机器增加额度。`parallelize-unaffected: true` 是该需求固定成本，不随并行数放大。
- JavaScript Number 超过 `9007199254740991` 可能损失整数精度。需要精确 long 能源时使用 JSON 整数。
- 新需求优先使用已支持的 JSON 或 typed API；不要凭其他模组的教程发明本项目的方法、仓口或兼容功能。

## 检查与交付

在有 Python 3 的环境运行（用当前环境实际解释器替换 `python`）：

```text
python <技能目录>/scripts/validate_recipe.py <配方.json> [更多配方.json] --machine <机器.json>
```

`--machine` 可省略，提供时仅核对机器 ID 关联。工具只读文件，检查常用字段、数值及已知误用；支持精确大整数。扩展需求和复杂选项会提示需人工复核。退出码 0 表示已覆盖的静态检查通过，1 表示发现问题，2 表示参数错误。它不加载 Forge 注册表、不执行 KubeJS，也不验证真实仓口或游戏运行。

有游戏验证条件时，先让在途加工结束，再 `/reload` 并检查第一条日志错误；`/mm-syntax` 诊断定义及关联，**不会应用修改**。对准控制器 `/mm-diagnose`，用 `/mm-hand` 查询手中物品/NBT（这些诊断命令需权限 2）。随后实际验证一批输入、输出、FE/t 和必要的逐 tick、并行行为。没有游戏条件时交付文件和验证步骤，明确记录尚未实机验证。

JSON 数据包根含 `pack.mcmeta` 和 `data/`，1.20.1 的 `pack_format` 为 15。机器目录是 `data/<namespace>/machinery/`，配方是 `data/<namespace>/recipes/`。配置机器放 `config/modular_machinery_reborn/machinery/`；机器来源优先级为 KubeJS > 配置 > 数据包。新增专属控制器方块需要两端预声明并重启，单纯 `/reload` 不会注册新方块。

## 当前源码核对入口

在项目根下按需读取，避免每次通读全部文档：

| 要核对的行为 | 文件（相对项目根） |
|---|---|
| 目录、重载、命令 | `docs/安装与重载.md` |
| 配方 JSON 与生命周期 | `docs/KJS-配方指南.md`；`src/main/java/com/reborn/modularmachinery/recipe/ModRecipeSerializers.java`、`MachineRecipe.java` |
| 需求字段与 NBT | 上述 `recipe/` 目录内的 `RequirementTypes.java`、`MachineRequirementParser.java`、`IngredientArrayParser.java`、`CatalystParser.java`、`NbtPredicate.java` |
| typed 方法 | `src/main/java/com/reborn/modularmachinery/kubejs/MachineRecipeJS.java` |
| 机器字段 | `src/main/java/com/reborn/modularmachinery/machine/MachineSchema.java` |
| 魔法、气体 | `docs/魔法资源.md`；`magic/MagicRequirements.java`、`magic/MagicRequirement.java`、`compat/mekanism/MekanismModule.java`（后者位于同一 Java 包根） |
| 脚本回调与高级结构 | `docs/高级脚本指南.md`、`docs/进阶结构示例.md` |

旧注释可能记录历史限制；以当前解析器、注册逻辑和可复现行为判断支持情况。
