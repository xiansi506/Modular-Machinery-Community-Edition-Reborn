package com.reborn.modularmachinery.machine;

import mekanism.api.chemical.gas.IGasHandler;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.items.IItemHandler;

import java.util.ArrayList;
import java.util.List;

/**
 * The hatches belonging to one formed machine, bucketed by family and direction.
 *
 * <p>The original collected its structure's {@code MachineComponent}s the same way and then let the recipe
 * requirement engine draw from whichever component could serve a requirement. M3 keeps the simple recipe model
 * of M1 but routes it through these ports instead of the controller's own slots: items come from item input
 * hatches, FE from energy input hatches, and results go to item output hatches.
 *
 * <p>Fluid hatches are collected and fully functional as storage (pipes can fill and drain them), but the
 * current recipe type has no fluid fields — those arrive with the requirement engine.
 *
 * <h2>M6d-b: the smart-interface values travel with the ports</h2>
 *
 * <p>{@code interface_number_input} is a requirement that reads a number the controller holds rather than one a
 * hatch holds, and the recipe lifecycle has no per-controller context object to pass it through
 * ({@code RecipeCraftingContext} is M2's deliberate omission — the modifiers are method parameters instead). The
 * per-craft object the lifecycle does already pass to every requirement is this one, and it is rebuilt from the
 * controller on every structure check, so the value source rides along here.
 *
 * <p>It is a {@link java.util.function.Supplier} rather than a snapshot because a player may edit the value
 * while a craft is running: the requirement re-reads it on each check, which is what reading a live block
 * entity's field did in the original.
 */
public final class HatchCollection {

    public static final HatchCollection EMPTY = new HatchCollection(List.of(), List.of(), List.of(),
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
            () -> SmartInterfaceValueSource.NONE, () -> null);

    /**
     * 「这个端口没有标签」的哨兵值。
     *
     * <p>为什么不用 {@code null}：{@code List} 与 {@code List.copyOf} 都<strong>接受</strong> {@code null} 元素，
     * 所以一个漏打了标签的端口会安静地混进去，直到某次筛选把它当成「没标签」而<strong>错误地</strong>交给一个
     * 无标签的需求。一个显式哨兵让这种遗漏无处可藏——{@link #withTag} 里两个分支都只认这两种值之一。
     */
    private static final String UNTAGGED = "\u0000untagged";

    private final List<IItemHandler> itemInputs;
    private final List<IItemHandler> itemOutputs;
    private final List<IFluidHandler> fluidInputs;
    private final List<IFluidHandler> fluidOutputs;
    private final List<IEnergyStorage> energyInputs;
    private final List<IEnergyStorage> energyOutputs;

    /**
     * 每个端口来自哪个打了标签的结构位置，与上面六个桶<strong>逐个对应</strong>。
     *
     * <p>这是旧版 {@code ProcessingComponent} 携带 {@code ComponentSelectorTag} 的对应层
     * （{@code ProcessingComponent.java:41}），也是 {@code selector-tag} 能起作用的全部依据：
     * 需求通过标签筛选端口，靠的就是「每个端口知道自己属于哪个部件」。
     *
     * <p>与端口桶分开存而不是把端口包成一个带标签的记录，是为了不惊动已有的六个访问器——
     * {@link #itemInputs()} 这些方法是整个配方引擎的读取面，它们的形状不变，标签只是多出来的一层筛选。
     */
    private final List<String> itemInputTags;
    private final List<String> itemOutputTags;
    private final List<String> fluidInputTags;
    private final List<String> fluidOutputTags;
    private final List<String> energyInputTags;
    private final List<String> energyOutputTags;

    /**
     * Where a smart-interface requirement reads its value. Never {@code null}: {@link
     * SmartInterfaceValueSource#NONE} is the honest answer for a collection built without a controller, and it
     * answers {@code null} for every type — which is the original's "no interface of that type found" case.
     */
    private final java.util.function.Supplier<SmartInterfaceValueSource> smartInterfaces;

    /**
     * The formed machine's definition, for the one thing a requirement needs from it: the interface type's own
     * {@code notequal} message. It is a supplier for the same reason the values are — the machine a controller
     * forms can change between structure checks.
     */
    private final java.util.function.Supplier<MachineDefinition> machine;

    private HatchCollection(List<IItemHandler> itemInputs, List<IItemHandler> itemOutputs,
                            List<IFluidHandler> fluidInputs, List<IFluidHandler> fluidOutputs,
                            List<IEnergyStorage> energyInputs, List<IEnergyStorage> energyOutputs,
                            List<String> itemInputTags, List<String> itemOutputTags,
                            List<String> fluidInputTags, List<String> fluidOutputTags,
                            List<String> energyInputTags, List<String> energyOutputTags,
                            java.util.function.Supplier<SmartInterfaceValueSource> smartInterfaces,
                            java.util.function.Supplier<MachineDefinition> machine) {
        this.itemInputs = List.copyOf(itemInputs);
        this.itemOutputs = List.copyOf(itemOutputs);
        this.fluidInputs = List.copyOf(fluidInputs);
        this.fluidOutputs = List.copyOf(fluidOutputs);
        this.energyInputs = List.copyOf(energyInputs);
        this.energyOutputs = List.copyOf(energyOutputs);
        // 断言而不是信任：一个长度不匹配的标签列表会让「端口 i 的标签」整体错位，
        // 而错位是静默的——它只会让某个需求读到另一个部件的端口。与其在世界里查，不如在这里就炸。
        this.itemInputTags = checkedTags(itemInputTags, this.itemInputs.size(), "itemInputs");
        this.itemOutputTags = checkedTags(itemOutputTags, this.itemOutputs.size(), "itemOutputs");
        this.fluidInputTags = checkedTags(fluidInputTags, this.fluidInputs.size(), "fluidInputs");
        this.fluidOutputTags = checkedTags(fluidOutputTags, this.fluidOutputs.size(), "fluidOutputs");
        this.energyInputTags = checkedTags(energyInputTags, this.energyInputs.size(), "energyInputs");
        this.energyOutputTags = checkedTags(energyOutputTags, this.energyOutputs.size(), "energyOutputs");
        this.smartInterfaces = smartInterfaces;
        this.machine = machine;
    }

    private static List<String> checkedTags(List<String> tags, int ports, String bucket) {
        if (tags.size() != ports) {
            throw new IllegalStateException("HatchCollection." + bucket + " has " + ports + " ports but "
                    + tags.size() + " tags; the two lists are positional and must line up");
        }
        return List.copyOf(tags);
    }

    public List<IItemHandler> itemInputs() {
        return this.itemInputs;
    }

    public List<IItemHandler> itemOutputs() {
        return this.itemOutputs;
    }

    public List<IFluidHandler> fluidInputs() {
        return this.fluidInputs;
    }

    public List<IFluidHandler> fluidOutputs() {
        return this.fluidOutputs;
    }

    public List<IEnergyStorage> energyInputs() {
        return this.energyInputs;
    }

    public List<IEnergyStorage> energyOutputs() {
        return this.energyOutputs;
    }

    /**
     * 气体端口，**永远是空的**（见 {@link #gasInputs()} 的说明）。
     *
     * <p>做成 {@code static final} 常量而不是构造器参数，是因为「现在一个端口都没有」不是一个
     * 需要逐实例保存的事实——那样会往 {@link #withTag} 与 {@link Builder#build} 各塞一个恒为空的参数，
     * 也就是给未来的自己留两个必须记得传对的地方。等端口真的接通时，它会连同自己的标签列表一起
     * 变成实例字段，那时这场改动本来就要动那些地方。
     */
    private static final List<IGasHandler> NO_GAS_PORTS = List.of();

    /**
     * 气体（Mekanism 化学体系）端口。
     *
     * <p><b>它们现在永远是空的，这是事实而不是占位。</b>化学端口还没有实现：旧版把它做在**已有的流体仓**
     * 上（{@code HybridGasTank extends HybridTank}，宿主 {@code TileFluidTank} / {@code GuiContainerFluidHatch}，
     * 全部 {@code @Optional.Method(modid = "mekanism")}），所以 1.20.1 这一侧的正确形状是「给流体仓加一个
     * 可选的气体能力」，而不是新方块——那条路与本项目的 D8（方块只能在构造期注册）不冲突，
     * 但它是**结算路径上的结构改动**，因此单独一轮做，见
     * {@code docs/波5.3-勘测-Mekanism化学体系.md} §4。
     *
     * <p><b>为什么把这两个访问器先放进来：</b>需求类要能编译、要被断言驱动，就必须有一个统一的端口读取面。
     * 若气体需求自己去别处取端口，那么等端口真的接通时，「哪几处能看气体端口」就会有第二个答案——
     * 这正是本项目已经吃过一次的「两条入口各自演进」。
     *
     * <p><b>空列表的语义是安全的：</b>{@code GasIo} 对空列表一律答「没有 / 抽不到 / 灌不进」，
     * 于是一条气体配方在没有化学端口的世界里表现为**开工失败**（并给出 {@code craftcheck.failure.gas.input}），
     * 而不是崩溃或静默成功。
     */
    public List<IGasHandler> gasInputs() {
        return NO_GAS_PORTS;
    }

    /** 气体输出端口。同 {@link #gasInputs()}：现在永远是空的。 */
    public List<IGasHandler> gasOutputs() {
        return NO_GAS_PORTS;
    }

    /**
     * 只看属于 {@code selectorTag} 那个部件的端口；{@code null} 表示只看<strong>没有标签</strong>的端口。
     *
     * <p>这是 {@code selector-tag} 生效的地方，也是旧版 {@code RecipeCraftingContext} 里那行
     * {@code tag.equals(typeComponent.getTag())}（{@code RecipeCraftingContext.java:222}）的对应实现。
     *
     * <p><strong>为什么「没有标签」是「只看无标签的端口」而不是「全都看」</strong>：旧版的比较是
     * {@code tag.equals(...)}，而 {@code ComponentSelectorTag} 把 {@code null}/空串归一成一个空标签
     * （{@code ComponentSelectorTag.java:26-29}）。于是<strong>无标签需求与无标签部件互相匹配</strong>，
     * 而一个指定了标签的需求<strong>看不到</strong>无标签的端口。这个区分正是这个特性的意义所在：
     * 装配线上「原料进第 3 段」必须真的只进第 3 段，而不是「随便进哪个输入仓都行」。
     *
     * <p>六个桶各自筛选，未知的桶（智能接口与机器定义）原样带过去——它们不是位置性的端口，
     * 没有标签可言，也从不参与筛选。
     *
     * @param selectorTag 要筛的标签名，或 {@code null} 表示只看无标签的端口
     */
    public HatchCollection withTag(@javax.annotation.Nullable String selectorTag) {
        String wanted = (selectorTag == null || selectorTag.isEmpty()) ? UNTAGGED : selectorTag;
        return new HatchCollection(
                filter(this.itemInputs, this.itemInputTags, wanted),
                filter(this.itemOutputs, this.itemOutputTags, wanted),
                filter(this.fluidInputs, this.fluidInputTags, wanted),
                filter(this.fluidOutputs, this.fluidOutputTags, wanted),
                filter(this.energyInputs, this.energyInputTags, wanted),
                filter(this.energyOutputs, this.energyOutputTags, wanted),
                filterTags(this.itemInputTags, wanted),
                filterTags(this.itemOutputTags, wanted),
                filterTags(this.fluidInputTags, wanted),
                filterTags(this.fluidOutputTags, wanted),
                filterTags(this.energyInputTags, wanted),
                filterTags(this.energyOutputTags, wanted),
                this.smartInterfaces,
                this.machine);
    }

    /** 这个位置上的端口在筛选后是否仍然可见。 */
    private static boolean keeps(String tag, String wanted) {
        return tag.equals(wanted);
    }

    /**
     * 每个端口一行的人类可读描述，带上它所属的 {@code selector-tag}（没有标签的显示为 {@code -}）。
     *
     * <p>为什么需要它：`selector-tag` 是<strong>看不见</strong>的。结构成型之后，玩家在世界上看到的只是一堆
     * 仓口方块，没有任何东西告诉他某个仓属于 `input_line_0` 还是 `input_line_1`——而配方的成败完全取决于
     * 这个看不见的名字。实测时就卡在这里：一台成型、配方已加载的机器不开工，而唯一能看出原因的方式是
     * 把「哪个仓带哪个标签」打出来。
     *
     * <p>顺序与 {@link #withTag} 筛选前的桶顺序一致，便于对照。
     */
    public List<String> describePorts() {
        List<String> out = new ArrayList<>();
        describeBucket(out, "item input", this.itemInputs.size(), this.itemInputTags);
        describeBucket(out, "item output", this.itemOutputs.size(), this.itemOutputTags);
        describeBucket(out, "fluid input", this.fluidInputs.size(), this.fluidInputTags);
        describeBucket(out, "fluid output", this.fluidOutputs.size(), this.fluidOutputTags);
        describeBucket(out, "energy input", this.energyInputs.size(), this.energyInputTags);
        describeBucket(out, "energy output", this.energyOutputs.size(), this.energyOutputTags);
        return List.copyOf(out);
    }

    private static void describeBucket(List<String> out, String family, int ports, List<String> tags) {
        for (int i = 0; i < ports; i++) {
            String tag = tags.get(i);
            out.add(family + " #" + i + " tag=" + (UNTAGGED.equals(tag) ? "-" : tag));
        }
    }

    private static <T> List<T> filter(List<T> ports, List<String> tags, String wanted) {
        List<T> kept = new ArrayList<>(ports.size());
        for (int i = 0; i < ports.size(); i++) {
            if (keeps(tags.get(i), wanted)) {
                kept.add(ports.get(i));
            }
        }
        return kept;
    }

    private static List<String> filterTags(List<String> tags, String wanted) {
        List<String> kept = new ArrayList<>(tags.size());
        for (String tag : tags) {
            if (keeps(tag, wanted)) {
                kept.add(tag);
            }
        }
        return kept;
    }

    /**
     * Where a smart-interface requirement reads the value it compares against: the controller this collection was
     * gathered from.
     *
     * <p>Called on every check rather than captured once, so an edit made while a craft runs is seen at once —
     * the original read the live {@code SmartInterfaceData} object the same way.
     */
    public SmartInterfaceValueSource smartInterfaces() {
        SmartInterfaceValueSource source = this.smartInterfaces.get();
        return source == null ? SmartInterfaceValueSource.NONE : source;
    }

    /** The value stored for one declared interface type, or {@code null} when there is none. */
    @javax.annotation.Nullable
    public Float smartInterfaceValue(String type) {
        return smartInterfaces().valueOf(type);
    }

    /**
     * The formed machine's declared interface type of that name, or {@code null} when the machine declares no
     * such type — the original's {@code DynamicMachine#getSmartInterfaceType}, reached the same way the value is.
     */
    @javax.annotation.Nullable
    public SmartInterfaceType declaredSmartInterface(String type) {
        MachineDefinition definition = this.machine.get();
        return definition == null ? null : definition.smartInterface(type);
    }

    public boolean isEmpty() {
        return this.itemInputs.isEmpty() && this.itemOutputs.isEmpty() && this.fluidInputs.isEmpty()
                && this.fluidOutputs.isEmpty() && this.energyInputs.isEmpty() && this.energyOutputs.isEmpty();
    }

    public int hatchCount() {
        return this.itemInputs.size() + this.itemOutputs.size() + this.fluidInputs.size()
                + this.fluidOutputs.size() + this.energyInputs.size() + this.energyOutputs.size();
    }

    /** Total FE currently held by the structure's energy input hatches. */
    public long storedEnergy() {
        long total = 0L;
        for (IEnergyStorage storage : this.energyInputs) {
            total += storage.getEnergyStored();
        }
        return total;
    }

    /** Total FE the structure's energy input hatches can hold. */
    public long storedEnergyCapacity() {
        long total = 0L;
        for (IEnergyStorage storage : this.energyInputs) {
            total += storage.getMaxEnergyStored();
        }
        return total;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private final List<IItemHandler> itemInputs = new ArrayList<>();
        private final List<IItemHandler> itemOutputs = new ArrayList<>();
        private final List<IFluidHandler> fluidInputs = new ArrayList<>();
        private final List<IFluidHandler> fluidOutputs = new ArrayList<>();
        private final List<IEnergyStorage> energyInputs = new ArrayList<>();
        private final List<IEnergyStorage> energyOutputs = new ArrayList<>();

        /** 与上面六个桶逐个对应，见 {@link HatchCollection#itemInputTags} 的说明。 */
        private final List<String> itemInputTags = new ArrayList<>();
        private final List<String> itemOutputTags = new ArrayList<>();
        private final List<String> fluidInputTags = new ArrayList<>();
        private final List<String> fluidOutputTags = new ArrayList<>();
        private final List<String> energyInputTags = new ArrayList<>();
        private final List<String> energyOutputTags = new ArrayList<>();

        /** Defaults to "nothing", so a collection built without a controller keeps behaving as it always did. */
        private SmartInterfaceValueSource smartInterfaces = SmartInterfaceValueSource.NONE;

        /** Defaults to "no machine", for the same reason. */
        private java.util.function.Supplier<MachineDefinition> machine = () -> null;

        /**
         * Where smart-interface values come from — the controller the structure was gathered from. The single
         * argument is the very object whose own accessor the requirement needs, which is the original's access
         * path with the one intermediate block removed.
         */
        public Builder smartInterfaces(SmartInterfaceValueSource source) {
            this.smartInterfaces = source == null ? SmartInterfaceValueSource.NONE : source;
            return this;
        }

        /** The formed machine's definition, for the interface type's own {@code notequal} message. */
        public Builder machine(MachineDefinition definition) {
            this.machine = () -> definition;
            return this;
        }

        /**
         * Routes one hatch's storage into the bucket its kind and direction call for, under the
         * {@code selector-tag} of the structure position it was found at.
         *
         * @param selectorTag the position's {@code selector-tag}, or {@code null} when the position has none —
         *                    which is every position of a structure that declares no tags at all
         */
        public Builder add(com.reborn.modularmachinery.block.HatchKind kind,
                           IItemHandler items, IFluidHandler fluids, IEnergyStorage energy,
                           @javax.annotation.Nullable String selectorTag) {
            String tag = (selectorTag == null || selectorTag.isEmpty()) ? UNTAGGED : selectorTag;
            switch (kind.family()) {
                case ITEM -> {
                    if (items != null) {
                        (kind.isInput() ? this.itemInputs : this.itemOutputs).add(items);
                        (kind.isInput() ? this.itemInputTags : this.itemOutputTags).add(tag);
                    }
                }
                case FLUID -> {
                    if (fluids != null) {
                        (kind.isInput() ? this.fluidInputs : this.fluidOutputs).add(fluids);
                        (kind.isInput() ? this.fluidInputTags : this.fluidOutputTags).add(tag);
                    }
                }
                case ENERGY -> {
                    if (energy != null) {
                        (kind.isInput() ? this.energyInputs : this.energyOutputs).add(energy);
                        (kind.isInput() ? this.energyInputTags : this.energyOutputTags).add(tag);
                    }
                }
            }
            return this;
        }

        /** {@link #add} for a position that declares no {@code selector-tag}. */
        public Builder add(com.reborn.modularmachinery.block.HatchKind kind,
                           IItemHandler items, IFluidHandler fluids, IEnergyStorage energy) {
            return add(kind, items, fluids, energy, null);
        }

        /**
         * Registers a bare handler as a port. Used to fold the controller's own slots and buffer in as fallback
         * ports so the recipe engine sees a single uniform set of ports.
         *
         * <p>These are always <b>untagged</b>, and that is deliberate rather than incidental: the controller is
         * not a structure position, so it belongs to no {@code selector-tag} and must stay invisible to every
         * tagged requirement. Folding it into a tagged bucket would let a recipe that asked for "the input at
         * line segment 3" be satisfied by the controller's own slot.
         */
        public Builder addItemHandler(IItemHandler handler, boolean input) {
            if (handler != null) {
                (input ? this.itemInputs : this.itemOutputs).add(handler);
                (input ? this.itemInputTags : this.itemOutputTags).add(UNTAGGED);
            }
            return this;
        }

        public Builder addFluidHandler(IFluidHandler handler, boolean input) {
            if (handler != null) {
                (input ? this.fluidInputs : this.fluidOutputs).add(handler);
                (input ? this.fluidInputTags : this.fluidOutputTags).add(UNTAGGED);
            }
            return this;
        }

        public Builder addEnergyStorage(IEnergyStorage storage, boolean input) {
            if (storage != null) {
                (input ? this.energyInputs : this.energyOutputs).add(storage);
                (input ? this.energyInputTags : this.energyOutputTags).add(UNTAGGED);
            }
            return this;
        }

        public HatchCollection build() {
            return new HatchCollection(this.itemInputs, this.itemOutputs, this.fluidInputs, this.fluidOutputs,
                    this.energyInputs, this.energyOutputs,
                    this.itemInputTags, this.itemOutputTags, this.fluidInputTags, this.fluidOutputTags,
                    this.energyInputTags, this.energyOutputTags,
                    () -> this.smartInterfaces, this.machine);
        }
    }
}
