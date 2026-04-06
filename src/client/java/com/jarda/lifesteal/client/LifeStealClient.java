package com.jarda.lifesteal.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

public class LifeStealClient implements ClientModInitializer {
    private static final String OPEN_MENU_KEY = "key.lifesteal.open_menu";

    @Override
    public void onInitializeClient() {
        KeyBinding openMenuKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            OPEN_MENU_KEY,
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_G,
            KeyBinding.Category.MISC
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openMenuKey.wasPressed()) {
                openClientMenu(client);
            }
        });
    }

    private static void openClientMenu(MinecraftClient client) {
        if (client.player == null || client.currentScreen != null) {
            return;
        }
        client.setScreen(new LifeStealMenuScreen());
    }
}
