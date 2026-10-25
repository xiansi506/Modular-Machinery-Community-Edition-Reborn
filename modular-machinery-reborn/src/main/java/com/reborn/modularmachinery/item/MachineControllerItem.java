package com.reborn.modularmachinery.item;

import com.reborn.modularmachinery.block.ControllerSecurity;
import com.reborn.modularmachinery.block.MachineControllerBlockEntity;
import com.reborn.modularmachinery.config.ModConfig;
import com.reborn.modularmachinery.machine.MachineDefinition;
import com.reborn.modularmachinery.machine.MachineRegistry;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * The item form of a controller.
 *
 * <p>A bound controller is named after its machine, like the original's
 * {@code tile.modularmachinery.machinecontroller.name=%s控制器}. The machine's display name comes from its
 * data pack definition, so it is resolved at runtime; when the definition is not available (for example a
 * dedicated-server client, which does not receive machine definitions yet) the item falls back to the shipped
 * per-machine language key rather than showing a raw key.
 *
 * <p>Not final since 0.27.0: {@link MocMachineControllerItem} is this class plus the original's two deprecation
 * lines, which is exactly how the original expressed it — one item class whose tooltip consulted the block's
 * namespace ({@code BlockController.java:116-122}). A separate class rather than a namespace test is the 1.20.1
 * spelling of the same rule, because the {@code BlockItem} is what carries a block's tooltip here.
 */
public class MachineControllerItem extends BlockItem {

    @Nullable
    private final ResourceLocation boundMachine;

    public MachineControllerItem(Block block, @Nullable ResourceLocation boundMachine, Properties properties) {
        super(block, properties);
        this.boundMachine = boundMachine;
    }

    /** {@code null} for the generic controller, otherwise the machine this controller belongs to. */
    @Nullable
    public ResourceLocation boundMachine() {
        return this.boundMachine;
    }

    public boolean isGeneric() {
        return this.boundMachine == null;
    }

    /**
     * A11: enforce the controller item's owner tag before placing, then record the placer as owner.
     *
     * <p>The original did both in {@code ItemBlockController#placeBlockAt} and {@code EventHandler#onBlockPlaced}:
     * the event cancelled a non-owner placing an item whose tag named another player, and the successful placement
     * wrote the tag's owner (or the placer, when the item had no tag). Creative placement is exempt from the gate,
     * but still preserves the item's owner — the original's place event returned early for creative players.
     */
    @Override
    public InteractionResult place(BlockPlaceContext context) {
        Player player = context.getPlayer();
        ItemStack stack = context.getItemInHand();
        UUID taggedOwner = ControllerSecurity.ownerOf(stack);
        boolean hasOwnerTag = ControllerSecurity.hasOwnerTag(stack);
        if (!ControllerSecurity.canPlace(ModConfig.enableSecuritySystem(),
                player != null && player.isCreative(), taggedOwner, player == null ? null : player.getUUID())) {
            return InteractionResult.FAIL;
        }
        InteractionResult result = super.place(context);
        if (result.consumesAction() && !context.getLevel().isClientSide
                && context.getLevel().getBlockEntity(context.getClickedPos())
                        instanceof MachineControllerBlockEntity controller) {
            if (hasOwnerTag) {
                // A malformed tag reads as null and leaves the controller unowned, as the original's catch did.
                controller.setOwner(taggedOwner);
            } else if (player != null) {
                controller.setOwner(player.getUUID());
            }
        }
        return result;
    }

    @Override
    public String getDescriptionId() {
        return this.boundMachine == null
                ? super.getDescriptionId()
                : "block.modular_machinery_reborn." + this.boundMachine.getPath() + "_controller";
    }

    @Override
    public Component getName(ItemStack stack) {
        if (this.boundMachine != null) {
            MachineDefinition definition = MachineRegistry.presentationById(this.boundMachine).orElse(null);
            if (definition != null) {
                return Component.translatable("block.modular_machinery_reborn.machine_controller.named",
                        definition.displayName());
            }
        }
        return super.getName(stack);
    }
}
