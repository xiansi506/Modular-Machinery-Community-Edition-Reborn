package com.reborn.modularmachinery.recipe;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.reborn.modularmachinery.ModularMachineryReborn;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads and syncs machine recipes.
 *
 * <p>Requirement {@code type} ids are accepted with either namespace so the original files load unchanged:
 * {@code modularmachinery:item} and {@code modular_machinery_reborn:item} are the same thing, and a bare
 * {@code item} works too. Five kinds exist from 0.26.0 on — {@code item}, {@code fluid}, {@code energy},
 * {@code interface_number_input} and {@code ingredient_array_input}. The original's remaining kinds
 * ({@code gas}, {@code gas_pertick}, {@code fluid_pertick}) are not implemented, because they need a gas API
 * this port does not have; an unknown kind is a load error rather than a silently ignored requirement.
 *
 * <p><b>波 5.2：这个类不再知道「有哪些需求种类」。</b>它原来持有五个
 * {@code private static final String} 常量，并在**三处**各自把那些名字硬编码一遍——JSON 解析的
 * {@code switch}、写包的 {@code instanceof} 链、读包的 {@code switch}。那三处现在都不在了：
 * 种类住在 {@link RequirementTypes} 这张注册表里，每条需求自己知道自己是哪一种
 * （{@link MachineRequirement#type()}），解析与过网由 {@link MachineRequirementParser} 分派。
 * 于是「加一种需求」不再等于改这个文件——那正是兼容模块（{@code compat/}）成立的前提。
 *
 * <p><b>{@code item_durability} and {@code catalyst} are deliberately absent, and are not gaps.</b> Neither
 * exists as a usable requirement type in the original: {@code RequirementTypeItemDurability#createRequirement}
 * returns {@code null} and no code ever constructs it, and the catalyst type has no registration entry at all
 * (its only constructors were the CraftTweaker bridge). See D18 in {@code 移植方案-v2.md}.
 */
public final class ModRecipeSerializers {

    public static final DeferredRegister<RecipeSerializer<?>> SERIALIZERS =
            DeferredRegister.create(ForgeRegistries.RECIPE_SERIALIZERS, ModularMachineryReborn.MOD_ID);
    public static final RegistryObject<RecipeSerializer<MachineRecipe>> MACHINE =
            SERIALIZERS.register("machine", MachineSerializer::new);

    private static final class MachineSerializer implements RecipeSerializer<MachineRecipe> {

        @Override
        public MachineRecipe fromJson(ResourceLocation id, JsonObject json) {
            List<String> machines = MachineRecipe.parseMachines(json, id);
            String registryName = json.has("registryName") ? json.get("registryName").getAsString() : id.getPath();
            int recipeTime = json.has("recipeTime") ? json.get("recipeTime").getAsInt() : 100;
            if (recipeTime < 1) {
                throw new JsonParseException("'recipeTime' of " + id + " must be at least 1");
            }

            JsonElement rawRequirements = json.get("requirements");
            if (rawRequirements == null || !rawRequirements.isJsonArray()) {
                throw new JsonParseException("Missing 'requirements' array in " + id);
            }
            JsonArray array = rawRequirements.getAsJsonArray();
            if (array.isEmpty()) {
                throw new JsonParseException("Empty 'requirements' in " + id);
            }

            List<MachineRequirement> requirements = new ArrayList<>(array.size());
            for (JsonElement element : array) {
                if (!element.isJsonObject()) {
                    throw new JsonParseException("A requirement of " + id + " is not a JSON object");
                }
                // 需求的解析与 `selector-tag` 的挂载都在 MachineRequirementParser 里：它对每种需求类型
                // 含义完全相同，所以整条路径只有一处。种类分派则交给 RequirementTypes 注册表。
                requirements.add(MachineRequirementParser.parse(element.getAsJsonObject(), id));
            }
            return new MachineRecipe(id, machines, registryName, recipeTime, requirements,
                    parseMaxParallelism(json, id), MachineRecipe.parseCancelIfPerTickFails(json, id),
                    RecipeCommands.parse(json));
        }

        /**
         * The optional per-recipe parallelism ceiling, {@code max-parallelism}.
         *
         * <p>The original had no such recipe field — a recipe's ceiling was always the machine's — which is why
         * omitting it (the default, {@link MachineRecipe#MACHINE_LIMIT}) keeps every existing recipe behaving
         * exactly as it did. Writing it is how a single recipe says "even on a machine that can run 512 copies,
         * this one may run at most 8".
         */
        private static int parseMaxParallelism(JsonObject json, ResourceLocation id) {
            if (!json.has("max-parallelism")) {
                return MachineRecipe.MACHINE_LIMIT;
            }
            JsonElement element = json.get("max-parallelism");
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
                throw new JsonParseException("'max-parallelism' of " + id + " must be a number. Write a whole "
                        + "number of copies, e.g. \"max-parallelism\": 8, or remove the field to let the "
                        + "machine's own max-parallelism decide.");
            }
            int value = element.getAsInt();
            if (value < 1) {
                throw new JsonParseException("'max-parallelism' of " + id + " is " + value + ", but a recipe may "
                        + "not run fewer than one copy. Write at least 1, or remove the field to let the "
                        + "machine's own max-parallelism decide.");
            }
            return value;
        }

        @Override
        public MachineRecipe fromNetwork(ResourceLocation id, FriendlyByteBuf buf) {
            int machineCount = buf.readVarInt();
            List<String> machines = new ArrayList<>(machineCount);
            for (int i = 0; i < machineCount; i++) {
                machines.add(buf.readUtf());
            }
            String registryName = buf.readUtf();
            int recipeTime = buf.readVarInt();
            int maxParallelism = buf.readVarInt();
            boolean cancelIfPerTickFails = buf.readBoolean();
            RecipeCommands commands = RecipeCommands.read(buf);
            int count = buf.readVarInt();
            List<MachineRequirement> requirements = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                requirements.add(MachineRequirementParser.read(buf));
            }
            return new MachineRecipe(id, machines, registryName, recipeTime, requirements, maxParallelism,
                    cancelIfPerTickFails, commands);
        }

        @Override
        public void toNetwork(FriendlyByteBuf buf, MachineRecipe recipe) {
            buf.writeVarInt(recipe.machines().size());
            for (String machine : recipe.machines()) {
                buf.writeUtf(machine);
            }
            buf.writeUtf(recipe.registryName());
            buf.writeVarInt(recipe.recipeTime());
            buf.writeVarInt(recipe.maxParallelism());
            buf.writeBoolean(recipe.cancelIfPerTickFails());
            recipe.commands().write(buf);
            buf.writeVarInt(recipe.requirements().size());
            for (MachineRequirement requirement : recipe.requirements()) {
                MachineRequirementParser.write(buf, requirement);
            }
        }
    }

    // --------------------------------------------------------------- network
    //
    // 需求的两端都不再转发到私有方法：种类分派住在 MachineRequirementParser 里，因为那是**能离机驱动**的
    // 地方，而本类不能（它持有 DeferredRegister，静态初始化会碰 net.minecraftforge.fml.common.Mod）。
    // 这里留一层同名的私有转发除了让调用点看起来没变，没有任何作用——而「看起来没变」正是这一波要拆掉的东西。
}
