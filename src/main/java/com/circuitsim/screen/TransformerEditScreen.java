package com.circuitsim.screen;

import com.circuitsim.blockentity.ComponentBlockEntity;
import com.circuitsim.network.ModMessages;
import com.circuitsim.network.TransformerUpdatePacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Edit dialog for the 2×3 transformer (coupled inductors, K element).
 * Right-click any of the 6 cells to open; {@code pos} is the anchor cell.
 * Two-column layout — primary winding on the left, secondary on the right:
 * <ul>
 *   <li>Inductance Lp / Ls (H) — wParam / lParam slots
 *   <li>Series resistance per winding (Ω, 0 = ideal) — multParam / nfParam
 *       slots; defaults to 1 mΩ, which keeps the DC operating point solvable
 *       when a winding sits directly across an ideal voltage source
 *   <li>Coupling coefficient k, 0 &lt; k ≤ 1 — value slot
 *   <li>Netlist index — manual {@code K<n>}, 0 = auto
 * </ul>
 * Each value field also accepts a Parametric block variable name.
 */
public class TransformerEditScreen extends Screen {

    private static final double DEFAULT_RSER = 0.001;

    private final BlockPos pos;

    private EditBox lpField;
    private EditBox lsField;
    private EditBox rpField;
    private EditBox rsField;
    private EditBox kField;
    private EditBox numberField;

    private static final int W = 260, H = 248;

    private static final int BG     = 0xFF1E1E1E;
    private static final int BORDER = 0xFF4A90D9;
    private static final int TITLE  = 0xFFFFD700;
    private static final int LABEL  = 0xFFFFFFFF;

    /** Two-column geometry: [16 | field 106 | 16 | field 106 | 16]. */
    private static final int COL_W  = (W - 48) / 2;
    private static final int COL1_X = 16;
    private static final int COL2_X = 32 + COL_W;

    private static final int Y_L_LABEL      = 30;
    private static final int Y_L_FIELD      = 44;
    private static final int Y_RSER_LABEL   = 76;
    private static final int Y_RSER_FIELD   = 90;
    private static final int Y_K_LABEL      = 122;
    private static final int Y_K_FIELD      = 136;
    private static final int Y_NUMBER_LABEL = 168;
    private static final int Y_NUMBER_FIELD = 182;

    public TransformerEditScreen(BlockPos pos) {
        super(Component.literal("Transformer (K)"));
        this.pos = pos;
    }

    @Override
    protected void init() {
        super.init();
        int px = (width - W) / 2;
        int py = (height - H) / 2;

        double savedLp = 1.0, savedLs = 1.0, savedRp = DEFAULT_RSER, savedRs = DEFAULT_RSER;
        double savedK = 0.0;
        String savedLpExpr = "", savedLsExpr = "", savedRpExpr = "", savedRsExpr = "", savedKExpr = "";
        int savedNumber = 0;
        BlockEntity be = Minecraft.getInstance().level.getBlockEntity(pos);
        if (be instanceof ComponentBlockEntity cbe) {
            savedLp     = cbe.getWParam();
            savedLpExpr = cbe.getWExpr();
            savedLs     = cbe.getLParam();
            savedLsExpr = cbe.getLExpr();
            savedK      = cbe.getValue();
            savedKExpr  = cbe.getValueExpr();
            savedNumber = cbe.getComponentNumber();
            // Never-edited block: mult/nf still hold the BE's generic 1.0
            // defaults, which were never meant as resistances — show the
            // effective 1 mΩ default instead (mirrors formatTransformer).
            if (savedK != 0.0 || !savedKExpr.isEmpty()) {
                savedRp     = cbe.getMultParam();
                savedRpExpr = cbe.getMultExpr();
                savedRs     = cbe.getNfParam();
                savedRsExpr = cbe.getNfExpr();
            }
        }

        lpField = makeBox(px + COL1_X, py + Y_L_FIELD, COL_W,
                !savedLpExpr.isEmpty() ? savedLpExpr : ComponentEditScreen.formatValue(savedLp));
        lsField = makeBox(px + COL2_X, py + Y_L_FIELD, COL_W,
                !savedLsExpr.isEmpty() ? savedLsExpr : ComponentEditScreen.formatValue(savedLs));
        rpField = makeBox(px + COL1_X, py + Y_RSER_FIELD, COL_W,
                !savedRpExpr.isEmpty() ? savedRpExpr : ComponentEditScreen.formatValue(savedRp));
        rsField = makeBox(px + COL2_X, py + Y_RSER_FIELD, COL_W,
                !savedRsExpr.isEmpty() ? savedRsExpr : ComponentEditScreen.formatValue(savedRs));
        kField  = makeBox(px + COL1_X, py + Y_K_FIELD, W - 32,
                !savedKExpr.isEmpty() ? savedKExpr
                        : ComponentEditScreen.formatValue(savedK == 0.0 ? 1.0 : savedK));

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

        setInitialFocus(lpField);
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

        g.drawCenteredString(f, "Transformer (K)", width / 2, py + 7, TITLE);
        g.drawString(f, "Primary Lp (H):",   px + COL1_X, py + Y_L_LABEL, LABEL);
        g.drawString(f, "Secondary Ls (H):", px + COL2_X, py + Y_L_LABEL, LABEL);
        g.drawString(f, "Rser pri (Ω):", px + COL1_X, py + Y_RSER_LABEL, LABEL);
        g.drawString(f, "Rser sec (Ω):", px + COL2_X, py + Y_RSER_LABEL, LABEL);
        g.drawString(f, "Coupling k (0 < k ≤ 1):", px + COL1_X, py + Y_K_LABEL,  LABEL);
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
        String[] lpExpr = new String[1], lsExpr = new String[1],
                 rpExpr = new String[1], rsExpr = new String[1], kExpr = new String[1];
        double lp = parseField(lpField, lpExpr, 1.0);
        double ls = parseField(lsField, lsExpr, 1.0);
        double rp = parseField(rpField, rpExpr, DEFAULT_RSER);
        double rs = parseField(rsField, rsExpr, DEFAULT_RSER);
        double k  = parseField(kField,  kExpr,  1.0);

        // ngspice rejects K outside (0, 1]; clamp numeric input (expressions
        // are the user's responsibility). Negative Rser is meaningless — an
        // explicit 0 stays 0 (ideal winding, no series resistor emitted).
        if (kExpr[0].isEmpty()) {
            if (k <= 0) k = 1.0;
            if (k > 1)  k = 1.0;
        }
        if (lpExpr[0].isEmpty() && lp <= 0) lp = 1.0;
        if (lsExpr[0].isEmpty() && ls <= 0) ls = 1.0;
        if (rpExpr[0].isEmpty() && rp < 0) rp = 0.0;
        if (rsExpr[0].isEmpty() && rs < 0) rs = 0.0;

        int num;
        try { num = Integer.parseInt(numberField.getValue().trim()); }
        catch (NumberFormatException e) { num = 0; }
        if (num < 0) num = 0;

        ModMessages.sendToServer(new TransformerUpdatePacket(
                pos, lp, lpExpr[0], ls, lsExpr[0],
                rp, rpExpr[0], rs, rsExpr[0], k, kExpr[0], num));
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
