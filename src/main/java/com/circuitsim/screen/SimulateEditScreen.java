package com.circuitsim.screen;

import com.circuitsim.blockentity.ComponentBlockEntity;
import com.circuitsim.network.ModMessages;
import com.circuitsim.network.SimulatePacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntity;

public class SimulateEditScreen extends Screen {

    private final BlockPos pos;
    private String analysis = "OP";

    // PDK state
    private String pdkName    = "none"; // "none", "sky130A", "placeholder"
    private String ngBehavior = "hsa";
    private EditBox pdkLibField;

    private static final String[] NG_MODES = {"hsa", "ps", "hs", "lt", "ki", "va"};

    private EditBox param1Field;
    private EditBox param2Field;
    private EditBox param3Field;

    private static final int W = 290,
        H = 310;

    private static final int BG = 0xFF1E1E1E;
    private static final int BORDER = 0xFF4A90D9;
    private static final int TITLE = 0xFFFFD700;
    private static final int LABEL = 0xFFFFFFFF;
    private static final int DIM = 0xFF666666;
    private static final int SEL = 0xFF4FC3F7;
    private static final int CHK_ON = 0xFF4FC3F7;

    public SimulateEditScreen(BlockPos pos) {
        super(Component.literal("Circuit Analysis"));
        this.pos = pos;
    }

    @Override
    protected void init() {
        super.init();
        int px = (width - W) / 2,
            py = (height - H) / 2;

        // Load all persisted state from the simulate block entity
        BlockEntity be = Minecraft.getInstance().level.getBlockEntity(pos);
        String savedP1, savedP2, savedP3, savedLib;
        if (be instanceof ComponentBlockEntity cbe) {
            pdkName    = cbe.getPdkName();
            ngBehavior = cbe.getNgBehavior();
            analysis   = cbe.getSimAnalysis();
            savedP1    = cbe.getSimParam1();
            savedP2    = cbe.getSimParam2();
            savedP3    = cbe.getSimParam3();
            savedLib   = cbe.getPdkLibPath();
        } else {
            savedP1  = "10";
            savedP2  = "1Meg";
            savedP3  = "10";
            savedLib = "";
        }

        pdkLibField = box(px + 16, py + 226, W - 32, savedLib);
        // Show hint only when the field is empty; clear it as soon as the user types.
        String libHint = "C:\\path\\to\\sky130.lib.spice tt";
        pdkLibField.setSuggestion(savedLib.isEmpty() ? libHint : "");
        pdkLibField.setResponder(text -> pdkLibField.setSuggestion(text.isEmpty() ? libHint : ""));

        param1Field = box(px + 166, py + 108, 90, savedP1);
        param2Field = box(px + 166, py + 131, 90, savedP2);
        param3Field = box(px + 166, py + 154, 90, savedP3);
        refreshFields();
        refreshPdkField();

        addRenderableWidget(
            Button.builder(Component.literal("Simulate"), b -> {
                sendPacket();
                onClose();
            })
                .bounds(px + 20, py + H - 28, 110, 20)
                .build()
        );
        addRenderableWidget(
            Button.builder(Component.literal("Cancel"), b -> onClose())
                .bounds(px + 160, py + H - 28, 110, 20)
                .build()
        );
    }

    private EditBox box(int x, int y, int w, String init) {
        EditBox b = new EditBox(
            Minecraft.getInstance().font,
            x,
            y,
            w,
            18,
            Component.empty()
        );
        b.setValue(init);
        b.setMaxLength(256);
        b.setBordered(true);
        addRenderableWidget(b);
        return b;
    }

    private void refreshFields() {
        boolean ac = "AC".equals(analysis);
        boolean tran = "TRAN".equals(analysis);

        param1Field.setEditable(ac || tran);
        param1Field.setTextColor((ac || tran) ? 0xFFFFFFFF : DIM);

        param2Field.setEditable(ac || tran);
        param2Field.setTextColor((ac || tran) ? 0xFFFFFFFF : DIM);

        param3Field.setEditable(ac);
        param3Field.setTextColor(ac ? 0xFFFFFFFF : DIM);
    }

    private void refreshPdkField() {
        boolean hasPdk = !"none".equals(pdkName);
        pdkLibField.setEditable(hasPdk);
        pdkLibField.setTextColor(hasPdk ? 0xFFFFFFFF : DIM);
    }

    private void setAnalysis(String newAnalysis) {
        analysis = newAnalysis;
        switch (newAnalysis) {
            case "AC" -> {
                param1Field.setValue("10");
                param2Field.setValue("1Meg");
                param3Field.setValue("10");
            }
            case "TRAN" -> {
                param1Field.setValue("1u");
                param2Field.setValue("10m");
                param3Field.setValue("");
            }
        }
        refreshFields();
    }

    private void setPdk(String name) {
        pdkName = name;
        refreshPdkField();
    }

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        int px = (width - W) / 2,
            py = (height - H) / 2;
        // Analysis type rows
        if (hit(mx, my, px + 14, py + 43, W - 20, 16)) {
            setAnalysis("OP");
            return true;
        }
        if (hit(mx, my, px + 14, py + 63, W - 20, 16)) {
            setAnalysis("AC");
            return true;
        }
        if (hit(mx, my, px + 14, py + 83, W - 20, 16)) {
            setAnalysis("TRAN");
            return true;
        }
        // PDK radio options
        if (hit(mx, my, px + 14, py + 193, 84, 16)) {
            setPdk("none");
            return true;
        }
        if (hit(mx, my, px + 98, py + 193, 75, 16)) {
            setPdk("sky130A");
            return true;
        }
        if (hit(mx, my, px + 173, py + 193, 110, 16)) {
            setPdk("placeholder");
            return true;
        }
        // ngbehavior mode chips
        for (int i = 0; i < NG_MODES.length; i++) {
            if (hit(mx, my, px + 70 + i * 34, py + 252, 30, 14)) {
                ngBehavior = NG_MODES[i];
                return true;
            }
        }
        return super.mouseClicked(mx, my, btn);
    }

    private static boolean hit(
        double mx,
        double my,
        int x,
        int y,
        int w,
        int h
    ) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    private void drawBackground(GuiGraphics g) {
        int px = (width - W) / 2,
            py = (height - H) / 2;
        g.fill(px, py, px + W, py + H, BG);
        border(g, px, py, W, H);
        g.fill(px + 2, py + 22, px + W - 2, py + 23, 0xFF444444);
        g.fill(px + 2, py + 102, px + W - 2, py + 103, 0xFF333333);
        g.fill(px + 2, py + 174, px + W - 2, py + 175, 0xFF333333);
        g.fill(px + 2, py + 248, px + W - 2, py + 249, 0xFF333333);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        renderBackground(g);
        drawBackground(g);
        super.render(g, mx, my, pt);

        int px = (width - W) / 2,
            py = (height - H) / 2;
        var f = Minecraft.getInstance().font;

        g.drawCenteredString(f, "Circuit Analysis", width / 2, py + 7, TITLE);
        g.drawString(f, "Analysis Type:", px + 14, py + 29, LABEL);

        boolean op = "OP".equals(analysis);
        boolean ac = "AC".equals(analysis);
        boolean tran = "TRAN".equals(analysis);

        radio(g, px + 16, py + 45, op);
        g.drawString(
            f,
            ".OP  - DC Operating Point",
            px + 30,
            py + 44,
            op ? SEL : DIM
        );

        radio(g, px + 16, py + 65, ac);
        g.drawString(
            f,
            ".AC  - Frequency Sweep",
            px + 30,
            py + 64,
            ac ? SEL : DIM
        );

        radio(g, px + 16, py + 85, tran);
        g.drawString(
            f,
            ".TRAN - Transient Analysis",
            px + 30,
            py + 84,
            tran ? SEL : DIM
        );

        if (ac) {
            g.drawString(f, "Start Freq (Hz):", px + 16, py + 111, LABEL);
            g.drawString(f, "Stop Freq  (Hz):", px + 16, py + 134, LABEL);
            g.drawString(f, "Pts / Decade:", px + 16, py + 157, LABEL);
        } else if (tran) {
            g.drawString(f, "Time Step:", px + 16, py + 111, LABEL);
            g.drawString(f, "Stop Time:", px + 16, py + 134, LABEL);
            g.drawString(f, "Pts / Dec:", px + 16, py + 157, DIM);
        } else {
            g.drawString(f, "Start Freq (Hz):", px + 16, py + 111, DIM);
            g.drawString(f, "Stop Freq  (Hz):", px + 16, py + 134, DIM);
            g.drawString(f, "Pts / Decade:", px + 16, py + 157, DIM);
        }

        // PDK section
        g.drawString(f, "PDK:", px + 14, py + 181, LABEL);

        boolean isNone        = "none".equals(pdkName);
        boolean isSky130      = "sky130A".equals(pdkName);
        boolean isPlaceholder = "placeholder".equals(pdkName);

        checkbox(g, px + 16, py + 195, isNone);
        g.drawString(f, "no pdk", px + 30, py + 194, isNone ? CHK_ON : DIM);

        checkbox(g, px + 100, py + 195, isSky130);
        g.drawString(f, "sky130A", px + 114, py + 194, isSky130 ? CHK_ON : DIM);

        checkbox(g, px + 175, py + 195, isPlaceholder);
        g.drawString(f, "placeholder", px + 189, py + 194, isPlaceholder ? CHK_ON : DIM);

        boolean hasPdk = !"none".equals(pdkName);
        g.drawString(f, ".lib path:", px + 16, py + 213, hasPdk ? LABEL : DIM);

        // ngbehavior section
        g.drawString(f, "compat:", px + 16, py + 255, LABEL);
        for (int i = 0; i < NG_MODES.length; i++) {
            int cx = px + 70 + i * 34;
            int cy = py + 252;
            boolean sel = NG_MODES[i].equals(ngBehavior);
            g.fill(cx, cy, cx + 30, cy + 14, sel ? 0xFF1A4A6A : 0xFF333333);
            g.fill(cx + 1, cy + 1, cx + 29, cy + 13, sel ? 0xFF2A6A9A : 0xFF444444);
            g.drawCenteredString(f, NG_MODES[i], cx + 15, cy + 3, sel ? SEL : DIM);
        }
    }

    private void radio(GuiGraphics g, int x, int y, boolean sel) {
        g.fill(x, y, x + 10, y + 10, 0xFF888888);
        g.fill(x + 1, y + 1, x + 9, y + 9, BG);
        if (sel) g.fill(x + 3, y + 3, x + 7, y + 7, SEL);
    }

    private void checkbox(GuiGraphics g, int x, int y, boolean sel) {
        g.fill(x, y, x + 10, y + 10, 0xFF888888);
        g.fill(x + 1, y + 1, x + 9, y + 9, BG);
        if (sel) g.fill(x + 2, y + 2, x + 8, y + 8, CHK_ON);
    }

    private void border(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + 2, BORDER);
        g.fill(x, y + h - 2, x + w, y + h, BORDER);
        g.fill(x, y, x + 2, y + h, BORDER);
        g.fill(x + w - 2, y, x + w, y + h, BORDER);
    }

    private void sendPacket() {
        double p1 = 0.0,
            p2 = 0.0;
        int p3 = 10;

        if ("AC".equals(analysis)) {
            try {
                p1 = ComponentEditScreen.parseSI(param1Field.getValue().trim());
            } catch (Exception ignored) {
                p1 = 10.0;
            }
            try {
                p2 = ComponentEditScreen.parseSI(param2Field.getValue().trim());
            } catch (Exception ignored) {
                p2 = 1_000_000.0;
            }
            try {
                p3 = Integer.parseInt(param3Field.getValue().trim());
            } catch (Exception ignored) {
                p3 = 10;
            }
            if (p3 < 1) p3 = 1;
            if (p3 > 100) p3 = 100;
        } else if ("TRAN".equals(analysis)) {
            try {
                p1 = ComponentEditScreen.parseSI(param1Field.getValue().trim());
            } catch (Exception ignored) {
                p1 = 1e-6;
            }
            try {
                p2 = ComponentEditScreen.parseSI(param2Field.getValue().trim());
            } catch (Exception ignored) {
                p2 = 1e-2;
            }
            p3 = 0;
        }

        String libPath = pdkLibField.getValue().trim();
        ModMessages.sendToServer(
            new SimulatePacket(pos, analysis, p1, p2, p3, pdkName, libPath, ngBehavior,
                param1Field.getValue().trim(), param2Field.getValue().trim(), param3Field.getValue().trim())
        );
    }

    @Override
    public boolean keyPressed(int k, int s, int m) {
        if (
            getFocused() instanceof EditBox eb && eb.keyPressed(k, s, m)
        ) return true;
        return super.keyPressed(k, s, m);
    }

    @Override
    public boolean charTyped(char c, int m) {
        if (
            getFocused() instanceof EditBox eb && eb.charTyped(c, m)
        ) return true;
        return super.charTyped(c, m);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }
}
