package com.jarda.lifesteal.mixin;

import com.jarda.lifesteal.LifeSteal;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.AnvilScreenHandler;
import net.minecraft.screen.EnchantmentScreenHandler;
import net.minecraft.screen.GrindstoneScreenHandler;
import net.minecraft.screen.SmithingScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ScreenHandler.class)
public abstract class ScreenHandlerMixin {

    @Inject(method = "onSlotClick", at = @At("HEAD"), cancellable = true)
    private void onSlotClick(int slotIndex, int button, SlotActionType actionType, PlayerEntity player, CallbackInfo ci) {
        if (player == null) {
            return;
        }
        if (!(player instanceof ServerPlayerEntity serverPlayer)) {
            return;
        }

        ScreenHandler handler = (ScreenHandler) (Object) this;
        if (handler == null) {
            return;
        }

        // Block repair/combine/disenchant for kit-locked items.
        if (handler instanceof AnvilScreenHandler || handler instanceof GrindstoneScreenHandler || handler instanceof SmithingScreenHandler) {
            // Ensure we have enough slots to access indices 0,1,2 and slotIndex
            if (slotIndex >= 0 && slotIndex < handler.slots.size()
                    && handler.getSlot(0) != null
                    && handler.getSlot(1) != null
                    && handler.getSlot(2) != null
                    && handler.getSlot(slotIndex) != null) {
                ItemStack in0 = handler.getSlot(0).getStack();
                ItemStack in1 = handler.getSlot(1).getStack();
                ItemStack out = handler.getSlot(2).getStack();
                ItemStack clicked = handler.getSlot(slotIndex).getStack();
                if (LifeSteal.isUnmodifiable(in0) || LifeSteal.isUnmodifiable(in1) || LifeSteal.isUnmodifiable(out) || LifeSteal.isUnmodifiable(clicked)) {
                    ci.cancel();
                    serverPlayer.sendMessage(Text.literal("§cKit item nelze upravovat v kovadlině, brusce ani smithingu."), true);
                    handler.syncState();
                    return;
                }
            }
        }

        // Menu handling - check if this player has a menu open
        String menuType = LifeSteal.OPEN_MENUS.get(serverPlayer.getUuid());
        if (menuType != null) {
            ci.cancel();
            if (handler != null) {
                handler.setCursorStack(ItemStack.EMPTY);
            }

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
            if (handler != null) {
                handler.setCursorStack(ItemStack.EMPTY);
            }

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

    @Inject(method = "onButtonClick", at = @At("HEAD"), cancellable = true)
    private void onButtonClick(PlayerEntity player, int id, CallbackInfoReturnable<Boolean> cir) {
        if (player == null) {
            return;
        }
        if (!(player instanceof ServerPlayerEntity serverPlayer)) {
            return;
        }

        ScreenHandler handler = (ScreenHandler) (Object) this;
        if (handler == null) {
            return;
        }
        if (handler instanceof EnchantmentScreenHandler) {
            // Ensure slot 0 exists
            if (handler.getSlot(0) != null && !handler.getSlot(0).getStack().isEmpty()) {
                ItemStack toEnchant = handler.getSlot(0).getStack();
                if (LifeSteal.isUnmodifiable(toEnchant)) {
                    serverPlayer.sendMessage(Text.literal("§cKit item nelze enchantit."), true);
                    if (handler != null) {
                        handler.syncState();
                    }
                    cir.setReturnValue(false);
                }
            }
        }
    }

    @Inject(method = "onClosed", at = @At("HEAD"))
    private void onClosed(PlayerEntity player, CallbackInfo ci) {
        if (player == null) {
            return;
        }
        if (!(player instanceof ServerPlayerEntity serverPlayer)) {
            return;
        }

        ScreenHandler handler = (ScreenHandler) (Object) this;
        if (handler == null) {
            return;
        }
        if (handler instanceof GenericContainerScreenHandler) {
            LifeSteal.OPEN_SHOPS.remove(serverPlayer.getUuid());
            LifeSteal.OPEN_MENUS.remove(serverPlayer.getUuid());
        }
    }
}
