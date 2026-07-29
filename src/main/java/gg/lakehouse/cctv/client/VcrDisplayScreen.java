package gg.lakehouse.cctv.client;

import gg.lakehouse.cctv.network.PacketHandler;
import gg.lakehouse.cctv.network.ServerboundVcrDisplayPacket;
import gg.lakehouse.cctv.vcr.VcrBlockEntity;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Placeholder GUI: type the deck's front-panel text by hand. Real art later. */
public class VcrDisplayScreen extends Screen {
    private final BlockPos pos;
    private final String initial;
    private EditBox textBox;

    public VcrDisplayScreen(BlockPos pos, String text) {
        super(Component.literal("VCR Display"));
        this.pos = pos;
        this.initial = text;
    }

    @Override
    protected void init() {
        int centerX = width / 2;
        int row = height / 2 - 10;
        textBox = new EditBox(font, centerX - 90, row, 180, 20, Component.literal("Display text"));
        textBox.setMaxLength(VcrBlockEntity.DISPLAY_CELLS);
        textBox.setValue(initial);
        addRenderableWidget(textBox);
        addRenderableWidget(Button.builder(Component.literal("Set"), button -> send(textBox.getValue()))
            .bounds(centerX - 90, row + 26, 88, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Auto"), button -> send(""))
            .bounds(centerX + 2, row + 26, 88, 20).build());
        setInitialFocus(textBox);
    }

    private void send(String text) {
        PacketHandler.CHANNEL.sendToServer(new ServerboundVcrDisplayPacket(pos, text));
        onClose();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) && textBox.isFocused()) {
            send(textBox.getValue());
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.drawCenteredString(font, title, width / 2, height / 2 - 44, 0xFFFFFF);
        graphics.drawCenteredString(font, Component.literal("12 characters max; Auto restores the counter"),
            width / 2, height / 2 - 30, 0xA0A0A0);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
