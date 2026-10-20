import com.reborn.modularmachinery.machine.HatchCollection;
import com.reborn.modularmachinery.compat.BlockEntityPortMapper;
import com.reborn.modularmachinery.compat.CompatModule;
import com.reborn.modularmachinery.compat.CompatRegistry;
import com.reborn.modularmachinery.compat.ModCompat;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Map;

/**
 * Runs with the harness classpath minus every jar whose path contains "mekanism".
 * Question: what does the main module do when Mekanism (an optional mod) is absent?
 *
 * <p>X4 (survey docs/勘测-1.12.2-vs-0.62.9.md:212): the main module used to keep
 * {@code List<IGasHandler>} in HatchCollection, so the moment any mapper supplied a port the
 * gasPorts loop had to resolve IGasHandler and the class load / call died with
 * NoClassDefFoundError. This probe registers a mapper whose handler is a plain Object and then
 * asks the main module to collect ports.
 */
public final class NoMekProbe {

    public static void main(String[] args) {
        System.out.println("== class load ==");
        try {
            Class<?> cls = Class.forName("com.reborn.modularmachinery.machine.HatchCollection");
            System.out.println("LOADED " + cls.getName() + " definedBy=" + cls.getClassLoader());
        } catch (Throwable t) {
            System.out.println("LOAD FAILED " + t.getClass().getName() + ": " + t.getMessage());
            return;
        }

        System.out.println("== ordinary collection ==");
        try {
            HatchCollection empty = HatchCollection.builder().build();
            System.out.println("built isEmpty=" + empty.isEmpty() + " hatchCount=" + empty.hatchCount());
            System.out.println("withTag ok describeRows=" + empty.withTag("line_1").describePorts().size());
            System.out.println("gasInputs=" + empty.gasInputs().size() + " gasOutputs=" + empty.gasOutputs().size());
        } catch (Throwable t) {
            System.out.println("ORDINARY FAILED " + t.getClass().getName() + ": " + t.getMessage());
        }

        System.out.println("== gasPorts with no mapper registered ==");
        try {
            HatchCollection p = HatchCollection.builder().gasPorts(new Object(), true, null).build();
            System.out.println("OK gas inputs=" + p.gasInputs().size());
        } catch (Throwable t) {
            System.out.println("GASPORT FAILED " + t.getClass().getName() + ": " + t.getMessage());
        }

        System.out.println("== register a mapper that returns one handler, then gasPorts ==");
        try {
            List<CompatModule> modules = List.of(new ProbeModule());
            List<CompatModule> registered = ModCompat.register(modules, id -> true);
            System.out.println("registered modules=" + registered.size()
                    + " portMappers=" + ModCompat.portMapperCount());
            System.out.println("handlers(all)=" + ModCompat.portHandlers(new Object()).size()
                    + " handlers(gas)=" + ModCompat.portHandlers(new Object(), "gas").size()
                    + " handlers(aura)=" + ModCompat.portHandlers(new Object(), "aura").size());
            HatchCollection p = HatchCollection.builder().gasPorts(new Object(), true, null).build();
            System.out.println("OK gas inputs=" + p.gasInputs().size()
                    + (p.gasInputs().isEmpty() ? "" : " storedType=" + p.gasInputs().get(0).getClass().getName()));
        } catch (Throwable t) {
            System.out.println("GASPORT WITH MAPPER FAILED " + t.getClass().getName() + ": " + t.getMessage());
        }
    }

    public static final class ProbeModule implements CompatModule {
        @Override
        public ResourceLocation id() {
            return new ResourceLocation("probe", "gas");
        }

        @Override
        public void register(CompatRegistry registry) {
        }

        @Override
        public void ports(CompatRegistry registry) {
            registry.port(new ProbeMapper());
        }
    }

    public static final class ProbeMapper implements BlockEntityPortMapper {
        @Override
        public net.minecraftforge.common.util.LazyOptional<?> map(
                net.minecraftforge.common.capabilities.Capability<?> capability, Object entity) {
            return null;
        }

        @Override
        public List<?> handlers(Object entity, String family) {
            // Answers only for its own family: "gas" (or null = every family).
            if (family != null && !"gas".equals(family)) {
                return List.of();
            }
            return List.of(new Object());
        }

        @Override
        public Map<String, String> capabilityNames() {
            return Map.of();
        }
    }
}
