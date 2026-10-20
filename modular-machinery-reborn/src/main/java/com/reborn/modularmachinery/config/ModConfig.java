package com.reborn.modularmachinery.config;

import com.reborn.modularmachinery.block.ParallelControllerTier;
import com.reborn.modularmachinery.block.UpgradeBusTier;
import net.minecraftforge.common.ForgeConfigSpec;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The mod's <b>single</b> Forge config, published as {@code config/modular_machinery_reborn-common.toml}.
 *
 * <h2>Why exactly one spec</h2>
 * Forge's {@code ConfigTracker} keys its file map on the config <i>file name</i>
 * ({@code ConfigTracker#trackConfig}, {@code fmlcore-1.20.1-47.2.0.jar}: the bytecode reads
 * {@code ModConfig#getFileName()} and throws when the key is already present), and every {@code COMMON} config of a
 * mod is named {@code <modid>-common.toml}. A second {@code registerConfig(COMMON, ...)} therefore lands on the
 * same key and Forge throws {@code RuntimeException("Config conflict detected!")} from the mod constructor. 0.21.0
 * did exactly that — a first config class registered the parallel-controller spec and a second one, added for the
 * upgrade bus, registered another — and the game died at construction.
 *
 * <p>Both key sets consequently live in the one spec below, and the one registration lives in {@link #register()}.
 * Adding a config section means adding a block to {@link #build()} — <b>never</b> a second call to
 * {@code ModLoadingContext.registerConfig}.
 *
 * <h2>The key paths</h2>
 * These are the original 1.12.2 mod's own key names, so they are a compatibility surface and must not be renamed:
 * <ul>
 *   <li>{@code parallel-controller.<tier>.max-parallelism} — defaults 4/16/64/256/512, range
 *       {@code 1 .. Integer.MAX_VALUE} ({@code ParallelControllerData#loadFromConfig}, {@code :23-29}).</li>
 *   <li>{@code upgrade-bus.<tier>.max-upgrade_slot} — defaults 3/6/9/12/18, range {@code 1 .. 18}, the underscore
 *       in {@code max-upgrade_slot} included ({@code UpgradeBusData#loadFromConfig}, {@code :23-29}). The bound of
 *       18 is the original's own clamp.</li>
 *   <li>{@code fluidhatch.size.<TIER>}, {@code energyhatch.size.<TIER>} and {@code energyhatch.limit.<TIER>} — the
 *       original's hatch keys, kept in the original's categories and with the enum constant as the upper-case key
 *       ({@code FluidHatchSize#loadFromConfig}, {@code :43-47}; {@code EnergyHatchData#loadFromConfig},
 *       {@code :67-85}). Defaults come from {@code HatchTier}; the energy keys' upper bound is
 *       {@code Integer.MAX_VALUE} instead of the original's {@code Long.MAX_VALUE} because this port's energy
 *       buffer is int-backed (item F7). See {@link HatchConfig}.</li>
 *   <li>{@code factory-system.enable-factory-controller-bydefault} — default {@code false}
 *       ({@code Config.java:41} field initialiser and {@code :120-121} the config default);
 *       {@code factory-system.default-factory-max-thread} — default <b>10</b>, range {@code 1 .. 100}
 *       ({@code Config.java:122-124}). These two are the original's own key names inside its
 *       {@code factory-system} section, kept verbatim because the class comment above calls the key names a
 *       compatibility surface. See M6e-2.</li>
 *   <li>{@code smart-interface.enable-smart-interface-bydefault} — default {@code false}, M6d-b's single key.
 *       The original had no config entry for the smart data interface at all: its types were registered by a
 *       CraftTweaker script and a machine without one simply had none. This key exists only to make that same
 *       answer configurable for the message {@code MachineLoader} prints, mirroring
 *       {@code factory-system.enable-factory-controller-bydefault}. See D16.</li>
 * </ul>
 * Only the file's root name differs from the original's {@code modularmachinery.cfg}, because this project uses the
 * Forge default name for its mod id. Both sets are named in {@code 移植方案-v2.md} (the M6d and M6b entries) and in
 * the {@code 迁移日志.md} entries for 0.20.0 and 0.21.0.
 *
 * <h2>Why the paths are passed as lists</h2>
 * {@code ForgeConfigSpec.Builder} is <b>path-accumulating</b>: {@code push(x)} appends to a running context that
 * {@code pop()} does not restore to the caller's starting point, which is why 0.20.0's loop produced
 * {@code parallel-controller.normal.max-parallelism} for the first tier but
 * {@code parallel-controller.parallel-controller.reinforced.max-parallelism} for the second, and ever longer
 * duplications after that (the 0.20.0 jar's own generated file, kept at
 * {@code D:\.minecraft\versions\彩虹花园\config\modular_machinery_reborn-common.toml}, shows the nesting). Every
 * key here is therefore defined with an explicit path list, so each definition is independent and cannot inherit a
 * neighbour's prefix. The tier enums themselves stay in code: a block's variants have to exist when blocks are
 * registered, and 1.20.1 forbids registering them later (D8). Only the numbers move to the config.
 */
public final class ModConfig {

    /**
     * The one and only spec for this mod. It carries both sections; see the class comment for why there cannot be
     * a second spec of the same type.
     */
    public static final ForgeConfigSpec SPEC;

    /** The original's upper bound on an upgrade-bus slot count ({@code UpgradeBusData.java:26}). */
    public static final int MAX_UPGRADE_SLOTS_LIMIT = 18;

    /**
     * The original's upper bound on the factory system's default thread count
     * ({@code Config.java:122-124}, the {@code getInt} call's max), and its lower bound.
     */
    public static final int DEFAULT_FACTORY_MAX_THREAD_LIMIT = 100;
    public static final int DEFAULT_FACTORY_MAX_THREAD_FLOOR = 1;

    /**
     * The value used when {@link #factoryDefaultMaxThread()} is asked before the config file has been read —
     * the original's own config-file default ({@code Config.java:122-124} passes 10 to {@code getInt}).
     *
     * <p><b>Honest note on 10 versus 20.</b> The original's Java field is initialised to {@code 20}
     * ({@code Config.java:47}), but {@code Config.load()} unconditionally overwrites it with the config read,
     * whose default is {@code 10}. Whenever the original ran with a config file — always, in practice — the
     * effective default was therefore 10, and 20 was only ever visible in the window between class
     * initialisation and {@code loadFrom(…)}. This projection keeps the <i>observable</i> value.
     * M6e-1's {@code DEFAULT_MAX_THREADS = 20} was read off the field initialiser and is corrected here.
     * A pre-existing {@code config/modular_machinery_reborn-common.toml} is not invalidated: this is a new
     * section, so Forge simply appends it with the default.
     */
    private static final int FACTORY_MAX_THREAD_BEFORE_CONFIG = 10;

    private static final String PARALLEL_ROOT = "parallel-controller";
    private static final String PARALLEL_VALUE = "max-parallelism";
    private static final String UPGRADE_ROOT = "upgrade-bus";
    private static final String UPGRADE_VALUE = "max-upgrade_slot";
    private static final String FACTORY_ROOT = "factory-system";
    private static final String FACTORY_MAX_THREAD = "default-factory-max-thread";
    private static final String FACTORY_ENABLED = "enable-factory-controller-bydefault";
    private static final String SMART_INTERFACE_ROOT = "smart-interface";
    private static final String SMART_INTERFACE_ENABLED = "enable-smart-interface-bydefault";
    private static final String GENERAL_ROOT = "general";
    private static final String MOC_COMPATIBLE = "modular-controller-compatible-mode";
    private static final String MOC_DEPRECATED_TIP = "disable-moc-deprecated-tip";
    /** The original's own category and key spelling: {@code display.energy.Display_Energy_Type}. */
    private static final String DISPLAY_ENERGY_ROOT = "display.energy";
    private static final String DISPLAY_ENERGY_KEY = "Display_Energy_Type";

    private static final Map<ParallelControllerTier, ForgeConfigSpec.IntValue> PARALLELISM =
            new EnumMap<>(ParallelControllerTier.class);

    private static final Map<UpgradeBusTier, ForgeConfigSpec.IntValue> UPGRADE_SLOTS =
            new EnumMap<>(UpgradeBusTier.class);

    /**
     * {@code display.energy.Display_Energy_Type} — the unit every energy number is <b>printed</b> in.
     *
     * <p>Stored as a string rather than an enum so the file's own spelling is the four names the original used
     * ({@code FE} / {@code RF} / {@code IC2_EU} / {@code GT_EU}) and a typo is reported rather than crashing;
     * {@link EnergyDisplay#parse} does the tolerant read, exactly as the original's {@code EnergyType.getType}
     * did. Same reasoning as {@link #FACTORY_MAX_THREADS} for not being {@code final}: it is assigned in
     * {@link #build()}.
     */
    private static ForgeConfigSpec.ConfigValue<String> DISPLAY_ENERGY_TYPE;

    /**
     * {@code factory-system.default-factory-max-thread}.
     *
     * <p>Not {@code final}, unlike nothing else in this class, because it is assigned inside {@link #build()} —
     * a blank {@code final} static may only be assigned from a static initialiser or the class body, and
     * {@code build()} is neither. It is written exactly once, from the one static initialiser that calls
     * {@code build()}, and never afterwards.
     */
    private static ForgeConfigSpec.IntValue FACTORY_MAX_THREADS;
    /**
     * {@code factory-system.enable-factory-controller-bydefault}. Same reasoning as
     * {@link #FACTORY_MAX_THREADS}.
     */
    private static ForgeConfigSpec.BooleanValue FACTORY_ENABLED_BY_DEFAULT;
    /**
     * {@code smart-interface.enable-smart-interface-bydefault}. Same reasoning as
     * {@link #FACTORY_MAX_THREADS}: a blank {@code final} cannot be assigned from {@code build()}.
     */
    private static ForgeConfigSpec.BooleanValue SMART_INTERFACE_ENABLED_BY_DEFAULT;
    /**
     * {@code general.modular-controller-compatible-mode}. Same reasoning as {@link #FACTORY_MAX_THREADS}.
     *
     * <p><b>Inert on 1.20.1, deliberately and visibly.</b> See {@link #mocCompatibleMode()}.
     */
    private static ForgeConfigSpec.BooleanValue MOC_COMPATIBLE_MODE;
    /**
     * {@code general.disable-moc-deprecated-tip}. Same reasoning as {@link #FACTORY_MAX_THREADS}.
     */
    private static ForgeConfigSpec.BooleanValue DISABLE_MOC_DEPRECATED_TIP;

    static {
        SPEC = build();
    }

    /**
     * Builds the one spec: both key sets, in one builder, with explicit per-key paths.
     *
     * <p>Do not call {@code build()} twice and do not hand the builder's output to a second
     * {@code registerConfig}: one spec per type per mod is a Forge invariant, not a style choice.
     */
    private static ForgeConfigSpec build() {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        // ---- Section 1 of 2: parallel-controller.<tier>.max-parallelism -------------------------------
        // The comment is set once for the section root: Forge attaches a comment to the path it is handed, and a
        // comment handed the full key path would also be written above the value as a repeated "#<path>" line.
        builder.comment("Defines the max parallelism for the parallel controller.");
        for (ParallelControllerTier tier : ParallelControllerTier.values()) {
            List<String> path = Arrays.asList(PARALLEL_ROOT, tier.tierName(), PARALLEL_VALUE);
            PARALLELISM.put(tier, builder.defineInRange(path, tier.defaultMaxParallelism(),
                    1, Integer.MAX_VALUE));
        }

        // ---- Section 2 of 2: upgrade-bus.<tier>.max-upgrade_slot --------------------------------------
        // This is a block in the same builder, not a spec of its own: a second ForgeConfigSpec would mean a second
        // COMMON registration, which is the startup crash described in the class comment.
        builder.comment("Defines the max upgrade slot for the upgrade bus.");
        for (UpgradeBusTier tier : UpgradeBusTier.values()) {
            List<String> path = Arrays.asList(UPGRADE_ROOT, tier.tierName(), UPGRADE_VALUE);
            UPGRADE_SLOTS.put(tier, builder.defineInRange(path, tier.defaultMaxUpgradeSlots(),
                    1, MAX_UPGRADE_SLOTS_LIMIT));
        }

        // ---- Section 3 of 3: factory-system.* ----------------------------------------------------------
        // The two keys M6e-2 adds, again with explicit path lists. Both are flat keys inside one section
        // (`factory-system`), which is how the original's own config file looked: its `getBoolean` /
        // `getInt` calls named the category "factory-system" with the key directly under it, so no second
        // path element is involved — and no loop, so the path-accumulation hazard described above cannot
        // apply here at all.
        builder.comment("Defines the factory system's defaults.");
        FACTORY_MAX_THREADS = builder.defineInRange(
                List.of(FACTORY_ROOT, FACTORY_MAX_THREAD), FACTORY_MAX_THREAD_BEFORE_CONFIG,
                DEFAULT_FACTORY_MAX_THREAD_FLOOR, DEFAULT_FACTORY_MAX_THREAD_LIMIT);
        FACTORY_ENABLED_BY_DEFAULT = builder.define(
                List.of(FACTORY_ROOT, FACTORY_ENABLED), false);

        // ---- Section 4 of 4: smart-interface.* ---------------------------------------------------------
        // M6d-b's one key, again a literal path list, again no loop. It is a *message* switch and nothing
        // else: whether a machine may use a smart interface is decided by its own `smart-interfaces`
        // declaration, exactly as the original decided it by DynamicMachine#smartInterfaceTypesIsEmpty. The
        // key exists because that is the one thing about this feature a server operator might want to hear
        // less about, mirroring factory-system.enable-factory-controller-bydefault.
        builder.comment("Defines the smart data interface's defaults.");
        SMART_INTERFACE_ENABLED_BY_DEFAULT = builder.define(
                List.of(SMART_INTERFACE_ROOT, SMART_INTERFACE_ENABLED), false);

        // ---- Section 5 of 5: general.* — the ModularController compatibility keys ---------------------
        // The original's own two keys, in the original's own section ("general") and with the original's own
        // defaults (Config.java:100-105). Both are compatibility surfaces: a pack author's existing
        // modularmachinery.cfg, or a reader of the original's documentation, expects exactly these names.
        //
        // The first one's comment is the original's sentence with its promise CROSSED OUT, because the promise
        // cannot be kept here and an inert key that lies is worse than no key: on 1.20.1 a block id has to exist
        // before Forge has read any config file (RegisterEvent runs in the LOAD_REGISTRIES state, CONFIG_LOAD
        // after it — see MocNamespace), so nothing can gate registration on this value. See
        // mocCompatibleMode() for what the projection does instead.
        builder.comment("ModularController merge support (the original's `modular-controller-compatible-mode`).",
                "INERT ON 1.20.1: this key cannot gate anything. The original registered the compatibility blocks",
                "when it was true, but Forge fires the block registry event (LOAD_REGISTRIES) before it has read",
                "any config file (CONFIG_LOAD), so a block id must already exist by then — and a save is loaded",
                "by block id, which means a world holding such a block cannot be opened in order to read this",
                "key. The `modularcontroller` controllers are therefore always registered for every machine",
                "declared in config/modular_machinery_reborn/machinery/ (skip: factory-only). Their items are not",
                "put in the creative tab: they exist so an old save keeps working, not to be handed out. The key",
                "is kept only because the original had it.");
        MOC_COMPATIBLE_MODE = builder.define(List.of(GENERAL_ROOT, MOC_COMPATIBLE), false);
        builder.comment("Disable the ModularController is deprecated tooltip (the original's",
                "`disable-moc-deprecated-tip`). Live: a tooltip is built long after the config has been read.");
        DISABLE_MOC_DEPRECATED_TIP = builder.define(List.of(GENERAL_ROOT, MOC_DEPRECATED_TIP), false);

        // ---- Section 6 of 6: display.energy.Display_Energy_Type ---------------------------------------
        // The original's key, in the original's own category and with the original's own spelling and casing
        // (`EnergyDisplayUtil.loadFromConfig`: cfg.getString("Display_Energy_Type", "display.energy", …)), so a
        // pack author's existing modularmachinery.cfg line is understood as written.
        //
        // The comment states the two things a reader must not get wrong: this is a *display* choice, and the
        // stored numbers never change. The original's own description promised "energy be displayed in that type
        // of energy", which reads like a conversion; its implementation multiplied only for printing, and saying
        // so here is the one place this departs from the original's wording.
        builder.comment("The unit every energy number is displayed in (the original's `display.energy`).",
                "Available options: " + EnergyDisplay.vocabulary() + " - Default: 'FE'.",
                "DISPLAY ONLY: no stored value is converted. A hatch holding 8192 FE prints as '2048 EU' with",
                "'IC2_EU' selected and still holds 8192 FE, which is what the original did as well — it scaled the",
                "number it printed and nothing else. 'RF' is 1:1, i.e. a label rather than a conversion.",
                "The original's two tooltip switches (IC2_EU_Tooltip / GT_EU_Tooltip) are not implemented: each",
                "only had an effect with that mod installed, and neither IC2 (absent on 1.20.1) nor GregTech is a",
                "dependency of this mod, so both were no-ops here.");
        DISPLAY_ENERGY_TYPE = builder.define(List.of(DISPLAY_ENERGY_ROOT, DISPLAY_ENERGY_KEY),
                EnergyDisplay.FE.getSerializedName());

        // ---- Hatch capacities and transfer rates (A4) ------------------------------------------------
        // The original read these in three loops over its three hatch enums; HatchConfig owns the maps and the
        // accessors, and this call is the only place they are attached to the one spec above. Not a separate
        // spec: a second COMMON registration is the startup crash the class comment describes.
        HatchConfig.define(builder);

        return builder.build();
    }

    /**
     * Registers {@link #SPEC} — the mod's only config registration.
     *
     * <p><b>Do not add a second call to {@code ModLoadingContext.registerConfig} anywhere.</b> Forge allows one
     * config per type per mod, and a second {@code COMMON} registration is a hard crash at mod construction
     * ("Config conflict detected!"), not a warning. New sections go into {@link #build()} instead.
     */
    public static void register() {
        net.minecraftforge.fml.ModLoadingContext.get()
                .registerConfig(net.minecraftforge.fml.config.ModConfig.Type.COMMON, SPEC);
    }

    /**
     * The ceiling a parallel controller of this tier contributes. Falls back to the original's hardcoded default
     * while the config file has not been read yet, so a block entity built before that moment cannot end up with a
     * ceiling of {@code 0}.
     */
    public static int maxParallelism(ParallelControllerTier tier) {
        ForgeConfigSpec.IntValue value = PARALLELISM.get(tier);
        if (value == null || !SPEC.isLoaded()) {
            return tier.defaultMaxParallelism();
        }
        return value.get();
    }

    /**
     * The slots a bus of this tier provides. Falls back to the original's hardcoded default while the config file
     * has not been read yet, so a block entity built before that moment cannot end up with zero slots.
     */
    public static int maxUpgradeSlots(UpgradeBusTier tier) {
        ForgeConfigSpec.IntValue value = UPGRADE_SLOTS.get(tier);
        if (value == null || !SPEC.isLoaded()) {
            return tier.defaultMaxUpgradeSlots();
        }
        return value.get();
    }

    /**
     * {@code factory-system.default-factory-max-thread}: how many ordinary threads a factory controller may hold
     * when the machine's definition does not say — the original's {@code Config.defaultFactoryMaxThread}
     * ({@code Config.java:122-124}), which {@code AbstractMachine.maxThreads} was initialised from
     * ({@code AbstractMachine.java:24}).
     *
     * <p>Falls back to the original's own config default while the file has not been read yet, so a machine
     * definition loaded before that moment cannot end up with a thread count of {@code 0}.
     */
    public static int factoryDefaultMaxThread() {
        if (FACTORY_MAX_THREADS == null || !SPEC.isLoaded()) {
            return FACTORY_MAX_THREAD_BEFORE_CONFIG;
        }
        return FACTORY_MAX_THREADS.get();
    }

    /**
     * {@code display.energy.Display_Energy_Type}: the unit every energy number is printed in.
     *
     * <p>Read fresh on each call rather than cached, because the value is only ever consulted while a tooltip or a
     * GUI line is being built — long after {@code CONFIG_LOAD} — so the file's value is what the player sees and a
     * cached copy would only add a way for the two to disagree.
     *
     * <p>A value that names none of the four units falls back to {@link EnergyDisplay#FE} and is <b>reported
     * once</b>: the original's own parser swallowed the failure silently, which leaves a typo in the config
     * looking like the setting simply does not work.
     */
    public static EnergyDisplay energyDisplay() {
        if (DISPLAY_ENERGY_TYPE == null || !SPEC.isLoaded()) {
            return EnergyDisplay.FE;
        }
        String raw = DISPLAY_ENERGY_TYPE.get();
        EnergyDisplay parsed = EnergyDisplay.find(raw);
        if (parsed == null) {
            if (WARNED_ABOUT_ENERGY_TYPE.add(String.valueOf(raw))) {
                com.reborn.modularmachinery.ModularMachineryReborn.LOGGER.warn(
                        "[{}] {}.{} is '{}', which is not one of {}; displaying energy as '{}' instead. The value"
                                + " is a display choice only — no stored amount is converted.",
                        com.reborn.modularmachinery.ModularMachineryReborn.MOD_ID, DISPLAY_ENERGY_ROOT,
                        DISPLAY_ENERGY_KEY, raw, EnergyDisplay.vocabulary(),
                        EnergyDisplay.FE.getSerializedName());
            }
            return EnergyDisplay.FE;
        }
        return parsed;
    }

    /**
     * Values already complained about, so a per-frame caller reports once instead of flooding the log.
     *
     * <p>Keyed by the offending string, which means a corrected value that is then broken again is reported again
     * — the message is about what the file says now, not about how many times it has been read.
     */
    private static final Set<String> WARNED_ABOUT_ENERGY_TYPE = ConcurrentHashMap.newKeySet();

    /**
     * {@code factory-system.enable-factory-controller-bydefault}: whether the machine definition layer should
     * assume a machine has a factory when it does not say so — the original's
     * {@code Config.enableFactoryControllerByDefault} ({@code Config.java:41}, {@code :120-121}), which
     * {@code AbstractMachine.hasFactory} was initialised from ({@code AbstractMachine.java:27}).
     *
     * <p>It defaults to {@code false}, exactly as the original's field and config default do, so the projection's
     * behaviour with an untouched config is unchanged from M6e-1.
     */
    public static boolean factoryControllerEnabledByDefault() {
        if (FACTORY_ENABLED_BY_DEFAULT == null || !SPEC.isLoaded()) {
            return false;
        }
        return FACTORY_ENABLED_BY_DEFAULT.get();
    }

    /**
     * {@code smart-interface.enable-smart-interface-bydefault}: whether the loader stays quiet about a machine
     * that does not declare any {@code smart-interfaces} while some recipe on it asks for an interface value.
     *
     * <p>It defaults to {@code false}, which is the loud setting, and it deliberately does <b>not</b> decide
     * whether a machine may answer an {@code interface_number_input} requirement: that is the machine's own
     * declaration, which is how the original decided it ({@code DynamicMachine#smartInterfaceTypesIsEmpty}
     * gating {@code checkAndAddSmartInterface}). Turning this on cannot make a value appear — there would be no
     * declared type to hold it under — so it only switches a warning off.
     */
    public static boolean smartInterfaceEnabledByDefault() {
        if (SMART_INTERFACE_ENABLED_BY_DEFAULT == null || !SPEC.isLoaded()) {
            return false;
        }
        return SMART_INTERFACE_ENABLED_BY_DEFAULT.get();
    }

    /**
     * {@code general.modular-controller-compatible-mode} — the original's switch for registering a controller in
     * the {@code modularcontroller} namespace ({@code Config.java:100-102}).
     *
     * <p><b>This key is inert, and this accessor exists so that the fact is readable rather than implied.</b> On
     * 1.20.1 the blocks cannot be gated on it at all: Forge fires {@code RegisterEvent} from its
     * {@code LOAD_REGISTRIES} state, and {@code CONFIG_LOAD} — the state that hands a spec its file — runs
     * afterwards, so while blocks are being registered {@code SPEC.isLoaded()} is still false and this method
     * returns the fallback below whatever the file says. Nor could it be otherwise: a save deserialises a placed
     * block by its registry id, so the id has to exist before the game can open the world in which the key would
     * have been read. See {@code MocNamespace} for the chain and for what the projection does instead.
     *
     * <p>The accessor is kept, and {@code ModBlocks.MOC_CONTROLLERS} deliberately does <b>not</b> consult it, so
     * that a reader of this class sees the whole story in one place rather than a silently dropped key.
     */
    public static boolean mocCompatibleMode() {
        if (MOC_COMPATIBLE_MODE == null || !SPEC.isLoaded()) {
            return false;
        }
        return MOC_COMPATIBLE_MODE.get();
    }

    /**
     * {@code general.disable-moc-deprecated-tip} — the original's switch for the two deprecation lines on a
     * {@code modularcontroller} controller ({@code Config.java:103-105}, applied at
     * {@code BlockController.java:118}).
     *
     * <p>Unlike {@link #mocCompatibleMode()} this one is <b>live</b>: it is read while a tooltip is built, which
     * happens long after {@code CONFIG_LOAD}, so {@code SPEC.isLoaded()} is true by then and the file's value is
     * what the player sees. Defaults to {@code false}, the original's own default, i.e. the tips are shown.
     */
    public static boolean disableMocDeprecatedTip() {
        if (DISABLE_MOC_DEPRECATED_TIP == null || !SPEC.isLoaded()) {
            return false;
        }
        return DISABLE_MOC_DEPRECATED_TIP.get();
    }

    private ModConfig() {
    }
}
