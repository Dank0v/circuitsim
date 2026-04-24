package com.circuitsim.screen;

import com.circuitsim.network.ModMessages;
import com.circuitsim.network.SimulatePacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

public class SimulateEditScreen extends Screen {

    private final BlockPos pos;
    private String analysis = "OP";

    private EditBox fStartField;
    private EditBox fStopField;
    private EditBox ptsDecField;

    private static final int W = 290, H = 200;
    private static final int BG     = 0xFF1E1E1E;
    private static final int BORDER = 0xFF4A90D9;
    private static final int TITLE  = 0xFFFFD700;
    private static final int LABEL  = 0xFFFFFFFF;
    private static final int DIM    = 0xFF666666;
    private static final int SEL    = 0xFF4FC3F7;

    public SimulateEditScreen(BlockPos pos) {
        super(Component.literal("Circuit Analysis"));
        this.pos = pos;
    }

    @Override
    protected void init() {
        super.init();
        int px = (width - W) / 2, py = (height - H) / 2;

        fStartField = box(px + 166, py + 88,  90, "10");
        fStopField  = box(px + 166, py + 111, 90, "1Meg");
        ptsDecField = box(px + 166, py + 134, 90, "10");
        refreshFields();

        addRenderableWidget(
            Button.builder(Component.literal("Simulate"), b -> { sendPacket(); onClose(); })
                  .bounds(px + 20, py + H - 30, 110, 20).build());
        addRenderableWidget(
            Button.builder(Component.literal("Cancel"), b -> onClose())
                  .bounds(px + 160, py + H - 30, 110, 20).build());
    }

    private EditBox box(int x, int y, int w, String init) {
        EditBox b = new EditBox(Minecraft.getInstance().font, x, y, w, 18, Component.empty());
        b.setValue(init);
        b.setMaxLength(24);
        b.setBordered(true);
        addRenderableWidget(b);
        return b;
    }

    private void refreshFields() {
        boolean ac = "AC".equals(analysis);
        fStartField.setEditable(ac); fStartField.setTextColor(ac ? 0xFFFFFFFF : DIM);
        fStopField.setEditable(ac);  fStopField.setTextColor(ac ? 0xFFFFFFFF : DIM);
        ptsDecField.setEditable(ac); ptsDecField.setTextColor(ac ? 0xFFFFFFFF : DIM);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        int px = (width - W) / 2, py = (height - H) / 2;
        // OP radio row
        if (hit(mx, my, px + 14, py + 43, W - 20, 16)) { analysis = "OP"; refreshFields(); return true; }
        // AC radio row
        if (hit(mx, my, px + 14, py + 63, W - 20, 16)) { analysis = "AC"; refreshFields(); return true; }
        return super.mouseClicked(mx, my, btn);
    }

    private static boolean hit(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    private void drawBackground(GuiGraphics g) {
        int px = (width - W) / 2, py = (height - H) / 2;
        g.fill(px, py, px + W, py + H, BG);
        border(g, px, py, W, H);
        g.fill(px + 2, py + 22, px + W - 2, py + 23, 0xFF444444);
        g.fill(px + 2, py + 82, px + W - 2, py + 83, 0xFF333333);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        renderBackground(g);
        drawBackground(g);
        super.render(g, mx, my, pt);

        int px = (width - W) / 2, py = (height - H) / 2;
        var f = Minecraft.getInstance().font;

        g.drawCenteredString(f, "Circuit Analysis", width / 2, py + 7, TITLE);
        g.drawString(f, "Analysis Type:", px + 14, py + 29, LABEL);

        boolean op = "OP".equals(analysis), ac = "AC".equals(analysis);

        radio(g, px + 16, py + 45, op);
        g.drawString(f, ".OP  DC Operating Point",  px + 30, py + 44, op ? SEL : DIM);

        radio(g, px + 16, py + 65, ac);
        g.drawString(f, ".AC  Frequency Sweep",     px + 30, py + 64, ac ? SEL : DIM);

        int lc = ac ? LABEL : DIM;
        g.drawString(f, "Start Freq (Hz):", px + 16, py + 91,  lc);
        g.drawString(f, "Stop Freq  (Hz):", px + 16, py + 114, lc);
        g.drawString(f, "Pts / Decade:",    px + 16, py + 137, lc);
    }

    private void radio(GuiGraphics g, int x, int y, boolean sel) {
        g.fill(x, y, x + 10, y + 10, 0xFF888888);
        g.fill(x + 1, y + 1, x + 9, y + 9, BG);
        if (sel) g.fill(x + 3, y + 3, x + 7, y + 7, SEL);
    }

    private void border(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x,         y,         x + w, y + 2,     BORDER);
        g.fill(x,         y + h - 2, x + w, y + h,     BORDER);
        g.fill(x,         y,         x + 2, y + h,     BORDER);
        g.fill(x + w - 2, y,         x + w, y + h,     BORDER);
    }

    private void sendPacket() {
        double fs = 10.0, fe = 1_000_000.0;
        int pts = 10;
        if ("AC".equals(analysis)) {
            try { fs  = ComponentEditScreen.parseSI(fStartField.getValue().trim()); } catch (Exception ignored) {}
            try { fe  = ComponentEditScreen.parseSI(fStopField.getValue().trim());  } catch (Exception ignored) {}
            try { pts = Integer.parseInt(ptsDecField.getValue().trim()); }            catch (Exception ignored) {}
            if (pts < 1)  pts = 1;
            if (pts > 100) pts = 100;
        }
        ModMessages.sendToServer(new SimulatePacket(pos, analysis, fs, fe, pts));
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

    @Override public boolean isPauseScreen()    { return false; }
    @Override public boolean shouldCloseOnEsc() { return true;  }
}