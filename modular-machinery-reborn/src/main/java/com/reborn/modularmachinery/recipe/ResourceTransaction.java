package com.reborn.modularmachinery.recipe;

import com.reborn.modularmachinery.block.MeFluidTanks;
import net.minecraft.nbt.IntTag;
import com.reborn.modularmachinery.energy.*;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.energy.EnergyStorage;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.templates.FluidTank;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.IItemHandlerModifiable;
import net.minecraftforge.items.ItemStackHandler;
import java.util.*;
import java.util.function.BooleanSupplier;

/** 结算在共享副本上规划，全部通过后才提交；同一端口的不同标签视图共用副本。 */
public final class ResourceTransaction {
    /** 可选集成自己实现副本，主模块不引用其 API。 */
    public interface OpaquePort { Object transactionView(ResourceTransaction transaction); }
    private final Map<Object,Object> shared = new HashMap<>();
    @SuppressWarnings("unchecked")
    public <T> T shared(Object key, java.util.function.Supplier<T> factory) {
        return (T) shared.computeIfAbsent(key, ignored -> factory.get());
    }
    private final IdentityHashMap<Object,Object> views = new IdentityHashMap<>();
    private final IdentityHashMap<IFluidHandler, BooleanSupplier> fluidBlockers = new IdentityHashMap<>();
    private final List<BooleanSupplier> validations = new ArrayList<>(), operations = new ArrayList<>();
    private final List<Runnable> restores = new ArrayList<>();
    public void enlist(BooleanSupplier unchanged, Runnable restore) { validations.add(unchanged); restores.add(restore); }
    public void record(BooleanSupplier operation) { operations.add(operation); }
    public boolean commit() {
        for (BooleanSupplier check : validations) if (!check.getAsBoolean()) return false;
        boolean failed = false;
        try {
            for (BooleanSupplier step : operations) if (!step.getAsBoolean()) { failed = true; break; }
        } catch (RuntimeException | Error failure) {
            try { rollback(); }
            catch (RuntimeException | Error secondary) { if (secondary != failure) failure.addSuppressed(secondary); }
            throw failure;
        }
        if (failed) { rollback(); return false; }
        return true;
    }
    private void rollback() {
        Throwable failure = null;
        for (int i = restores.size() - 1; i >= 0; i--) {
            try { restores.get(i).run(); }
            catch (RuntimeException | Error error) {
                if (failure == null) failure = error;
                else if (failure != error) failure.addSuppressed(error);
            }
        }
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
    }
    public IItemHandler items(IItemHandler source) {
        return source instanceof OpaquePort ? (IItemHandler) opaque(source)
                : (IItemHandler) views.computeIfAbsent(source,k->new Items(source));
    }
    public IFluidHandler fluids(IFluidHandler source) {
        return source instanceof OpaquePort ? (IFluidHandler) opaque(source)
                : (IFluidHandler) views.computeIfAbsent(source,k->new Fluids(source));
    }
    /**
     * A compat port shares this native tank with another medium. Its virtual occupancy replaces the
     * live tank's occupancy gate during planning; storage capacity and fluid validation still apply.
     */
    public IFluidHandler coupleFluidTank(FluidTank source, BooleanSupplier blocked) {
        fluidBlockers.put(source, blocked);
        return fluids(source);
    }
    public IEnergyStorage energy(IEnergyStorage source) { return (IEnergyStorage)views.computeIfAbsent(source,k->new Energy(source)); }
    public Object opaque(Object source) {
        return views.computeIfAbsent(source,k->{
            if (source instanceof OpaquePort port) return port.transactionView(this);
            throw new IllegalStateException("Opaque recipe port must implement ResourceTransaction.OpaquePort: "+source.getClass().getName());
        });
    }
    /** Replace a paid tool/container in a transaction view. Simulation must use the slot after extraction,
     * rather than asking the live occupied slot whether it could accept a differently tagged replacement. */
    public static boolean replaceItem(IItemHandler handler, int slot, ItemStack expected, ItemStack replacement) {
        return handler instanceof ResourceTransaction.Items view && view.replace(slot, expected, replacement);
    }
    private final class Items extends ItemStackHandler {
        final IItemHandler source;
        final List<ItemStack> before = new ArrayList<>();
        Items(IItemHandler source) {
            super(source.getSlots()); this.source=source;
            for(int i=0;i<getSlots();i++){ before.add(source.getStackInSlot(i).copy()); setStackInSlot(i,before.get(i).copy()); }
            enlist(()->{
                for(int i=0;i<getSlots();i++) if(!ItemStack.matches(before.get(i),source.getStackInSlot(i))) return false;
                return true;
            },()->{
                for(int i=0;i<getSlots();i++) {
                    if (ItemStack.matches(before.get(i), source.getStackInSlot(i))) continue;
                    if(source instanceof IItemHandlerModifiable mutable) mutable.setStackInSlot(i,before.get(i).copy());
                    else if(!ItemStack.matches(before.get(i),source.getStackInSlot(i))) {
                        source.extractItem(i,Integer.MAX_VALUE,false);
                        if(!source.insertItem(i,before.get(i).copy(),false).isEmpty()) throw new IllegalStateException("Item rollback refused");
                    }
                }
            });
        }
        @Override public int getSlotLimit(int slot){return source.getSlotLimit(slot);}
        @Override public boolean isItemValid(int slot,ItemStack stack){return source.isItemValid(slot,stack);}
        @Override public ItemStack insertItem(int slot,ItemStack stack,boolean simulate){
            int allowed=stack.getCount()-source.insertItem(slot,stack.copy(),true).getCount();
            if(allowed<=0)return stack;
            ItemStack offer=stack.copyWithCount(Math.min(allowed,stack.getCount()));
            int accepted=offer.getCount()-super.insertItem(slot,offer,simulate).getCount();
            if(!simulate&&accepted>0){ItemStack exact=stack.copyWithCount(accepted);record(()->source.insertItem(slot,exact.copy(),false).isEmpty());}
            return accepted==stack.getCount()?ItemStack.EMPTY:stack.copyWithCount(stack.getCount()-accepted);
        }
        @Override public ItemStack extractItem(int slot,int amount,boolean simulate){
            int allowed=source.extractItem(slot,amount,true).getCount();
            ItemStack result=super.extractItem(slot,Math.min(amount,allowed),simulate);
            if(!simulate&&!result.isEmpty()){ItemStack exact=result.copy();record(()->ItemStack.matches(exact,source.extractItem(slot,exact.getCount(),false)));}
            return result;
        }
        boolean replace(int slot, ItemStack expected, ItemStack replacement) {
            if (!ItemStack.matches(getStackInSlot(slot), expected)
                    || !ItemStack.matches(source.extractItem(slot, expected.getCount(), true), expected)) return false;
            if (!replacement.isEmpty() && (!source.isItemValid(slot, replacement)
                    || replacement.getCount() > Math.min(source.getSlotLimit(slot), replacement.getMaxStackSize()))) return false;
            super.setStackInSlot(slot, replacement.copy());
            record(() -> {
                if (source instanceof ResourceTransaction.Items view) return view.replace(slot, expected, replacement);
                if (!ItemStack.matches(source.extractItem(slot, expected.getCount(), false), expected)) return false;
                return replacement.isEmpty() || source.insertItem(slot, replacement.copy(), false).isEmpty();
            });
            return true;
        }
    }
    private final class Fluids implements IFluidHandler {
        final IFluidHandler source; final FluidStack[] before; final FluidTank[] working;
        Fluids(IFluidHandler source){
            this.source=source; before=new FluidStack[source.getTanks()];working=new FluidTank[before.length];
            for(int i=0;i<before.length;i++){final int tank=i;before[i]=source.getFluidInTank(i).copy();working[i]=new FluidTank(source.getTankCapacity(i),s->source.isFluidValid(tank,s));working[i].setFluid(before[i].copy());}
            enlist(()->{for(int i=0;i<before.length;i++)if(!before[i].isFluidStackIdentical(source.getFluidInTank(i)))return false;return true;},()->{
                boolean unchanged = true;
                for (int i = 0; i < before.length; i++)
                    unchanged &= before[i].isFluidStackIdentical(source.getFluidInTank(i));
                if (unchanged) return;
                if(source instanceof FluidTank tank)tank.setFluid(before[0].copy());
                else if(source instanceof MeFluidTanks tanks){for(int i=0;i<before.length;i++)tanks.tank(i).setFluid(before[i].copy());}
                else {
                    for(int i=0;i<before.length;i++){FluidStack current=source.getFluidInTank(i).copy();if(!current.isEmpty())source.drain(current,FluidAction.EXECUTE);}
                    for(FluidStack stack:before)if(!stack.isEmpty()&&source.fill(stack.copy(),FluidAction.EXECUTE)!=stack.getAmount())throw new IllegalStateException("Fluid rollback refused");
                }
            });
        }
        @Override public int getTanks(){return working.length;}
        @Override public FluidStack getFluidInTank(int tank){return working[tank].getFluid();}
        @Override public int getTankCapacity(int tank){return working[tank].getCapacity();}
        @Override public boolean isFluidValid(int tank,FluidStack stack){return source.isFluidValid(tank,stack);}
        @Override public int fill(FluidStack resource,FluidAction action){
            if (resource.isEmpty()) return 0;
            BooleanSupplier blocker = fluidBlockers.get(source);
            if (blocker != null && blocker.getAsBoolean()) return 0;
            int remaining = blocker == null ? Math.min(resource.getAmount(), source.fill(resource, FluidAction.SIMULATE))
                    : resource.getAmount();
            int offered = remaining;
            // 与罐池一致，先补同种流体，避免把空罐提前占掉而错误拒绝本批次的另一种流体。
            for (FluidTank tank : working) {
                if (remaining <= 0) break;
                if (!tank.isEmpty() && tank.getFluid().isFluidEqual(resource))
                    remaining -= tank.fill(new FluidStack(resource, remaining), action);
            }
            for (FluidTank tank : working) {
                if (remaining <= 0) break;
                if (tank.isEmpty()) remaining -= tank.fill(new FluidStack(resource, remaining), action);
            }
            int accepted=offered-remaining;
            if(action.execute()&&accepted>0){FluidStack exact=new FluidStack(resource,accepted);record(()->source.fill(exact.copy(),FluidAction.EXECUTE)==accepted);}
            return accepted;
        }
        @Override public FluidStack drain(FluidStack resource,FluidAction action){
            if(resource.isEmpty())return FluidStack.EMPTY;
            BooleanSupplier blocker = fluidBlockers.get(source);
            if (blocker != null && blocker.getAsBoolean()) return FluidStack.EMPTY;
            int remaining = blocker == null ? Math.min(resource.getAmount(), source.drain(resource, FluidAction.SIMULATE).getAmount())
                    : resource.getAmount();
            int offered = remaining;
            for(FluidTank tank:working){if(remaining<=0)break;remaining-=tank.drain(new FluidStack(resource,remaining),action).getAmount();}
            int extracted=offered-remaining;
            if(action.execute()&&extracted>0){FluidStack exact=new FluidStack(resource,extracted);record(()->exact.isFluidStackIdentical(source.drain(exact.copy(),FluidAction.EXECUTE)));}
            return extracted==0?FluidStack.EMPTY:new FluidStack(resource,extracted);
        }
        @Override public FluidStack drain(int amount,FluidAction action){for(FluidTank tank:working)if(!tank.isEmpty())return drain(new FluidStack(tank.getFluid(),amount),action);return FluidStack.EMPTY;}
    }
    private final class Energy implements LongEnergyAccess {
        final IEnergyStorage source;
        final long before;
        long stored;
        Energy(IEnergyStorage source) {
            this.source = source;
            before = stored = EnergyAccess.stored(source);
            enlist(() -> EnergyAccess.stored(source) == before, () -> {
                if (source instanceof LongEnergyStorage wide) wide.setStored(before);
                else if (source instanceof EnergyStorage mutable) mutable.deserializeNBT(IntTag.valueOf((int) before));
                else {
                    long current = EnergyAccess.stored(source);
                    long delta = before - current;
                    long changed = delta > 0 ? EnergyAccess.receive(source, delta, false) : -EnergyAccess.extract(source, -delta, false);
                    if (changed != delta) throw new IllegalStateException("Energy rollback refused");
                }
            });
        }
        @Override public long receiveEnergyL(long amount, boolean simulate) {
            long n = Math.max(0, Math.min(Math.min(amount, getMaxEnergyStoredL() - stored), EnergyAccess.receive(source, amount, true)));
            if (!simulate && n > 0) { stored += n; record(() -> EnergyAccess.receive(source, n, false) == n); }
            return n;
        }
        @Override public long extractEnergyL(long amount, boolean simulate) {
            long n = Math.max(0, Math.min(Math.min(amount, stored), EnergyAccess.extract(source, amount, true)));
            if (!simulate && n > 0) { stored -= n; record(() -> EnergyAccess.extract(source, n, false) == n); }
            return n;
        }
        @Override public long getEnergyStoredL() { return stored; }
        @Override public long getMaxEnergyStoredL() { return EnergyAccess.capacity(source); }
        @Override public boolean canExtract() { return source.canExtract(); }
        @Override public boolean canReceive() { return source.canReceive(); }
    }
}
