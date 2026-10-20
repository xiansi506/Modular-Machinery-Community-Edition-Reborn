package mmverify;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.reborn.modularmachinery.recipe.LegacyIngredients;
import com.reborn.modularmachinery.recipe.MachineRequirementParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Ingredient;

/**
 * B2: {@code any:fuel} must be refused by name, not fall through to "unknown item".
 *
 * <p>The original parsed {@code any:fuel} as a fuel input and stored a burn time, but its consumer
 * ({@code RequirementItem.java:342-381}) had no FUEL branch, so a machine that asked for it could never start.
 * This port does not implement the fuel consumer (owner decision: refuse rather than invent). The useful part of
 * the refusal is that it tells an author what is wrong and how to write the recipe instead; an "unknown item"
 * would suggest a typo and invite them to keep trying.
 */
final class FuelRequirementCheck {

    private static final ResourceLocation WHERE = new ResourceLocation("mmverify", "b2_fuel");
    private static final String ITEM = "modular_machinery_reborn:item";
    private static final String ARRAY = "modular_machinery_reborn:ingredient_array_input";

    private FuelRequirementCheck() {
    }

    static void fuelRequirements() {
        section("B2. any:fuel is refused with a reason, not as an unknown item");

        rejected("direct parse, lower case", () -> LegacyIngredients.parse("any:fuel", "a test recipe"));
        rejected("direct parse, upper case", () -> LegacyIngredients.parse("ANY:FUEL", "a test recipe"));
        rejected("item requirement", () -> MachineRequirementParser.parse(itemRequirement("any:fuel"), WHERE));
        rejected("ingredient_array entry", () -> MachineRequirementParser.parse(arrayRequirement("any:fuel"), WHERE));

        // A negative control: the guard must not turn every odd item id into the fuel message.
        try {
            Ingredient stone = LegacyIngredients.parse("minecraft:stone", "a test recipe");
            check("minecraft:stone still parses as an ordinary item", 1, stone.getItems().length);
        } catch (RuntimeException exception) {
            report("minecraft:stone still parses as an ordinary item: " + oneLine(exception), false);
        }
    }

    private static JsonObject itemRequirement(String item) {
        JsonObject json = new JsonObject();
        json.addProperty("type", ITEM);
        json.addProperty("io-type", "input");
        json.addProperty("item", item);
        return json;
    }

    private static JsonObject arrayRequirement(String item) {
        JsonArray items = new JsonArray();
        JsonObject entry = new JsonObject();
        entry.addProperty("item", item);
        items.add(entry);

        JsonObject json = new JsonObject();
        json.addProperty("type", ARRAY);
        json.addProperty("io-type", "input");
        json.add("items", items);
        return json;
    }

    private static void rejected(String what, Runnable parsing) {
        try {
            parsing.run();
            report(what + ": expected a refusal, but it parsed", false);
        } catch (JsonParseException exception) {
            String message = String.valueOf(exception.getMessage());
            boolean namesTheReason = message.contains("not supported")
                    && message.contains("RequirementItem.java:342-381")
                    && message.contains("could never start");
            boolean notUnknownItem = !message.contains("Unknown item");
            report(what + ": expected a named any:fuel refusal; got " + oneLine(exception),
                    namesTheReason && notUnknownItem);
        } catch (RuntimeException exception) {
            report(what + ": expected JsonParseException; got " + oneLine(exception), false);
        }
    }

    private static String oneLine(Throwable throwable) {
        String message = String.valueOf(throwable.getMessage());
        return throwable.getClass().getSimpleName() + ": " + message.replace('\n', ' ').replace('\r', ' ');
    }

    private static void check(String what, long expected, long actual) {
        ParallelCraftCheck.check(what, expected, actual);
    }

    private static void report(String what, boolean ok) {
        ParallelCraftCheck.report(what, ok);
    }

    private static void section(String title) {
        ParallelCraftCheck.section(title);
    }
}
