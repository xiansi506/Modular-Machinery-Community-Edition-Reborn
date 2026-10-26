package com.reborn.modularmachinery.integration.jade;

import com.reborn.modularmachinery.block.MachineHatchBlockEntity;
import com.reborn.modularmachinery.energy.EnergyAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import snownee.jade.api.Accessor;
import snownee.jade.api.view.*;
import java.util.List;

/** Jade's long view takes precedence over the clamped Forge Energy capability view. */
public final class HatchEnergyStorageProvider {
    private static final ResourceLocation UID = new ResourceLocation("modular_machinery_reborn", "hatch_energy_storage");
    public static final IServerExtensionProvider<MachineHatchBlockEntity, CompoundTag> SERVER = new Server();
    private HatchEnergyStorageProvider() {}
    public static IClientExtensionProvider<CompoundTag, EnergyView> client() { return new Client(); }
    private static final class Server implements IServerExtensionProvider<MachineHatchBlockEntity, CompoundTag> {
        @Override public List<ViewGroup<CompoundTag>> getGroups(ServerPlayer player, ServerLevel level,
                                                               MachineHatchBlockEntity hatch, boolean details) {
            var storage = hatch.energyStorage();
            return storage == null ? List.of() : List.of(new ViewGroup<>(List.of(
                    EnergyView.of(EnergyAccess.stored(storage), EnergyAccess.capacity(storage)))));
        }
        @Override public ResourceLocation getUid() { return UID; }
        @Override public int getDefaultPriority() { return -1000; }
    }
    private static final class Client implements IClientExtensionProvider<CompoundTag, EnergyView> {
        @Override public List<ClientViewGroup<EnergyView>> getClientGroups(Accessor<?> accessor, List<ViewGroup<CompoundTag>> groups) {
            return ClientViewGroup.map(groups, tag -> {
                EnergyView view = EnergyView.read(tag, "FE");
                view.overrideText = net.minecraft.network.chat.Component.literal(String.format(java.util.Locale.ROOT,
                        "%,d / %,d FE", tag.getLong("Cur"), tag.getLong("Capacity")));
                return view;
            }, null);
        }
        @Override public ResourceLocation getUid() { return UID; }
    }
}
