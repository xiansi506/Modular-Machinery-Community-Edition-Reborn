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
                                                              RegistryObject<Block> input,
                                                              RegistryObject<Block> output) {
        // 0.73.1: 这里必须接收 RegistryObject 而不是已经解引用过的 Block。0.73.0 在 ModBlocks 的静态
        // 初始化里调用 input.get()/output.get()，那时方块注册事件还没跑，Forge 抛
        // "Registry Object not present: ...:me_item_input_bus"，整个模组构造失败。
        // get() 只能放在下面的 supplier 里：它在 BLOCK_ENTITIES 注册事件期间才执行，方块已经就位。
        RegistryObject<BlockEntityType<MeItemBusBlockEntity>> registered = register.register("me_item_bus",
                () -> BlockEntityType.Builder.of(MeItemBusBlockEntity::new, input.get(), output.get()).build(null));
        return (RegistryObject) registered;
    }
}
