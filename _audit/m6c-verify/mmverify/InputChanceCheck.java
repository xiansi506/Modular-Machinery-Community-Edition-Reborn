package mmverify;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.reborn.modularmachinery.machine.HatchCollection;
import com.reborn.modularmachinery.recipe.FluidRequirement;
import com.reborn.modularmachinery.recipe.ItemRequirement;
import com.reborn.modularmachinery.recipe.MachineRecipe;
import com.reborn.modularmachinery.recipe.MachineRequirement;
import com.reborn.modularmachinery.recipe.MachineRequirementParser;
import com.reborn.modularmachinery.recipe.RecipeModifiers;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.templates.FluidTank;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.items.ItemStackHandler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * B6：输入侧的 chance。
 *
 * <p>旧版 {@code RequirementItem#startCrafting} / {@code RequirementFluid#startCrafting} 在 INPUT 方向先
 * {@code chance.canWork(...)} 再移动资源；没命中就<strong>不扣料，但配方照跑</strong>。开工检查
 * {@code canStartCrafting} 用的是 {@code ResultChance.GUARANTEED}，所以概率从不阻止开工。
 *
 * <p>本版此前把输入 chance 丢了：JSON 解析不读、网络不传、{@code modifiedChance} 对输入恒返回 1。这里钉住
 * 修复后的四层：解析、网络、概率门控、描述文字。
 */
final class InputChanceCheck {

    private static final ResourceLocation RECIPE = new ResourceLocation("mmverify", "input_chance_check");

    private InputChanceCheck() {
    }

    static void inputChances() {
        section("B6 输入侧 chance（0.74.0）");
        run("item parsing", InputChanceCheck::itemParsing);
        run("item network", InputChanceCheck::itemNetwork);
        run("item consumption", InputChanceCheck::itemConsumption);
        run("item parallelism and output", InputChanceCheck::itemParallelismAndOutput);
        run("fluid parsing and network", InputChanceCheck::fluidParsingAndNetwork);
        run("fluid consumption", InputChanceCheck::fluidConsumption);
        run("gas source", InputChanceCheck::gasSource);
    }

    private static void itemParsing() {
        ItemRequirement input = (ItemRequirement) parse("""
                {"type": "modular_machinery_reborn:item", "io-type": "input",
                 "item": "minecraft:stone", "amount": 1, "chance": 0.25}
                """);
        check("an item input keeps the chance from the file", "0.25", Float.toString(input.chance()));
        check("...and its modified chance is the same without modifiers", "0.25",
                Float.toString(input.modifiedChance(RecipeModifiers.EMPTY)));
        report("...and its description carries the chance", input.describe().contains("(25%)"));

        ItemRequirement output = (ItemRequirement) parse("""
                {"type": "modular_machinery_reborn:item", "io-type": "output",
                 "item": "minecraft:stone", "amount": 1, "chance": 0.25}
                """);
        check("an item output still keeps its chance", "0.25", Float.toString(output.chance()));
        check("...and still describes it", "0.25", Float.toString(output.modifiedChance(RecipeModifiers.EMPTY)));
    }

    private static void itemNetwork() {
        ItemRequirement original = (ItemRequirement) parse("""
                {"type": "modular_machinery_reborn:item", "io-type": "input",
                 "item": "minecraft:stone", "amount": 2, "chance": 0.25}
                """);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        MachineRequirementParser.write(buffer, original);
        MachineRequirement back = MachineRequirementParser.read(buffer);
        check("the wire carries an item input's chance", "0.25",
                Float.toString(((ItemRequirement) back).chance()));
        check("...and the buffer is fully consumed", 0L, (long) buffer.readableBytes());
    }

    private static void itemConsumption() {
        ItemStackHandler input = new ItemStackHandler(1);
        input.setStackInSlot(0, new ItemStack(Items.STONE, 8));
        HatchCollection ports = HatchCollection.builder().addItemHandler(input, true).build();

        MachineRecipe never = recipe(req(ITEM_INPUT_ZERO));
        report("a chance-0 item input still allows the craft to start", never.canStart(ports));
        report("...and starting consumes nothing",
                never.start(ports, RandomSource.create(1L)) && input.getStackInSlot(0).getCount() == 8);

        input.setStackInSlot(0, new ItemStack(Items.STONE, 8));
        MachineRecipe always = recipe(req(ITEM_INPUT_ONE));
        report("a chance-1 item input consumes its amount",
                always.start(ports, RandomSource.create(2L)) && input.getStackInSlot(0).getCount() == 5);

        MachineRecipe half = recipe(req(ITEM_INPUT_HALF));
        input.setStackInSlot(0, new ItemStack(Items.STONE, 8));
        report("a chance-0.5 item input consumes when the roll hits",
                half.start(ports, RandomSource.create(seedFor(0.5F, true)))
                        && input.getStackInSlot(0).getCount() == 5);
        input.setStackInSlot(0, new ItemStack(Items.STONE, 8));
        report("...and leaves the items when the roll misses",
                half.start(ports, RandomSource.create(seedFor(0.5F, false)))
                        && input.getStackInSlot(0).getCount() == 8);
    }

    private static void itemParallelismAndOutput() {
        ItemStackHandler input = new ItemStackHandler(1);
        input.setStackInSlot(0, new ItemStack(Items.STONE, 8));
        ItemStackHandler output = new ItemStackHandler(1);
        HatchCollection ports = HatchCollection.builder()
                .addItemHandler(input, true)
                .addItemHandler(output, false)
                .build();

        MachineRecipe parallel = recipe(req("""
                {"type": "modular_machinery_reborn:item", "io-type": "input",
                 "item": "minecraft:stone", "amount": 1, "chance": 0}
                """));
        parallel.setParallelism(4);
        report("a chance-0 input at parallelism 4 still starts", parallel.canStart(ports));
        report("...and the single roll leaves all four copies",
                parallel.start(ports, RandomSource.create(3L)) && input.getStackInSlot(0).getCount() == 8);

        MachineRecipe hit = recipe(req("""
                {"type": "modular_machinery_reborn:item", "io-type": "input",
                 "item": "minecraft:stone", "amount": 1, "chance": 0.5}
                """));
        hit.setParallelism(4);
        input.setStackInSlot(0, new ItemStack(Items.STONE, 8));
        report("a hit still consumes all four copies in one roll",
                hit.start(ports, RandomSource.create(seedFor(0.5F, true)))
                        && input.getStackInSlot(0).getCount() == 4);

        MachineRecipe both = recipe(req(ITEM_INPUT_ZERO), req("""
                {"type": "modular_machinery_reborn:item", "io-type": "output",
                 "item": "minecraft:diamond", "amount": 1}
                """));
        input.setStackInSlot(0, new ItemStack(Items.STONE, 3));
        report("a missed input does not block the craft", both.canStart(ports));
        report("...the input survives start", both.start(ports, RandomSource.create(4L))
                && input.getStackInSlot(0).getCount() == 3);
        report("...and the output is still produced", both.finish(ports, RandomSource.create(5L))
                && output.getStackInSlot(0).getCount() == 1);
    }

    private static void fluidParsingAndNetwork() {
        FluidRequirement input = (FluidRequirement) parse("""
                {"type": "modular_machinery_reborn:fluid", "io-type": "input",
                 "fluid": "minecraft:water", "amount": 100, "chance": 0.25}
                """);
        check("a fluid input keeps the chance from the file", "0.25", Float.toString(input.chance()));
        check("...and its modified chance is the same without modifiers", "0.25",
                Float.toString(input.modifiedChance(RecipeModifiers.EMPTY)));
    }

    private static void fluidConsumption() {
        FluidTank tank = new FluidTank(1000);
        tank.fill(new FluidStack(Fluids.WATER, 1000), IFluidHandler.FluidAction.EXECUTE);
        HatchCollection ports = HatchCollection.builder().addFluidHandler(tank, true).build();

        MachineRecipe never = recipe(fluidReq(0.0F, 200));
        report("a chance-0 fluid input still allows the craft to start", never.canStart(ports));
        report("...and starting drains nothing",
                never.start(ports, RandomSource.create(6L)) && tank.getFluidAmount() == 1000);

        MachineRecipe always = recipe(fluidReq(1.0F, 200));
        report("a chance-1 fluid input drains its amount",
                always.start(ports, RandomSource.create(7L)) && tank.getFluidAmount() == 800);

        MachineRecipe half = recipe(fluidReq(0.5F, 200));
        tank.drain(1000, IFluidHandler.FluidAction.EXECUTE);
        tank.fill(new FluidStack(Fluids.WATER, 1000), IFluidHandler.FluidAction.EXECUTE);
        report("a chance-0.5 fluid input drains when the roll hits",
                half.start(ports, RandomSource.create(seedFor(0.5F, true))) && tank.getFluidAmount() == 800);
        tank.drain(1000, IFluidHandler.FluidAction.EXECUTE);
        tank.fill(new FluidStack(Fluids.WATER, 1000), IFluidHandler.FluidAction.EXECUTE);
        report("...and leaves the tank full when the roll misses",
                half.start(ports, RandomSource.create(seedFor(0.5F, false))) && tank.getFluidAmount() == 1000);

        MachineRecipe parallel = recipe(fluidReq(0.0F, 200));
        parallel.setParallelism(3);
        report("a chance-0 fluid input at parallelism 3 drains nothing",
                parallel.start(ports, RandomSource.create(8L)) && tank.getFluidAmount() == 1000);
    }

    private static void gasSource() {
        try {
            String gas = source("compat/mekanism/GasRequirement.java");
            report("the gas input uses its chance instead of returning 1 for inputs",
                    !gas.contains("if (this.ioType() != IOType.OUTPUT) {")
                            && gas.contains("float decidedChance = modifiedChance(modifiers);"));
            report("...and rolls before extracting",
                    gas.indexOf("modifiedChance(modifiers)") < gas.indexOf("GasIo.extract(ports.gasInputs()"));
        } catch (Throwable broken) {
            report("the gas requirement source cannot be read: " + broken, false);
        }
    }

    // ------------------------------------------------------------------ fixtures

    private static final String ITEM_INPUT_ZERO = """
            {"type": "modular_machinery_reborn:item", "io-type": "input",
             "item": "minecraft:stone", "amount": 3, "chance": 0}
            """;
    private static final String ITEM_INPUT_ONE = """
            {"type": "modular_machinery_reborn:item", "io-type": "input",
             "item": "minecraft:stone", "amount": 3, "chance": 1}
            """;
    private static final String ITEM_INPUT_HALF = """
            {"type": "modular_machinery_reborn:item", "io-type": "input",
             "item": "minecraft:stone", "amount": 3, "chance": 0.5}
            """;

    private static MachineRequirement req(String json) {
        return parse(json);
    }

    private static MachineRequirement fluidReq(float chance, int amount) {
        return parse("""
                {"type": "modular_machinery_reborn:fluid", "io-type": "input",
                 "fluid": "minecraft:water", "amount": %d, "chance": %s}
                """.formatted(amount, Float.toString(chance)));
    }

    private static MachineRequirement parse(String json) {
        JsonObject object = JsonParser.parseString(json).getAsJsonObject();
        return MachineRequirementParser.parse(object, RECIPE);
    }

    private static MachineRecipe recipe(MachineRequirement... requirements) {
        return new MachineRecipe(RECIPE, "input_chance_machine", "input_chance_check", 100,
                List.of(requirements));
    }

    /**
     * The first seed whose first {@code nextFloat()} is on the requested side of {@code chance}. Using a real
     * {@link RandomSource} keeps the test honest: it exercises the same roll the recipe uses, not a stub.
     */
    private static long seedFor(float chance, boolean hit) {
        for (long seed = 0L; seed < 10_000L; seed++) {
            float roll = RandomSource.create(seed).nextFloat();
            if ((roll < chance) == hit) {
                return seed;
            }
        }
        throw new IllegalStateException("no RandomSource seed found for chance " + chance);
    }

    private static void check(String what, long expected, long actual) {
        ParallelCraftCheck.check(what, expected, actual);
    }

    private static void check(String what, String expected, String actual) {
        ParallelCraftCheck.check(what, expected, actual);
    }

    private static void report(String line, boolean ok) {
        ParallelCraftCheck.report(line, ok);
    }

    private static void section(String title) {
        ParallelCraftCheck.section(title);
    }

    private static void run(String label, Runnable body) {
        try {
            body.run();
        } catch (Throwable broken) {
            report(label + " crashed: " + broken, false);
        }
    }

    private static String source(String relative) throws Exception {
        return Files.readString(Path.of("C:/mmwork/modular-machinery-reborn/src/main/java/com/reborn/modularmachinery/"
                + relative));
    }
}
