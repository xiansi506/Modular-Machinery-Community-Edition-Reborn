package com.reborn.modularmachinery.command;

import com.reborn.modularmachinery.authoring.RecipeEditorService;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.Commands;
import net.minecraftforge.event.RegisterCommandsEvent;

public final class RecipeEditorCommand {
    private RecipeEditorCommand() {}
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("mm-recipe_editor").requires(source->source.hasPermission(2))
            .executes(ctx->{ RecipeEditorService.open(ctx.getSource().getPlayerOrException(),""); return 1; })
            .then(Commands.argument("machine",StringArgumentType.string()).suggests((ctx,builder)->net.minecraft.commands.SharedSuggestionProvider.suggest(com.reborn.modularmachinery.machine.MachineRegistry.all().stream().map(m->m.id().toString()),builder))
                .executes(ctx->{ RecipeEditorService.open(ctx.getSource().getPlayerOrException(),StringArgumentType.getString(ctx,"machine")); return 1; })));
    }
}
