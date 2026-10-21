package com.reborn.modularmachinery.compat.ae2;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/**
 * C4（0.73.0）：ME 物品总线方块实体的注册入口。
 *
 * <p>这个类只从 {@code ModBlocks} 在 {@code AE2_LOADED} 为真时调用，因此它里面的
 * {@code MeItemBusBlockEntity} 类型在没装 AE2 的世界里不会被解析。字段类型故意写成
 * {@code RegistryObject<BlockEntityType<?>>}，避免把 {@code MeItemBusBlockEntity} 放进
 * {@code ModBlocks} 的常量池。
 */
public final class Ae2ItemBusRegistry {

    private Ae2ItemBusRegistry() {
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static RegistryObject<BlockEntityType<?>> register(DeferredRegister<BlockEntityType<?>> register,
                                                              Block input, Block output) {
        RegistryObject<BlockEntityType<MeItemBusBlockEntity>> registered = register.register("me_item_bus",
                () -> BlockEntityType.Builder.of(MeItemBusBlockEntity::new, input, output).build(null));
        return (RegistryObject) registered;
    }
}
