package com.jarda.lifesteal.menu;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.core.NonNullList;

import java.util.function.Consumer;

/**
 * Základní třída pro zamčené GUI (chest-style), které ignoruje všechny typy kliknutí
 * kromě povolených callbacků na konkrétních slotech.
 */
public abstract class LockedGuiMenu extends AbstractContainerMenu {
    private final ServerPlayer player;
    private final SimpleContainer container;
    private final Consumer<ClickContext> clickCallback;

    protected LockedGuiMenu(MenuType<?> type, int containerId, ServerPlayer player, int rows, Consumer<ClickContext> clickCallback) {
        super(type, containerId);
        this.player = player;
        this.container = new SimpleContainer(rows * 9);
        this.clickCallback = clickCallback;
        
        // Přidáme sloty do kontejneru
        for (int i = 0; i < rows * 9; i++) {
            final int slotIndex = i;
            this.addSlot(new LockedSlot(this.container, i, 0, 0) {
                public boolean mayPickup(ServerPlayer player) {
                    return false;
                }
                
                public boolean mayPlace(ItemStack stack) {
                    return false;
                }
            });
        }
    }

    public boolean stillValid(ServerPlayer player) {
        return true;
    }

    public ItemStack quickMoveStack(ServerPlayer player, int index) {
        return ItemStack.EMPTY;
    }

    /**
     * Nastaví item na daný slot a posílá aktualizaci klientovi.
     */
    public void setItem(int slot, ItemStack stack) {
        this.container.setItem(slot, stack);
        this.broadcastChanges();
    }

    /**
     * Aktualizuje jen jeden slot u klienta.
     */
    public void refreshSlot(int slot) {
        ItemStack stack = this.container.getItem(slot);
        player.connection.send(new ClientboundContainerSetSlotPacket(this.containerId, this.incrementStateId(), slot, stack));
    }

    /**
     * Posílá celý obsah kontejneru klientovi.
     */
    public void refreshAll() {
        net.minecraft.core.NonNullList<ItemStack> items = net.minecraft.core.NonNullList.withSize(this.container.getContainerSize(), ItemStack.EMPTY);
        for (int i = 0; i < this.container.getContainerSize(); i++) {
            items.set(i, this.container.getItem(i));
        }
        player.connection.send(new ClientboundContainerSetContentPacket(this.containerId, this.incrementStateId(), items, ItemStack.EMPTY));
    }

    public boolean clickMenu(ServerPlayer player, int slotId, int button, net.minecraft.world.inventory.ClickAction action) {
        // Ignorujeme všechny typy kliknutí
        if (slotId < 0) {
            return false;
        }
        
        // Zkontrolujeme, zda máme callback pro tento slot
        if (clickCallback != null && slotId < this.container.getContainerSize()) {
            ClickContext context = new ClickContext(slotId, action, this);
            clickCallback.accept(context);
        }
        
        // Resync klienta aby nic neviděl jako posunuté
        this.refreshAll();
        return true;
    }

    /**
     * Kontext kliknutí pro callback.
     */
    public static class ClickContext {
        private final int slot;
        private final net.minecraft.world.inventory.ClickAction action;
        private final LockedGuiMenu menu;

        public ClickContext(int slot, net.minecraft.world.inventory.ClickAction action, LockedGuiMenu menu) {
            this.slot = slot;
            this.action = action;
            this.menu = menu;
        }

        public int getSlot() { return slot; }
        public net.minecraft.world.inventory.ClickAction getAction() { return action; }
        public LockedGuiMenu getMenu() { return menu; }
        
        public boolean isLeftClick() { return "PICKUP".equals(action.name()); }
        public boolean isRightClick() { return "QUICK_MOVE".equals(action.name()) || "SECONDARY".equals(action.name()); }
        public boolean isShiftClick() { return "QUICK_MOVE".equals(action.name()); }
    }

    /**
     * Slot, který nelze manipulovat.
     */
    private static class LockedSlot extends net.minecraft.world.inventory.Slot {
        public LockedSlot(net.minecraft.world.Container container, int index, int x, int y) {
            super(container, index, x, y);
        }
        
        public boolean mayPickup(ServerPlayer player) {
            return false;
        }
        
        public boolean mayPlace(net.minecraft.world.item.ItemStack stack) {
            return false;
        }
    }
}