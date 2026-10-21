package com.reborn.modularmachinery.block;

import com.reborn.modularmachinery.config.ModConfig;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.level.BlockEvent;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * A11: the original's owner check.
 *
 * <p>The original wrote an owner onto every placed controller ({@code ItemBlockController.java:35-63}), whether
 * {@code enable-security-system} was on or off. The switch only gated two actions, both in
 * {@code EventHandler}:
 *
 * <ul>
 *   <li>{@code onBlockBroken} ({@code :104-129}) cancelled a non-owner breaking a controller in survival;</li>
 *   <li>{@code onBlockPlaced} ({@code :131-159}) cancelled placing a controller item whose {@code owner} tag named
 *       someone else.</li>
 * </ul>
 *
 * <p>Its config comment promised to deny "players using the controller", but the code did <b>not</b> gate opening
 * the screen or running the machine. This port keeps the same two gates rather than inventing stronger ones; the
 * difference between the sentence and the code is recorded in {@code docs/已知限制.md}.
 *
 * <p>The decision methods are pure so the offline harness can drive every branch without a level, a player or a
 * registered block entity type. The owner is stored as a UUID string under the original's own key, {@code owner}.
 */
public final class ControllerSecurity {

    /** The original's NBT key ({@code TileMultiblockMachineController.java:1415-1417}). */
    public static final String OWNER_TAG = "owner";

    private ControllerSecurity() {
    }

    /**
     * Whether {@code player} may break a controller. {@code owner == null} means "no owner recorded" (a save from
     * before A11, or an invalid tag), which the original treated as unrestricted.
     */
    public static boolean canBreak(boolean enabled, boolean creative, @Nullable UUID owner, @Nullable UUID player) {
        if (!enabled || creative || owner == null || player == null) {
            return true;
        }
        return owner.equals(player);
    }

    /**
     * Whether {@code player} may place a controller item carrying {@code owner}. An item with no owner tag has
     * {@code owner == null} and is always placeable; the placer then becomes the owner.
     */
    public static boolean canPlace(boolean enabled, boolean creative, @Nullable UUID owner, @Nullable UUID player) {
        if (!enabled || creative || owner == null || player == null) {
            return true;
        }
        return owner.equals(player);
    }

    /** Writes the owner under the original's key; a {@code null} owner removes the key rather than writing "null". */
    public static void putOwner(CompoundTag tag, @Nullable UUID owner) {
        if (owner == null) {
            tag.remove(OWNER_TAG);
        } else {
            tag.putString(OWNER_TAG, owner.toString());
        }
    }

    /**
     * Reads the owner, or {@code null} when the key is absent or malformed.
     *
     * <p>The original caught a bad UUID and only warned ({@code TileMultiblockMachineController.java:1387-1394});
     * this returns {@code null}, which leaves the controller unrestricted instead of denying everyone.
     */
    @Nullable
    public static UUID ownerOf(CompoundTag tag) {
        if (!tag.contains(OWNER_TAG)) {
            return null;
        }
        try {
            return UUID.fromString(tag.getString(OWNER_TAG));
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    /** Whether the item carries an {@code owner} key at all (even a malformed one), matching the original's hasKey. */
    public static boolean hasOwnerTag(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag != null && tag.contains(OWNER_TAG);
    }

    /** The owner recorded on a controller item, or {@code null}. */
    @Nullable
    public static UUID ownerOf(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag == null ? null : ownerOf(tag);
    }

    /**
     * The original's {@code EventHandler#onBlockBroken}: cancel a non-owner's break, and only on the server.
     *
     * <p>Creative players are exempt, as in the original ({@code :110-111}). The event's level has to be narrowed
     * to {@link ServerLevel} before the block entity can be read; a client event must not decide this.
     */
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (!(event.getState().getBlock() instanceof MachineControllerBlock)) {
            return;
        }
        Player player = event.getPlayer();
        if (player == null) {
            return;
        }
        if (!(level.getBlockEntity(event.getPos()) instanceof MachineControllerBlockEntity controller)) {
            return;
        }
        if (!canBreak(ModConfig.enableSecuritySystem(), player.isCreative(),
                controller.getOwner(), player.getUUID())) {
            event.setCanceled(true);
        }
    }
}
