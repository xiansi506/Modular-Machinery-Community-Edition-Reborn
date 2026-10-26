package com.reborn.modularmachinery.compat.magic;

import com.reborn.modularmachinery.magic.*;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.LazyOptional;
import java.lang.reflect.*;
import java.util.*;
import static com.reborn.modularmachinery.compat.magic.ApiReflection.*;

/** Botania 1.20.1-455 capability and ManaPool contracts. Proxies keep common classes optional. */
public final class BotaniaBackend implements MagicBackend {
    private final MagicHatchBlockEntity hatch;
    private final Class<?> receiver = type("vazkii.botania.api.mana.ManaReceiver");
    private final Class<?> network = type("vazkii.botania.api.internal.ManaNetwork");
    private final Class<?> blockType = type("vazkii.botania.api.mana.ManaBlockType");
    private final Class<?> action = type("vazkii.botania.api.mana.ManaNetworkAction");
    private final Capability<?> manaCap, sparkCap;
    private final Object mana, spark;
    private LazyOptional<Object> manaView, sparkView;
    private boolean joined, valid = true;
    public BotaniaBackend(MagicHatchBlockEntity hatch) {
        this.hatch = hatch;
        Class<?> caps = type("vazkii.botania.api.BotaniaForgeCapabilities");
        manaCap = (Capability<?>) field(caps, "MANA_RECEIVER"); sparkCap = (Capability<?>) field(caps, "SPARK_ATTACHABLE");
        Class<?> manaType = hatch.input() ? receiver : type("vazkii.botania.api.mana.ManaPool");
        mana = Proxy.newProxyInstance(manaType.getClassLoader(), new Class<?>[]{manaType}, this::manaCall);
        Class<?> sparkType = type("vazkii.botania.api.mana.spark.SparkAttachable");
        spark = Proxy.newProxyInstance(sparkType.getClassLoader(), new Class<?>[]{sparkType}, this::sparkCall);
        renew();
    }
    public MagicFamily family() { return MagicFamily.MANA; }
    public boolean input() { return hatch.input(); }
    private void renew() { manaView = LazyOptional.of(() -> mana); sparkView = LazyOptional.of(() -> spark); }
    public LazyOptional<?> capability(Capability<?> cap) {
        if (cap == manaCap) return manaView;
        if (cap == sparkCap) return sparkView;
        return null;
    }
    private Object objectCall(Object proxy, Method method, Object[] args) throws Throwable {
        return switch (method.getName()) {
            case "equals" -> proxy == args[0]; case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> "MMR " + method.getDeclaringClass().getSimpleName();
            default -> { if (method.isDefault()) yield InvocationHandler.invokeDefault(proxy, method, args); throw new UnsupportedOperationException(method.toString()); }
        };
    }
    private Object manaCall(Object proxy, Method method, Object[] args) throws Throwable {
        return switch (method.getName()) {
            case "getManaReceiverLevel" -> hatch.getLevel(); case "getManaReceiverPos" -> hatch.getBlockPos();
            case "getCurrentMana" -> hatch.stored(); case "getMaxMana" -> hatch.manaCapacity();
            case "isFull" -> hatch.stored() >= hatch.manaCapacity();
            case "isOutputtingPower" -> !input();
            case "canReceiveManaFromBursts" -> input() && valid && !hatch.isRemoved();
            case "getColor" -> Optional.of(DyeColor.WHITE); case "setColor" -> null;
            case "receiveMana" -> {
                int amount = (int) args[0];
                // ManaPool callers (including generating flowers) use signed transfers directly.
                if (valid && !hatch.isRemoved() && hatch.getLevel() != null && !hatch.getLevel().isClientSide)
                    hatch.stored((int) Math.max(0, Math.min(hatch.manaCapacity(), (long) hatch.stored() + amount)));
                yield null;
            }
            default -> objectCall(proxy, method, args);
        };
    }
    @SuppressWarnings({"rawtypes", "unchecked"}) private Object sparkCall(Object proxy, Method method, Object[] args) throws Throwable {
        return switch (method.getName()) {
            case "canAttachSpark" -> valid;
            case "attachSpark" -> null;
            case "getAvailableSpaceForMana" -> input() && valid ? Math.max(0, hatch.manaCapacity() - hatch.stored()) : 0;
            case "areIncomingTranfersDone" -> !input() || !valid || hatch.stored() >= hatch.manaCapacity();
            case "getAttachedSpark" -> {
                Level level = hatch.getLevel();
                if (level == null) yield null;
                Class sparkClass = type("vazkii.botania.common.entity.ManaSparkEntity");
                var found = level.getEntitiesOfClass(sparkClass, new AABB(hatch.getBlockPos().above()));
                yield found.size() == 1 ? found.get(0) : null;
            }
            default -> objectCall(proxy, method, args);
        };
    }
    private void event(String event) {
        Class<?> api = type("vazkii.botania.api.BotaniaAPI");
        Object instance = call(api, null, "instance", new Class<?>[0]);
        Object net = call(api, instance, "getManaNetworkInstance", new Class<?>[0]);
        call(network, net, "fireManaNetworkEvent", new Class<?>[]{receiver, blockType, action}, mana, constant(blockType,"POOL"), constant(action,event));
    }
    @Override public void load() { if (!input() && valid && !joined && hatch.getLevel() != null && !hatch.isRemoved()) { event("ADD"); joined = true; } }
    @Override public void tick() { load(); }
    @Override public void unload() { if (joined) { event("REMOVE"); joined = false; } }
    @Override public void invalidate() { unload(); valid = false; manaView.invalidate(); sparkView.invalidate(); }
    @Override public void revive() { valid = true; renew(); load(); }
    public Access access(String variant) {
        if (!variant.isEmpty()) return null;
        return new Access() {
            public Object identity() { return hatch; }
            public double amount() { return hatch.stored(); }
            public double minimum() { return 0; }
            public double maximum() { return hatch.manaCapacity(); }
            public boolean valid() { return valid && !hatch.isRemoved() && hatch.getLevel() != null && !hatch.getLevel().isClientSide; }
            public double move(double delta, boolean simulate) {
                if (!valid() || !Double.isFinite(delta)) return 0;
                double n = delta >= 0 ? Math.min(Math.floor(delta), maximum()-amount()) : -Math.min(Math.floor(-delta),amount());
                if (!simulate) hatch.stored((int)(amount()+n)); return n;
            }
            public void restore(Object snapshot) { hatch.stored(((Double)snapshot).intValue()); }
        };
    }
}
