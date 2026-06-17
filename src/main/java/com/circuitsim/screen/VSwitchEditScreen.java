package com.circuitsim.screen;

import com.circuitsim.blockentity.ComponentBlockEntity;
import com.circuitsim.network.ModMessages;
import com.circuitsim.network.VSwitchUpdatePacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Edit dialog for the voltage-controlled switch. Exposes the four SW model
 * parameters (Vt threshold, Vh hysteresis, Ron, Roff — all SI-suffix parsed),
 * the optional initial state (auto / on / off), and a manual netlist index.
 */
public class VSwitchEditScreen extends Screen {

    private final BlockPos pos;

    private EditBox vtField, vhField, ronField, roffField, numberField;
    private String  initState = "";   // "", "on", "off"
    private String  errorMessage = "";

    private static final int W = 260, H = 248;

    private static final int BG     = 0xFF1E1E1E;
    private static final int BORDER = 0xFF4A90D9;
    private static final int TITLE  = 0xFFFFD700;
    private static final int LABEL  = 0xFFFFFFFF;
    private static final int DIM    = 0xFF666666;
    private static final int SEL    = 0xFF4FC3F7;
    private static final int ERROR  = 0xFFFF6060;

    private static final int Y_VT     = 34;
    private static final int Y_VH     = 58;
    private static final int Y_RON    = 82;
    private static final int Y_ROFF   = 106;
    private static final int Y_INIT   = 132;
    private static final int Y_NUMBER = 158;

    private static final String[] INIT_STATES = {"", "on", "off"};
    private static final String[] INIT_LABELS = {"auto", "on", "off"};

    public VSwitchEditScreen(BlockPos pos) {
        super(Component.literal("VC Switch"));
        this.pos = pos;
    }

    @Override
    protected void init() {
        super.init();
        int px = (width - W) / 2;
        int py = (height - H) / 2;

        double vt = 2.5, vh = 0.0, ron = 1.0, roff = 1e12;
        int    savedNumber = 0;
        BlockEntity be = Minecraft.getInstance().level.getBlockEntity(pos);
        if (be instanceof ComponentBlockEntity cbe) {
            vt   = cbe.getSwVt();
            vh   = cbe.getSwVh();
            ron  = cbe.getSwRon();
            roff = cbe.getSwRoff();
            initState   = cbe.getSwInit();
            savedNumber = cbe.getComponentNumber();
        }

        vtField   = makeBox(px + 120, py + Y_VT,   100, ComponentEditScreen.formatValue(vt));
        vhField   = makeBox(px + 120, py + Y_VH,   100, ComponentEditScreen.formatValue(vh));
        ronField  = makeBox(px + 120, py + Y_RON,  100, ComponentEditScreen.formatValue(ron));
        roffField = makeBox(px + 120, py + Y_ROFF, 100, ComponentEditScreen.formatValue(roff));

        numberField = makeBox(px + 120, py + Y_NUMBER, 60,
                savedNumber == 0 ? "" : Integer.toString(savedNumber));
        numberField.setSuggestion(savedNumber == 0 ? "auto" : "");
        numberField.setResponder(t -> numberField.setSuggestion(t.isEmpty() ? "auto" : ""));

        addRenderableWidget(
            Button.builder(Component.literal("Save"), b -> { if (save()) onClose(); })
                .bounds(px + 20, py + H - 28, 100, 20).build());
        addRenderableWidget(
            Button.builder(Component.literal("Cancel"), b -> onClose())
                .bounds(px + W - 120, py + H - 28, 100, 20).build());
    }

    private EditBox makeBox(int x, int y, int w, String init) {
        EditBox b = new EditBox(Minecraft.getInstance().font, x, y, w, 18, Component.empty());
        b.setMaxLength(32);
        b.setValue(init);
        b.setBordered(true);
        addRenderableWidget(b);
        return b;
    }

    private boolean save() {
        errorMessage = "";
        double vt, vh, ron, roff;
        try { vt = ComponentEditScreen.parseSI(vtField.getValue().trim()); }
        catch (NumberFormatException e) { errorMessage = "Vt must be a number (e.g. 2.5)"; return false; }
        try { vh = ComponentEditScreen.parseSI(vhField.getValue().trim()); }
        catch (NumberFormatException e) { errorMessage = "Vh must be a number (e.g. 0.5)"; return false; }
        try { ron = ComponentEditScreen.parseSI(ronField.getValue().trim()); }
        catch (NumberFormatException e) { errorMessage = "Ron must be a number (e.g. 1)"; return false; }
        try { roff = ComponentEditScreen.parseSI(roffField.getValue().trim()); }
        catch (NumberFormatException e) { errorMessage = "Roff must be a number (e.g. 1T)"; return false; }
        if (ron <= 0 || roff <= 0) { errorMessage = "Ron and Roff must be positive"; return false; }
        if (vh < 0) { errorMessage = "Vh must be >= 0"; return false; }

        int num;
        try { num = Integer.parseInt(numberField.getValue().trim()); }
        catch (NumberFormatException e) { num = 0; }
        if (num < 0) num = 0;

        ModMessages.sendToServer(new VSwitchUpdatePacket(pos, vt, vh, ron, roff, initState, num));
        return true;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        int px = (width - W) / 2;
        int py = (height - H) / 2;
        for (int i = 0; i < INIT_STATES.length; i++) {
            int cx = px + 120 + i * 36;
            int cy = py + Y_INIT + 2;
            if (mx >= cx && mx <= cx + 32 && my >= cy && my <= cy + 14) {
                initState = INIT_STATES[i];
                return true;
            }
        }
        return super.mouseClicked(mx, my, btn);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        renderBackground(g);
        drawBackground(g);
        super.render(g, mx, my, pt);

        int px = (width - W) / 2;
        int py = (height - H) / 2;
        var f = Minecraft.getInstance().font;

        g.drawCenteredString(f, "Voltage-Controlled Switch", width / 2, py + 7, TITLE);
        g.drawString(f, "Vt threshold (V):",  px + 16, py + Y_VT + 5,     LABEL);
        g.drawString(f, "Vh hysteresis (V):", px + 16, py + Y_VH + 5,     LABEL);
        g.drawString(f, "Ron (Ω):",      px + 16, py + Y_RON + 5,    LABEL);
        g.drawString(f, "Roff (Ω):",     px + 16, py + Y_ROFF + 5,   LABEL);
        g.drawString(f, "Initial state:",     px + 16, py + Y_INIT + 4,   LABEL);
        g.drawString(f, "Netlist index S:",   px + 16, py + Y_NUMBER + 5, LABEL);

        for (int i = 0; i < INIT_STATES.length; i++) {
            int cx = px + 120 + i * 36;
            int cy = py + Y_INIT + 2;
            boolean sel = INIT_STATES[i].equals(initState);
            g.fill(cx, cy, cx + 32, cy + 14, sel ? 0xFF1A4A6A : 0xFF333333);
            g.fill(cx + 1, cy + 1, cx + 31, cy + 13, sel ? 0xFF2A6A9A : 0xFF444444);
            g.drawCenteredString(f, INIT_LABELS[i], cx + 16, cy + 3, sel ? SEL : DIM);
        }

        g.drawString(f, "Closes above Vt+Vh, opens below Vt-Vh.",
                px + 16, py + Y_NUMBER + 26, DIM);

        if (!errorMessage.isEmpty()) {
            g.drawCenteredString(f, errorMessage, width / 2, py + H - 44, ERROR);
        }
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
