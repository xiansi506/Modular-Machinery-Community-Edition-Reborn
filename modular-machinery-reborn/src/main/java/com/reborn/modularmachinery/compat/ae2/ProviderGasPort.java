package com.reborn.modularmachinery.compat.ae2;

import appeng.api.stacks.AEKey;
import me.ramidzkh.mekae2.ae2.MekanismKey;
import mekanism.api.Action;
import mekanism.api.chemical.gas.*;
import com.reborn.modularmachinery.recipe.ResourceTransaction;
import java.util.List;

/** Optional AppMek adapter. Shares the item/fluid buffer's transaction owner. */
public final class ProviderGasPort implements IGasHandler, ResourceTransaction.OpaquePort {
    private final ProviderBuffer buffer;
    public ProviderGasPort(ProviderBuffer buffer){this.buffer=buffer;}
    public static boolean isGas(AEKey key){return key instanceof MekanismKey chemical&&chemical.getForm()==MekanismKey.GAS&&chemical.getStack() instanceof GasStack gas&&!gas.isEmpty();}
    private List<AEKey> keys(){return buffer.keys(ProviderGasPort::isGas);}
    public int getTanks(){return keys().size();}
    public GasStack getChemicalInTank(int tank){var keys=keys();if(!buffer.accessible()||tank<0||tank>=keys.size())return GasStack.EMPTY;var key=(MekanismKey)keys.get(tank);return new GasStack((GasStack)key.getStack(),buffer.amount(key));}
    public void setChemicalInTank(int tank,GasStack stack){throw new UnsupportedOperationException("Read-only input storage");}
    public long getTankCapacity(int tank){return Long.MAX_VALUE;}
    public boolean isValid(int tank,GasStack stack){return false;}
    public GasStack insertChemical(int tank,GasStack stack,Action action){return stack;}
    public GasStack extractChemical(int tank,long amount,Action action){var keys=keys();if(tank<0||tank>=keys.size())return GasStack.EMPTY;var key=(MekanismKey)keys.get(tank);return new GasStack((GasStack)key.getStack(),buffer.take(key,amount,action.execute()));}
    public Object transactionView(ResourceTransaction tx){return new ProviderGasPort((ProviderBuffer)tx.opaque(buffer));}
}
