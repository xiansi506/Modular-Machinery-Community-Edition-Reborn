import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Arrays;

/** Reflection over public common APIs without optional scripting, network and chemical classes. */
public final class MagicOptionalIsolationCheck {
    private static int passed;
    private static final String PREFIX = "com.reborn.modularmachinery.";

    private static URLClassLoader loader(URL[] urls, boolean denyAE2, boolean denyMekanism) {
        return new URLClassLoader(urls, ClassLoader.getPlatformClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("vazkii.botania.") || name.startsWith("de.ellpeck.naturesaura.") || name.startsWith("wayoftime.bloodmagic.") || name.startsWith("sonar.fluxnetworks.") || name.startsWith("com.brandon3055.") || name.startsWith("software.bernie.geckolib.") || (denyAE2 && denyMekanism && (name.startsWith("net.minecraft.client.") || name.startsWith("com.mojang.blaze3d."))) || name.startsWith("dev.latvian.mods.kubejs.") || name.startsWith("dev.latvian.mods.rhino.")
                        || name.startsWith("me.ramidzkh.mekae2.")
                        || (denyAE2 && name.startsWith("appeng."))
                        || (denyMekanism && name.startsWith("mekanism."))) {
                    throw new ClassNotFoundException("Optional dependency deliberately absent: " + name);
                }
                return super.loadClass(name, resolve);
            }
        };
    }

    private static void verify(ClassLoader loader, String name, String mode) throws Exception {
        Class<?> type = Class.forName(PREFIX + name, false, loader);
        type.getDeclaredMethods();
        type.getDeclaredConstructors();
        type.getDeclaredFields();
        passed++;
        System.out.println("PASS " + mode + ": " + name);
    }

    public static void main(String[] args) throws Exception {
        URL[] urls = Arrays.stream(System.getProperty("java.class.path").split(java.io.File.pathSeparator))
                .map(p -> { try { return Path.of(p).toUri().toURL(); } catch (Exception e) { throw new RuntimeException(e); } })
                .toArray(URL[]::new);
        try (var loader = loader(urls, true, true)) {
            for (String name : new String[]{
                    "script/ClientControllerView".replace('/','.'), "script.ClientModelRegistry", "script.ClientModelRegistry$Selection", "api.event.ControllerModelGetEvent", "api.event.ControllerModelAnimationEvent", "api.event.ControllerModelAnimationEvent$Snapshot", "api.event.ControllerModelAnimationEvent$AnimationStep", "api.ControllerGuiInfoEvent".replace('/','.'), "machine.GeoModelSpec", "machine.StructurePreview", "machine.StructurePreview$Block", "block.MeGasStorage", "block.MeGasStorage$Entry", "block.MeBusAccess",
                    "block.MachineHatchBlockEntity", "machine.HatchCollection", "recipe.MachineRequirement",
                    "compat.ModCompat", "machine.MachineInputProvider", "block.MachineControllerBlockEntity", "block.ModBlocks",
                    "script.MachineControllerAccess", "script.MachineControllerAccess$ThreadAccess",
                    "script.MachineControllerAccess$UpgradeAccess", "script.MachineScriptState", "script.ScriptHooks",
                    "script.ScriptHooks$ItemChecker", "script.ScriptHooks$ItemModifier", "script.ScriptHooks$BlockChecker",
                    "script.ScriptUpgradeDefinitions", "recipe.ItemRequirement", "recipe.MachineRecipe",
                    "magic.MagicFamily", "magic.MagicPort", "magic.MagicPort$Access", "magic.MagicPort$Planned", "magic.MagicBackend", "magic.MagicConfig", "magic.MagicBlocks", "magic.MagicHatchBlock", "magic.MagicHatchBlockEntity", "magic.MagicHatchMenu", "magic.MagicRequirement", "magic.MagicRequirements", "magic.MagicModule", "magic.MagicLootTables", "compat.magic.ApiReflection", "compat.magic.BotaniaBackend", "compat.magic.AuraBackend", "compat.magic.BloodMagicBackend", "energy.EnergyPort", "energy.LongEnergyAccess", "energy.EnergyAccess", "energy.LongEnergyStorage", "energy.EnergyMenuValues", "compat.energy.OptionalEnergy", "compat.energy.OptionalEnergy$Handle", "menu.MeConfigAmount", "recipe.EnergyUsage", "render.WorldHidingHooks", "render.WorldHidingHooks$RenderView", "render.WorldVisibilityIndex", "machine.MachinePattern", "machine.MachineDefinition", "factory.FactoryEngine", "factory.FactoryThread",
                    "recipe.RecipeModifiers", "recipe.MachineRequirementParser", "upgrade.UpgradeLoader"
            }) verify(loader, name, "no KubeJS/Rhino/AE2/Mekanism/AppMek");
        }
        for (boolean denyMekanism : new boolean[]{true, false}) {
            try (var loader = loader(urls, false, denyMekanism)) {
                for (String name : new String[]{"MeItemBusBlockEntity", "PatternInputTransfer", "MePatternProviderBlockEntity",
                        "ProviderBuffer", "ProviderState", "MePatternMirrorBlockEntity", "MePatternProviderBlock", "MePatternMirrorBlock"}) {
                    verify(loader, "compat.ae2." + name, "AE2 without KubeJS/Rhino/" + (denyMekanism ? "Mekanism/AppMek" : "AppMek"));
                }
            }
        }
        if (passed != 85) throw new AssertionError("Expected 85 class checks, observed " + passed);
        System.out.println("SUMMARY: " + passed + " class-load checks / 0 optional linkage failures (reflection only; not a Forge boot or gameplay test)");
    }
}
