package com.jarda.lifesteal.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

public class LifeStealMenuScreen extends Screen {
    private static final int BUTTON_WIDTH = 180;
    private static final int BUTTON_HEIGHT = 20;

    protected LifeStealMenuScreen() {
        super(Text.literal("LifeSteal Menu"));
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int centerY = this.height / 2;

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Open Server Menu (/menu)"), button -> {
            sendServerCommand("menu");
            close();
        }).dimensions(centerX - (BUTTON_WIDTH / 2), centerY - 28, BUTTON_WIDTH, BUTTON_HEIGHT).build());

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Open Shop (/shop)"), button -> {
            sendServerCommand("shop");
            close();
        }).dimensions(centerX - (BUTTON_WIDTH / 2), centerY - 2, BUTTON_WIDTH, BUTTON_HEIGHT).build());

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Close"), button -> close())
            .dimensions(centerX - (BUTTON_WIDTH / 2), centerY + 24, BUTTON_WIDTH, BUTTON_HEIGHT)
            .build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);

        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, (this.height / 2) - 52, 0xFFFFFF);
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Quick access to LifeSteal actions"), this.width / 2, (this.height / 2) - 40, 0xA0A0A0);
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Press G to open this menu"), this.width / 2, (this.height / 2) + 52, 0x808080);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private void sendServerCommand(String command) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            return;
        }
        client.player.networkHandler.sendChatCommand(command);
    }
}
