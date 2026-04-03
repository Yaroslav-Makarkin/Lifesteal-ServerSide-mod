package com.jarda.lifesteal.mixin;

import net.minecraft.block.FarmlandBlock;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FarmlandBlock.class)
public abstract class FarmlandMixin {
    @Inject(method = "setToDirt", at = @At("HEAD"), cancellable = true)
    private static void lifesteal$noTrample(Entity entity, BlockState state, World world, BlockPos pos, CallbackInfo ci) {
        ci.cancel();
    }
}
