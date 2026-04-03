package com.jarda.lifesteal.mixin;

import com.jarda.lifesteal.LifeSteal;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.AnvilScreenHandler;
import net.minecraft.screen.GrindstoneScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ScreenHandler.class)
public abstract class ScreenHandlerMixin {

    @Inject(method = "onSlotClick", at = @At("HEAD"), cancellable = true)
    private void onSlotClick(int slotIndex, int button, SlotActionType actionType, PlayerEntity player, CallbackInfo ci) {
        if (!(player instanceof ServerPlayerEntity serverPlayer)) return;

        ScreenHandler handler = (ScreenHandler) (Object) this;

        // Block modifications of "Index" (unmodifiable) items in anvil/grindstone
        if (handler instanceof AnvilScreenHandler || handler instanceof GrindstoneScreenHandler) {
            if (slotIndex >= 0 && slotIndex <= 2) {
                ItemStack in0 = handler.getSlot(0).getStack();
                ItemStack in1 = handler.getSlot(1).getStack();
                ItemStack out = handler.getSlot(2).getStack();
                ItemStack clicked = handler.getSlot(slotIndex).getStack();
                if (LifeSteal.isUnmodifiable(in0) || LifeSteal.isUnmodifiable(in1) || LifeSteal.isUnmodifiable(out) || LifeSteal.isUnmodifiable(clicked)) {
                    ci.cancel();
                    serverPlayer.sendMessage(Text.literal("§cTento předmět je uzamčený (Index) a nelze jej upravit."), true);
                    handler.syncState();
                    return;
                }
            }
        }

        // Menu handling - check if this player has a menu open
        String menuType = LifeSteal.OPEN_MENUS.get(serverPlayer.getUuid());
        if (menuType != null) {
            ci.cancel();
            handler.setCursorStack(ItemStack.EMPTY);

            final int clickedSlot = slotIndex;
            serverPlayer.getCommandSource().getServer().execute(() -> {
                if (LifeSteal.OPEN_MENUS.containsKey(serverPlayer.getUuid())) {
                    LifeSteal.handleMenuClick(serverPlayer, clickedSlot);
                }
            });
            return;
        }

        // Shop handling - check if this player has a shop open
        Inventory shopInv = LifeSteal.OPEN_SHOPS.get(serverPlayer.getUuid());
        if (shopInv != null) {
            ci.cancel();
            handler.setCursorStack(ItemStack.EMPTY);

            if (slotIndex < 0) {
                return;
            }

            final int clickedSlot = slotIndex;
            serverPlayer.getCommandSource().getServer().execute(() -> {
                if (LifeSteal.OPEN_SHOPS.containsKey(serverPlayer.getUuid())) {
                    LifeSteal.handleShopClick(serverPlayer, clickedSlot);
                }
            });
            return;
        }
    }

    @Inject(method = "onClosed", at = @At("HEAD"))
    private void onClosed(PlayerEntity player, CallbackInfo ci) {
        if (player instanceof ServerPlayerEntity serverPlayer) {
            ScreenHandler handler = (ScreenHandler) (Object) this;
            if (handler instanceof GenericContainerScreenHandler) {
                LifeSteal.OPEN_SHOPS.remove(serverPlayer.getUuid());
                LifeSteal.OPEN_MENUS.remove(serverPlayer.getUuid());
            }
        }
    }
}
