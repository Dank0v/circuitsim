package com.circuitsim.screen;

import com.circuitsim.blockentity.ComponentBlockEntity;
import com.circuitsim.network.ControlledSourceUpdatePacket;
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
 * Edit dialog for a VCVS/VCCS 2×3 controlled-source instance. Right-click any
 * of the 6 cells to open; {@code pos} is the anchor cell. Lets the player set:
 * <ul>
 *   <li>Value — voltage gain (VCVS) or transconductance in S (VCCS)
 *   <li>Netlist index — manual {@code E<n>}/{@code G<n>}, 0 = auto
 * </ul>
 */
public class ControlledSourceEditScreen extends Screen {

    private final BlockPos pos;
    private final String displayName;
    private final String valueLabel;

    private EditBox valueField;
    private EditBox numberField;

    private static final int W = 260, H = 160;

    private static final int BG     = 0xFF1E1E1E;
    private static final int BORDER = 0xFF4A90D9;
    private static final int TITLE  = 0xFFFFD700;
    private static final int LABEL  = 0xFFFFFFFF;

    private static final int Y_VALUE_LABEL  = 30;
    private static final int Y_VALUE_FIELD  = 44;
    private static final int Y_NUMBER_LABEL = 76;
    private static final int Y_NUMBER_FIELD = 90;

    public ControlledSourceEditScreen(BlockPos pos, String displayName, String valueLabel) {
        super(Component.literal(displayName));
        this.pos         = pos;
        this.displayName = displayName;
        this.valueLabel  = valueLabel;
    }

    @Override
    protected void init() {
        super.init();
        int px = (width - W) / 2;
        int py = (height - H) / 2;

        double savedValue = 0.0;
        int savedNumber = 0;
        BlockEntity be = Minecraft.getInstance().level.getBlockEntity(pos);
        if (be instanceof ComponentBlockEntity cbe) {
            savedValue  = cbe.getValue();
            savedNumber = cbe.getComponentNumber();
        }

        valueField = makeBox(px + 16, py + Y_VALUE_FIELD, W - 32,
                ComponentEditScreen.formatValue(savedValue));
        valueField.setMaxLength(32);

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

        setInitialFocus(valueField);
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

        g.drawCenteredString(f, displayName, width / 2, py + 7, TITLE);
        g.drawString(f, valueLabel + ":",       px + 16, py + Y_VALUE_LABEL,  LABEL);
        g.drawString(f, "Netlist index (blank = auto):", px + 16, py + Y_NUMBER_LABEL, LABEL);
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
        double value;
        try { value = ComponentEditScreen.parseSI(valueField.getValue()); }
        catch (NumberFormatException e) { value = 0; }

        int num;
        try { num = Integer.parseInt(numberField.getValue().trim()); }
        catch (NumberFormatException e) { num = 0; }
        if (num < 0) num = 0;

        ModMessages.sendToServer(new ControlledSourceUpdatePacket(pos, value, num));
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
