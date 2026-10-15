package com.reborn.modularmachinery.compat.mekanism;

import com.reborn.modularmachinery.block.MachineHatchBlockEntity;
import com.reborn.modularmachinery.compat.BlockEntityPortMapper;
import mekanism.api.chemical.gas.IGasHandler;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fluids.capability.templates.FluidTank;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 把 Mekanism 的化学能力接到本模组的流体仓上——**唯一一个碰 Mekanism 的 Forge 能力的类**。
 *
 * <p>它存在的全部理由是**类加载隔离**：{@link HybridGasTank} 与 {@link IGasHandler} 都要求 Mekanism 在场，
 * 而本模组的方块实体每个世界都会被加载、**包括没装 Mekanism 的进程**。所以主模组里一行 Mekanism 都不得出现；
 * 它只认识 {@code ModCompat.mapPort} 这个通用入口，而本类由 {@link MekanismModule} 在
 * {@code FMLCommonSetupEvent} 里挂上去——那个时机只在装了 Mekanism 时才到达。
 *
 * <p>静态字段 {@link #CHEMICAL_HANDLER} 是「Mekanism 的化学能力对象」。它必须由
 * {@code RegisterCapabilitiesEvent} 里调 {@code IGasHandler.getCapability()} 来取：Forge 的能力对象
 * 只有在**注册之后**才拿得到，而在此之前 {@code getCapability()} 会抛异常。这也是
 * {@link MekanismModule#registerCapabilities} 存在的原因。
 */
final class MekanismAnchor {

    /**
     * 每个流体仓一个气体罐，**按方块实体身份**索引。
     *
     * <p>用 {@link WeakHashMap} 而不是让方块实体自己持有一个字段：方块实体在主模组里，加一个
     * 「气体罐」字段就意味着它的类型里出现 Mekanism 的类——那正是本类要避免的。
     * 弱键保证区块卸载后条目会被回收，不需要自己管生命周期。
     *
     * <p>并发：方块实体的能力查询可能来自任意线程（区块加载、管道、客户端同步），
     * 所以用同步包装的 map。
     */
    private static final Map<MachineHatchBlockEntity, HybridGasTank> TANKS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private MekanismAnchor() {
    }

    /** 挂到主模组上的那个映射器。 */
    static BlockEntityPortMapper portMapper() {
        return new Mapper();
    }

    /**
     * 取这个实体上的气体罐，需要时新建；不是流体仓则返回 {@code null}。
     *
     * <p>「只有在流体仓上才有气体」是**旧版的形状**（{@code HybridGasTank} 的宿主是
     * {@code TileFluidTank}），也是本条目的语义：一个罐子要么装流体要么装气体，所以气体那一半与流体那一半
     * 必须住在同一个方块上。
     */
    @Nullable
    static HybridGasTank tankOf(Object entity) {
        if (!(entity instanceof MachineHatchBlockEntity hatch)) {
            return null;
        }
        FluidTank fluid = hatch.fluidTank();
        if (fluid == null) {
            return null;
        }
        synchronized (TANKS) {
            return TANKS.computeIfAbsent(hatch, key -> new HybridGasTank(key, fluid, key::markStorageChanged));
        }
    }

    /** 存档用：把某个仓的气体那一半写进 NBT；没有气体那一半的仓不写。 */
    static void saveGasTo(MachineHatchBlockEntity hatch, net.minecraft.nbt.CompoundTag tag) {
        HybridGasTank tank;
        synchronized (TANKS) {
            tank = TANKS.get(hatch);
        }
        // 只有**已经建过**的罐才写盘。没有条目意味着这个仓这一生没被问过气体能力，
        // 那它就不可能有气体内容——不去 computeIfAbsent，免得单纯存盘就给每个流体仓造一个对象。
        if (tank != null) {
            tank.writeToNbt(tag);
        }
    }

    /** 读盘用：把 NBT 里的气体那一半读回某个仓；没有气体那一半的仓忽略。 */
    static void loadGasFrom(MachineHatchBlockEntity hatch, net.minecraft.nbt.CompoundTag tag) {
        HybridGasTank tank = tankOf(hatch);
        if (tank != null) {
            tank.readFromNbt(tag);
        }
    }

    /**
     * 这个实体上**属于本模块**的端口处理器与能力应答。
     *
     * <p><b>Mekanism 的能力对象是懒取的</b>（{@code Capabilities.GAS_HANDLER} 在比较时才读），不做缓存。
     * 理由：那个静态字段由 Mekanism 自己的 {@code Capabilities.<clinit>} 建立，而本模组的
     * {@code ports()} 跑在 {@code FMLCommonSetupEvent} 里——**能力对象那时是否已经建立，取决于模组加载顺序**，
     * 而 mods.toml 的 {@code ordering="AFTER"} 只保证 Mekanism 的**构造**在前，不保证它的静态初始化已经跑到那一步。
     * 缓存一个当时还可能是 {@code null} 的值，症状是**气体端口永远不工作**，而日志里什么异常都没有。
     * 而比较发生的能力查询是在玩家进世界之后，那时一切早就绪了。
     */
    private static final class Mapper implements BlockEntityPortMapper {

        @Override
        @Nullable
        public LazyOptional<?> map(Capability<?> capability, Object entity) {
            Capability<IGasHandler> chemical = MekanismCapabilities.gasHandler();
            if (chemical == null || capability != chemical) {
                return null;
            }
            HybridGasTank tank = tankOf(entity);
            if (tank == null) {
                // 「是我的能力，但这个实体没有」——与 Forge 的语义一致，也让后面的映射器不必再被问一遍。
                return LazyOptional.empty();
            }
            return LazyOptional.of(tank::handler);
        }

        /**
         * 端口收集用：这个实体上有一个气体端口（如果它是流体仓）。
         *
         * <p>返回的是 {@code tankOf} 里那**一个**对象，与 {@link #map} 给 Forge 的是同一个——
         * 配方读到的那份状态与管道灌进去的那份必须是同一份，这是 {@code tankOf} 用身份 map 的原因。
         */
        @Override
        public java.util.List<?> handlers(Object entity) {
            HybridGasTank tank = tankOf(entity);
            return tank == null ? java.util.List.of() : java.util.List.of(tank.handler());
        }

        @Override
        public void save(net.minecraft.nbt.CompoundTag tag, Object entity) {
            if (entity instanceof MachineHatchBlockEntity hatch) {
                saveGasTo(hatch, tag);
            }
        }

        @Override
        public void load(net.minecraft.nbt.CompoundTag tag, Object entity) {
            if (entity instanceof MachineHatchBlockEntity hatch) {
                loadGasFrom(hatch, tag);
            }
        }
    }

    /** 气体能力这一半是否可用（给诊断用）。 */
    static boolean gasHandlerAvailable() {
        return MekanismCapabilities.gasHandler() != null;
    }

    /**
     * Mekanism 的能力对象，**隔离在这一个方法里**，原因见 {@link Mapper} 的注释。
     *
     * <p>{@code mekanism.common.capabilities.Capabilities} 而不是自己注册一个 {@code Capability}：
     * Forge 的能力是**按类型**的身份对象，两边必须用同一个，否则 Mekanism 的管道问的是它的那个、
     * 本模组答的是另一个，永远匹配不上。Mekanism 自己已经用
     * {@code @AutoRegisterCapability} 声明了 {@code IGasHandler}，所以这里只读不注册。
     */
    private static final class MekanismCapabilities {
        @Nullable
        static Capability<IGasHandler> gasHandler() {
            try {
                return mekanism.common.capabilities.Capabilities.GAS_HANDLER;
            } catch (Throwable unavailable) {
                // 没有 Mekanism 时这个类根本不该被加载；真到了这里说明有人在没装它的情况下问起了气体，
                // 那就诚实地答「没有这个能力」，而不是让一个 NoClassDefFoundError 逃进能力查询路径。
                return null;
            }
        }
    }
}
