package com.reborn.modularmachinery.block;

import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.block.state.properties.EnumProperty;

import java.util.List;

/**
 * The six hatch families of the original mod, in the original input/output × item/fluid/energy split.
 *
 * <p>Each family owns exactly one registered block whose {@code size} property selects the tier,
 * mirroring the original {@code blockinputbus} / {@code blockoutputbus} / {@code blockenergyinputhatch}
 * / {@code blockenergyoutputhatch} / {@code blockfluidinputhatch} / {@code blockfluidoutputhatch}
 * layout. The block state property is created per family because the three ladders differ.
 */
public enum HatchKind implements StringRepresentable {

    ITEM_INPUT("item_input_hatch", HatchTier.ITEM_LADDER, true, Family.ITEM),
    ITEM_OUTPUT("item_output_hatch", HatchTier.ITEM_LADDER, false, Family.ITEM),
    FLUID_INPUT("fluid_input_hatch", HatchTier.FLUID_LADDER, true, Family.FLUID),
    FLUID_OUTPUT("fluid_output_hatch", HatchTier.FLUID_LADDER, false, Family.FLUID),
    ENERGY_INPUT("energy_input_hatch", HatchTier.ENERGY_LADDER, true, Family.ENERGY),
    ENERGY_OUTPUT("energy_output_hatch", HatchTier.ENERGY_LADDER, false, Family.ENERGY);

    /** Which storage a hatch of this family carries. */
    public enum Family {
        ITEM,
        FLUID,
        ENERGY
    }

    private final String id;
    private final List<HatchTier> ladder;
    private final boolean input;
    private final Family family;
    private final EnumProperty<HatchTier> sizeProperty;

    HatchKind(String id, List<HatchTier> ladder, boolean input, Family family) {
        this.id = id;
        this.ladder = ladder;
        this.input = input;
        this.family = family;
        this.sizeProperty = EnumProperty.create("size", HatchTier.class, ladder.toArray(new HatchTier[0]));
    }

    /** Registry path of the family block, e.g. {@code item_input_hatch}. */
    public String id() {
        return this.id;
    }

    /** The tiers this family actually has, in the original order. */
    public List<HatchTier> ladder() {
        return this.ladder;
    }

    /** {@code true} for the input side of a family, {@code false} for the output side. */
    public boolean isInput() {
        return this.input;
    }

    public Family family() {
        return this.family;
    }

    /**
     * 这个仓口能不能被玩家分到一个「输入组」（A2，勘测 §4 第 115 行）。
     *
     * <p>旧版只有两个家族回答 {@code true}：物品输入总线
     * （{@code TileItemInputBus.java:141-144}）与流体输入仓
     * （{@code base/TileFluidTank.java:144-147}，且仅 {@code IOType.INPUT}——输出仓继承同一个方法但
     * 那一半是 {@code false}）。输出仓与能量仓从来不可分组：旧版
     * {@code TileColorableMachineComponent#getGroupId} 对它们一律答 {@code -1}（通用）。
     *
     * <p>为什么这条规则住在枚举里而不是 UI 里：它同时决定「仓口能不能存组号」
     * （{@code MachineHatchBlockEntity#saveAdditional}）、「端口收集时带不带组号」
     * （{@code MachineControllerBlockEntity#collectHatches}）和「右键能不能打开分组界面」。
     * 三处如果各写一遍，迟早会有一处忘了改。
     */
    public boolean canGroupInput() {
        return this == ITEM_INPUT || this == FLUID_INPUT;
    }

    /** The {@code size} blockstate property for this family. */
    public EnumProperty<HatchTier> sizeProperty() {
        return this.sizeProperty;
    }

    /** Registry path of a single tier's block item, e.g. {@code item_input_hatch_tiny}. */
    public String itemId(HatchTier tier) {
        return this.id + "_" + tier.getSerializedName();
    }

    /** Translation key of a single tier's block item. */
    public String itemTranslationKey(HatchTier tier) {
        return "block.modular_machinery_reborn." + itemId(tier);
    }

    @Override
    public String getSerializedName() {
        return this.id;
    }
}
