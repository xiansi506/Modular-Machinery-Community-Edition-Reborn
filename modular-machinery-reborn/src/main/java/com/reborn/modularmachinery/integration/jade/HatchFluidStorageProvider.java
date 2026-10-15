package com.reborn.modularmachinery.integration.jade;

import com.reborn.modularmachinery.block.HatchKind;
import com.reborn.modularmachinery.block.MachineHatchBlockEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fluids.FluidStack;
import snownee.jade.api.Accessor;
import snownee.jade.api.fluid.JadeFluidObject;
import snownee.jade.api.view.ClientViewGroup;
import snownee.jade.api.view.FluidView;
import snownee.jade.api.view.IClientExtensionProvider;
import snownee.jade.api.view.IServerExtensionProvider;
import snownee.jade.api.view.ViewGroup;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Supplies the fluid tank view from the hatch's own server-side tank. */
public final class HatchFluidStorageProvider {

    private static final ResourceLocation UID = new ResourceLocation(
            com.reborn.modularmachinery.ModularMachineryReborn.MOD_ID, "hatch_fluid_storage");

    public static final IServerExtensionProvider<MachineHatchBlockEntity, CompoundTag> SERVER =
            new ServerProvider();
    private static final AtomicInteger REQUESTS = new AtomicInteger();
    private static final AtomicInteger PROVIDED = new AtomicInteger();
    private static volatile String lastResult = "尚未收到 Jade 请求";
    public static IClientExtensionProvider<CompoundTag, FluidView> client() {
        return new ClientProvider();
    }

    private HatchFluidStorageProvider() {
    }

    public static String diagnostics() {
        return "服务端请求 " + REQUESTS.get() + " 次 / 返回流体条 " + PROVIDED.get()
                + " 次；最近一次: " + lastResult;
    }

    private static final class ServerProvider
            implements IServerExtensionProvider<MachineHatchBlockEntity, CompoundTag> {

        @Override
        public List<ViewGroup<CompoundTag>> getGroups(ServerPlayer player, ServerLevel level,
                                                       MachineHatchBlockEntity hatch, boolean showDetails) {
            REQUESTS.incrementAndGet();
            if (hatch.kind().family() != HatchKind.Family.FLUID || hatch.fluidTank() == null) {
                lastResult = "非流体仓";
                return List.of();
            }
            FluidStack fluid = hatch.fluidTank().getFluid();
            if (fluid.isEmpty()) {
                lastResult = hatch.getBlockPos().toShortString() + " 仓内读为空；原始量="
                        + hatch.realFluidAmount() + " mB";
                return List.of();
            }
            PROVIDED.incrementAndGet();
            lastResult = hatch.getBlockPos().toShortString() + " "
                    + fluid.getFluid().builtInRegistryHolder().key().location()
                    + " " + fluid.getAmount() + "/" + hatch.fluidTank().getCapacity() + " mB";
            CompoundTag view = FluidView.writeDefault(
                    JadeFluidObject.of(fluid.getFluid(), fluid.getAmount()),
                    hatch.fluidTank().getCapacity());
            return List.of(new ViewGroup<>(List.of(view)));
        }

        @Override
        public ResourceLocation getUid() {
            return UID;
        }

        @Override
        public int getDefaultPriority() {
            // Jade orders storage extensions by ascending priority; precede the generic capability wrappers.
            return -1000;
        }
    }

    private static final class ClientProvider implements IClientExtensionProvider<CompoundTag, FluidView> {

        @Override
        public List<ClientViewGroup<FluidView>> getClientGroups(Accessor<?> accessor,
                                                                 List<ViewGroup<CompoundTag>> groups) {
            return ClientViewGroup.map(groups, FluidView::readDefault, null);
        }

        @Override
        public ResourceLocation getUid() {
            return UID;
        }
    }
}
