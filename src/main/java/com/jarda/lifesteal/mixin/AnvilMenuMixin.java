package com.jarda.lifesteal.mixin;

import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(net.minecraft.world.inventory.AnvilMenu.class)
public abstract class AnvilMenuMixin {
    @Inject(method = "createResult", at = @At("TAIL"), cancellable = true)
    private void lifesteal$blockKitModification(CallbackInfo ci) {
        net.minecraft.world.inventory.AnvilMenu anvil = (net.minecraft.world.inventory.AnvilMenu) (Object) this;
        
        // Check slot 0 (left input)
        net.minecraft.world.item.ItemStack left = anvil.getSlot(0).getItem();
        if (com.jarda.lifesteal.menu.KitFactory.isKitItem(left)) {
            anvil.setItem(0, 2, net.minecraft.world.item.ItemStack.EMPTY);
            anvil.broadcastChanges();
            return;
        }
        
        // Check slot 1 (right input)
        net.minecraft.world.item.ItemStack right = anvil.getSlot(1).getItem();
        if (com.jarda.lifesteal.menu.KitFactory.isKitItem(right)) {
            anvil.setItem(0, 2, net.minecraft.world.item.ItemStack.EMPTY);
            anvil.broadcastChanges();
            return;
        }
    }
}