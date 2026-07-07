package com.circuitsim.screen;

import com.circuitsim.block.TransmissionLineBlock;
import com.circuitsim.blockentity.ComponentBlockEntity;
import com.circuitsim.network.ModMessages;
import com.circuitsim.network.TransmissionLineUpdatePacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Edit dialog for the 2×3 transmission line. Right-click any of the 6 cells
 * to open; {@code pos} is the anchor cell. A mode button switches between the
 * two ngspice line models, each with its own field set (both sets survive
 * toggling within one dialog; only the active set is saved):
 * <ul>
 *   <li><b>Lossless (T)</b> — Z0 (Ω) plus either the delay TD (s) or a
 *       frequency F (Hz) with optional normalized length NL (blank = 0.25,
 *       quarter-wave). TD wins when both are given.
 *   <li><b>Lossy (LTRA, O)</b> — R (Ω), L (H), G (S), C (F) per unit length
 *       and the line length LEN.
 * </ul>
 * Slot mapping (lossless | lossy): value = Z0 | R, wParam = TD | L,
 * lParam = F | G, multParam = NL | C, nfParam = — | LEN; the mode string
 * rides in modelName. Each value field also accepts a Parametric variable.
 */
public class TransmissionLineEditScreen extends Screen {

    private final BlockPos pos;

    private boolean lossy;

    // Lossless field set.
    private EditBox z0Field;
    private EditBox tdField;
    private EditBox fField;
    private EditBox nlField;
    // Lossy (LTRA) field set.
    private EditBox rField;
    private EditBox lField;
    private EditBox gField;
    private EditBox cField;
    private EditBox lenField;

    private EditBox numberField;
    private Button  modeButton;

    private static final int W = 260, H = 270;

    private static final int BG     = 0xFF1E1E1E;
    private static final int BORDER = 0xFF4A90D9;
    private static final int TITLE  = 0xFFFFD700;
    private static final int LABEL  = 0xFFFFFFFF;
    private static final int HINT   = 0xFF999999;

    /** Two-column geometry: [16 | field 106 | 16 | field 106 | 16]. */
    private static final int COL_W  = (W - 48) / 2;
    private static final int COL1_X = 16;
    private static final int COL2_X = 32 + COL_W;

    private static final int Y_MODE_BTN     = 30;
    private static final int Y_ROW1_LABEL   = 58;
    private static final int Y_ROW1_FIELD   = 72;
    private static final int Y_ROW2_LABEL   = 104;
    private static final int Y_ROW2_FIELD   = 118;
    private static final int Y_ROW3_LABEL   = 150;
    private static final int Y_ROW3_FIELD   = 164;
    private static final int Y_NUMBER_LABEL = 196;
    private static final int Y_NUMBER_FIELD = 210;

    public TransmissionLineEditScreen(BlockPos pos) {
        super(Component.literal("Transmission Line"));
        this.pos = pos;
    }

    @Override
    protected void init() {
        super.init();
        int px = (width - W) / 2;
        int py = (height - H) / 2;

        // Saved slot values (interpreted by the saved mode; the other mode's
        // fields start at their defaults).
        double p1 = 0, p2 = 0, p3 = 0, p4 = 0, p5 = 0;
        String e1 = "", e2 = "", e3 = "", e4 = "", e5 = "";
        int savedNumber = 0;
        String savedMode = "";
        BlockEntity be = Minecraft.getInstance().level.getBlockEntity(pos);
        if (be instanceof ComponentBlockEntity cbe) {
            savedMode   = cbe.getModelName();
            p1 = cbe.getValue();     e1 = cbe.getValueExpr();
            p2 = cbe.getWParam();    e2 = cbe.getWExpr();
            p3 = cbe.getLParam();    e3 = cbe.getLExpr();
            p4 = cbe.getMultParam(); e4 = cbe.getMultExpr();
            p5 = cbe.getNfParam();   e5 = cbe.getNfExpr();
            savedNumber = cbe.getComponentNumber();
        }
        lossy = TransmissionLineBlock.MODE_LTRA.equals(savedMode);
        // Never-edited block: the slots still hold the BE's generic defaults
        // (value 0, w/l/mult/nf 1.0), which were never meant as seconds or
        // hertz — start from the effective Z0=50/TD=10ns (mirrors
        // formatTransmissionLine).
        boolean neverEdited = !lossy && p1 == 0 && e1.isEmpty();

        // Lossless set (Z0, TD, F, NL).
        String z0 = neverEdited ? "50"  : (lossy ? "50"  : text(p1, e1, false));
        String td = neverEdited ? "10n" : (lossy ? "10n" : text(p2, e2, true));
        String ff = (neverEdited || lossy) ? "" : text(p3, e3, true);
        String nl = (neverEdited || lossy) ? "" : text(p4, e4, true);
        // Lossy set (R, L, G, C, LEN). Defaults model a generic 50 Ω line:
        // sqrt(L/C) = 50, 5 ns delay per unit.
        String rr  = lossy ? text(p1, e1, false) : "0.1";
        String ll  = lossy ? text(p2, e2, false) : "250n";
        String gg  = lossy ? text(p3, e3, true)  : "0";
        String cc  = lossy ? text(p4, e4, false) : "100p";
        String len = lossy ? text(p5, e5, false) : "1";

        modeButton = Button.builder(modeLabel(), b -> {
                    lossy = !lossy;
                    modeButton.setMessage(modeLabel());
                    applyVisibility();
                })
                .bounds(px + COL1_X, py + Y_MODE_BTN, W - 32, 20).build();
        addRenderableWidget(modeButton);

        z0Field = makeBox(px + COL1_X, py + Y_ROW1_FIELD, COL_W, z0);
        tdField = makeBox(px + COL2_X, py + Y_ROW1_FIELD, COL_W, td);
        fField  = makeBox(px + COL1_X, py + Y_ROW2_FIELD, COL_W, ff);
        nlField = makeBox(px + COL2_X, py + Y_ROW2_FIELD, COL_W, nl);

        rField   = makeBox(px + COL1_X, py + Y_ROW1_FIELD, COL_W, rr);
        lField   = makeBox(px + COL2_X, py + Y_ROW1_FIELD, COL_W, ll);
        gField   = makeBox(px + COL1_X, py + Y_ROW2_FIELD, COL_W, gg);
        cField   = makeBox(px + COL2_X, py + Y_ROW2_FIELD, COL_W, cc);
        lenField = makeBox(px + COL1_X, py + Y_ROW3_FIELD, COL_W, len);

        numberField = makeBox(px + COL1_X, py + Y_NUMBER_FIELD, 80,
                savedNumber == 0 ? "" : Integer.toString(savedNumber));
        numberField.setSuggestion(savedNumber == 0 ? "auto" : "");
        numberField.setResponder(t -> numberField.setSuggestion(t.isEmpty() ? "auto" : ""));

        addRenderableWidget(
            Button.builder(Component.literal("Save"), b -> { sendPacket(); onClose(); })
                .bounds(px + 20, py + H - 28, 100, 20).build());
        addRenderableWidget(
            Button.builder(Component.literal("Cancel"), b -> onClose())
                .bounds(px + W - 120, py + H - 28, 100, 20).build());

        applyVisibility();
        setInitialFocus(lossy ? rField : z0Field);
    }

    private Component modeLabel() {
        return Component.literal(lossy ? "Mode: Lossy (LTRA)" : "Mode: Lossless (T)");
    }

    private void applyVisibility() {
        for (EditBox b : new EditBox[]{z0Field, tdField, fField, nlField}) b.setVisible(!lossy);
        for (EditBox b : new EditBox[]{rField, lField, gField, cField, lenField}) b.setVisible(lossy);
        if (getFocused() instanceof EditBox eb && !eb.isVisible()) setFocused(null);
    }

    /** Saved slot → field text; blank-able fields show "" instead of 0. */
    private static String text(double v, String expr, boolean blankZero) {
        if (!expr.isEmpty()) return expr;
        if (blankZero && v == 0) return "";
        return ComponentEditScreen.formatValue(v);
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

        g.drawCenteredString(f, "Transmission Line", width / 2, py + 7, TITLE);
        if (lossy) {
            g.drawString(f, "R (Ω/unit):",   px + COL1_X, py + Y_ROW1_LABEL, LABEL);
            g.drawString(f, "L (H/unit):",   px + COL2_X, py + Y_ROW1_LABEL, LABEL);
            g.drawString(f, "G (S/unit):",   px + COL1_X, py + Y_ROW2_LABEL, LABEL);
            g.drawString(f, "C (F/unit):",   px + COL2_X, py + Y_ROW2_LABEL, LABEL);
            g.drawString(f, "Length (units):", px + COL1_X, py + Y_ROW3_LABEL, LABEL);
            g.drawString(f, "RLC / RC / LC / RG only", px + COL2_X, py + Y_ROW3_FIELD + 5, HINT);
        } else {
            g.drawString(f, "Z0 (Ω):",       px + COL1_X, py + Y_ROW1_LABEL, LABEL);
            g.drawString(f, "Delay TD (s):", px + COL2_X, py + Y_ROW1_LABEL, LABEL);
            g.drawString(f, "F (Hz):",       px + COL1_X, py + Y_ROW2_LABEL, LABEL);
            g.drawString(f, "NL (blank = 0.25):", px + COL2_X, py + Y_ROW2_LABEL, LABEL);
            g.drawString(f, "TD wins; blank TD uses F/NL (td = nl/f)",
                    px + COL1_X, py + Y_ROW3_LABEL, HINT);
        }
        g.drawString(f, "Netlist index (blank = auto):", px + COL1_X, py + Y_NUMBER_LABEL, LABEL);
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

    /** Parses one field into a (value, expr) pair — expr wins when the text is an identifier. */
    private static double parseField(EditBox box, String[] exprOut, double fallback) {
        double value = fallback;
        exprOut[0] = "";
        String raw = box.getValue().trim();
        if (!raw.isEmpty()) {
            try {
                value = ComponentEditScreen.parseSI(raw);
            } catch (NumberFormatException nfe) {
                if (ComponentEditScreen.isIdentifier(raw)) exprOut[0] = raw;
            }
        }
        return value;
    }

    private void sendPacket() {
        String[] e1 = new String[1], e2 = new String[1], e3 = new String[1],
                 e4 = new String[1], e5 = new String[1];
        double p1, p2, p3, p4, p5;
        if (lossy) {
            p1 = parseField(rField,   e1, 0.0);
            p2 = parseField(lField,   e2, 0.0);
            p3 = parseField(gField,   e3, 0.0);
            p4 = parseField(cField,   e4, 0.0);
            p5 = parseField(lenField, e5, 1.0);
            if (e1[0].isEmpty() && p1 < 0) p1 = 0.0;
            if (e2[0].isEmpty() && p2 < 0) p2 = 0.0;
            if (e3[0].isEmpty() && p3 < 0) p3 = 0.0;
            if (e4[0].isEmpty() && p4 < 0) p4 = 0.0;
            if (e5[0].isEmpty() && p5 <= 0) p5 = 1.0;   // ngspice requires LEN
        } else {
            p1 = parseField(z0Field, e1, 50.0);
            p2 = parseField(tdField, e2, 0.0);
            p3 = parseField(fField,  e3, 0.0);
            p4 = parseField(nlField, e4, 0.0);
            p5 = 0.0; e5[0] = "";
            if (e1[0].isEmpty() && p1 <= 0) p1 = 50.0;
            if (e2[0].isEmpty() && p2 < 0) p2 = 0.0;
            if (e3[0].isEmpty() && p3 < 0) p3 = 0.0;
            if (e4[0].isEmpty() && p4 < 0) p4 = 0.0;
            // One of TD / F must exist — fall back to the classic 10 ns.
            if (e2[0].isEmpty() && p2 == 0 && e3[0].isEmpty() && p3 == 0) p2 = 10e-9;
        }

        int num;
        try { num = Integer.parseInt(numberField.getValue().trim()); }
        catch (NumberFormatException e) { num = 0; }
        if (num < 0) num = 0;

        ModMessages.sendToServer(new TransmissionLineUpdatePacket(
                pos, lossy ? TransmissionLineBlock.MODE_LTRA : "lossless",
                p1, e1[0], p2, e2[0], p3, e3[0], p4, e4[0], p5, e5[0], num));
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
