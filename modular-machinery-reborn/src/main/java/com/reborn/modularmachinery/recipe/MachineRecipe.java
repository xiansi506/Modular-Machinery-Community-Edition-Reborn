package com.reborn.modularmachinery.recipe;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.reborn.modularmachinery.ModularMachineryReborn;
import com.reborn.modularmachinery.machine.HatchCollection;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import com.reborn.modularmachinery.config.ModConfig;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A machine recipe: a machine name, a duration, and a list of typed requirements.
 *
 * <p>This is the original schema, not the flat {@code input}/{@code output}/{@code duration}/{@code energy}
 * shortcut of 0.4–0.7:
 *
 * <pre>{@code
 * {
 *   "machine": "alloy_furnace",
 *   "registryName": "alloy_furnace_diamond",
 *   "recipeTime": 5,
 *   "requirements": [
 *     { "type": "...:energy", "io-type": "input", "energyPerTick": 100 },
 *     { "type": "...:item",   "io-type": "input", "item": "minecraft:coal", "amount": 32 },
 *     { "type": "...:item",   "io-type": "output", "item": "minecraft:diamond", "amount": 1 }
 *   ]
 * }
 * }</pre>
 *
 * <p>The phase split follows the original lifecycle: {@link #start} consumes inputs, {@link #tick} runs the
 * per-tick requirements, and {@link #finish} produces outputs while rolling each output's chance
 * independently.
 *
 * <h2>Parallelism and modifiers</h2>
 *
 * <p>Both live on this class because both are properties of a <i>settlement</i> rather than of a requirement:
 *
 * <ul>
 *   <li>{@link #parallelism(HatchCollection, RecipeModifiers, int)} reproduces
 *       {@code RecipeCraftingContext#getMaxParallelism} — start from this recipe's own limit, then take the
 *       minimum over every requirement that can be parallelised, returning {@code 0} the moment one reports it
 *       cannot serve even a single copy.</li>
 *   <li>Every lifecycle method takes the machine's {@link RecipeModifiers} so requirement values and chances
 *       can be modified, which is what the original did by hanging a {@code RecipeCraftingContext} off the
 *       craft. The zero-argument overloads remain for callers with no machine attached (JEI, the recipe book,
 *       the plain-container check) and behave exactly as before M6c.</li>
 * </ul>
 *
 * <p>A craft freezes its parallelism when it starts, which is why {@link #setParallelism} exists separately:
 * the original's {@code RecipeCraftingContext#setParallelism} did the same, and only revisited it from
 * {@code canRestartCrafting}.
 */
public final class MachineRecipe implements Recipe<Container> {

    /**
     * The implicit limit of a recipe that does not set {@code max-parallelism}.
     *
     * <p>The original had no per-recipe parallelism field at all: a recipe's ceiling was the machine's
     * {@code getMaxParallelism()}, and only a CraftTweaker script could lower it through
     * {@code ActiveMachineRecipe#setMaxParallelism}. {@code 0} here means "no opinion — use the machine's",
     * which is the faithful default and keeps every existing recipe behaving exactly as it did.
     */
    public static final int MACHINE_LIMIT = 0;

    private final ResourceLocation id;
    private final List<String> machines;
    private final List<ResourceLocation> machineIds;
    private final String registryName;
    private final int recipeTime;
    private final List<MachineRequirement> requirements;

    /** This recipe's own parallelism ceiling, or {@link #MACHINE_LIMIT} to defer to the machine's. */
    private final int maxParallelism;

    /**
     * The original's {@code cancelIfPerTickFails}: when a per-tick requirement cannot be served, drop this
     * craft (keeping the inputs already paid at start) instead of applying the machine's failure-action.
     */
    private final boolean cancelIfPerTickFails;

    private final RecipeCommands commands;

    /**
     * 旧版配方级 {@code priority}：数字小的层先被遍历，同层再按 {@link #buildWeight()} 升序。
     *
     * <p>它不是"权重越大越优先"——旧版把它当 TreeMap 的键，{@code getRecipesFor} 按升序拼接，
     * 所以 0 层比 1 层先被看到。缺省 0。
     */
    private final int priority;

    /**
     * 构造顺序号，旧版 {@code MachineRecipe#sortId} 的全局自增计数器。
     *
     * <p><b>这一版把它写进网络包</b>，而旧版没有：客户端也要按同一顺序列 JEI 配方，若两端各自自增，
     * 同一条配方在两端的 {@link #buildWeight()} 会不同。读包时用服务端给的值构造，不推进本端计数器。
     */
    private final int sortId;

    /** 旧版每构造一条配方就 {@code counter++}；{@code AtomicInteger} 只为线程安全，语义不变。 */
    private static final AtomicInteger NEXT_SORT_ID = new AtomicInteger();

    private static int nextSortId() {
        return NEXT_SORT_ID.getAndIncrement();
    }

    public MachineRecipe(ResourceLocation id, String machine, String registryName, int recipeTime,
                         List<MachineRequirement> requirements) {
        this(id, List.of(machine), registryName, recipeTime, requirements, MACHINE_LIMIT, false,
                RecipeCommands.EMPTY);
    }

    public MachineRecipe(ResourceLocation id, String machine, String registryName, int recipeTime,
                         List<MachineRequirement> requirements, int maxParallelism) {
        this(id, List.of(machine), registryName, recipeTime, requirements, maxParallelism, false,
                RecipeCommands.EMPTY);
    }

    public MachineRecipe(ResourceLocation id, String machine, String registryName, int recipeTime,
                         List<MachineRequirement> requirements, int maxParallelism,
                         boolean cancelIfPerTickFails) {
        this(id, List.of(machine), registryName, recipeTime, requirements, maxParallelism, cancelIfPerTickFails,
                RecipeCommands.EMPTY);
    }

    /**
     * The canonical constructor. A recipe may name several owning machines (the original's {@code machine}
     * array); every one of them sees the recipe in its recipe list.
     */
    public MachineRecipe(ResourceLocation id, List<String> machines, String registryName, int recipeTime,
                         List<MachineRequirement> requirements, int maxParallelism,
                         boolean cancelIfPerTickFails, RecipeCommands commands) {
        this(id, machines, registryName, recipeTime, requirements, maxParallelism, cancelIfPerTickFails,
                commands, 0, nextSortId());
    }

    /** The same recipe at {@code priority}; the sort id still comes from the global construction counter. */
    public MachineRecipe(ResourceLocation id, List<String> machines, String registryName, int recipeTime,
                         List<MachineRequirement> requirements, int maxParallelism,
                         boolean cancelIfPerTickFails, RecipeCommands commands, int priority) {
        this(id, machines, registryName, recipeTime, requirements, maxParallelism, cancelIfPerTickFails,
                commands, priority, nextSortId());
    }

    /**
     * The canonical constructor, including the original's ordering fields.
     *
     * <p>{@code sortId} is supplied rather than allocated for one caller only: the network reader, which must
     * reproduce the server's order instead of advancing its own counter (see {@link #sortId()}).
     */
    public MachineRecipe(ResourceLocation id, List<String> machines, String registryName, int recipeTime,
                         List<MachineRequirement> requirements, int maxParallelism,
                         boolean cancelIfPerTickFails, RecipeCommands commands, int priority, int sortId) {
        if (machines.isEmpty()) {
            throw new IllegalArgumentException("A machine recipe needs at least one owning machine");
        }
        this.id = id;
        this.machines = List.copyOf(machines);
        List<ResourceLocation> resolved = new ArrayList<>(machines.size());
        for (String machine : machines) {
            resolved.add(resolveMachineId(machine));
        }
        this.machineIds = List.copyOf(resolved);
        this.registryName = registryName;
        this.recipeTime = Math.max(1, recipeTime);
        this.requirements = List.copyOf(requirements);
        this.maxParallelism = Math.max(0, maxParallelism);
        this.cancelIfPerTickFails = cancelIfPerTickFails;
        this.commands = commands == null ? RecipeCommands.EMPTY : commands;
        this.priority = priority;
        this.sortId = sortId;
    }

    private static ResourceLocation resolveMachineId(String machine) {
        ResourceLocation parsed = machine.indexOf(':') >= 0 ? ResourceLocation.tryParse(machine) : null;
        return parsed != null ? parsed : new ResourceLocation(ModularMachineryReborn.MOD_ID, machine);
    }

    /** The first owning machine, kept for callers that only need one name. */
    public String machine() {
        return this.machines.get(0);
    }

    /** Every owning machine, in the order the recipe file named them. */
    public List<String> machines() {
        return this.machines;
    }

    /** Every owning machine resolved to a registry name. */
    public List<ResourceLocation> machineIds() {
        return this.machineIds;
    }

    /** Whether this recipe belongs to {@code machine}. */
    public boolean belongsTo(ResourceLocation machine) {
        return this.machineIds.contains(machine);
    }

    /** The three command lists carried by this recipe. */
    public RecipeCommands commands() {
        return this.commands;
    }

    /** The {@code registryName} the original recipe file carried, or the file path when absent. */
    public String registryName() {
        return this.registryName;
    }

    /**
     * The first {@code machine} field resolved to a registry name.
     *
     * <p>Accepts either a full resource location ({@code modular_machinery_reborn:transformer}) or a bare path
     * ({@code transformer}), which is prefixed with this mod's namespace. The result is compared against the
     * machines in {@code MachineRegistry}.
     */
    public ResourceLocation machineId() {
        return this.machineIds.get(0);
    }

    /**
     * Reads the required {@code machine} field, which the original accepted as one string or a non-empty array
     * of strings (1.12.2 {@code MachineRecipe.java:338-360}).
     */
    public static List<String> parseMachines(JsonObject json, ResourceLocation id) {
        if (!json.has("machine")) {
            throw new JsonParseException("No 'machine'-entry specified!");
        }
        JsonElement element = json.get("machine");
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            if (array.isEmpty()) {
                throw new JsonParseException("'machine' is an array, but it's empty! Provide at least 1 "
                        + "owning-machine name!");
            }
            List<String> names = new ArrayList<>(array.size());
            for (JsonElement entry : array) {
                if (!entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isString()) {
                    throw new JsonParseException("Found an element in the array specified in 'machine' that "
                            + "is not a string! " + entry);
                }
                names.add(entry.getAsString());
            }
            return List.copyOf(names);
        }
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            return List.of(element.getAsString());
        }
        throw new JsonParseException("'machine' has to be either an array of strings or just a string! - "
                + "Found " + element + " instead!");
    }

    public int recipeTime() {
        return this.recipeTime;
    }

    public List<MachineRequirement> requirements() {
        return this.requirements;
    }

    /** The original's {@code priority}: lower layers are visited first. Defaults to {@code 0}. */
    public int priority() {
        return this.priority;
    }

    /**
     * The construction-order number that breaks ties inside one priority layer.
     *
     * <p>On the client this is the value received from the server, not a locally allocated one, so both sides
     * compute the same {@link #buildWeight()} for the same recipe.
     */
    public int sortId() {
        return this.sortId;
    }

    /**
     * The original's {@code buildWeight()}: the sort id minus every <b>input</b> requirement's sorting weight.
     *
     * <p>Outputs are skipped exactly as the original comparator skipped them ({@code RecipeRegistry:272-282}).
     * Smaller sorts first: every input subtracts its weight, so a recipe with more inputs — or with energy
     * inputs ({@code 50_000_000}) rather than item inputs ({@code 50_000}) — gets a smaller value and is tried
     * earlier. That is the original's way of preferring the "heavier" recipe inside one priority layer.
     *
     * <p>Arithmetic is deliberately plain {@code int}: the original could overflow (a recipe with 43 energy
     * inputs crosses {@link Integer#MIN_VALUE}), and clamping here would silently invent an order the original
     * never had. The verifier asserts the ordinary, non-overflowing case.
     */
    public int buildWeight() {
        int weight = this.sortId;
        for (MachineRequirement requirement : this.requirements) {
            if (requirement.ioType() == IOType.OUTPUT) {
                continue;
            }
            weight -= requirement.sortingWeight();
        }
        return weight;
    }

    /** This recipe's own parallelism ceiling, or {@code 0} when it defers to the machine's. */
    public int maxParallelism() {
        return this.maxParallelism;
    }

    /**
     * Whether a failed per-tick check cancels the whole craft rather than applying the failure-action.
     *
     * <p>This is the original's {@code MachineRecipe#doesCancelRecipeOnPerTickFailure} (1.12.2
     * {@code MachineRecipe.java:227-229}), read from {@code RecipeFailureEvent#isDestructRecipe}. The original
     * allowed that event to overwrite the value; this port has no failure-event layer yet, so the field is
     * consumed directly (registered as a deviation in {@code 迁移日志}).
     */
    public boolean cancelIfPerTickFails() {
        return this.cancelIfPerTickFails;
    }

    /**
     * Reads the optional {@code cancelIfPerTickFails} field, with the original's strict boolean rule.
     *
     * <p>It lives here rather than in the serializer's nested class so the offline verifier can exercise it:
     * {@code ModRecipeSerializers} holds a {@code DeferredRegister} and cannot initialise outside the game. The
     * original accepted only a JSON boolean and rejected everything else at parse time
     * ({@code MachineRecipe.java:381-388}); like the other recipe fields, omitting it means {@code false}.
     */
    public static boolean parseCancelIfPerTickFails(JsonObject json, ResourceLocation id) {
        if (!json.has("cancelIfPerTickFails")) {
            return false;
        }
        JsonElement element = json.get("cancelIfPerTickFails");
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
            throw new JsonParseException("'cancelIfPerTickFails' has to be a boolean! (if specified) in " + id);
        }
        return element.getAsBoolean();
    }

    /**
     * Reads the optional {@code priority}, with the original's rule: absent means {@code 0}, anything that is not
     * a number is rejected at parse time.
     *
     * <p>The error keeps the original's sentence verbatim (1.12.2 {@code MachineRecipe.java:372-379}) and only
     * appends the recipe id, because "which file?" is the first question a pack author asks. Negative values are
     * accepted as-is: the original used whatever integer was written.
     */
    public static int parsePriority(JsonObject json, ResourceLocation id) {
        if (!json.has("priority")) {
            return 0;
        }
        JsonElement element = json.get("priority");
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new JsonParseException("'priority' has to be a number! (if specified) in " + id);
        }
        return element.getAsInt();
    }

    public List<MachineRequirement> requirements(IOType ioType) {
        List<MachineRequirement> out = new ArrayList<>();
        for (MachineRequirement requirement : this.requirements) {
            if (requirement.ioType() == ioType) {
                out.add(requirement);
            }
        }
        return out;
    }

    // ------------------------------------------------------------ parallelism

    /**
     * How long one craft takes, after the machine's {@code duration} modifiers.
     *
     * <p>The original recomputed this on every tick ({@code ActiveMachineRecipe#tick}) and again at start, and
     * clamped it to at least one tick — a modifier may legitimately shorten a craft below a tick, in which case
     * {@code calculateExtraParallelism} multiplied the parallelism by {@code 1 / totalTick} instead.
     */
    public int duration(RecipeModifiers modifiers) {
        if (modifiers.isEmpty()) {
            return this.recipeTime;
        }
        double modified = modifiers.applyDuration(this.recipeTime);
        return modified < 1.0 ? 1 : (int) Math.round(modified);
    }

    /**
     * The modifiers the catalysts that <b>are present in these ports</b> contribute, merged.
     *
     * <p>A catalyst is an optional input in the original: absent means the craft runs without it and without
     * its modifiers ({@code CraftCheck.skipComponent()} was not an error), present means it is paid once and its
     * modifiers are folded in. Presence therefore has to be part of this query. The caller freezes the result
     * for the lifetime of one craft: the catalyst is consumed at start, so re-asking the ports on every tick
     * would silently strip the modifiers half-way through.
     */
    public RecipeModifiers catalystModifiers(HatchCollection ports) {
        List<RecipeModifier> merged = new ArrayList<>();
        for (MachineRequirement requirement : this.requirements) {
            if (requirement instanceof CatalystRequirement catalyst && catalyst.hasCatalyst(ports)) {
                merged.addAll(catalyst.modifiers());
            }
        }
        return RecipeModifiers.of(merged);
    }

    /**
     * The original's {@code RecipeCraftingContext#getMaxParallelism}: the lowest ceiling any parallelisable
     * requirement is willing to serve, starting from this recipe's own limit.
     *
     * <p>{@code 0} means "cannot be parallelised at all right now", which the original used to fall back to a
     * plain, single-copy craft check. That is the safe reading and it is what the controller does too.
     *
     * @param ceiling the machine's own limit, i.e. the original's {@code activeRecipe.getMaxParallelism()}
     */
    public int parallelism(HatchCollection ports, RecipeModifiers modifiers, int ceiling) {
        int limit = this.maxParallelism > 0 ? Math.min(ceiling, this.maxParallelism) : ceiling;
        if (limit <= 0) {
            return 0;
        }
        for (MachineRequirement requirement : this.requirements) {
            if (!requirement.isParallelizable()) {
                continue;
            }
            limit = Math.min(limit, requirement.parallelLimit(ports, modifiers, limit));
            if (limit <= 0) {
                return 0;
            }
        }
        return limit;
    }

    /**
     * Freezes how many copies every requirement settles. Called once, when a craft starts; the original did the
     * same through {@code RecipeCraftingContext#setParallelism}.
     */
    public void setParallelism(int parallelism) {
        for (MachineRequirement requirement : this.requirements) {
            requirement.setParallelism(parallelism);
        }
    }

    /**
     * The copies currently settled per craft, as fixed by the last {@link #setParallelism}.
     *
     * <p>Unaffected requirements are skipped: a catalyst keeps its own field on one copy, so reading it first would
     * report 1 for a craft that is actually running several copies.
     */
    public int parallelism() {
        for (MachineRequirement requirement : this.requirements) {
            if (requirement.isParallelizable()) {
                return requirement.parallelism();
            }
        }
        return 1;
    }

    /**
     * Pushes the shortened-duration cost compensation into every per-tick energy requirement.
     *
     * <p>The config decision is {@link ModConfig#enableDurationMultiplier()}: with the original's default
     * ({@code false}) this method writes {@code 1.0} and a duration modifier only changes the tick count. The
     * overload takes the decision explicitly for callers that have already resolved it, and for the offline
     * harness, which has no loaded config file.
     */
    public void applyDurationMultiplier(RecipeModifiers modifiers) {
        applyDurationMultiplier(modifiers, ModConfig.enableDurationMultiplier());
    }

    /** {@link #applyDurationMultiplier(RecipeModifiers)} with the config decision supplied by the caller. */
    public void applyDurationMultiplier(RecipeModifiers modifiers, boolean enabled) {
        float multiplier = modifiers.durationMultiplier(this.recipeTime, enabled);
        for (MachineRequirement requirement : this.requirements) {
            if (requirement instanceof EnergyRequirement energy) {
                energy.setDurationMultiplier(multiplier);
            }
        }
    }

    // ------------------------------------------------------------ craft lifecycle

    /** All one-shot inputs can be paid right now. */
    public boolean canStart(HatchCollection ports) {
        return canStart(ports, RecipeModifiers.EMPTY);
    }

    /** {@link #canStart} with modifiers applied to every requirement. */
    public boolean canStart(HatchCollection ports, RecipeModifiers modifiers) {
        for (MachineRequirement requirement : this.requirements) {
            if (!requirement.isStartPhase()) {
                continue;
            }
            if (!canSatisfy(requirement, ports, modifiers)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The translation key of the first start-phase requirement that refuses this craft, or {@code null} when
     * every one of them is satisfied.
     *
     * <p>It exists because a boolean cannot say <i>why</i>: the controller used to report a bare
     * {@code idle} for every unmet start requirement, which for {@code interface_number_input} would hide the
     * original's own two messages ({@code component.missing.modularmachinery.interface.number},
     * {@code craftcheck.failure.interface.number.notequal}). The same
     * {@link #canSatisfy(MachineRequirement, HatchCollection, RecipeModifiers)} dispatch is used here, so the
     * message is only ever produced by a requirement that really did answer {@code false} — the two cannot
     * disagree.
     */
    @javax.annotation.Nullable
    public String startFailure(HatchCollection ports, RecipeModifiers modifiers) {
        for (MachineRequirement requirement : this.requirements) {
            if (!requirement.isStartPhase()) {
                continue;
            }
            if (canSatisfy(requirement, ports, modifiers)) {
                continue;
            }
            String message = requirement.startFailure(ports);
            if (message != null) {
                return message;
            }
        }
        return null;
    }

    /** Consumes the one-shot inputs. Only call after {@link #canStart}. */
    public boolean start(HatchCollection ports, RandomSource random) {
        return start(ports, random, RecipeModifiers.EMPTY);
    }

    /** {@link #start} with modifiers applied to every requirement. */
    public boolean start(HatchCollection ports, RandomSource random, RecipeModifiers modifiers) {
        for (MachineRequirement requirement : this.requirements) {
            if (!requirement.isStartPhase()) {
                continue;
            }
            if (!applyPhase(requirement, ports, random, modifiers)) {
                return false;
            }
        }
        return true;
    }

    /** Every per-tick requirement can be served this tick. */
    public boolean canTick(HatchCollection ports) {
        return canTick(ports, RecipeModifiers.EMPTY);
    }

    /** {@link #canTick} with modifiers applied to every requirement. */
    public boolean canTick(HatchCollection ports, RecipeModifiers modifiers) {
        for (MachineRequirement requirement : this.requirements) {
            if (!requirement.isTickPhase()) {
                continue;
            }
            if (!canSatisfy(requirement, ports, modifiers)) {
                return false;
            }
        }
        return true;
    }

    /** Runs the per-tick requirements: energy and per-tick fluids. */
    public boolean tick(HatchCollection ports, RandomSource random) {
        return tick(ports, random, RecipeModifiers.EMPTY);
    }

    /** {@link #tick} with modifiers applied to every requirement. */
    public boolean tick(HatchCollection ports, RandomSource random, RecipeModifiers modifiers) {
        for (MachineRequirement requirement : this.requirements) {
            if (!requirement.isTickPhase()) {
                continue;
            }
            if (!applyPhase(requirement, ports, random, modifiers)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Every output has room for its <b>guaranteed</b> amount. The chance roll is deliberately not applied here:
     * the original checked the finish with {@code ResultChance.GUARANTEED} so that a craft cannot start ending
     * when its possible result would not fit.
     */
    public boolean canFinish(HatchCollection ports) {
        return canFinish(ports, RecipeModifiers.EMPTY);
    }

    /** {@link #canFinish} with modifiers applied to every requirement. */
    public boolean canFinish(HatchCollection ports, RecipeModifiers modifiers) {
        for (MachineRequirement requirement : this.requirements) {
            if (!requirement.isFinishPhase()) {
                continue;
            }
            if (!canSatisfy(requirement, ports, modifiers)) {
                return false;
            }
        }
        return true;
    }

    /** Produces the outputs, rolling each output requirement's chance on its own. */
    public boolean finish(HatchCollection ports, RandomSource random) {
        return finish(ports, random, RecipeModifiers.EMPTY);
    }

    /** {@link #finish} with modifiers applied to every requirement. */
    public boolean finish(HatchCollection ports, RandomSource random, RecipeModifiers modifiers) {
        for (MachineRequirement requirement : this.requirements) {
            if (!requirement.isFinishPhase()) {
                continue;
            }
            if (!applyPhase(requirement, ports, random, modifiers)) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------ dispatch

    /**
     * Routes a requirement to its modifier-aware method. The original had one implementation per requirement
     * class reading the context's modifier appliers directly; this mod has to pass the set in, and the
     * {@code instanceof} ladder here is the single place that knows which overload to call.
     *
     * <p><b>这是 {@code selector-tag} 的落点。</b>每个需求的四个阶段（能不能开工 / 开工结算 / 每 tick 检查 /
     * 结束时结算）都要经过这里或 {@link #applyPhase}，所以在这一处把端口换成
     * {@link MachineRequirement#visiblePorts} 的筛选视图，就同时覆盖了全部阶段——
     * 不会有「能开工但料消耗到别的仓」这种某一阶段漏筛的不一致。
     */
    private static boolean canSatisfy(MachineRequirement requirement, HatchCollection ports,
                                      RecipeModifiers modifiers) {
        HatchCollection visible = requirement.visiblePorts(ports);
        if (requirement instanceof ItemRequirement item) {
            return item.canSatisfy(visible, modifiers);
        }
        if (requirement instanceof FluidRequirement fluid) {
            return fluid.canSatisfy(visible, modifiers);
        }
        if (requirement instanceof EnergyRequirement energy) {
            return energy.canSatisfy(visible, modifiers);
        }
        return requirement.canSatisfy(visible);
    }

    /** {@link #canSatisfy} 的副作用那一半，所以它同样要把端口筛成需求能看见的那部分。 */
    private static boolean applyPhase(MachineRequirement requirement, HatchCollection ports, RandomSource random,
                                      RecipeModifiers modifiers) {
        HatchCollection visible = requirement.visiblePorts(ports);
        if (requirement instanceof ItemRequirement item) {
            return item.apply(visible, random, modifiers);
        }
        if (requirement instanceof FluidRequirement fluid) {
            return requirement.isTickPhase()
                    ? fluid.tick(visible, random, modifiers)
                    : fluid.apply(visible, random, modifiers);
        }
        if (requirement instanceof EnergyRequirement energy) {
            return energy.tick(visible, random, modifiers);
        }
        return requirement.isTickPhase()
                ? requirement.tick(visible, random)
                : requirement.apply(visible, random);
    }

    // ------------------------------------------------------------ Recipe<Container>

    /**
     * A weak check over a plain container: hand-written so the recipe is usable through the vanilla interfaces.
     * Real matching goes through {@link #canStart}, which also sees fluids, energy and every port.
     */
    @Override
    public boolean matches(Container container, Level level) {
        for (MachineRequirement requirement : this.requirements) {
            if (!(requirement instanceof ItemRequirement item) || !item.isStartPhase()) {
                continue;
            }
            if (item.ingredient() == null) {
                continue;
            }
            int found = 0;
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                // Same rule the machine uses: ingredient plus NBT template, not the ingredient alone.
                if (item.inputMatches(stack)) {
                    found += stack.getCount();
                }
            }
            if (found < item.amount()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public ItemStack assemble(Container container, RegistryAccess access) {
        return primaryOutput();
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return true;
    }

    @Override
    public ItemStack getResultItem(RegistryAccess access) {
        return primaryOutput();
    }

    @Override
    public NonNullList<Ingredient> getIngredients() {
        NonNullList<Ingredient> list = NonNullList.create();
        for (MachineRequirement requirement : this.requirements) {
            if (requirement instanceof ItemRequirement item && item.isStartPhase() && item.ingredient() != null) {
                list.add(item.ingredient());
            }
        }
        return list;
    }

    @Override
    public ResourceLocation getId() {
        return this.id;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return ModRecipeSerializers.MACHINE.get();
    }

    @Override
    public RecipeType<?> getType() {
        return ModRecipeTypes.MACHINE.get();
    }

    /** Total FE a craft will draw from energy inputs over its whole duration, before modifiers. */
    public long totalEnergy() {
        long perTick = 0L;
        for (MachineRequirement requirement : this.requirements) {
            if (requirement instanceof EnergyRequirement energy && energy.ioType() == IOType.INPUT) {
                perTick += energy.energyPerTick();
            }
        }
        return perTick * this.recipeTime;
    }

    /** The stack shown as this recipe's result, for JEI and the recipe book. */
    public ItemStack primaryOutput() {
        for (MachineRequirement requirement : this.requirements) {
            if (requirement instanceof ItemRequirement item && item.isFinishPhase()) {
                return item.outputStack();
            }
        }
        return ItemStack.EMPTY;
    }
}
