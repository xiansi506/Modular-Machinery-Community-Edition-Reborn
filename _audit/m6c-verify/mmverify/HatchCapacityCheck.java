package mmverify;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.reborn.modularmachinery.block.HatchTier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * A4（勘测 §4 第 117 行）：仓口容量与能量传输可配置。
 *
 * <p>旧版把这三组数字放在配置里：{@code FluidHatchSize#loadFromConfig}（{@code :43-47}）读
 * {@code fluidhatch.size.<TIER>}，{@code EnergyHatchData#loadFromConfig}（{@code :67-85}）读
 * {@code energyhatch.size.<TIER>} 与 {@code energyhatch.limit.<TIER>}。本版此前把数字写死在
 * {@code HatchTier}（{@code :25-33}），{@code ModConfig} 里没有任何相关键。
 *
 * <p>这一节钉四件事：
 * <ol>
 *   <li>spec 自己渲染出来的键路径与默认值——路径不是从源码抄的，源码只能证明「想写什么」；</li>
 *   <li>配置未读时三个访问器退回编译期默认值；</li>
 *   <li>把值写进 spec 后访问器读的是配置，且 {@code 2^31} 与旧版的 {@code Long.MAX_VALUE} 会被判定为
 *       越界并修正，而不是静默截断；</li>
 *   <li>方块实体与提示行真的走访问器，而不是又读回 {@code HatchTier}。</li>
 * </ol>
 *
 * <p>为什么能量上限定在 {@code Integer.MAX_VALUE}：本版用 Forge 的 {@code EnergyStorage}，容量与传输
 * 都是 {@code int}；旧版能存到 {@code long} 是 {@code TileEnergyHatch} 的事，不是这组键能假装支持的。
 * 这是 {@code docs/剩余项清点.md} 的 F7，不是本节的缺陷。
 *
 * <p>物品槽位**不**可配：旧版 {@code ItemBusSize.java:32-36} 只有 {@code //TODO configurable}，没有读取
 * 配置的代码。本节用阴性对照钉住「没有 itembus 键」，免得以后把 TODO 当成已实现。
 */
final class HatchCapacityCheck {

    private static final Path SOURCE = Path.of("src/main/java/com/reborn/modularmachinery");

    private HatchCapacityCheck() {
    }

    static void hatchCapacities() {
        section("A4 仓口容量与能量传输可配置（0.64.2）");
        definedKeys();
        accessorFallback();
        configuredValues();
        wiring();
    }

    // ---------------------------------------------------------------- the keys Forge actually defines

    private static void definedKeys() {
        Map<String, String> rendered = ParallelCraftCheck.renderConfigSpec();
        int fluid = 0;
        int energySize = 0;
        int energyLimit = 0;
        for (HatchTier tier : HatchTier.FLUID_LADDER) {
            String key = "fluidhatch.size." + tier.name();
            if (rendered.containsKey(key)) {
                fluid++;
            }
            check("fluidhatch.size." + tier.name() + " defaults to the original's " + tier.fluidCapacity(),
                    String.valueOf(tier.fluidCapacity()), rendered.get(key));
        }
        for (HatchTier tier : HatchTier.ENERGY_LADDER) {
            String sizeKey = "energyhatch.size." + tier.name();
            String limitKey = "energyhatch.limit." + tier.name();
            if (rendered.containsKey(sizeKey)) {
                energySize++;
            }
            if (rendered.containsKey(limitKey)) {
                energyLimit++;
            }
            check("energyhatch.size." + tier.name() + " defaults to the original's " + tier.energyCapacity(),
                    String.valueOf((int) tier.energyCapacity()), rendered.get(sizeKey));
            check("energyhatch.limit." + tier.name() + " defaults to the original's " + tier.energyTransfer(),
                    String.valueOf((int) tier.energyTransfer()), rendered.get(limitKey));
        }
        check("the fluid ladder defines one key per tier", 8L, fluid);
        check("the energy ladder defines one size key per tier", 8L, energySize);
        check("the energy ladder defines one limit key per tier", 8L, energyLimit);

        // Negative controls: these categories must not exist, or the TODO would have been read as a feature.
        long itemKeys = rendered.keySet().stream().filter(key -> key.startsWith("itembus.")).count();
        long gasKeys = rendered.keySet().stream()
                .filter(key -> key.toLowerCase(java.util.Locale.ROOT).contains("gas")).count();
        check("there is no itembus key: item slots stayed compiled constants", 0L, itemKeys);
        check("there is no gas key: gas shares the fluid tank, as in the original", 0L, gasKeys);
        report("the hatch keys are ints within 1 .. Integer.MAX_VALUE",
                rendered.keySet().stream().filter(key -> key.startsWith("fluidhatch.size.")
                                || key.startsWith("energyhatch.size.") || key.startsWith("energyhatch.limit."))
                        .allMatch(key -> {
                            try {
                                int value = Integer.parseInt(rendered.get(key));
                                return value >= 1;
                            } catch (NumberFormatException notAnInt) {
                                return false;
                            }
                        }));
    }

    // ---------------------------------------------------------------- before the file is read

    private static void accessorFallback() {
        Object spec = spec();
        if (spec == null) {
            report("HatchConfig is absent, so the accessors cannot be asked", false);
            return;
        }
        try {
            spec.getClass().getMethod("setConfig", CommentedConfig.class).invoke(spec, (Object) null);
            report("the spec is unloaded before the fallback is measured",
                    !(Boolean) spec.getClass().getMethod("isLoaded").invoke(spec));
            for (HatchTier tier : HatchTier.FLUID_LADDER) {
                check("unloaded: fluid " + tier.name() + " falls back to " + tier.fluidCapacity(),
                        (long) tier.fluidCapacity(), hatchValue("fluidHatchCapacity", tier));
            }
            for (HatchTier tier : HatchTier.ENERGY_LADDER) {
                check("unloaded: energy " + tier.name() + " capacity falls back to " + tier.energyCapacity(),
                        tier.energyCapacity(), hatchValue("energyHatchCapacity", tier));
                check("unloaded: energy " + tier.name() + " transfer falls back to " + tier.energyTransfer(),
                        tier.energyTransfer(), hatchValue("energyHatchTransfer", tier));
            }
        } catch (Throwable broken) {
            report("the unloaded fallback could not be measured: " + describe(broken), false);
        } finally {
            restore(spec);
        }
    }

    // ---------------------------------------------------------------- values from the file

    private static void configuredValues() {
        Object spec = spec();
        if (spec == null) {
            report("HatchConfig is absent, so configured values cannot be measured", false);
            return;
        }
        try {
            CommentedConfig config = CommentedConfig.inMemory();
            spec.getClass().getMethod("correct", CommentedConfig.class).invoke(spec, config);
            spec.getClass().getMethod("setConfig", CommentedConfig.class).invoke(spec, config);
            report("the generated config is correct before any override",
                    (Boolean) spec.getClass().getMethod("isCorrect", CommentedConfig.class).invoke(spec, config));
            report("the spec reports itself loaded after setConfig",
                    (Boolean) spec.getClass().getMethod("isLoaded").invoke(spec));

            // A value no tier has, so a passing check cannot be the default leaking through.
            config.set(List.of("fluidhatch.size", "TINY"), 4321);
            config.set(List.of("energyhatch.size", "TINY"), 9999);
            config.set(List.of("energyhatch.limit", "TINY"), 777);
            config.set(List.of("fluidhatch.size", "VACUUM"), 12345);
            spec.getClass().getMethod("setConfig", CommentedConfig.class).invoke(spec, config);
            check("configured fluidhatch.size.TINY is read back", 4321L, hatchValue("fluidHatchCapacity", HatchTier.TINY));
            check("configured energyhatch.size.TINY is read back", 9999L,
                    hatchValue("energyHatchCapacity", HatchTier.TINY));
            check("configured energyhatch.limit.TINY is read back", 777L,
                    hatchValue("energyHatchTransfer", HatchTier.TINY));
            check("configured fluidhatch.size.VACUUM is read back", 12345L,
                    hatchValue("fluidHatchCapacity", HatchTier.VACUUM));
            check("a tier that was not overridden still reads its default",
                    HatchTier.NORMAL.fluidCapacity(), hatchValue("fluidHatchCapacity", HatchTier.NORMAL));
            report("the overrides are accepted as a correct file",
                    (Boolean) spec.getClass().getMethod("isCorrect", CommentedConfig.class).invoke(spec, config));

            // The original accepted Long.MAX_VALUE here; this port must reject it visibly instead of
            // truncating to an int and building a 0 or negative buffer.
            config.set(List.of("energyhatch.size", "TINY"), Long.MAX_VALUE);
            report("energyhatch.size.TINY = Long.MAX_VALUE is not accepted",
                    !(Boolean) spec.getClass().getMethod("isCorrect", CommentedConfig.class).invoke(spec, config));
            int corrections = (int) spec.getClass().getMethod("correct", CommentedConfig.class).invoke(spec, config);
            report("Forge corrects the out-of-range value (" + corrections + " correction(s))", corrections > 0);
            spec.getClass().getMethod("setConfig", CommentedConfig.class).invoke(spec, config);
            int corrected = hatchValue("energyHatchCapacity", HatchTier.TINY);
            report("after correction the value fits this port's int-backed buffer (read back " + corrected + ")",
                    corrected >= 1 && corrected <= Integer.MAX_VALUE);
            report("the corrected file is correct",
                    (Boolean) spec.getClass().getMethod("isCorrect", CommentedConfig.class).invoke(spec, config));

            // The boundary itself is legal. This is what makes "rejected above int" a range, not a clamp.
            config.set(List.of("energyhatch.size", "TINY"), Integer.MAX_VALUE);
            spec.getClass().getMethod("setConfig", CommentedConfig.class).invoke(spec, config);
            check("Integer.MAX_VALUE is the accepted boundary", (long) Integer.MAX_VALUE,
                    hatchValue("energyHatchCapacity", HatchTier.TINY));
            report("...and the boundary file is correct",
                    (Boolean) spec.getClass().getMethod("isCorrect", CommentedConfig.class).invoke(spec, config));
        } catch (Throwable broken) {
            report("configured values could not be measured: " + describe(broken), false);
        } finally {
            restore(spec);
        }
    }

    // ---------------------------------------------------------------- the blocks use them

    private static void wiring() {
        try {
            String entity = withoutComments(Files.readString(SOURCE.resolve("block/MachineHatchBlockEntity.java")));
            String block = withoutComments(Files.readString(SOURCE.resolve("block/MachineHatchBlock.java")));
            String config = withoutComments(Files.readString(SOURCE.resolve("config/ModConfig.java")));
            String hatchConfig = withoutComments(Files.readString(SOURCE.resolve("config/HatchConfig.java")));

            report("the fluid tank is built from HatchConfig, not from the tier",
                    entity.contains("HatchConfig.fluidHatchCapacity(this.tier)"));
            report("the energy buffer is built from HatchConfig's capacity and transfer",
                    entity.contains("HatchConfig.energyHatchCapacity(this.tier)")
                            && entity.contains("HatchConfig.energyHatchTransfer(this.tier)"));
            report("the block entity no longer reads the tier's capacity accessors",
                    !entity.contains("this.tier.fluidCapacity()") && !entity.contains("this.tier.energyCapacity()")
                            && !entity.contains("this.tier.energyTransfer()"));
            report("the energy transfer used by the ticker is the one recorded at construction",
                    methodBody(entity, "private void tickEnergyOutput()").contains("this.energyTransfer")
                            && !methodBody(entity, "private void tickEnergyOutput()").contains("tier.energyTransfer"));
            report("the capacity description reads the built storages, not the enum again",
                    methodBody(entity, "public String capacityDescription()").contains("this.tank.getCapacity()")
                            && methodBody(entity, "public String capacityDescription()")
                                    .contains("this.energy.getMaxEnergyStored()"));

            report("the hatch tooltip's fluid line uses HatchConfig",
                    block.contains("HatchConfig.fluidHatchCapacity(tier)"));
            report("the hatch tooltip's energy line uses HatchConfig's capacity and transfer",
                    block.contains("HatchConfig.energyHatchCapacity(tier)")
                            && block.contains("HatchConfig.energyHatchTransfer(tier)"));

            int defineCall = config.indexOf("HatchConfig.define(builder);");
            int build = config.indexOf("return builder.build();");
            report("ModConfig attaches the hatch keys to its single spec before build()",
                    defineCall >= 0 && build > defineCall);
            report("HatchConfig defines the three original categories",
                    hatchConfig.contains("fluidhatch.size") && hatchConfig.contains("energyhatch.size")
                            && hatchConfig.contains("energyhatch.limit"));
            report("HatchConfig defines one key per tier on the fluid and energy ladders only",
                    hatchConfig.contains("for (HatchTier tier : HatchTier.FLUID_LADDER)")
                            && hatchConfig.contains("for (HatchTier tier : HatchTier.ENERGY_LADDER)")
                            && !hatchConfig.contains("HatchTier.ITEM_LADDER"));
            report("every key's declared range stops at Integer.MAX_VALUE",
                    count(hatchConfig, "1, Integer.MAX_VALUE") == 3);

            // Negative control that would pass even if the accessors were never wired: the tier table
            // itself must still match the original, because the defaults come from it.
            check("the fluid tier defaults are the original's ladder", "100,400,1000,2000,4500,8000,16000,32000",
                    HatchTier.FLUID_LADDER.stream().map(tier -> String.valueOf(tier.fluidCapacity()))
                            .reduce((a, b) -> a + "," + b).orElse(""));
            check("item slots did not change: the original never made them configurable",
                    "1,4,6,9,12,16,32", HatchTier.ITEM_LADDER.stream()
                            .map(tier -> String.valueOf(tier.itemSlots()))
                            .reduce((a, b) -> a + "," + b).orElse(""));
        } catch (Throwable broken) {
            report("the wiring could not be read: " + describe(broken), false);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static Object spec() {
        try {
            return Class.forName("com.reborn.modularmachinery.config.ModConfig").getField("SPEC").get(null);
        } catch (Throwable absent) {
            return null;
        }
    }

    private static int hatchValue(String method, HatchTier tier) throws Exception {
        Class<?> hatchConfig = Class.forName("com.reborn.modularmachinery.config.HatchConfig");
        return (int) hatchConfig.getMethod(method, HatchTier.class).invoke(null, tier);
    }

    private static void restore(Object spec) {
        try {
            spec.getClass().getMethod("setConfig", CommentedConfig.class).invoke(spec, (Object) null);
            report("the spec is left unloaded, as this section found it",
                    !(Boolean) spec.getClass().getMethod("isLoaded").invoke(spec));
        } catch (Throwable broken) {
            report("the spec could not be restored: " + describe(broken), false);
        }
    }

    /** The body of a method, from its signature through its matching closing brace. */
    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        if (start < 0) {
            return "";
        }
        int open = source.indexOf('{', start);
        if (open < 0) {
            return "";
        }
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return source.substring(open, i + 1);
            }
        }
        return "";
    }

    private static int count(String haystack, String needle) {
        int count = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }

    /** Removes line and block comments so a comment cannot satisfy a source check. */
    private static String withoutComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }

    private static String describe(Throwable throwable) {
        Throwable cause = throwable.getCause() == null ? throwable : throwable.getCause();
        return cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }

    // The harness counts and prints through ParallelCraftCheck; these stay private so this file has no
    // static import that would shadow its own helpers.
    private static void section(String title) {
        ParallelCraftCheck.section(title);
    }

    private static void report(String message, boolean passed) {
        ParallelCraftCheck.report(message, passed);
    }

    private static void check(String message, long expected, long actual) {
        ParallelCraftCheck.check(message, expected, actual);
    }

    private static void check(String message, String expected, String actual) {
        ParallelCraftCheck.check(message, expected, actual);
    }
}
