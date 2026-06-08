package com.circuitsim.screen;

import com.circuitsim.blockentity.ComponentBlockEntity;
import com.circuitsim.network.DiscretePnpUpdatePacket;
import com.circuitsim.network.ModMessages;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Edit dialog for a discrete PNP block. Lets the player set the {@code .SUBCKT}
 * model name (e.g. {@code Q2N3906}) and an optional manual X-index for the
 * netlist line.
 */
public class DiscretePnpEditScreen extends Screen {

    private final BlockPos pos;

    private EditBox modelField;
    private EditBox numberField;

    private static final int W = 260, H = 160;

    private static final int BG     = 0xFF1E1E1E;
    private static final int BORDER = 0xFF4A90D9;
    private static final int TITLE  = 0xFFFFD700;
    private static final int LABEL  = 0xFFFFFFFF;

    private static final int Y_MODEL_LABEL  = 30;
    private static final int Y_MODEL_FIELD  = 44;
    private static final int Y_NUMBER_LABEL = 76;
    private static final int Y_NUMBER_FIELD = 90;

    private static final String HINT = "e.g. Q2N3906, BC557";

    public DiscretePnpEditScreen(BlockPos pos) {
        super(Component.literal("Discrete PNP"));
        this.pos = pos;
    }

    @Override
    protected void init() {
        super.init();
        int px = (width - W) / 2;
        int py = (height - H) / 2;

        String savedModel = "";
        int    savedNumber = 0;
        BlockEntity be = Minecraft.getInstance().level.getBlockEntity(pos);
        if (be instanceof ComponentBlockEntity cbe) {
            savedModel  = cbe.getModelName();
            savedNumber = cbe.getComponentNumber();
        }

        modelField = makeBox(px + 16, py + Y_MODEL_FIELD, W - 32, savedModel);
        modelField.setSuggestion(savedModel.isEmpty() ? HINT : "");
        modelField.setResponder(t -> modelField.setSuggestion(t.isEmpty() ? HINT : ""));
        modelField.setMaxLength(64);

        numberField = makeBox(px + 16, py + Y_NUMBER_FIELD, 80,
                savedNumber == 0 ? "" : Integer.toString(savedNumber));
        numberField.setSuggestion(savedNumber == 0 ? "auto" : "");
        numberField.setResponder(t -> numberField.setSuggestion(t.isEmpty() ? "auto" : ""));

        addRenderableWidget(
            Button.builder(Component.literal("Save"), b -> { sendPacket(); onClose(); })
                .bounds(px + 20, py + H - 28, 100, 20).build());
        addRenderableWidget(
            Button.builder(Component.literal("Cancel"), b -> onClose())
                .bounds(px + W - 120, py + H - 28, 100, 20).build());
    }

    private EditBox makeBox(int x, int y, int w, String init) {
        EditBox b = new EditBox(Minecraft.getInstance().font, x, y, w, 18, Component.empty());
        b.setMaxLength(64);
        b.setValue(init);
        b.setBordered(true);
        addRenderableWidget(b);
        return b;
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        renderBackground(g);
        drawBackground(g);
        super.render(g, mx, my, pt);

        int px = (width - W) / 2;
        int py = (height - H) / 2;
        var f = Minecraft.getInstance().font;

        g.drawCenteredString(f, "Discrete PNP", width / 2, py + 7, TITLE);
        g.drawString(f, "BJT model name:",        px + 16, py + Y_MODEL_LABEL,  LABEL);
        g.drawString(f, "Netlist index (X<n>):", px + 16, py + Y_NUMBER_LABEL, LABEL);
    }

    private void drawBackground(GuiGraphics g) {
        int px = (width - W) / 2;
        int py = (height - H) / 2;
        g.fill(px, py, px + W, py + H, BG);
        g.fill(px, py, px + W, py + 2, BORDER);
        g.fill(px, py + H - 2, px + W, py + H, BORDER);
        g.fill(px, py, px + 2, py + H, BORDER);
        g.fill(px + W - 2, py, px + W, py + H, BORDER);
        g.fill(px + 2, py + 22, px + W - 2, py + 23, 0xFF444444);
    }

    private void sendPacket() {
        int num;
        try { num = Integer.parseInt(numberField.getValue().trim()); }
        catch (NumberFormatException e) { num = 0; }
        if (num < 0) num = 0;
        ModMessages.sendToServer(new DiscretePnpUpdatePacket(
                pos, modelField.getValue().trim(), num));
    }

    @Override
    public boolean keyPressed(int k, int s, int m) {
        if (getFocused() instanceof EditBox eb && eb.keyPressed(k, s, m)) return true;
        return super.keyPressed(k, s, m);
    }

    @Override
    public boolean charTyped(char c, int m) {
        if (getFocused() instanceof EditBox eb && eb.charTyped(c, m)) return true;
        return super.charTyped(c, m);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public boolean shouldCloseOnEsc() { return true; }
}
