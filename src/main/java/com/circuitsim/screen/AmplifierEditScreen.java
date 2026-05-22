package com.circuitsim.screen;

import com.circuitsim.blockentity.ComponentBlockEntity;
import com.circuitsim.network.AmplifierUpdatePacket;
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
 * Edit dialog for an amplifier block (op-amp subcircuit instance).
 * Right-click any of the 25 cells to open; the {@code pos} stored here is
 * the anchor cell. Lets the player set:
 * <ul>
 *   <li>Model name — subcircuit name from the included .lib file
 *   <li>X-index — manual netlist index ({@code X<n>}), 0 = auto
 *   <li>Offset toggle — 5-pin vs 7-pin variant
 * </ul>
 */
public class AmplifierEditScreen extends Screen {

    private final BlockPos pos;

    private EditBox modelField;
    private EditBox numberField;
    private boolean offsetEnabled = false;
    private boolean mirrored      = false;

    private static final int W = 260, H = 224;

    private static final int BG     = 0xFF1E1E1E;
    private static final int BORDER = 0xFF4A90D9;
    private static final int TITLE  = 0xFFFFD700;
    private static final int LABEL  = 0xFFFFFFFF;
    private static final int DIM    = 0xFF888888;
    private static final int CHK_ON = 0xFF4FC3F7;

    // y offsets within the dialog
    private static final int Y_MODEL_LABEL  = 30;
    private static final int Y_MODEL_FIELD  = 44;
    private static final int Y_NUMBER_LABEL = 76;
    private static final int Y_NUMBER_FIELD = 90;
    private static final int Y_OFFSET_CHK   = 124;
    private static final int Y_MIRROR_CHK   = 148;

    public AmplifierEditScreen(BlockPos pos) {
        super(Component.literal("Amplifier"));
        this.pos = pos;
    }

    @Override
    protected void init() {
        super.init();
        int px = (width - W) / 2;
        int py = (height - H) / 2;

        String savedModel = "";
        int savedNumber = 0;
        BlockEntity be = Minecraft.getInstance().level.getBlockEntity(pos);
        if (be instanceof ComponentBlockEntity cbe) {
            savedModel    = cbe.getLabel();
            savedNumber   = cbe.getComponentNumber();
            offsetEnabled = cbe.isOffsetEnabled();
        }
        // Mirror lives on the anchor cell's blockstate, not the BE — read it
        // there so the dialog re-opens with the current visual state.
        var bs = Minecraft.getInstance().level.getBlockState(pos);
        if (bs.hasProperty(com.circuitsim.block.AmplifierBlock.MIRRORED)) {
            mirrored = bs.getValue(com.circuitsim.block.AmplifierBlock.MIRRORED);
        }

        modelField = makeBox(px + 16, py + Y_MODEL_FIELD, W - 32, savedModel);
        modelField.setSuggestion(savedModel.isEmpty() ? "e.g. LM741, AD648A" : "");
        modelField.setResponder(t -> modelField.setSuggestion(t.isEmpty() ? "e.g. LM741, AD648A" : ""));
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
    public boolean mouseClicked(double mx, double my, int btn) {
        int px = (width - W) / 2;
        int py = (height - H) / 2;
        if (hit(mx, my, px + 16, py + Y_OFFSET_CHK, W - 32, 14)) {
            offsetEnabled = !offsetEnabled;
            return true;
        }
        if (hit(mx, my, px + 16, py + Y_MIRROR_CHK, W - 32, 14)) {
            mirrored = !mirrored;
            return true;
        }
        return super.mouseClicked(mx, my, btn);
    }

    private static boolean hit(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        renderBackground(g);
        drawBackground(g);
        super.render(g, mx, my, pt);

        int px = (width - W) / 2;
        int py = (height - H) / 2;
        var f = Minecraft.getInstance().font;

        g.drawCenteredString(f, "Amplifier", width / 2, py + 7, TITLE);

        g.drawString(f, "Subcircuit model:", px + 16, py + Y_MODEL_LABEL, LABEL);
        g.drawString(f, "Netlist index (X<n>):", px + 16, py + Y_NUMBER_LABEL, LABEL);

        // offset toggle row
        int cx = px + 16;
        int cy = py + Y_OFFSET_CHK + 2;
        g.fill(cx, cy, cx + 10, cy + 10, 0xFF888888);
        g.fill(cx + 1, cy + 1, cx + 9, cy + 9, BG);
        if (offsetEnabled) g.fill(cx + 2, cy + 2, cx + 8, cy + 8, CHK_ON);
        g.drawString(f,
                offsetEnabled ? "7-pin variant (with offset null pins)"
                              : "5-pin variant",
                cx + 16, py + Y_OFFSET_CHK + 1,
                offsetEnabled ? CHK_ON : DIM);

        // mirror toggle row
        int mx2 = px + 16;
        int my2 = py + Y_MIRROR_CHK + 2;
        g.fill(mx2, my2, mx2 + 10, my2 + 10, 0xFF888888);
        g.fill(mx2 + 1, my2 + 1, mx2 + 9, my2 + 9, BG);
        if (mirrored) g.fill(mx2 + 2, my2 + 2, mx2 + 8, my2 + 8, CHK_ON);
        g.drawString(f,
                mirrored ? "mirrored (+/- swapped, VCC/VEE swapped)"
                         : "default orientation",
                mx2 + 16, py + Y_MIRROR_CHK + 1,
                mirrored ? CHK_ON : DIM);
    }

    private void drawBackground(GuiGraphics g) {
        int px = (width - W) / 2;
        int py = (height - H) / 2;
        g.fill(px, py, px + W, py + H, BG);
        // border
        g.fill(px, py, px + W, py + 2, BORDER);
        g.fill(px, py + H - 2, px + W, py + H, BORDER);
        g.fill(px, py, px + 2, py + H, BORDER);
        g.fill(px + W - 2, py, px + W, py + H, BORDER);
        // title divider
        g.fill(px + 2, py + 22, px + W - 2, py + 23, 0xFF444444);
    }

    private void sendPacket() {
        int num;
        try { num = Integer.parseInt(numberField.getValue().trim()); }
        catch (NumberFormatException e) { num = 0; }
        if (num < 0) num = 0;
        ModMessages.sendToServer(new AmplifierUpdatePacket(
                pos, modelField.getValue().trim(), num, offsetEnabled, mirrored));
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
