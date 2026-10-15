package com.reborn.modularmachinery.integration.jade;

import com.reborn.modularmachinery.ModularMachineryReborn;
import com.reborn.modularmachinery.block.MachineHatchBlockEntity;
import com.reborn.modularmachinery.block.HatchKind;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
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
        // 什么都不做：本模组的行由 FluidStorageSuppressor 在「工具提示收集完成」那一步统一给出。
        //
        // 为什么不在两个地方都给：这个回调会在一次悬停里被多次调用（所有者实测 410 次 / 改动 325 次），
        // 若这里也加、那里也加，提示里就会堆出好几份重复的行。只留一处，才可能做到「一件事只说一次」。
        //
        // 这个类仍然实现 IBlockComponentProvider 并被注册：它的 getUid() 就是本模组行的标记 id，
        // 而 suppressor 用那个 id 判断「是不是已经处理过了」。见 FluidStorageSuppressor。
    }

    /**
     * 这个仓该显示的行（液体两行，装了 Mekanism 且仓里有气体时再加一行）。
     *
     * <p>由 {@link FluidStorageSuppressor} **复用**：它把 Jade 通用流体组件画在方块名那一行上的图标与
     * 空框去掉之后，用这里的行填补原位。这样「同一件事」在提示里只出现一次，而且是本模组给的那一份。
     *
     * <p>非流体仓返回空表（气体仓的名字由别处负责）。
     */
    public static java.util.List<Component> linesFor(MachineHatchBlockEntity hatch) {
        java.util.List<Component> lines = new java.util.ArrayList<>(3);
        if (hatch.kind().family() != HatchKind.Family.FLUID) {
            return lines;
        }
        int capacity = Math.max(1, hatch.tier().fluidCapacity());
        // **读客户端自己的罐**（0.51.0 修正：不再读存档 NBT）。
        //
        // 为什么改：本组件第一版读的是 `saveWithoutMetadata()` 里的 tank 标签，所有者实测显示「空」，
        // 而同一个仓在界面上是满的。原因是那个罐的 `getFluid()` 会问「罐里是不是有气体」，
        // 而**客户端的气体罐来自上一次同步过来的旧 NBT**——于是它把客户端这个明明装着液体的罐
        // 读成了空。同一次修正还让那道闸门只在服务端生效（见 MachineHatchBlockEntity#gasBlocksFluid）。
        net.minecraftforge.fluids.FluidStack fluid = hatch.fluidTank() == null
                ? net.minecraftforge.fluids.FluidStack.EMPTY
                : hatch.fluidTank().getFluid();
        if (fluid.isEmpty()) {
            // 空的时候也要说话：**「空」与「这个方块没有流体那一半」是两件事**。
            lines.add(Component.translatable("tooltip.modular_machinery_reborn.fluidhatch.empty"));
            lines.add(Component.literal("0 mB / " + capacity + " mB"));
        } else {
            lines.add(fluid.getDisplayName());
            lines.add(Component.literal(fluid.getAmount() + " mB / " + capacity + " mB"));
        }
        // 气体那一行可选：没装 Mekanism（或仓里没有气体那一半）时返回 null，这里就不加。
        String[] gas = com.reborn.modularmachinery.compat.mekanism.MekanismModule.gasTooltip(hatch);
        if (gas != null) {
            lines.add(Component.translatable("tooltip.modular_machinery_reborn.gashatch.tank",
                    gas[0], gas[1]));
        }
        return lines;
    }
}
