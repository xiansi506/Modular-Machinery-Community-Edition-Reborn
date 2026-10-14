package com.reborn.modularmachinery.compat;

import com.reborn.modularmachinery.ModularMachineryReborn;
import com.reborn.modularmachinery.recipe.RequirementTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 兼容层的接入点：判断哪些兼容模块可用，然后让它们注册自己。
 *
 * <h2>什么时候注册，以及为什么是这个时机</h2>
 *
 * <p>在 **{@code FMLCommonSetupEvent}** 里，也就是**所有模组都构造完之后**。理由有两条，一条是技术上的、
 * 一条是纪律上的：
 *
 * <ul>
 *   <li>「这个模组装了没」这个问题在 mod 构造阶段**能不能问**，我**未查到依据**：本机 fmlcore 没有源码，
 *       {@code ModList.setLoadedMods} 是包内方法，它相对构造期的顺序读不出来。构造期读一个还没填好的表，
 *       后果是兼容类型**静默地永不注册**——这种 bug 只在装了那个模组的玩家身上出现，而开发者本机永远复现不了。
 *       放到 {@code FMLCommonSetupEvent} 就没有这个不确定性：那个事件按定义在所有构造之后触发。</li>
 *   <li>需求类型**不是** Forge 注册表内容，所以这一步不违 D8（方块只能在构造期注册、注册表随后冻结）。
 *       这也是为什么兼容层的**方块**入口至今没写：那需要构造期就拿到模块清单，是另一条路。</li>
 * </ul>
 *
 * <h2>两道门，不是重复</h2>
 *
 * <ol>
 *   <li>{@link CompatModule#requiresModid()}：注册**之前**筛掉依赖没装的模块（这个类在做）。</li>
 *   <li>{@link RequirementTypes#removeUnavailable}：注册**之后**把所有声明了 {@code requiresModid} 而模组不在场的
 *       类型摘掉，照旧版 {@code IntegrationTypeHelper.filterModIdRequirementTypes}（{@code :45-58}）。</li>
 * </ol>
 *
 * <p>第二道门存在的意义正是「第一道门可能判断错」。只有第一道门时，一次时机错误就会在注册表里留下一种
 * 永远不可用的需求；有第二道门，最坏情况只是它被晚一点摘掉。
 */
public final class ModCompat {

    /**
     * 已注册的兼容模块，**只增不减**。
     *
     * <p>不在每次 {@code FMLCommonSetupEvent} 时清空：那个事件在客户端可能因资源重载而多次触发，
     * 而重复注册同一种需求会被「重复 id 即报错」挡住。模块的集合在一次启动里不会变，
     * 所以「已经处理过」这个事实记在这里，比每次重新判断更诚实。
     */
    private static final Map<ResourceLocation, CompatModule> REGISTERED = new LinkedHashMap<>();

    /**
     * 本模组**已知**的兼容模块清单。
     *
     * <p>空的是**事实，不是 TODO**：波 5.3 的每个模块都要先拿到该模组 1.20.1 的 API jar，
     * 而本机 {@code libs/} 里只有 jei / kubejs / rhino（交接文档已核实）。所以这一波交付的是
     * 「框架能用」，而不是「某个模块能用」。一个模块写好了就在这里加一行——**只需改这一行**，
     * 主模组其余部分不必知道它的存在。
     *
     * <p>写成 {@code List.of()} 而不是反射扫描：反射能省掉这一行，代价是「哪些模块在」变成运行时才知道的
     * 事情，构建期没有任何东西能检查它。而这里的模块都是编译期就存在的类（它们要引用该模组的 API）。
     */
    private static List<CompatModule> modules() {
        return List.of();
    }

    private ModCompat() {
    }

    /** Forge 事件入口；注册在 {@code ModularMachineryReborn} 里。 */
    public static void onCommonSetup(FMLCommonSetupEvent event) {
        register(modules(), ModList.get()::isLoaded);
    }

    /**
     * 兼容层的真正逻辑，与 FML 无关。
     *
     * <p>{@code isLoaded} 传进来而不是在里面调 {@code ModList}：这一条是波 5.2 唯一能被离线断言的
     * 「框架行为」（哪个模块被调用、哪个被跳过、依赖没装的类型有没有被摘掉），
     * 而离线环境里没有 FML。
     *
     * @param modules  候选模块
     * @param isLoaded 某个 modid 装了没
     * @return 这次真的被调用过 {@code register} 的模块，按清单顺序
     */
    public static List<CompatModule> register(List<CompatModule> modules, Predicate<String> isLoaded) {
        List<CompatModule> registered = new ArrayList<>();
        for (CompatModule module : modules) {
            ResourceLocation id = module.id();
            if (REGISTERED.containsKey(id)) {
                // 同一个模块在一次启动里只会被注册一次：重复注册会撞上 RequirementTypes 的「重复 id 即报错」，
                // 而那条报错在这种情形下是**假警报**（不是命名冲突，是调用点重复），所以在这里挡掉。
                continue;
            }
            String required = module.requiresModid();
            if (required != null && !isLoaded.test(required)) {
                ModularMachineryReborn.LOGGER.info("[{}] Compat module {} is not loaded: it needs {}.",
                        ModularMachineryReborn.MOD_ID, id, required);
                continue;
            }
            CompatRegistry registry = new CompatRegistry();
            module.register(registry);
            REGISTERED.put(id, module);
            registered.add(module);
            ModularMachineryReborn.LOGGER.info("[{}] Compat module {} registered. Requirement kinds now: {}",
                    ModularMachineryReborn.MOD_ID, id, RequirementTypes.ids());
        }
        // 兜底：任何**声明了**依赖、而那个模组不在场的需求类型，一律从注册表里摘掉。
        // 这一条会把内置五种之外的一切都过一遍，而内置五种的 requiresModid() 是 null，所以不会误伤。
        List<ResourceLocation> removed = RequirementTypes.removeUnavailable(isLoaded);
        if (!removed.isEmpty()) {
            ModularMachineryReborn.LOGGER.info("[{}] Requirement kinds withdrawn because their mod is not "
                    + "installed: {}", ModularMachineryReborn.MOD_ID, removed);
        }
        return registered;
    }

    /** 已经注册的兼容模块，按注册顺序；给诊断与断言用。 */
    public static Map<ResourceLocation, CompatModule> registered() {
        return Map.copyOf(REGISTERED);
    }

    /** 把「已知模块清单」暴露给断言，免得断言自己抄一份（抄一份就迟早与实现不一致）。 */
    public static List<CompatModule> knownModules() {
        return modules();
    }
}
