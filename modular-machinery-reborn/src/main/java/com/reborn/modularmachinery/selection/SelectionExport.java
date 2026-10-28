package com.reborn.modularmachinery.selection;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Exports selected world blocks as north-oriented structure fragments. Positions and native block states
 * use the same rotation. NBT is a literal, typed snapshot; hand-written regex/range templates are unchanged.
 * Unloaded positions are counted without loading chunks, and an incomplete plan cannot be saved.
 */
public final class SelectionExport {

    private SelectionExport() {
    }

    /**
     * How many counter-clockwise quarter turns take {@code facing} to north — the number of rotations the
     * original applied before writing ({@code PlayerStructureSelectionHelper.java:85-94}).
     *
     * <p>Vertical directions cannot occur: a controller's {@code FACING} is
     * {@code BlockStateProperties.HORIZONTAL_FACING}. The original's
     * {@code while (face != NORTH) { face = face.rotateYCCW(); } } would spin forever on one, since
     * {@code UP.rotateYCCW() == UP}; this returns 0 instead, which is a guard against a loop the original only
     * escaped because its controller could not face up either.
     */
    public static int rotationSteps(Direction facing) {
        if (facing.getAxis() == Direction.Axis.Y) {
            return 0;
        }
        int steps = 0;
        Direction current = facing;
        while (current != Direction.NORTH) {
            current = current.getCounterClockWise();
            steps++;
        }
        return steps;
    }

    /**
     * One quarter turn counter-clockwise, exactly the original's {@code MiscUtils.rotateYCCW}:
     * {@code (x, y, z) -> (z, y, -x)}. The same rule as this project's
     * {@code MachinePattern.rotateYCounterClockwise}, which is what makes the fragment load unchanged.
     */
    public static BlockPos rotate(BlockPos pos, int steps) {
        BlockPos out = pos;
        for (int i = 0; i < Math.floorMod(steps, 4); i++) {
            out = new BlockPos(out.getZ(), out.getY(), -out.getX());
        }
        return out;
    }

    /**
     * The fragment's positions: each selected position relative to the controller, in the selection's own order,
     * rotated as if the controller faced north.
     *
     * <p>The controller's own position, if the player selected it too, becomes {@code (0,0,0)} and stays there —
     * the original kept it, and this project's loader drops it at load time
     * ({@code MachineSchema.java:231-234}, "the controller occupies the origin").
     */
    public static List<BlockPos> offsets(List<BlockPos> selection, BlockPos controller, Direction facing) {
        int steps = rotationSteps(facing);
        List<BlockPos> out = new ArrayList<>(selection.size());
        for (BlockPos pos : selection) {
            out.add(rotate(pos.subtract(controller), steps));
        }
        return List.copyOf(out);
    }

    /**
     * One part per position, dropping the ones whose sample is {@code null}, in order.
     *
     * @throws IllegalArgumentException when the two lists are different lengths — a caller that zipped them
     *                                  wrongly would otherwise write a fragment whose positions and blocks do
     *                                  not correspond, which is worse than a crash here
     */
    public static List<ExportPart> parts(List<BlockPos> offsets, List<BlockSample> samples) {
        if (offsets.size() != samples.size()) {
            throw new IllegalArgumentException("The construct tool was given " + offsets.size() + " position(s) "
                    + "but " + samples.size() + " sample(s); they are read pairwise, so they must correspond");
        }
        List<ExportPart> out = new ArrayList<>(offsets.size());
        for (int i = 0; i < offsets.size(); i++) {
            BlockSample sample = samples.get(i);
            if (sample == null) {
                continue;
            }
            out.add(new ExportPart(offsets.get(i), sample.descriptor(), sample.nbtJson()));
        }
        return List.copyOf(out);
    }

    /**
     * The descriptor a machine definition writes for one block state: the block's registry name plus every
     * property, in name order.
     *
     * <p>Uses {@code BuiltInRegistries.BLOCK} rather than {@code ForgeRegistries.BLOCKS}: both name the same
     * registry at runtime, and the vanilla one is the one this project can also read offline, which is what makes
     * the round trip assertable (harness section AA's registry probe).
     *
     * @throws IllegalStateException when the block is not registered at all, in which case no descriptor could
     *                              ever match it. {@link #sampleAt} turns that into "position unavailable"
     */
    public static String describe(BlockState state) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (id == null) {
            throw new IllegalStateException("The block " + state.getBlock() + " has no registry name, so no "
                    + "descriptor could be written for it");
        }
        StringBuilder out = new StringBuilder(id.toString());
        List<Property<?>> properties = new ArrayList<>(state.getProperties());
        if (properties.isEmpty()) {
            return out.toString();
        }
        properties.sort(Comparator.comparing(Property::getName));
        out.append('[');
        for (int i = 0; i < properties.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            appendProperty(out, state, properties.get(i));
        }
        return out.append(']').toString();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void appendProperty(StringBuilder out, BlockState state, Property<?> property) {
        out.append(property.getName()).append('=');
        out.append(((Property) property).getName(state.getValue((Property) property)));
    }

    /** The original removed the coordinates a tile entity writes about itself ({@code :170-172}). */
    public static CompoundTag withoutCoordinates(CompoundTag tag) {
        CompoundTag out = tag.copy();
        out.remove("x");
        out.remove("y");
        out.remove("z");
        return out;
    }

    /** Literal snapshots are separate from hand-written regex/range templates. */
    public static String nbtToJson(CompoundTag tag) {
        return com.reborn.modularmachinery.recipe.LiteralNbt.encode(tag).toString();
    }

    /** A complete write is the commit point: failures retain every selected position. */
    public static java.nio.file.Path saveComplete(StructureSelection selection, ExportPlan plan,
                                                  java.nio.file.Path directory, String fileName)
            throws java.io.IOException {
        if (plan.unavailable() != 0 || plan.parts().isEmpty()) {
            throw new java.io.IOException("The selection is incomplete or empty; no fragment was written.");
        }
        java.nio.file.Path written = MachineFragmentWriter.write(directory, fileName, toMachineJson(plan.parts()));
        selection.clear();
        return written;
    }

    /** Only parts are exported. Files live outside machinery/ until the author supplies a full definition. */
    public static String toMachineJson(List<ExportPart> parts) {
        String newline = System.lineSeparator();
        String move = "    ";
        StringBuilder out = new StringBuilder();
        out.append('{').append(newline);
        out.append(move).append("\"parts\": [").append(newline);
        for (int i = 0; i < parts.size(); i++) {
            ExportPart part = parts.get(i);
            BlockPos pos = part.position();
            out.append(move).append(move).append('{').append(newline);
            out.append(move).append(move).append(move).append("\"x\": ").append(pos.getX()).append(',')
                    .append(newline);
            out.append(move).append(move).append(move).append("\"y\": ").append(pos.getY()).append(',')
                    .append(newline);
            out.append(move).append(move).append(move).append("\"z\": ").append(pos.getZ()).append(',')
                    .append(newline);
            if (part.nbtJson() != null) {
                out.append(move).append(move).append(move).append("\"nbt\": ").append(part.nbtJson())
                        .append(',').append(newline);
            }
            out.append(move).append(move).append(move).append("\"elements\": [").append(newline);
            out.append(move).append(move).append(move).append(move).append('"').append(part.descriptor())
                    .append('"').append(newline);
            out.append(move).append(move).append(move).append(']').append(newline);
            out.append(move).append(move).append('}');
            if (i < parts.size() - 1) {
                out.append(',');
            }
            out.append(newline);
        }
        out.append(move).append(']');
        out.append('}');
        return out.toString();
    }

    // =====================================================================================================
    // The level-facing half: everything above is pure, everything below needs the world.
    // =====================================================================================================

    /**
     * What the tool is about to write, and what it had to leave out.
     *
     * @param parts       the fragment's entries, in selection order
     * @param selected    how many positions were selected
     * @param unavailable how many of them could not be read (see {@link #sampleAt})
     */
    public record ExportPlan(List<ExportPart> parts, int selected, int unavailable) {
    }

    /**
     * The whole export for one finalize click.
     *
     * <p><b>One deliberate divergence from the original.</b> {@code compressAsArray} called
     * {@code world.getBlockState(pos)} for every selected position; in 1.20.1 that method loads the chunk
     * ({@code Level.getChunkAt}), on the server thread, for a position the player may have selected minutes ago.
     * This project's structure matching already refuses to do that ({@code MachinePattern.matches} guards with
     * {@code level.isLoaded}), so a position in an unloaded chunk is <b>skipped and counted</b> rather than
     * silently read as air — a fragment that says {@code minecraft:air} at a position would be a wrong definition
     * that looks deliberate. The caller reports the count and retains the selection; the original had no such case because it
     * simply loaded the chunk.
     */
    public static ExportPlan plan(Level level, List<BlockPos> selection, BlockPos controller, Direction facing) {
        List<BlockSample> samples = new ArrayList<>(selection.size());
        int unavailable = 0;
        for (BlockPos pos : selection) {
            BlockSample sample = sampleAt(level, pos, rotationSteps(facing));
            if (sample == null) {
                unavailable++;
            }
            samples.add(sample);
        }
        return new ExportPlan(parts(offsets(selection, controller, facing), samples), selection.size(), unavailable);
    }

    /**
     * One position's block and tile entity, or {@code null} when it cannot be read: an unloaded chunk, or a block
     * with no registry name (neither of which can appear in a definition).
     *
     * <p>The NBT source is {@code BlockEntity.saveWithoutMetadata()}, the 1.20.1 counterpart of the original's
     * {@code te.writeToNBT(cmp)}, and the coordinates are removed exactly as the original removed them.
     */
    @Nullable
    public static BlockSample sampleAt(Level level, BlockPos pos) {
        return sampleAt(level, pos, 0);
    }

    @Nullable
    public static BlockSample sampleAt(Level level, BlockPos pos, int steps) {
        if (!level.isLoaded(pos)) {
            return null;
        }
        String descriptor;
        try {
            BlockState state = level.getBlockState(pos);
            for (int turn = 0; turn < Math.floorMod(steps, 4); turn++) {
                state = state.rotate(net.minecraft.world.level.block.Rotation.COUNTERCLOCKWISE_90);
            }
            descriptor = describe(state);
        } catch (IllegalStateException exception) {
            return null;
        }
        BlockEntity entity = level.getBlockEntity(pos);
        // The casing block entity stores only the runtime tint copied from its formed machine controller.
        // That value belongs to the machine definition, not to the exported structure fragment.
        String nbt = entity == null || entity instanceof com.reborn.modularmachinery.block.MachineCasingBlockEntity
                ? null : nbtToJson(withoutCoordinates(entity.saveWithoutMetadata()));
        return new BlockSample(descriptor, nbt);
    }
}
