package com.circuitsim.screen;

import com.circuitsim.network.ModMessages;
import com.circuitsim.network.SubcircuitConvertPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * Client-side screen for the Subcircuit Converter. The player types a name and
 * clicks "Convert to Subcircuit" (sends a {@link SubcircuitConvertPacket}) or
 * "Close". All validation and the actual conversion run server-side.
 */
public class SubcircuitConverterScreen extends Screen {

    private final BlockPos pos;
    private EditBox nameField;

    private static final int PANEL_W = 220;
    private static final int PANEL_H = 130;

    public SubcircuitConverterScreen(BlockPos pos) {
        super(Component.literal("Subcircuit Converter"));
        this.pos = pos;
    }

    @Override
    protected void init() {
        int left = (this.width - PANEL_W) / 2;
        int top = (this.height - PANEL_H) / 2;

        nameField = new EditBox(this.font, left + 12, top + 40, PANEL_W - 24, 18,
                Component.literal("Subcircuit name"));
        nameField.setMaxLength(48);
        nameField.setHint(Component.literal("subcircuit name (e.g. myamp)"));
        addRenderableWidget(nameField);
        setInitialFocus(nameField);

        addRenderableWidget(Button.builder(Component.literal("Convert to Subcircuit"), b -> {
            ModMessages.sendToServer(new SubcircuitConvertPacket(pos, nameField.getValue().trim()));
            onClose();
        }).bounds(left + 12, top + 72, PANEL_W - 24, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(left + 12, top + 98, PANEL_W - 24, 20).build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        int left = (this.width - PANEL_W) / 2;
        int top = (this.height - PANEL_H) / 2;
        g.fill(left, top, left + PANEL_W, top + PANEL_H, 0xFF1E1E1E);
        g.fill(left, top, left + PANEL_W, top + 1, 0xFF4A90D9);
        g.fill(left, top + PANEL_H - 1, left + PANEL_W, top + PANEL_H, 0xFF4A90D9);
        g.fill(left, top, left + 1, top + PANEL_H, 0xFF4A90D9);
        g.fill(left + PANEL_W - 1, top, left + PANEL_W, top + PANEL_H, 0xFF4A90D9);

        g.drawCenteredString(this.font, this.title, this.width / 2, top + 10, 0xFFFFD700);
        g.drawString(this.font, "Name:", left + 12, top + 30, 0xFFFFFFFF, false);
        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
