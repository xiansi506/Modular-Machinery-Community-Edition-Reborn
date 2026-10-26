package com.reborn.modularmachinery.magic;

import com.reborn.modularmachinery.block.ModBlocks;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.RegistryObject;
import java.util.*;

public final class MagicBlocks {
    private static final List<RegistryObject<? extends Block>> BLOCKS = new ArrayList<>();
    private static final List<RegistryObject<Item>> ITEMS = new ArrayList<>();
    public static RegistryObject<BlockEntityType<MagicHatchBlockEntity>> TYPE;
    private MagicBlocks() {}
    public static void register() {
        for (MagicFamily family : MagicFamily.values()) {
            if (ModList.get() == null || !ModList.get().isLoaded(family.modId)) continue;
            for (boolean input : new boolean[]{true, false}) {
                String name = family.path() + (input ? "_input_hatch" : "_output_hatch");
                var block = ModBlocks.BLOCKS.register(name, () -> new MagicHatchBlock(family, input,
                        BlockBehaviour.Properties.of().strength(3.5f).requiresCorrectToolForDrops()));
                BLOCKS.add(block);
                ITEMS.add(ModBlocks.ITEMS.register(name, () -> new BlockItem(block.get(), new Item.Properties())));
            }
        }
        if (!BLOCKS.isEmpty()) TYPE = ModBlocks.BLOCK_ENTITIES.register("magic_hatch", () ->
                BlockEntityType.Builder.of(MagicHatchBlockEntity::new, BLOCKS.stream().map(RegistryObject::get).toArray(Block[]::new)).build(null));
    }
    public static void creative(CreativeModeTab.Output output) { ITEMS.forEach(item -> output.accept(item.get())); }
    public static Block[] blocks() { return BLOCKS.stream().map(RegistryObject::get).toArray(Block[]::new); }
}
