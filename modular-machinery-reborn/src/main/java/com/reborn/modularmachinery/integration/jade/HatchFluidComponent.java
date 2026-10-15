package com.reborn.modularmachinery.integration.jade;

import com.reborn.modularmachinery.ModularMachineryReborn;
import com.reborn.modularmachinery.block.MachineHatchBlockEntity;
import com.reborn.modularmachinery.block.HatchKind;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fluids.FluidStack;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;

/**
 * 流体仓的流体内容，由**本模组自己**报给 Jade。
 *
 * <h2>为什么需要它（这是一次被实机逼出来的决定）</h2>
 *
 * <p>Jade 自带一个「通用流体存储」组件，它按 {@code BlockEntity.class} 注册，服务端向方块实体要
 * {@code ForgeCapabilities.FLUID_HANDLER}，逐罐读 {@code getTankCapacity} / {@code getFluidInTank}，
 * 再把结果发给客户端渲染。这条路对本模组的流体仓**本应正常工作**，所有者实例上的读数也确实证明
 * 服务端每一步都是对的：
 *
 * <pre>
 *   Jade 流程模拟（走 CapabilityProvider 的无方向查询）:
 *       罐数=1 容量=32000 内容=advanced_ae:quantum_infusion_source x32000 mB
 *   本类交出 / 罐真值 / 父类(Forge 派发)  三条读法全部给出 32000 mB
 * </pre>
 *
 * <p>而 Jade 客户端对**同一个方块**（坐标已核对一致）显示 {@code 空 : 0 mB}。也就是说：数据从本模组
 * 交出去是对的，在 Jade 的客户端/插件收尾那一层被丢掉了——**那一层不归本模组改**。
 *
 * <p>所以这里换一条**不受那一层影响**的路：Jade 的 {@code appendTooltip} 在客户端运行，而客户端方块
 * 实体上有**同步过来的存档 NBT**（{@code getUpdateTag} → {@code saveWithoutMetadata}），
 * 罐就在其中的 {@code tank} 键里（Forge 的 {@code FluidTank#writeToNBT} 格式：{@code Fluid} + {@code Amount}）。
 * 本组件直接读它——与流体仓界面自己读的是**同一份数据**，因此两者不可能再不一致。
 *
 * <p>这不是绕开问题，而是把「谁来显示」收回本模组：Jade 的通用组件是**可选**的加分项，
 * 本模组不能把「玩家能不能看见仓里有什么」押在它身上。
 */
public final class HatchFluidComponent implements IBlockComponentProvider {

    /** 稳定 id；同一个 id 只能被一个 provider 注册，所以它是本模组的命名空间 + 明确的名字。 */
    public static final HatchFluidComponent INSTANCE = new HatchFluidComponent();

    private static final ResourceLocation UID =
            new ResourceLocation(ModularMachineryReborn.MOD_ID, "hatch_fluid");

    /** 存档 NBT 里罐的键；与 {@code MachineHatchBlockEntity} 的 {@code TAG_TANK} 一致。 */
    private static final String TAG_TANK = "tank";

    /** Forge {@code FluidTank#writeToNBT} 用的两个键。 */
    private static final String TAG_FLUID = "Fluid";
    private static final String TAG_AMOUNT = "Amount";

    private HatchFluidComponent() {
    }

    @Override
    public ResourceLocation getUid() {
        return UID;
    }

    /**
     * 默认优先级，与 Jade 通用组件的相对位置无关紧要：这里显示的是**同一个仓的同一件事**，
     * 而通用组件在本模组的方块上不显示（所有者实例上实测如此）。
     */
    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        if (!(accessor.getBlockEntity() instanceof MachineHatchBlockEntity hatch)) {
            return;
        }
        // 只对流体仓说这件事：气体仓有自己的名字（气体那一半目前由兼容层显示）。
        if (hatch.kind().family() != HatchKind.Family.FLUID) {
            return;
        }
        CompoundTag tag = hatch.saveWithoutMetadata();
        FluidStack fluid = readFluid(tag);
        int capacity = Math.max(1, hatch.tier().fluidCapacity());
        if (!fluid.isEmpty()) {
            tooltip.add(fluid.getDisplayName());
            tooltip.add(Component.literal(fluid.getAmount() + " mB / " + capacity + " mB"));
            return;
        }
        // 空的时候也要说话：**「空」与「这个方块没有流体那一半」是两件事**，
        // 而 Jade 通用组件在空罐时只画一个空框（所有者截图上那个 `空 : 0 mB`），
        // 看起来像是本模组什么都没做。
        tooltip.add(Component.translatable("tooltip.modular_machinery_reborn.fluidhatch.empty"));
        tooltip.add(Component.literal("0 mB / " + capacity + " mB"));
    }

    /** 从同步过来的存档 NBT 里读出罐的内容；读不到就是空。 */
    private static FluidStack readFluid(CompoundTag tag) {
        if (!tag.contains(TAG_TANK)) {
            return FluidStack.EMPTY;
        }
        CompoundTag tank = tag.getCompound(TAG_TANK);
        if (!tank.contains(TAG_FLUID)) {
            return FluidStack.EMPTY;
        }
        FluidStack stack = FluidStack.loadFluidStackFromNBT(tank.getCompound(TAG_FLUID));
        if (stack.isEmpty() || tank.getInt(TAG_AMOUNT) <= 0) {
            return FluidStack.EMPTY;
        }
        stack.setAmount(tank.getInt(TAG_AMOUNT));
        return stack;
    }
}
