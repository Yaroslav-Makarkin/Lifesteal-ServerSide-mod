package com.jarda.lifesteal.mixin;

import com.jarda.lifesteal.LifeSteal;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CommandManager.class)
public abstract class CommandManagerMixin {

    @Inject(method = "parseAndExecute", at = @At("HEAD"), cancellable = true)
    private void lifesteal$guardCannonSetblock(ServerCommandSource source, String command, CallbackInfo ci) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) return;
        if (!LifeSteal.isCannonAccessPlayer(player)) return;

        if (LifeSteal.isBlockedCannonSetblockCommand(command)) {
            source.sendError(Text.literal("§cBěhem schválení kanónu nemůžeš použít /setblock na tento blok."));
            ci.cancel();
        }
    }
}
