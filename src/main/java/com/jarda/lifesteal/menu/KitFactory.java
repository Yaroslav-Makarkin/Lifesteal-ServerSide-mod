package com.jarda.lifesteal.menu;

import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.component.DataComponents;

/**
 * Továrna na vytváření kit itemů.
 */
public class KitFactory {
    
    /**
     * Zkontroluje, zda je item kit itemem.
     */
    public static boolean isKitItem(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        var customData = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        if (customData != null) {
            CompoundTag nbt = customData.copyTag();
            return nbt.getBoolean("lifesteal_kit") != null && nbt.getBoolean("lifesteal_kit").orElse(false);
        }
        return false;
    }
}