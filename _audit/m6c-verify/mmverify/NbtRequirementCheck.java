package mmverify;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.reborn.modularmachinery.machine.HatchCollection;
import com.reborn.modularmachinery.recipe.IngredientArrayRequirement;
import com.reborn.modularmachinery.recipe.ItemRequirement;
import com.reborn.modularmachinery.recipe.MachineRequirement;
import com.reborn.modularmachinery.recipe.MachineRequirementParser;
import com.reborn.modularmachinery.recipe.RecipeModifiers;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.ShortTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.energy.EnergyStorage;
import net.minecraftforge.items.ItemStackHandler;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * B1/B5：物品需求的 {@code nbt} 与 {@code ingredient_array_input} 的需求级 {@code nbt}。
 *
 * <p>旧版的匹配不是普通 NBT 相等，而是一套模板语义：compound 是子集、list 是多重集、字符串是整串不区分
 * 大小写的正则、数字必须同类型再按 {@code <}/{@code <=}/{@code ==}/{@code >=}/{@code >} 比较。
 * 这些规则在 {@code NBTMatchingHelper} 与 {@code NBTJsonDeserializer} 里，本波把它们搬进
 * {@code NbtPredicate}；下面每一条都从 <b>JSON 解析入口</b> 走一遍，而不是直接构造节点，否则测到的是
 * 测试自己的理解，不是作者写进配方后的行为。
 *
 * <p><b>为什么用反射。</b>本文件必须在 0.63.5 的源码上也能编译：那一版没有 {@code NbtPredicate}，
 * 也没有 {@code ItemRequirement#nbt()}。直接引用它们会让红跑在编译期失败，而红跑要证明的是「行为缺失」。
 * 所以 {@code nbt()}、{@code matches}、{@code previewTag} 等新入口一律反射调用；缺失时报告失败并说明缺什么。
 */
final class NbtRequirementCheck {

    private static final ResourceLocation WHERE = new ResourceLocation("mmverify", "nbt_requirement");

    private static final String ITEM = "modular_machinery_reborn:item";
    private static final String ARRAY = "modular_machinery_reborn:ingredient_array_input";

    private NbtRequirementCheck() {
    }

    static void nbtRequirements() {
        section("AC. B1/B5: item nbt templates and ingredient_array nbt");

        run("template semantics", NbtRequirementCheck::predicateMatching);
        run("reader refusals", NbtRequirementCheck::parserRejections);
        run("item input IO", NbtRequirementCheck::itemInputIo);
        run("item output tags", NbtRequirementCheck::itemOutputTag);
        run("item wire", NbtRequirementCheck::itemWireRoundTrip);
        run("ingredient array", NbtRequirementCheck::arrayRequirement);
        run("ingredient array wire", NbtRequirementCheck::arrayWireRoundTrip);
    }

    /** Keeps one absent API from skipping the rest of the section: every block reports on its own. */
    private static void run(String label, Runnable body) {
        try {
            body.run();
        } catch (RuntimeException exception) {
            report(label + " — " + exception.getMessage(), false);
        }
    }

    // ================================================================== the template semantics

    private static void predicateMatching() {
        CompoundTag display = new CompoundTag();
        display.putString("Name", "FooBar");
        display.putString("Extra", "ignored");
        CompoundTag root = tagWith("display", display);

        expectMatch("a compound template is a subset (extra item keys are ignored)",
                "{\"display\":{\"Name\":\"FooBar\"}}", root, true);
        expectMatch("... and a missing required key fails",
                "{\"display\":{\"Name\":\"FooBar\",\"Lore\":\"x\"}}", root, false);
        expectMatch("... a wrong value fails",
                "{\"display\":{\"Name\":\"Bar\"}}", root, false);
        expectMatch("a nested compound is matched recursively",
                "{\"display\":{\"Name\":\"Foo.*\"}}", root, true);

        expectMatch("a string is a whole-string regex, case-insensitively",
                "{\"display\":{\"Name\":\"foo.*\"}}", root, true);
        expectMatch("... and a partial match is not a match",
                "{\"display\":{\"Name\":\"Foo\"}}", root, false);
        expectMatch("... a regex only matches a string tag",
                "{\"energy\":\"1.*\"}", tagInt("energy", 1000), false);

        expectMatch("an exact int only matches an IntTag",
                "{\"energy\":\"1000\"}", tagInt("energy", 1000), true);
        expectMatch("... not a LongTag with the same value",
                "{\"energy\":\"1000\"}", tagLong("energy", 1000L), false);
        expectMatch("... not a string tag with the same text",
                "{\"energy\":\"1000\"}", tagString("energy", "1000"), false);
        expectMatch(">= compares the item's value against the written one",
                "{\"energy\":\">=1000\"}", tagInt("energy", 1000), true);
        expectMatch("... below the bound fails",
                "{\"energy\":\">=1000\"}", tagInt("energy", 999), false);
        expectMatch("< is the item's value below the written one",
                "{\"energy\":\"<5\"}", tagInt("energy", 4), true);
        expectMatch("... equal is not below",
                "{\"energy\":\"<5\"}", tagInt("energy", 5), false);

        expectMatch("5b only matches a ByteTag", "{\"v\":\"5b\"}", tagByte("v", (byte) 5), true);
        expectMatch("... not an IntTag", "{\"v\":\"5b\"}", tagInt("v", 5), false);
        expectMatch("5s only matches a ShortTag", "{\"v\":\"5s\"}", tagShort("v", (short) 5), true);
        expectMatch("5l only matches a LongTag", "{\"v\":\"5l\"}", tagLong("v", 5L), true);
        expectMatch("5.0f only matches a FloatTag", "{\"v\":\"5.0f\"}", tagFloat("v", 5.0F), true);
        expectMatch("5.0 only matches a DoubleTag", "{\"v\":\"5.0\"}", tagDouble("v", 5.0D), true);
        expectMatch("a suffix is case-insensitive", "{\"v\":\"5L\"}", tagLong("v", 5L), true);

        expectMatch("true becomes byte 1", "{\"flag\":\"true\"}", tagByte("flag", (byte) 1), true);
        expectMatch("... and false becomes byte 0", "{\"flag\":\"false\"}", tagByte("flag", (byte) 0), true);
        expectMatch("... but true is not int 1", "{\"flag\":\"true\"}", tagInt("flag", 1), false);

        ListTag letters = new ListTag();
        letters.add(StringTag.valueOf("b"));
        letters.add(StringTag.valueOf("a"));
        letters.add(StringTag.valueOf("a"));
        expectMatch("a list is a multiset: order does not matter",
                "{\"letters\":[\"a\",\"b\"]}", tagWith("letters", letters), true);
        expectMatch("... duplicates are counted, so two a's are satisfied",
                "{\"letters\":[\"a\",\"a\",\"b\"]}", tagWith("letters", letters), true);
        expectMatch("... and three a's are not",
                "{\"letters\":[\"a\",\"a\",\"a\"]}", tagWith("letters", letters), false);
        expectMatch("... a missing element fails",
                "{\"letters\":[\"a\",\"b\",\"c\"]}", tagWith("letters", letters), false);
        ListTag numbers = new ListTag();
        numbers.add(IntTag.valueOf(1));
        numbers.add(IntTag.valueOf(2));
        expectMatch("... a list type mismatch fails",
                "{\"letters\":[\"one\",\"two\"]}", tagWith("letters", numbers), false);

        expectMatch("an empty template matches an item with no tag",
                "{}", null, true);
        expectMatch("... and an item with a tag",
                "{}", display, true);
        expectMatch("a non-empty template does not match an item with no tag",
                "{\"display\":{\"Name\":\"FooBar\"}}", null, false);

        expectToTag("a range is written out as the written number",
                "{\"energy\":\">=1000\"}", "energy", IntTag.valueOf(1000));
        expectToTag("a pattern is written out as its source text",
                "{\"name\":\"Foo.*\"}", "name", StringTag.valueOf("Foo.*"));
        expectToTag("true is written as byte 1", "{\"flag\":\"true\"}", "flag", ByteTag.valueOf((byte) 1));
    }

    private static void expectMatch(String label, String nbtJson, CompoundTag tag, boolean expected) {
        ItemRequirement requirement;
        try {
            requirement = (ItemRequirement) parse(itemJson(ITEM, "input", "minecraft:stone", 1, nbtJson));
        } catch (RuntimeException exception) {
            report(label + " — the requirement did not parse: " + exception, false);
            return;
        }
        Object predicate;
        try {
            predicate = nbtOf(requirement);
        } catch (RuntimeException exception) {
            report(label + " — " + exception.getMessage(), false);
            return;
        }
        check(label, String.valueOf(expected), String.valueOf(matches(predicate, tag)));
    }

    private static void expectToTag(String label, String nbtJson, String key, Tag expected) {
        ItemRequirement requirement;
        try {
            requirement = (ItemRequirement) parse(itemJson(ITEM, "input", "minecraft:stone", 1, nbtJson));
        } catch (RuntimeException exception) {
            report(label + " — the requirement did not parse: " + exception, false);
            return;
        }
        try {
            CompoundTag tag = toTag(nbtOf(requirement));
            check(label, String.valueOf(expected), String.valueOf(tag.get(key)));
        } catch (RuntimeException exception) {
            report(label + " — " + exception.getMessage(), false);
        }
    }

    // ================================================================== what the reader refuses

    private static void parserRejections() {
        JsonObject bareNumber = itemJson(ITEM, "input", "minecraft:stone", 1, null);
        JsonObject bareNbt = new JsonObject();
        bareNbt.addProperty("energy", 1000);
        bareNumber.add("nbt", bareNbt);
        expectReject("a bare JSON number is refused (the original read every scalar as a quoted string)",
                bareNumber, "quoted string");

        JsonObject emptyScalar = itemJson(ITEM, "input", "minecraft:stone", 1, null);
        JsonObject emptyScalarNbt = new JsonObject();
        emptyScalarNbt.addProperty("name", "");
        emptyScalar.add("nbt", emptyScalarNbt);
        expectReject("an empty quoted scalar is refused (the original's parser: Expected value)",
                emptyScalar, "Expected value");

        JsonObject emptyList = itemJson(ITEM, "input", "minecraft:stone", 1, null);
        JsonObject emptyNbt = new JsonObject();
        emptyNbt.add("letters", new JsonArray());
        emptyList.add("nbt", emptyNbt);
        expectReject("an empty list is refused (the original's parser: Expected value)", emptyList, "empty");

        JsonObject mixed = itemJson(ITEM, "input", "minecraft:stone", 1, null);
        JsonObject mixedNbt = new JsonObject();
        JsonArray mixedList = new JsonArray();
        mixedList.add("a");
        mixedList.add("1");
        mixedNbt.add("letters", mixedList);
        mixed.add("nbt", mixedNbt);
        expectReject("a list mixing a string and an int is refused", mixed, "mixes");

        JsonObject badRegex = itemJson(ITEM, "input", "minecraft:stone", 1, null);
        JsonObject regexNbt = new JsonObject();
        regexNbt.addProperty("name", "[");
        badRegex.add("nbt", regexNbt);
        expectReject("an invalid regular expression is refused by name", badRegex, "regular expression");

        JsonObject nonObject = itemJson(ITEM, "input", "minecraft:stone", 1, null);
        nonObject.addProperty("nbt", "{}");
        expectReject("nbt must be a JSON compound, not a string", nonObject, "expects a json compound");

        JsonObject badDisplay = itemJson(ITEM, "input", "minecraft:stone", 1,
                "{\"display\":{\"Name\":\"Foo\"}}");
        badDisplay.addProperty("nbt-display", "{}");
        expectReject("nbt-display must also be a JSON compound", badDisplay,
                "expects a json compound");

        JsonObject loneDisplay = itemJson(ITEM, "input", "minecraft:stone", 1, null);
        loneDisplay.add("nbt-display", JsonParser.parseString("{\"display\":{\"Name\":\"Shown\"}}"));
        try {
            ItemRequirement parsed = (ItemRequirement) parse(loneDisplay);
            check("a lone nbt-display is ignored, as in the original (it was read only inside has('nbt'))",
                    "null", String.valueOf(previewTag(parsed)));
        } catch (RuntimeException exception) {
            report("a lone nbt-display should parse and be ignored: " + exception, false);
        }
    }

    private static void expectReject(String label, JsonObject json, String fragment) {
        try {
            parse(json);
            report(label + " — it was accepted instead", false);
        } catch (RuntimeException exception) {
            String message = String.valueOf(exception.getMessage());
            check(label + " [" + message + "]", "true", String.valueOf(message.contains(fragment)));
        }
    }

    // ================================================================== input filtering

    private static void itemInputIo() {
        CompoundTag wanted = tagInt("energy", 1000);
        ItemStack plain = new ItemStack(Items.STONE, 2);
        ItemStack tagged = new ItemStack(Items.STONE, 3);
        tagged.setTag(wanted.copy());

        ItemStackHandler inputs = new ItemStackHandler(4);
        inputs.setStackInSlot(0, plain.copy());
        inputs.setStackInSlot(1, tagged.copy());
        HatchCollection ports = ports(inputs, new ItemStackHandler(4));

        ItemRequirement requirement;
        try {
            requirement = (ItemRequirement) parse(itemJson(ITEM, "input", "minecraft:stone", 2,
                    "{\"energy\":\">=1000\"}"));
        } catch (RuntimeException exception) {
            report("an item input with nbt did not parse: " + exception, false);
            return;
        }

        check("inputMatches accepts the tagged stack", "true",
                String.valueOf(inputMatches(requirement, tagged)));
        check("inputMatches rejects the same item without the tag", "false",
                String.valueOf(inputMatches(requirement, plain)));
        check("the parallel limit counts only tagged stacks (3 / 2 = 1, not 5 / 2 = 2)", 1L,
                (long) requirement.parallelLimit(ports, RecipeModifiers.EMPTY, 8));

        requirement.setParallelism(1);
        check("one copy is satisfiable", "true", String.valueOf(requirement.canSatisfy(ports)));
        requirement.setParallelism(2);
        check("two copies are not satisfiable from the tagged stack alone", "false",
                String.valueOf(requirement.canSatisfy(ports)));
        requirement.setParallelism(1);
        check("apply consumes the tagged stack", "true",
                String.valueOf(requirement.apply(ports, RandomSource.create(7L))));
        check("... the plain stack is untouched", 2L, (long) inputs.getStackInSlot(0).getCount());
        check("... and one tagged item is left", 1L, (long) inputs.getStackInSlot(1).getCount());
        check("... the leftover is still the tagged one", "true",
                String.valueOf(inputs.getStackInSlot(1).getTag() != null));
    }

    // ================================================================== output tags and previews

    private static void itemOutputTag() {
        JsonObject outputJson = itemJson(ITEM, "output", "minecraft:diamond", 1,
                "{\"display\":{\"Name\":\"Produced\"}}");
        ItemRequirement output;
        try {
            output = (ItemRequirement) parse(outputJson);
        } catch (RuntimeException exception) {
            report("an item output with nbt did not parse: " + exception, false);
            return;
        }
        ItemStack produced = output.outputStack();
        check("the output stack carries the parsed tag", "Produced",
                tagString(produced.getTag(), "display", "Name"));
        ItemStackHandler outputs = new ItemStackHandler(2);
        HatchCollection ports = ports(new ItemStackHandler(2), outputs);
        check("the tagged output fits", "true", String.valueOf(output.canSatisfy(ports)));
        check("... and is inserted", "true",
                String.valueOf(output.apply(ports, RandomSource.create(3L))));
        check("the inserted stack keeps the tag", "Produced",
                tagString(outputs.getStackInSlot(0).getTag(), "display", "Name"));

        JsonObject displayJson = itemJson(ITEM, "output", "minecraft:diamond", 1,
                "{\"display\":{\"Name\":\"Produced\"}}");
        displayJson.add("nbt-display", JsonParser.parseString("{\"display\":{\"Name\":\"Shown\"}}"));
        ItemRequirement withDisplay = (ItemRequirement) parse(displayJson);
        check("nbt-display does not change the produced tag", "Produced",
                tagString(withDisplay.outputStack().getTag(), "display", "Name"));
        check("... it only overrides the JEI preview", "Shown",
                tagString(previewOutputStack(withDisplay).getTag(), "display", "Name"));
    }

    // ================================================================== the network

    private static void itemWireRoundTrip() {
        ItemRequirement input;
        try {
            input = (ItemRequirement) parse(itemJson(ITEM, "input", "minecraft:stone", 2,
                    "{\"energy\":\">=1000\"}"));
        } catch (RuntimeException exception) {
            report("the wire test could not parse its input requirement: " + exception, false);
            return;
        }
        MachineRequirement back = readBack(write(input));
        try {
            check("the input's nbt survives the wire", source(nbtOf(input)), source(nbtOf(back)));
            check("... and still matches the same tag", "true",
                    String.valueOf(inputMatches((ItemRequirement) back, taggedStone())));
            check("... and still rejects a plain stack", "false",
                    String.valueOf(inputMatches((ItemRequirement) back, new ItemStack(Items.STONE))));
        } catch (RuntimeException exception) {
            report("the input's nbt did not survive the wire: " + exception.getMessage(), false);
        }

        JsonObject outputJson = itemJson(ITEM, "output", "minecraft:diamond", 1,
                "{\"display\":{\"Name\":\"Produced\"}}");
        outputJson.add("nbt-display", JsonParser.parseString("{\"display\":{\"Name\":\"Shown\"}}"));
        MachineRequirement outputBack = readBack(write((MachineRequirement) parse(outputJson)));
        check("the output's tag survives the wire", "Produced",
                tagString(((ItemRequirement) outputBack).outputStack().getTag(), "display", "Name"));
        check("... and so does its nbt-display preview", "Shown",
                tagString(previewOutputStack((ItemRequirement) outputBack).getTag(), "display", "Name"));
    }

    // ================================================================== ingredient_array

    private static void arrayRequirement() {
        CompoundTag wanted = tagInt("energy", 1000);
        ItemStack plain = new ItemStack(Items.STONE, 3);
        ItemStack tagged = new ItemStack(Items.STONE, 2);
        tagged.setTag(wanted.copy());

        ItemStackHandler inputs = new ItemStackHandler(4);
        inputs.setStackInSlot(0, plain.copy());
        inputs.setStackInSlot(1, tagged.copy());
        HatchCollection ports = ports(inputs, new ItemStackHandler(4));

        IngredientArrayRequirement requirement;
        try {
            requirement = (IngredientArrayRequirement) parse(arrayJson(
                    "{\"energy\":\">=1000\"}", "{\"item\":\"minecraft:stone\"}"));
        } catch (RuntimeException exception) {
            report("an ingredient_array_input with nbt did not parse: " + exception, false);
            return;
        }

        check("entryAvailableIn counts only the tagged stacks", 2L, (long) requirement.entryAvailableIn(0, ports));
        check("the parallel limit uses the tagged count (2, not 5)", 2L,
                (long) requirement.parallelLimit(ports, RecipeModifiers.EMPTY, 8));
        CompoundTag preview = previewTag(requirement);
        check("the requirement exposes the array-level template", "1000",
                String.valueOf(preview == null ? null : preview.getInt("energy")));

        requirement.setParallelism(2);
        check("two copies are satisfiable from the tagged stacks", "true",
                String.valueOf(requirement.canSatisfy(ports)));
        check("apply consumes them", "true",
                String.valueOf(requirement.apply(ports, RandomSource.create(11L))));
        check("... the plain stacks are untouched", 3L, (long) inputs.getStackInSlot(0).getCount());
        check("... and the tagged stack is empty", "true",
                String.valueOf(inputs.getStackInSlot(1).isEmpty()));

        JsonObject entryLevel = arrayJson(null, "{\"item\":\"minecraft:stone\",\"nbt\":{\"energy\":\"1\"}}");
        expectReject("nbt inside an entry is still refused, with the 'next to items' wording",
                entryLevel, "belongs next to 'items'");

        JsonObject nonObject = arrayJson(null, "{\"item\":\"minecraft:stone\"}");
        nonObject.addProperty("nbt", "{}");
        expectReject("an ingredient_array nbt must be a JSON compound", nonObject, "expects a json compound");
    }

    private static void arrayWireRoundTrip() {
        IngredientArrayRequirement requirement;
        try {
            requirement = (IngredientArrayRequirement) parse(arrayJson(
                    "{\"energy\":\">=1000\"}", "{\"item\":\"minecraft:stone\"}"));
        } catch (RuntimeException exception) {
            report("the array wire test could not parse its requirement: " + exception, false);
            return;
        }
        MachineRequirement back = readBack(write(requirement));
        try {
            check("the array's nbt survives the wire", source(nbtOf(requirement)),
                    source(nbtOf(back)));
            ItemStack tagged = taggedStone();
            ItemStack plain = new ItemStack(Items.STONE);
            ItemStackHandler inputs = new ItemStackHandler(2);
            inputs.setStackInSlot(0, plain);
            inputs.setStackInSlot(1, tagged);
            HatchCollection ports = ports(inputs, new ItemStackHandler(2));
            check("... and the read-back requirement still filters by it", 1L,
                    (long) ((IngredientArrayRequirement) back).entryAvailableIn(0, ports));
        } catch (RuntimeException exception) {
            report("the array's nbt did not survive the wire: " + exception.getMessage(), false);
        }
    }

    // ================================================================== JSON and tags

    private static JsonObject itemJson(String type, String ioType, String item, int amount, String nbtJson) {
        JsonObject json = new JsonObject();
        json.addProperty("type", type);
        json.addProperty("io-type", ioType);
        json.addProperty("item", item);
        json.addProperty("amount", amount);
        if (nbtJson != null) {
            json.add("nbt", JsonParser.parseString(nbtJson));
        }
        return json;
    }

    private static JsonObject arrayJson(String nbtJson, String entryJson) {
        JsonObject json = new JsonObject();
        json.addProperty("type", ARRAY);
        json.addProperty("io-type", "input");
        json.addProperty("amount", 1);
        JsonArray items = new JsonArray();
        items.add(JsonParser.parseString(entryJson));
        json.add("items", items);
        if (nbtJson != null) {
            json.add("nbt", JsonParser.parseString(nbtJson));
        }
        return json;
    }

    private static MachineRequirement parse(JsonObject json) {
        return MachineRequirementParser.parse(json, WHERE);
    }

    private static byte[] write(MachineRequirement requirement) {
        io.netty.buffer.UnpooledByteBufAllocator allocator = io.netty.buffer.UnpooledByteBufAllocator.DEFAULT;
        io.netty.buffer.ByteBuf buffer = allocator.buffer();
        net.minecraft.network.FriendlyByteBuf buf = new net.minecraft.network.FriendlyByteBuf(buffer);
        MachineRequirementParser.write(buf, requirement);
        byte[] out = new byte[buf.readableBytes()];
        buf.getBytes(buf.readerIndex(), out);
        return out;
    }

    private static MachineRequirement readBack(byte[] bytes) {
        net.minecraft.network.FriendlyByteBuf buf = new net.minecraft.network.FriendlyByteBuf(
                io.netty.buffer.Unpooled.wrappedBuffer(bytes));
        return MachineRequirementParser.read(buf);
    }

    private static HatchCollection ports(ItemStackHandler inputs, ItemStackHandler outputs) {
        EnergyStorage energy = new EnergyStorage(1_000_000, Integer.MAX_VALUE, Integer.MAX_VALUE, 0);
        energy.receiveEnergy(1_000_000, false);
        return HatchCollection.builder()
                .addItemHandler(inputs, true)
                .addItemHandler(outputs, false)
                .addEnergyStorage(energy, true)
                .build();
    }

    private static CompoundTag tagInt(String key, int value) {
        CompoundTag tag = new CompoundTag();
        tag.putInt(key, value);
        return tag;
    }

    private static CompoundTag tagLong(String key, long value) {
        CompoundTag tag = new CompoundTag();
        tag.putLong(key, value);
        return tag;
    }

    private static CompoundTag tagShort(String key, short value) {
        CompoundTag tag = new CompoundTag();
        tag.putShort(key, value);
        return tag;
    }

    private static CompoundTag tagByte(String key, byte value) {
        CompoundTag tag = new CompoundTag();
        tag.putByte(key, value);
        return tag;
    }

    private static CompoundTag tagFloat(String key, float value) {
        CompoundTag tag = new CompoundTag();
        tag.putFloat(key, value);
        return tag;
    }

    private static CompoundTag tagDouble(String key, double value) {
        CompoundTag tag = new CompoundTag();
        tag.putDouble(key, value);
        return tag;
    }

    private static CompoundTag tagString(String key, String value) {
        CompoundTag tag = new CompoundTag();
        tag.putString(key, value);
        return tag;
    }

    private static CompoundTag tagWith(String key, Tag value) {
        CompoundTag tag = new CompoundTag();
        tag.put(key, value);
        return tag;
    }

    private static ItemStack taggedStone() {
        ItemStack stack = new ItemStack(Items.STONE);
        stack.setTag(tagInt("energy", 1000));
        return stack;
    }

    private static String tagString(CompoundTag root, String compoundKey, String key) {
        if (root == null) {
            return "null";
        }
        CompoundTag nested = root.getCompound(compoundKey);
        return nested.contains(key) ? nested.getString(key) : "null";
    }

    // ================================================================== reflection to the new API

    private static Object nbtOf(Object requirement) {
        return call(requirement, "nbt", new Class<?>[0]);
    }

    private static boolean matches(Object predicate, CompoundTag tag) {
        return (Boolean) call(predicate, "matches", new Class<?>[]{CompoundTag.class}, tag);
    }

    private static CompoundTag toTag(Object predicate) {
        return (CompoundTag) call(predicate, "toTag", new Class<?>[0]);
    }

    private static String source(Object predicate) {
        return (String) call(predicate, "source", new Class<?>[0]);
    }

    private static CompoundTag previewTag(Object requirement) {
        return (CompoundTag) call(requirement, "previewTag", new Class<?>[0]);
    }

    private static ItemStack previewOutputStack(ItemRequirement requirement) {
        return (ItemStack) call(requirement, "previewOutputStack", new Class<?>[0]);
    }

    private static boolean inputMatches(ItemRequirement requirement, ItemStack stack) {
        return (Boolean) call(requirement, "inputMatches", new Class<?>[]{ItemStack.class}, stack);
    }

    private static Object call(Object target, String name, Class<?>[] parameters, Object... args) {
        try {
            Method method = target.getClass().getMethod(name, parameters);
            return method.invoke(target, args);
        } catch (NoSuchMethodException exception) {
            throw new IllegalStateException(target.getClass().getSimpleName() + " has no " + name
                    + "(); the B1/B5 API is absent from this build", exception);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(name + "() failed: " + cause, cause);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(name + "() is not callable: " + exception, exception);
        }
    }

    // ================================================================== the harness's four forwards

    private static void section(String title) {
        ParallelCraftCheck.section(title);
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
}
