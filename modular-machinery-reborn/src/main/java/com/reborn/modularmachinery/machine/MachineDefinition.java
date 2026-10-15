package com.reborn.modularmachinery.machine;

import com.reborn.modularmachinery.factory.FactoryThreadModel;
import com.reborn.modularmachinery.recipe.RecipeModifier;
import com.reborn.modularmachinery.recipe.RecipeModifiers;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * A loaded machine definition: the data-driven counterpart of the original {@code DynamicMachine}.
 *
 * <p>M1 carries the fields that describe a structure and the machine's identity. The remaining original root
 * fields ({@code color}, {@code prefix}, {@code has-factory}, {@code factory-only},
 * {@code hide-components-when-formed}, {@code controller-bounding-box}, {@code dynamic-patterns}) are
 * deliberately not implemented yet and are still listed as gaps.
 *
 * <h2>M6c: parallelism and modifiers</h2>
 *
 * <p>Three root fields were added in 0.19.0 that the original's machine JSON schema did <b>not</b> have:
 * {@code max-parallelism}, {@code internal-parallelism} and {@code parallelizable}. The original's equivalents
 * lived on {@code AbstractMachine} and were only reachable from a CraftTweaker script through
 * {@code MachineModifier#setMaxParallelism} / {@code #setParallelizable}; this project delivers machines as data
 * (D3/D9/D10), so without these fields a data-pack machine could never be parallel at all. See the {@code D12}
 * record in {@code 移植方案-v2.md}.
 *
 * <p>{@code modifiers} is also evaluated from 0.19.0; it had been "recognised but not evaluated" since M1.
 *
 * <h2>M6e: the factory fields</h2>
 *
 * <p>Four more root fields arrive in 0.22.0 and leave {@code MachineLoader.UNIMPLEMENTED_ROOT_FIELDS}:
 * {@code has-factory} and {@code factory-only} (both in the original's schema, both parsed and ignored until
 * now), plus {@code max-threads} and {@code core-threads} (the original kept those on {@code AbstractMachine}
 * and only a CraftTweaker script could reach them, so they join {@code max-parallelism} and
 * {@code internal-parallelism} as this project's documented schema extensions — D12's reasoning applies
 * unchanged). See the D15 record in {@code 移植方案-v2.md}.
 *
 * <h2>M6d-b: the smart-interface declarations</h2>
 *
 * <p>{@code smart-interfaces} arrives in 0.24.0 and is the <b>fourth</b> schema divergence, for the same reason
 * as D12/D15: in the original the interface types were registered <b>only</b> by CraftTweaker
 * ({@code MachineModifier#addSmartInterfaceType:27-38}, {@code MachineBuilder#addSmartInterfaceType:337-341})
 * and {@code DynamicMachine.smartInterfaces} had no JSON entry point whatsoever
 * ({@code DynamicMachinePreDeserializer} never mentions it). A data-pack author here has no script, so without
 * the field a machine could never have an interface and {@code RequirementInterfaceNumInput} — the only reader
 * of any interface value — could never be satisfied. See the D16 record in {@code 移植方案-v2.md}.
 */
public final class MachineDefinition {

    /** The original's {@code Config.maxMachineParallelism}. */
    public static final int DEFAULT_MAX_PARALLELISM = 2048;
    /** The original's {@code AbstractMachine.internalParallelism}. */
    public static final int DEFAULT_INTERNAL_PARALLELISM = 0;
    /** The original's {@code Config.machineParallelizeEnabledByDefault}. */
    public static final boolean DEFAULT_PARALLELIZABLE = true;
    /**
     * The original's {@code Config.defaultFactoryMaxThread}, which {@code AbstractMachine.maxThreads} started
     * from — read through the config key ({@code factory-system.default-factory-max-thread}) rather than frozen
     * here, because M6e-2 made it configurable. The original's own config-file default is {@code 10}
     * ({@code Config.java:122-124}); see {@link com.reborn.modularmachinery.config.ModConfig} for why that value
     * and not the {@code 20} field initialiser.
     */
    public static final int DEFAULT_MAX_THREADS =
            com.reborn.modularmachinery.config.ModConfig.factoryDefaultMaxThread();
    /**
     * The original's {@code Config.enableFactoryControllerByDefault}, which {@code AbstractMachine.hasFactory}
     * started from — likewise read through its config key
     * ({@code factory-system.enable-factory-controller-bydefault}).
     */
    public static final boolean DEFAULT_HAS_FACTORY =
            com.reborn.modularmachinery.config.ModConfig.factoryControllerEnabledByDefault();
    /** The original's {@code AbstractMachine.factoryOnly}. */
    public static final boolean DEFAULT_FACTORY_ONLY = false;
    /**
     * The action a machine that does not declare {@code failure-action} gets — the original's config key
     * {@code default-failure-actions} in category {@code general}, whose default is {@code "still"}
     * ({@code RecipeFailureActions.java:38-46}, read from {@code Config.java:69}) and which seeded every
     * machine's field ({@code AbstractMachine.java:29}).
     *
     * <p>Kept as a constant rather than a config key of its own: this project has one config spec and one
     * {@code registerConfig} call (see {@code ModConfig}), and an author who wants another action says so in
     * the machine's own definition. {@code STILL} is also what this mod did before the field was consumed — a
     * craft whose per-tick requirement could not be served held its progress — so no existing definition
     * changes behaviour.
     */
    public static final FailureAction DEFAULT_FAILURE_ACTION = FailureAction.STILL;

    private final ResourceLocation id;
    private final String localizedName;
    private final MachinePattern pattern;
    private final FailureAction failureAction;
    private final boolean requiresBlueprint;
    private final int maxParallelism;
    private final int internalParallelism;
    private final boolean parallelizable;
    private final List<MachineModifier> modifiers;

    // ---------------------------------------------------------------- M6e: the factory
    private final int maxThreads;
    private final boolean hasFactory;
    private final boolean factoryOnly;
    private final List<FactoryThreadModel.CoreThreadSpec> coreThreads;

    // ---------------------------------------------------------------- M6d-b: the smart interfaces
    private final List<SmartInterfaceType> smartInterfaces;

    /**
     * 这台机器的动态结构（{@code dynamic-patterns}）：沿某个面重复、长度随世界变化的段落。
     *
     * <p>空列表表示「这台机器的形状是固定的」，也就是绝大多数机器的情形。空列表而不是 {@code null}：
     * 「没有动态结构」是一个正常状态而不是缺失，让调用者少一处判空。
     */
    private final List<DynamicPattern> dynamicPatterns;
    /**
     * 机器外壳的染色（{@code color}），旧版 {@code AbstractMachine.definedColor}。
     * 默认白色 = 不染色；见 {@link #DEFAULT_COLOR} 与 {@link #withColor}。
     */
    private final int color;

    public MachineDefinition(ResourceLocation id, String localizedName, MachinePattern pattern,
                             FailureAction failureAction, boolean requiresBlueprint) {
        this(id, localizedName, pattern, failureAction, requiresBlueprint,
                DEFAULT_MAX_PARALLELISM, DEFAULT_INTERNAL_PARALLELISM, DEFAULT_PARALLELIZABLE, List.of());
    }

    /**
     * 定义里 {@code color} 的默认值——白色，也就是「不染色」。
     *
     * <p>旧版这里不是常量：{@code AbstractMachine.definedColor} 从 {@code Config.machineColor} 起步，
     * 而那个配置键（{@code general-casing-color}，默认 {@code "FFFFFF"}）本版**没有移植**。
     * 本版先固定为白：**没有配置键时，「不染色」是唯一不撒谎的默认值**——编一个颜色出来就等于
     * 替包作者做了决定。等真的要做配置（与 §10 的其它配置项一起）时再把它接上。
     */
    public static final int DEFAULT_COLOR = 0xFFFFFF;

    /**
     * 解析定义里的 {@code color} 字段：**十六进制字符串**（旧版 {@code DynamicMachinePreDeserializer#getColor}）。
     *
     * <p>两条错误与旧版逐条对应，因为它们决定了包作者看得到的报错文案：
     * 非字符串 → 「应该是十六进制整数」；解析失败 → 同一句话（旧版第二条把它当 cause 包起来）。
     *
     * <p><b>与旧版的一处有意差异</b>：旧版用 {@code Integer.parseInt(hexStr, 16)}，于是
     * {@code "FFFFFF"} 与 {@code "FFFFFFFF"} 都能过（后者溢出成负数，被当成带 alpha 的颜色）。
     * 本版要求**恰好六个十六进制位**：多出来的位数在 1.20.1 的 block color handler 里没有意义
     * （那条路只取 RGB），接受它只会让包作者以为自己写了 alpha。少写位数同样拒绝——{@code "FFF"}
     * 在旧版会被当成 {@code 0x000FFF}（深蓝），那显然不是作者想要的。
     */
    public static int parseColor(com.google.gson.JsonObject root, String where) {
        com.google.gson.JsonElement element = root.get("color");
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("[color] 必须是十六进制字符串（例如 \"FF00FF\"）——"
                    + "在 " + where + " 里读到的是 " + element);
        }
        String text = element.getAsString();
        if (!text.matches("[0-9a-fA-F]{6}")) {
            throw new IllegalArgumentException("[color] 必须是**恰好六位**十六进制数字（例如 \"FF00FF\"）——"
                    + "在 " + where + " 里读到的是 \"" + text + "\"");
        }
        return Integer.parseInt(text, 16);
    }

    public MachineDefinition(ResourceLocation id, String localizedName, MachinePattern pattern,
                             FailureAction failureAction, boolean requiresBlueprint,
                             int maxParallelism, int internalParallelism, boolean parallelizable,
                             List<MachineModifier> modifiers) {
        this(id, localizedName, pattern, failureAction, requiresBlueprint, maxParallelism, internalParallelism,
                parallelizable, modifiers, DEFAULT_MAX_THREADS, DEFAULT_HAS_FACTORY, DEFAULT_FACTORY_ONLY,
                List.of());
    }

    public MachineDefinition(ResourceLocation id, String localizedName, MachinePattern pattern,
                             FailureAction failureAction, boolean requiresBlueprint,
                             int maxParallelism, int internalParallelism, boolean parallelizable,
                             List<MachineModifier> modifiers,
                             int maxThreads, boolean hasFactory, boolean factoryOnly,
                             List<FactoryThreadModel.CoreThreadSpec> coreThreads) {
        this(id, localizedName, pattern, failureAction, requiresBlueprint, maxParallelism, internalParallelism,
                parallelizable, modifiers, maxThreads, hasFactory, factoryOnly, coreThreads, List.of());
    }

    public MachineDefinition(ResourceLocation id, String localizedName, MachinePattern pattern,
                             FailureAction failureAction, boolean requiresBlueprint,
                             int maxParallelism, int internalParallelism, boolean parallelizable,
                             List<MachineModifier> modifiers,
                             int maxThreads, boolean hasFactory, boolean factoryOnly,
                             List<FactoryThreadModel.CoreThreadSpec> coreThreads,
                             List<SmartInterfaceType> smartInterfaces) {
        this(id, localizedName, pattern, failureAction, requiresBlueprint, maxParallelism, internalParallelism,
                parallelizable, modifiers, maxThreads, hasFactory, factoryOnly, coreThreads, smartInterfaces,
                List.of());
    }

    /**
     * 规范构造器。最后一批参数是波 3b 的动态结构（{@code dynamic-patterns}）。
     *
     * <p>上面的几个重载保留下来，是为了不动既有的调用点与复现器：绝大多数定义<strong>没有</strong>动态结构，
     * 而「没有」用一个空列表表示就够了。
     */
    public MachineDefinition(ResourceLocation id, String localizedName, MachinePattern pattern,
                             FailureAction failureAction, boolean requiresBlueprint,
                             int maxParallelism, int internalParallelism, boolean parallelizable,
                             List<MachineModifier> modifiers,
                             int maxThreads, boolean hasFactory, boolean factoryOnly,
                             List<FactoryThreadModel.CoreThreadSpec> coreThreads,
                             List<SmartInterfaceType> smartInterfaces,
                             List<DynamicPattern> dynamicPatterns) {
        this(id, localizedName, pattern, failureAction, requiresBlueprint, maxParallelism, internalParallelism,
                parallelizable, modifiers, maxThreads, hasFactory, factoryOnly, coreThreads, smartInterfaces,
                dynamicPatterns, DEFAULT_COLOR);
    }

    /** 规范构造器：**唯一**给所有字段赋值的那一个，包括 {@code color}。 */
    private MachineDefinition(ResourceLocation id, String localizedName, MachinePattern pattern,
                              FailureAction failureAction, boolean requiresBlueprint,
                              int maxParallelism, int internalParallelism, boolean parallelizable,
                              List<MachineModifier> modifiers,
                              int maxThreads, boolean hasFactory, boolean factoryOnly,
                              List<FactoryThreadModel.CoreThreadSpec> coreThreads,
                              List<SmartInterfaceType> smartInterfaces,
                              List<DynamicPattern> dynamicPatterns, int color) {
        this.id = id;
        this.localizedName = localizedName;
        this.pattern = pattern;
        this.failureAction = failureAction;
        this.requiresBlueprint = requiresBlueprint;
        this.maxParallelism = maxParallelism;
        this.internalParallelism = internalParallelism;
        this.parallelizable = parallelizable;
        this.modifiers = List.copyOf(modifiers);
        this.maxThreads = Math.max(0, maxThreads);
        this.hasFactory = hasFactory;
        this.factoryOnly = factoryOnly;
        this.coreThreads = List.copyOf(coreThreads);
        this.smartInterfaces = List.copyOf(smartInterfaces);
        this.dynamicPatterns = List.copyOf(dynamicPatterns);
        this.color = color;
    }

    /**
     * 定义里 {@code color}（机器外壳的染色），默认 {@link #DEFAULT_COLOR}。
     *
     * <p>旧版把它放在 {@code AbstractMachine.definedColor} 上、由 {@code DynamicMachine} 在解析时设置；
     * 本版照着放在定义上，并且**不可变**——「设置」做成返回新对象的 {@link #withColor}，
     * 因为一个已经建好的定义不该在解析期之外被改。
     */
    public int color() {
        return this.color;
    }

    /** 带指定染色的副本；解析器用它，而不是去改一个已经建好的定义。 */
    public MachineDefinition withColor(int color) {
        return new MachineDefinition(this.id, this.localizedName, this.pattern, this.failureAction,
                this.requiresBlueprint, this.maxParallelism, this.internalParallelism, this.parallelizable,
                this.modifiers, this.maxThreads, this.hasFactory, this.factoryOnly, this.coreThreads,
                this.smartInterfaces, this.dynamicPatterns, color);
    }

    /** Registry name of the machine, e.g. {@code modular_machinery_reborn:transformer}. */
    public ResourceLocation id() {
        return this.id;
    }

    /** The literal {@code localizedname} from the definition file. */
    public String localizedName() {
        return this.localizedName;
    }

    /**
     * Display name, following the original: the translation key {@code <namespace>.<path>} wins when present,
     * otherwise the literal {@code localizedname} is used.
     */
    public Component displayName() {
        return Component.translatableWithFallback(this.id.getNamespace() + "." + this.id.getPath(), this.localizedName);
    }

    public MachinePattern pattern() {
        return this.pattern;
    }

    public FailureAction failureAction() {
        return this.failureAction;
    }

    /** Whether the original required a blueprint before the machine may run. Parsed but not yet enforced. */
    public boolean requiresBlueprint() {
        return this.requiresBlueprint;
    }

    // ------------------------------------------------------------------ M6c

    /**
     * {@code max-parallelism}: the hard ceiling this machine's parallelism is clamped to, the original's
     * {@code AbstractMachine#getMaxParallelism}.
     */
    public int maxParallelism() {
        return this.maxParallelism;
    }

    /**
     * {@code internal-parallelism}: how many copies the machine can run <b>by itself</b>, before any parallel
     * controller contributes, the original's {@code AbstractMachine#internalParallelism}.
     *
     * <p>This is the field that actually turns parallelism on. The original's
     * {@code TileMultiblockMachineController#getMaxParallelism} starts from this value, adds every parallel
     * controller found in the structure, clamps the sum to {@code max-parallelism}, and floors it at 1 — so a
     * machine with {@code internal-parallelism: 0} and no controller runs one copy no matter what its
     * {@code max-parallelism} says.
     */
    public int internalParallelism() {
        return this.internalParallelism;
    }

    /**
     * {@code parallelizable}: whether this machine may run in parallel at all, the original's
     * {@code AbstractMachine#isParallelizable}, which
     * {@code TileMultiblockMachineController#isParallelized} combined with a parallelism above 1.
     */
    public boolean parallelizable() {
        return this.parallelizable;
    }

    /** Whether this machine has any parallelism available, i.e. the original's {@code isParallelized}. */
    public boolean parallelEnabled() {
        return this.parallelizable && effectiveParallelCeiling() > 1;
    }

    /**
     * The machine's own parallelism ceiling after {@code internal-parallelism} is floored at 1 and clamped to
     * {@code max-parallelism} — the original's {@code Math.max(1, parallelism)} over a <b>controller-less</b>
     * structure.
     *
     * <p>From M6d this is the zero-controller case of {@link ParallelismLimit#resolve}: the real ceiling of a
     * formed machine adds the parallel controllers standing in it, and the controller block entity therefore
     * calls that method directly rather than this one.
     */
    public int effectiveParallelCeiling() {
        return ParallelismLimit.resolve(this, 0);
    }

    /** The {@code modifiers} entries, evaluated against the formed structure at craft time. */
    public List<MachineModifier> modifiers() {
        return this.modifiers;
    }

    /** The recipe modifiers contributed by an arbitrary set of the machine's structure modifiers. */
    public static RecipeModifiers flatten(List<MachineModifier> active) {
        if (active.isEmpty()) {
            return RecipeModifiers.EMPTY;
        }
        List<RecipeModifier> flat = new ArrayList<>();
        for (MachineModifier modifier : active) {
            flat.addAll(modifier.modifiers());
        }
        return RecipeModifiers.of(flat);
    }

    // ------------------------------------------------------------------ M6e: the factory

    /**
     * {@code max-threads}: how many ordinary factory threads this machine allows — the original's
     * {@code AbstractMachine#getMaxThreads} ({@code :133-135}), which started from
     * {@code Config.defaultFactoryMaxThread = 20} and was only reachable from a CraftTweaker script.
     *
     * <p>Like {@code max-parallelism} (D12), this is a root field the original's machine JSON did not have.
     * The reason is the same: a data-pack author has no script, and without the field their machine could never
     * be anything but a twenty-thread factory.
     */
    public int maxThreads() {
        return this.maxThreads;
    }

    /**
     * {@code has-factory}: whether a factory controller may form this machine at all — the original's
     * {@code AbstractMachine#isHasFactory()}.
     *
     * <p>In the original this decided two things: whether {@code <machine>_factory_controller} was registered
     * at all, and — through {@code Config.enableFactoryControllerByDefault}, which defaulted to
     * {@code false} — nothing else. It is read here as the second half of {@code factoryEnabled}: the factory
     * controller's <b>block</b> must also be a factory block. See the D15 record in {@code 移植方案-v2.md}.
     */
    public boolean hasFactory() {
        return this.hasFactory;
    }

    /**
     * {@code factory-only}: the original's {@code AbstractMachine#isFactoryOnly()}. Such a machine is
     * <b>not</b> given an ordinary controller block by the original ({@code RegistryBlocks.java:431-433}), so
     * its structure can only ever be built around a factory controller.
     *
     * <p>This project cannot act on that at construction from a data-pack definition (the block set is frozen
     * before definitions load), so the flag is parsed, reported and used for the loader's own consistency
     * warning rather than silently ignored. Recorded in D15.
     */
    public boolean factoryOnly() {
        return this.factoryOnly;
    }

    /**
     * The core-thread preset: threads that exist for as long as the machine stands, each optionally pinned to
     * a fixed recipe set — the original's {@code DynamicMachine#coreThreadPreset} /
     * {@code FactoryRecipeThread#recipeSet}.
     */
    public List<FactoryThreadModel.CoreThreadSpec> coreThreads() {
        return this.coreThreads;
    }

    /** Whether this machine may run on a factory controller at all. */
    public boolean factoryEnabled() {
        return FactoryThreadModel.allowsFactory(this);
    }

    // ------------------------------------------------------------------ M6d-b: the smart interfaces

    /**
     * {@code smart-interfaces}: the interface types this machine declares, in declaration order.
     *
     * <p>Order matters in exactly one place — {@link #smartInterface(String)} plus the loader's own builder walk
     * is what decides which declared type a value binds to when the player has not chosen one — so the list is
     * kept as written and the priority sort happens in {@link SmartInterfaceType#highestPriority}. Every other
     * lookup is by name.
     */
    public List<SmartInterfaceType> smartInterfaces() {
        return this.smartInterfaces;
    }

    /**
     * 这台机器的动态结构，按定义里的顺序；没有动态结构时是<strong>空列表</strong>。
     *
     * <p>顺序有意义：结构匹配时每个模式都要在世界里量一次长度，全部量到才成型
     * （旧版 {@code matchesDynamicPatternRotation} 一旦有一个不匹配就整体返回 false）。
     */
    public List<DynamicPattern> dynamicPatterns() {
        return this.dynamicPatterns;
    }

    /** 这台机器是否有任何动态结构。空列表是常态，所以这个判断比 {@code !dynamicPatterns().isEmpty()} 好读。 */
    public boolean hasDynamicPatterns() {
        return !this.dynamicPatterns.isEmpty();
    }

    /** One declared type by name, or {@code null} — the original's {@code DynamicMachine#getSmartInterfaceType}. */
    @javax.annotation.Nullable
    public SmartInterfaceType smartInterface(String type) {
        for (SmartInterfaceType candidate : this.smartInterfaces) {
            if (candidate.type().equals(type)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Whether this machine declares any interface type at all — the original's
     * {@code DynamicMachine#smartInterfaceTypesIsEmpty}, which gated {@code checkAndAddSmartInterface}
     * ({@code TileMultiblockMachineController:910}). A machine that declares none cannot use one, and that is a
     * decision this project asserts offline rather than documents.
     */
    public boolean hasSmartInterfaces() {
        return !this.smartInterfaces.isEmpty();
    }

    /** The name of every declared type, in declaration order. */
    public List<String> smartInterfaceNames() {
        List<String> names = new ArrayList<>(this.smartInterfaces.size());
        for (SmartInterfaceType type : this.smartInterfaces) {
            names.add(type.type());
        }
        return List.copyOf(names);
    }

    @Override
    public String toString() {
        return "MachineDefinition[" + this.id + ", parts=" + this.pattern.partCount()
                + ", size=" + this.pattern.size().toShortString()
                + ", maxParallelism=" + this.maxParallelism
                + ", internalParallelism=" + this.internalParallelism
                + ", parallelizable=" + this.parallelizable
                + ", modifiers=" + this.modifiers.size()
                + ", maxThreads=" + this.maxThreads
                + ", hasFactory=" + this.hasFactory
                + ", factoryOnly=" + this.factoryOnly
                + ", coreThreads=" + this.coreThreads.size()
                + ", smartInterfaces=" + this.smartInterfaces.size() + "]";
    }
}
