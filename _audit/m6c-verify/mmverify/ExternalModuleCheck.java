package mmverify;

import com.google.gson.JsonObject;
import com.reborn.modularmachinery.compat.CompatModule;
import com.reborn.modularmachinery.compat.CompatRegistry;
import com.reborn.modularmachinery.compat.ModCompat;
import com.reborn.modularmachinery.recipe.IOType;
import com.reborn.modularmachinery.recipe.MachineRequirement;
import com.reborn.modularmachinery.recipe.RecipeModifier;
import com.reborn.modularmachinery.recipe.RequirementType;
import com.reborn.modularmachinery.recipe.RequirementTypes;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.capabilities.RegisterCapabilitiesEvent;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Section B10 — 外部模组能不能把**自己的**兼容模块交给主模组。
 *
 * <p>波 5.2 已经证明「一个 {@link CompatModule} 能注册一种需求」（见
 * {@link RequirementTypeCheck#compatModuleRegistersANewKind()}），但那条测试是自己把 List 传给
 * {@link ModCompat#register} 的。它没有回答本节的问题：一个**不在主模组源码里**的模块，
 * 有没有一条公开入口进入 {@link ModCompat#knownModules()}？
 *
 * <p>答案在 0.66.0 之前是否：清单是 {@code List.of(new MekanismModule())}，外部模块只能
 * 绕开框架直接调 {@code RequirementTypes.register}，于是它的 {@code requiresModid} 兜底、
 * 端口与能力钩子都不会被主模组跑到。本节先于实现写，用反射找入口，这样红跑能编译并报 FAIL，
 * 而不是在 javac 就停下。
 *
 * <p><b>为什么传 {@code null} 给 {@link ModCompat#onRegisterCapabilities}。</b>生产实现不读事件参数，
 * 只遍历模块并调用钩子；这条断言钉的是「能力阶段遍历得到外部模块」，不是 Forge 事件本身。
 * 若以后实现开始读事件，这条会以 NPE 红掉，届时再换成真事件或拆开接口。
 */
final class ExternalModuleCheck {

    private static final ResourceLocation MODULE_ID =
            new ResourceLocation("mmverify", "external_module");
    private static final ResourceLocation TYPE_ID =
            new ResourceLocation("mmverify", "external_kind");
    private static final ResourceLocation GATED_MODULE_ID =
            new ResourceLocation("mmverify", "external_gated_module");
    private static final ResourceLocation GATED_TYPE_ID =
            new ResourceLocation("mmverify", "external_gated_kind");
    private static final ResourceLocation LATE_MODULE_ID =
            new ResourceLocation("mmverify", "external_late_module");

    private static final String PRESENT = "mmverify_present";
    private static final String ABSENT = "mmverify_absent";

    private ExternalModuleCheck() {
    }

    static void externalModuleRegistration() {
        section("B10. an external mod joins the compat list without editing this mod");

        AtomicInteger capabilities = new AtomicInteger();
        CompatModule external = module(MODULE_ID, TYPE_ID, PRESENT, capabilities);

        Throwable apiFailure = add(external);
        // 这一条在旧源码上是唯一能编译的证明方式：方法不存在 → FAIL，但整个复现器仍然跑完。
        report("ModCompat has a public static registerModule(CompatModule) entry point",
                apiFailure == null);
        if (apiFailure != null) {
            report("an external module can put itself on knownModules() without editing this mod: "
                    + apiFailure, false);
            return;
        }

        report("an external module added through that entry point is on knownModules()",
                contains(ModCompat.knownModules(), MODULE_ID));

        Throwable duplicate = add(module(MODULE_ID, new ResourceLocation("mmverify", "other_kind"),
                PRESENT, new AtomicInteger()));
        report("a second module with the same id is rejected (not silently ignored)",
                duplicate instanceof IllegalStateException);

        AtomicInteger gatedCapabilities = new AtomicInteger();
        Throwable gatedFailure = add(module(GATED_MODULE_ID, GATED_TYPE_ID, ABSENT, gatedCapabilities));
        report("a second external module, whose required mod is absent, can also be added",
                gatedFailure == null);

        try {
            List<CompatModule> registered = ModCompat.register(ModCompat.knownModules(), PRESENT::equals);
            check("only the external module whose dependency is present was registered",
                    1, registered.size());
            report("...and it is the external module, not the built-in one",
                    registered.size() == 1 && MODULE_ID.equals(registered.get(0).id()));

            RequirementType type = RequirementTypes.byName(TYPE_ID.toString());
            report("its requirement kind lives in the registry under its own namespace",
                    type != null && TYPE_ID.equals(type.id()));
            report("...and a bare path does not resolve to it (the pre-existing first lookup asks this "
                            + "mod's namespace)",
                    RequirementTypes.byName("external_kind") == null);
            report("the module whose required mod is absent did not register its kind",
                    RequirementTypes.byName(GATED_TYPE_ID.toString()) == null);

            ModCompat.onRegisterCapabilities(null);
            check("the external module's capability hook ran once at the capability stage",
                    1, capabilities.get());

            Throwable late = add(module(LATE_MODULE_ID,
                    new ResourceLocation("mmverify", "external_late_kind"), PRESENT, new AtomicInteger()));
            report("adding a module after the capability stage is rejected, not silently ignored",
                    late instanceof IllegalStateException);
        } finally {
            RequirementTypes.deregister(TYPE_ID);
            clearExternalModules();
            removeRegistered(MODULE_ID);
            resetSealed();
        }

        report("after the section the external module is gone from knownModules()",
                !contains(ModCompat.knownModules(), MODULE_ID));
        report("after the section its requirement kind is gone",
                RequirementTypes.byName(TYPE_ID.toString()) == null);
    }

    private static CompatModule module(ResourceLocation moduleId, ResourceLocation typeId,
                                       String requiresModid, AtomicInteger capabilityCalls) {
        return new CompatModule() {
            @Override
            public ResourceLocation id() {
                return moduleId;
            }

            @Override
            public String requiresModid() {
                return requiresModid;
            }

            @Override
            public void register(CompatRegistry registry) {
                registry.requirement(RequirementType.of(typeId, new NoopHandler(), requiresModid));
            }

            @Override
            public void registerCapabilities(RegisterCapabilitiesEvent event) {
                capabilityCalls.incrementAndGet();
            }
        };
    }

    /**
     * 反射调用新入口：旧源码上方法不存在，返回 {@link NoSuchMethodException}，测试仍能编译。
     * 入口抛出的异常会被拆掉 InvocationTargetException，断言看到的是真实原因。
     */
    private static Throwable add(CompatModule module) {
        Method method;
        try {
            method = ModCompat.class.getMethod("registerModule", CompatModule.class);
        } catch (NoSuchMethodException missing) {
            return missing;
        }
        try {
            method.invoke(null, module);
            return null;
        } catch (InvocationTargetException wrapped) {
            return wrapped.getCause() == null ? wrapped : wrapped.getCause();
        } catch (ReflectiveOperationException failure) {
            return failure;
        }
    }

    private static boolean contains(List<CompatModule> modules, ResourceLocation id) {
        for (CompatModule module : modules) {
            if (id.equals(module.id())) {
                return true;
            }
        }
        return false;
    }

    private static void clearExternalModules() {
        try {
            Field field = ModCompat.class.getDeclaredField("EXTERNAL_MODULES");
            field.setAccessible(true);
            ((List<?>) field.get(null)).clear();
        } catch (ReflectiveOperationException failure) {
            report("could not clear ModCompat.EXTERNAL_MODULES after the section: " + failure, false);
        }
    }

    private static void removeRegistered(ResourceLocation id) {
        try {
            Field field = ModCompat.class.getDeclaredField("REGISTERED");
            field.setAccessible(true);
            ((Map<?, ?>) field.get(null)).remove(id);
        } catch (ReflectiveOperationException failure) {
            report("could not remove the external module from ModCompat.REGISTERED: " + failure, false);
        }
    }

    private static void resetSealed() {
        try {
            Field field = ModCompat.class.getDeclaredField("SEALED");
            field.setAccessible(true);
            field.setBoolean(null, false);
        } catch (ReflectiveOperationException failure) {
            report("could not reset ModCompat.SEALED after the section: " + failure, false);
        }
    }

    private static void section(String title) {
        ParallelCraftCheck.section(title);
    }

    private static void check(String what, long expected, long actual) {
        ParallelCraftCheck.check(what, expected, actual);
    }

    private static void report(String line, boolean ok) {
        ParallelCraftCheck.report(line, ok);
    }

    /** 只用于占位；本节不解析、不写包，注册表只要求一个 Handler 实例。 */
    private static final class NoopHandler implements RequirementType.Handler {

        @Override
        public MachineRequirement read(JsonObject json, IOType ioType, Object where) {
            throw new UnsupportedOperationException("not parsed in B10");
        }

        @Override
        public void write(FriendlyByteBuf buf, MachineRequirement requirement) {
            throw new UnsupportedOperationException("not written in B10");
        }

        @Override
        public MachineRequirement readBody(FriendlyByteBuf buf, IOType ioType) {
            throw new UnsupportedOperationException("not read in B10");
        }

        @Override
        public RecipeModifier.Target modifierTarget() {
            return RecipeModifier.Target.ALL;
        }
    }
}
