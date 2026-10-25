# 生命周期事件 API（0.79.0 首切片）

> **面向用 Java 写附属模组（addon）的作者。**
>
> 0.79.0 交付的是 C2 的**第一切片**：控制器结构、单配方生命周期共 7 个事件。
> **工厂控制器的配方事件、失败事件、setFailed 都还没有**，见 §5。
>
> 本轮**没有进游戏**，也**没有造真实附属模组**；下面的语义由离线断言
> （`_audit/m6c-verify/mmverify/EventLayerCheck.java`）与源码接线证明，**未实机验证**。

---

## 1. 怎么订阅

事件发在 **Forge 总线**（`MinecraftForge.EVENT_BUS`），不是模组总线。用
`@Mod.EventBusSubscriber` 时 `bus` 必须是 `Bus.FORGE`：

```java
package com.example.mymod;

import com.reborn.modularmachinery.api.event.RecipeFinishEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = "mymod", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class MyMachineEvents {

    @SubscribeEvent
    public static void onRecipeFinish(RecipeFinishEvent event) {
        // 事件只在服务端发；不要在这里做纯客户端的事
        if (event.controller() == null) {
            return; // Forge 探测用的空实例，不会真正派发；防御性判断
        }
        MyMod.LOGGER.info("finished {} at {} (parallelism {})",
                event.recipe().getId(), event.controller().getBlockPos(), event.parallelism());
    }
}
```

也可以直接 `MinecraftForge.EVENT_BUS.addListener(MyMachineEvents::onRecipeFinish)`。
**不要调用 `MachineEvents.post(...)`**：那是控制器内部派发用的，不是给附属模组的入口。

事件类都在 `com.reborn.modularmachinery.api.event` 包下。公共基类：

- `MachineEvent`：`controller()`（服务端方块实体）、`machineId()`（当前机器定义 id，未成型为 `null`）。
- `RecipeEvent`（继承 `MachineEvent`）：`recipe()`、`progress()`（**动作发生前**的进度）、
  `maxProgress()`（本次合成重算后的总时长）、`parallelism()`（本次合成冻结的并行数）。

> 事件里的 `recipe()` 是**全服共享的配方对象**，不是本次合成的副本。只读；不要修改它，也不要跨 tick 持有。
> 事件只保证在**服务端线程**派发。

---

## 2. 事件总表

| 事件 | Phase | 什么时候发 | 能改变什么 |
|---|---|---|---|
| `MachineStructureFormedEvent` | 无 | 结构检查把控制器从**未成型**变成**成型**时 | 只读 |
| `MachineStructureUpdateEvent` | 无 | **每次成功的结构检查**（包括刚成型那一次） | 只读 |
| `MachineTickEvent` | `START` / `END` | 包住控制器的**整个服务端 tick** | 只读通知，不能取消 |
| `RecipeCheckEvent` | `START` / `END` | 开工门前后 | `setParallelism(int)` |
| `RecipeStartEvent` | 无 | 开工真正成功之后、控制器写入 active 状态之前 | 只读 |
| `RecipeTickEvent` | `START` / `END` | 每次成功的逐 tick 结算前后 | `preventProgressing(String)` |
| `RecipeFinishEvent` | 无 | 产物结算完成之后、清空合成状态之前 | 只读 |

**工厂控制器**：`MachineTickEvent` 与两个结构事件照常发；**4 个 `Recipe*` 事件不发**。
工厂线程由 `FactoryEngine` 自己结算，本切片没有接它的配方事件（见 §5）。

---

## 3. 结构事件

派发点：`block/MachineControllerBlockEntity.java:593-599`，在结构检查成功、端口/升级总线/修改器/
智能接口都重新绑定之后。

- **成型**：只有 `wasFormed == false` 且本次 `match != null` 时发 `MachineStructureFormedEvent`。
  已成型机器换成另一份定义**不会**再发成型，只发更新。
- **更新**：同一次检查里紧接着发 `MachineStructureUpdateEvent`。顺序是**先 Formed、后 Update**，
  与旧版 `TileMultiblockMachineController#onStructureFormed` 一致。
- **不发的情况**：结构检查失败、结构检查被调度器跳过（A10 的节流/延迟）、控制器未加载。
  因此“更新”不是每 tick 一次，而是**每次真正重查且成功**一次。
- 事件发出时 `formed` 与 `machineId` **已经**是新值；`MachineEvent#machineId()` 不会为 `null`
  （这两个事件只在 `match != null` 时发）。

---

## 4. 单配方生命周期

### 4.1 `MachineTickEvent`

派发点：`MachineControllerBlockEntity.java:479-498`。

- `START` 在 `tickServer` 之前；`END` 在 `finally` 里，所以**正常提前 return**（未成型、红石停机、
  工厂路径、没有配方、检查失败等）也会配对发 `END`。
- **例外**：如果 `START` 的监听器自己抛异常，`END` 不会发（异常会在进入 `try` 之前抛出）。
- 事件是通知，不是开关：不能取消 tick，也不能改状态。想阻止进度请用 `RecipeTickEvent`。
- `START` 时读到的 `machineId()` 是**本 tick 结构检查之前**的旧值；如果本 tick 会成型/换机，
  要到 `END` 才是新值。

### 4.2 `RecipeCheckEvent`：开工门与并行数

派发点：`MachineControllerBlockEntity.java:717-741`。

- `START` 在 `canStart` / `canTick` **之前**；`END` 在两个门都通过之后、`recipe.start(...)`
  **之前**（也就是一次性输入还没付）。
- **`END` 不一定发**：`canStart` 或 `canTick` 拒绝时控制器直接记录失败并返回，不会补 `END`。
  监听器不要假设 START/END 成对，需要清理的状态请在 START 里同时准备超时/下个 START 重置。
- `parallelism()`（继承自 `RecipeEvent`）是控制器算出的并行数；
  `requestedParallelism()` 是事件当前请求值，初始相同。
- `setParallelism(int)` 只写“请求值”；控制器用
  `max(1, min(computed, requested))` 解析（`RecipeCheckEvent#resolvedParallelism`）。
  所以：
  - 可以提高，但**不能超过**控制器算出的上限；
  - 不能降到 0（最低 1）；
  - `START` 的改写会影响紧接着的 `canStart` / `canTick` / `start`；
  - `END` 的改写是**最后一次生效**（后写者胜），同样受上限约束。
- 事件发完、控制器解析后，会把最终值写回 `recipe.setParallelism(...)`；监听器读
  `event.recipe().parallelism()` 时看到的是**改写前**的值，不是自己的请求。

### 4.3 `RecipeStartEvent`

派发点：`MachineControllerBlockEntity.java:748-751`。

- 只在 `recipe.start(...)` 返回成功后发；一次性输入已经被接受。
- 发在控制器写入 `activeRecipeId` / `activeParallelism` / `activeCraftModifiers` **之前**，
  所以此时 `controller().getStatus()` 可能还是上一 tick 的状态，`progress()` 也还是开工前的值（0）。
- **只读，没有取消开关**。旧版在 `start()` 之前发，且即使监听器取消，合成仍会开始；
  本版挪到成功之后，理由是不能宣布一次没有开始的合成。

### 4.4 `RecipeTickEvent`：逐 tick 结算与阻止进度

派发点：`MachineControllerBlockEntity.java:778-813`。

- `START`：在本 tick 的 `canFinish` 检查、`canTick` 检查、`recipe.tick(...)` **之前**。
- `END`：在 `recipe.tick(...)` **之后**、`progress` 增加之前。
- `preventProgressing(String reason)` 的两种效果：
  - 在 `START` 调用：本 tick 不检查产物空间、不检查逐 tick 需求、不扣资源、不前进；
  - 在 `END` 调用：本 tick 的资源**已经扣了**，但不增加进度（旧版也退不回资源）。
- `reason` 只存在事件对象里，**不会**写进控制器状态、GUI 或日志；需要留痕请监听器自己打日志。
- 每次 tick 都阻止可以无限期挂住这次合成；开工时付掉的一次性输入不会返还。
- **末 tick 的坑（已知）**：如果在 `END` 阻止的是**最后一 tick**，资源已扣但 `progress` 没到
  `maxProgress`，下一次还会再走一遍 `recipe.tick(...)`，于是同一份合成可能多扣一次逐 tick 资源。
  本切片没有为“最后一 tick”做特殊处理；旧版同样不能退资源，但这里是否与旧版逐 tick 等价**未验证**。
  需要精确控制时，请在 `START` 阻止，或自己记录“本合成已阻止过末 tick”。

### 4.5 `RecipeFinishEvent`

派发点：`MachineControllerBlockEntity.java:820-824`。

- 在 `recipe.finish(...)` 完成产物结算之后发；概率产物已经掷完，能放下的已经放下。
- 在 `finishCommands` 执行、`progress` 归零、active 字段清空**之前**发，所以监听器还能读到
  `recipe`、`maxProgress`、`parallelism`，`progress()` 此时等于 `maxProgress()`。
- 只读，没有取消/改写产物的入口。

---

## 5. 这一版**没有**的东西

以下都还没做，不要按旧版 API 写：

- **失败事件**：`RecipeFailureEvent`（以及旧版 `ResultChanceCreateEvent`、
  `SmartInterfaceUpdateEvent`）。本版的逐 tick 需求没有可携带给客户端的失败原因，
  发一个“失败了但界面只显示 idle”的事件正是本项目禁止的隐形失败；等 C2b 把原因链补上。
- **`RecipeCheckEvent#setFailed`**、**`RecipeTickEvent#setFailed(destruct, reason)`**：
  同上，没有可见 cause 就不开放“宣布失败/销毁”的能力。
- **开工取消**：`RecipeStartEvent` 只读；旧版的取消不会阻止 `start()`，本版干脆不提供。
- **工厂配方事件**：旧版 `FactoryRecipeCheckEvent` / `FactoryRecipeStartEvent` /
  `FactoryRecipeTickEvent` / `FactoryRecipeFinishEvent` 四件都未移植；
  工厂控制器目前只发 `MachineTickEvent` 与结构事件。
- **GUI / GeckoLib 事件**：本版已有各自的界面/渲染路径，不在 C2 切片内。
- **客户端同步**：事件只在服务端发；没有“把事件结果同步给客户端”的通用通道。

---

## 6. 与旧版 1.12.2 的差异

1. **总线不同**：旧版生命周期事件的 `EVENT_BUS.post(...)` 是注释掉的，实际只发给组件、
   升级处理器和 CraftTweaker 回调；本版发 Forge 总线。
2. **没有 CraftTweaker 桥**（D7），所以旧版脚本里的 `event` 回调不会出现；Java 附属模组直接订阅。
3. **载荷是重建的**：旧版事件带 `ActiveMachineRecipe` / `RecipeCraftingContext` / `RecipeThread`，
   本版只有 `MachineRecipe` + 进度/时长/并行数。依赖旧对象的方法无法照搬。
4. **开工事件时序**：旧版在 `start()` 前发；本版在成功之后发，避免“宣布了没开始的合成”。
5. **失败能力缺失**：见 §5，不是改名，是真的没有。
6. **工厂未覆盖**：见 §5。

---

## 8. 0.88.0 新增（C2 第二切片）

- **`RecipeCheckEvent.setFailed(reason)`**：否决这次开工（`@Cancelable`）。`START` 段取消 = 不问门槛不开工；`END` 段取消 = 门槛已过、一次性输入尚未支付，同样不开工、什么都不扣。`reason` 是可直接展示的文案（含 `.` 按 translatable 键渲染，否则明文），经方块实体同步到界面、排在固定失败键行之后。
- **`RecipeFailureEvent`**：运行中的配方放弃时发出。`cause()` = 本次失败键；`destructRecipe()` 默认 = 配方 `cancelIfPerTickFails`，`setDestructRecipe(boolean)` 可按次改写（true = 清合成、已付一次性输入不返还；false = 走机器 failure-action）。0.75.0 起记录的「第三方不能覆盖 cancelIfPerTickFails」差异就此收回。
- **0.91.0（C2 小件）**：`ResultChanceCreateEvent`（结算前，可 `setRandom(RandomSource)` 替换本次产物掷点随机源——旧版替换 ResultChance 的等价物）；`SmartInterfaceUpdateEvent`（智能接口值成功写入后，携带 type/newValue；旧版是独立方块带坐标，本版接口在控制器上——差异已记）。GUI/geo 三事件不移植（渲染半边属 C7 延伸）。
- **0.90.0（C3）**：KubeJS 脚本侧回调——事件组绑定 `MachineLifecycleEvents`（`structureFormed/structureUpdated/machineTick/recipeCheck/recipeStart/recipeTick/recipeFinish/recipeFailure/factoryRecipeStart/Tick/Finish/Failure`），取消/改写语义原样转发（`setFailed`、`cancel()`、`destructRecipe =`）。**未实机：没有真实脚本订阅验证过**。
- **0.89.0（C2 收尾）**：`FactoryRecipeStartEvent`（开工成后，只读）、`FactoryRecipeTickEvent`（`@Cancelable`：START 取消 = 整 tick 跳过不扣；END 取消 = 资源留扣、进度不加）、`FactoryRecipeFailureEvent`（cause + destruct 默认 `cancelIfPerTickFails` 可改写）、`FactoryRecipeFinishEvent`（完成后，只读）。全部携带 `threadOrdinal()`（槽位序号）。**工厂侧仍只发这些，不发单控制器的 Recipe 事件族**。C2 剩余小事件：`ResultChanceCreateEvent`、`SmartInterfaceUpdateEvent`、GUI/geo 事件。

## 7. 未验证清单

- **没有进游戏**：所有事件都只在离线复现器里用独立 `BusBuilder` 总线和 `Unsafe` 分配的控制器跑过；
  真实服务端 tick、区块加载/卸载、多人环境未跑。
- **没有真实附属模组**：没有第二个 `@Mod` 订阅这些事件；真实模组构造顺序、事件总线阶段、
  与服务端线程的交互未验证。
- **没有工厂对照**：工厂路径只做了源码断言“不派发 Recipe 事件”，没有实际工厂线程。**0.88.0 新增的 setFailed / RecipeFailureEvent / 失败原因字符串通道同样未实机**（否决路径、update tag 同步、界面原因行显示）。
- **监听器异常**：Forge 6.0.5 的 `EventBus#post` 会先记录再**重新抛出**监听器异常；本版没有在每个
  派发点包 try/catch。监听器自己抛异常会中断本次控制器 tick。请在自己的处理器里捕获预期异常。
- **性能**：每个服务端 tick 至少两次 `MachineTickEvent`；单配方每 tick 两次 `RecipeTickEvent`。
  没有做事件对象池或“无监听器时跳过”的优化；监听器应保持轻量。
