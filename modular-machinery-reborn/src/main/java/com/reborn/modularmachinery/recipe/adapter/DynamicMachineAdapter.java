package com.reborn.modularmachinery.recipe.adapter;

import com.reborn.modularmachinery.recipe.EnergyRequirement;
import com.reborn.modularmachinery.recipe.FluidRequirement;
import com.reborn.modularmachinery.recipe.IOType;
import com.reborn.modularmachinery.recipe.ItemRequirement;
import com.reborn.modularmachinery.recipe.MachineRecipe;
import com.reborn.modularmachinery.recipe.MachineRequirement;
import com.reborn.modularmachinery.recipe.MachineRequirementParser;
import com.reborn.modularmachinery.recipe.ModRecipeTypes;
import com.reborn.modularmachinery.recipe.RecipeModifier;
import com.reborn.modularmachinery.recipe.RecipeModifiers;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeManager;

import java.util.ArrayList;
import java.util.List;

/**
 * C1 第一纵切（0.107.0）：机器间配方复用——旧版 {@code DynamicMachineRecipeAdapter} 的对应物。
 *
 * <p>旧版机制（:42-62）：每台已注册机器获得一个以自身 registryName 为名的适配器；配方 JSON 声明
 * {@code "adapter": "<机器 id>"} 即把那台机器的配方整批复制过来——
 * {@code MachineRecipe.copy}（1.12.2 :244-265）做三件事：时长按 duration 修饰符重算、
 * 每条需求 {@code deepCopyModified(modifiers)}、其余字段（priority/failure 行为/tooltip）原样保留。
 * 本版逐条对应，adapter key 就是父机器的 id。
 *
 * <p><b>深拷贝的通道</b>：需求的 parallelism 是可变的结算状态（{@code setParallelism}），
 * 两条配方共享同一条需求实例会互相污染——所以每条需求必须深拷贝。这里用现成的网络往返
 * （{@link MachineRequirementParser#write} + {@link MachineRequirementParser#read}）：
 * 它就是客户端同步走的同一条编解码链，任何需求类型（含兼容模块新增的）都自动被覆盖，
 * 不需要每加一种需求就多写一个 copy 方法。
 *
 * <p><b>修饰符烘入的覆盖范围（第一纵切）</b>：item / fluid / energy 三类值承载型需求逐字段重建
 * （数量、机会、能耗都过 {@link RecipeModifiers#apply}）；catalyst / ingredient_array /
 * interface_number_input 透传不烘——旧版对它们各有 deepCopyModified 实现，但前两类本身携带
 * 运行期修饰符、第三类是等值判断没有数值可烘，等有真实场景再逐类补。
 */
final class DynamicMachineAdapter {

    private DynamicMachineAdapter() {
    }

    static List<MachineRecipe> generate(MachineRecipe declaration, ResourceLocation owning,
                                        RecipeManager manager) {
        RecipeModifiers mods = RecipeModifiers.of(declaration.adapterModifiers());
        ResourceLocation parent = declaration.adapterKey();
        List<MachineRecipe> generated = new ArrayList<>();
        for (MachineRecipe original : manager.getAllRecipesFor(ModRecipeTypes.MACHINE.get())) {
            // 占位（别的适配器声明）不是可执行配方，不复制——旧版没有这一类，没有对应物可言。
            if (original.isAdapterDeclaration() || !original.belongsTo(parent)) {
                continue;
            }
            generated.add(copyOf(original, owning, mods, RecipeAdapterRegistry.generatedId(declaration, owning, original.getId())));
        }
        return generated;
    }

    /** 旧版 {@code MachineRecipe.copy}（1.12.2 :244-265）的对应物，见类注释的三件事。 */
    private static MachineRecipe copyOf(MachineRecipe original, ResourceLocation owning, RecipeModifiers mods,
                                        ResourceLocation newId) {
        int duration = Math.max(1, (int) Math.round(mods.applyDuration(original.recipeTime())));
        List<MachineRequirement> requirements = new ArrayList<>(original.requirements().size());
        for (MachineRequirement requirement : original.requirements()) {
            requirements.add(copyModified(requirement, mods));
        }
        return new MachineRecipe(newId, List.of(owning.toString()),
                original.registryName() + ".copy." + newId.getPath(), duration, requirements,
                original.maxParallelism(), original.cancelIfPerTickFails(), original.commands(),
                original.priority(), original.tooltips());
    }

    /** 网络往返深拷贝 + 三类值承载型需求的修饰符烘入；其余类型原样透传（类注释的覆盖范围说明）。 */
    private static MachineRequirement copyModified(MachineRequirement requirement, RecipeModifiers mods) {
        MachineRequirement copy = roundTrip(requirement);
        MachineRequirement result = copy;
        if (copy instanceof ItemRequirement item) {
            result = copyItem(item, mods);
        } else if (copy instanceof FluidRequirement fluid) {
            result = copyFluid(fluid, mods);
        } else if (copy instanceof EnergyRequirement energy) {
            long perTick = Math.max(0, Math.round(mods.apply(RecipeModifier.Target.ENERGY, energy.ioType(),
                    energy.energyPerTick(), false)));
            result = new EnergyRequirement(energy.ioType(), perTick);
        }
        result.setSelectorTag(copy.selectorTag());
        result.setParallelizeUnaffected(copy.isParallelizeUnaffected());
        return result;
    }

    private static ItemRequirement copyItem(ItemRequirement item, RecipeModifiers mods) {
        if (item.ioType() == IOType.INPUT) {
            int amount = Math.max(1, (int) Math.round(mods.apply(RecipeModifier.Target.ITEM, IOType.INPUT,
                    item.amount(), false)));
            float chance = (float) mods.apply(RecipeModifier.Target.ITEM, IOType.INPUT, item.chance(), true);
            return ItemRequirement.input(item.ingredient(), amount, item.nbt(), item.displayTag(), chance);
        }
        int min = Math.max(1, (int) Math.round(mods.apply(RecipeModifier.Target.ITEM, IOType.OUTPUT,
                item.minAmount(), false)));
        int max = Math.max(min, (int) Math.round(mods.apply(RecipeModifier.Target.ITEM, IOType.OUTPUT,
                item.maxAmount(), false)));
        float chance = (float) mods.apply(RecipeModifier.Target.ITEM, IOType.OUTPUT, item.chance(), true);
        return ItemRequirement.output(item.outputStack().copyWithCount(min), min, max, chance, item.displayTag());
    }

    private static FluidRequirement copyFluid(FluidRequirement fluid, RecipeModifiers mods) {
        int amount = Math.max(1, (int) Math.round(mods.apply(RecipeModifier.Target.FLUID, fluid.ioType(),
                fluid.amount(), false)));
        float chance = (float) mods.apply(RecipeModifier.Target.FLUID, fluid.ioType(), fluid.chance(), true);
        return new FluidRequirement(fluid.ioType(), fluid.fluidType(), amount, chance, fluid.isTickPhase());
    }

    /**
     * 经网络编解码链深拷贝一条需求——与客户端同步同一条 {@code write/read} 链，
     * 所以任何需求类型的往返保真度与同步一致。
     */
    private static MachineRequirement roundTrip(MachineRequirement requirement) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            MachineRequirementParser.write(buf, requirement);
            return MachineRequirementParser.read(buf);
        } finally {
            buf.release();
        }
    }
}
