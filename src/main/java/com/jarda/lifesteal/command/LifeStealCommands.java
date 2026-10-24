package com.jarda.lifesteal.command;

import com.jarda.lifesteal.LifeSteal;
import com.jarda.lifesteal.data.PlayerDataManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;

import java.util.Collection;

public class LifeStealCommands {
    private static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher, net.minecraft.commands.CommandBuildContext context) {
        dispatcher.register(Commands.literal("lifesteal")
            .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)) // Operators only
            .then(Commands.literal("sethearts")
                .then(Commands.argument("target", EntityArgument.player())
                    .then(Commands.argument("hearts", IntegerArgumentType.integer())
                        .executes(LifeStealCommands::executeSetHearts)
                    )
                )
            )
        );
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, net.minecraft.commands.CommandBuildContext context, Object selection) {
        registerCommands(dispatcher, context);
    }

    private static int executeSetHearts(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        Collection<ServerPlayer> targets;
        try {
            targets = EntityArgument.getPlayers(context, "target");
        } catch (CommandSyntaxException e) {
            source.sendFailure(Component.literal("§cHráč nenalezen"));
            return 0;
        }
        int hearts = IntegerArgumentType.getInteger(context, "hearts");

        PlayerDataManager dataManager = LifeSteal.getPlayerDataManager();
        if (dataManager == null) {
            source.sendFailure(Component.literal("§cSystém dat není inicializován"));
            return 0;
        }

        int minHearts = dataManager.getConfig().getMinHearts();
        int maxHearts = dataManager.getConfig().getMaxHearts();

        if (hearts < minHearts) {
            hearts = minHearts;
            source.sendSuccess(() -> Component.literal("§eHodnota oříznuta na minimum: " + minHearts), false);
        } else if (hearts > maxHearts) {
            hearts = maxHearts;
            source.sendSuccess(() -> Component.literal("§eHodnota oříznuta na maximum: " + maxHearts), false);
        }

        int finalHearts = hearts;
        for (ServerPlayer target : targets) {
            java.util.UUID uuid = target.getUUID();
            dataManager.setHearts(uuid, finalHearts);
            dataManager.applyMaxHealth(target);
            source.sendSuccess(() -> Component.literal("§aNastaveno " + finalHearts + " srdcí pro " + target.getName().getString()), false);
            target.sendSystemMessage(Component.literal("§aTvé srdce byla nastavena na " + finalHearts + " administrátolem"));
        }

        return targets.size();
    }
}