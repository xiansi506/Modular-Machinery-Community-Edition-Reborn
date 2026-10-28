package com.reborn.modularmachinery.compat.mekanism;

import mekanism.api.chemical.gas.GasStack;

/** Resolved only after the optional Mekanism guard in the JEI handler. */
public final class MekanismEditorIngredient {
    public static String id(Object ingredient) { return ingredient instanceof GasStack gas && !gas.isEmpty() ? gas.getType().getRegistryName().toString() : null; }
}
