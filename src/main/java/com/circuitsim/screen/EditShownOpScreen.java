package com.circuitsim.screen;

import com.circuitsim.client.ClientOpData;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * The "edit shown OP" menu: one row per device type present in the last .OP
 * run (so two resistors collapse into a single "Resistor" row), each with
 * {@link ClientOpData#MAX_SLOTS} slot buttons showing the currently-chosen
 * params. Clicking a slot opens {@link ParamPickerScreen} to pick from every
 * param ngspice reported for that device type. "Annotate" applies the
 * selection and turns annotation on; "Back" returns to {@link AnnotateOpScreen}.
 */
public class EditShownOpScreen extends Screen {

    private static final int PANEL_W = 332;

    private static final int C_BG     = 0xF01A1A2E;
    private static final int C_BORDER = 0xFF4A90D9;
    private static final int C_TITLE  = 0xFFFFD700;
    private static final int C_TEXT   = 0xFFE0E0E0;
    private static final int C_DIM    = 0xFF8888AA;

    private static final int ROW_H   = 24;
    private static final int SLOT_W  = 46;
    private static final int SLOT_H  = 20;
    private static final int LABEL_W = 96;

    private List<String> types;
    private int panelX, panelY, panelH;
    private int contentY, slotX;

    public EditShownOpScreen() {
        super(Component.literal("Edit Shown OP"));
    }

    @Override
    protected void init() {
        super.init();
        types = ClientOpData.types();

        // Cap rows so the panel always fits on screen; circuits rarely have
        // more device types than this.
        int maxRows = Math.max(1, (this.height - 140) / ROW_H);
        int rows = Math.min(types.size(), maxRows);

        panelH = 70 + rows * ROW_H + 34;
        panelX = (this.width  - PANEL_W) / 2;
        panelY = (this.height - panelH) / 2;

        contentY = panelY + 44;
        slotX    = panelX + 14 + LABEL_W + 6;

        for (int r = 0; r < rows; r++) {
            final String type = types.get(r);
            int ry = contentY + r * ROW_H;
            for (int s = 0; s < ClientOpData.MAX_SLOTS; s++) {
                final int slot = s;
                String pv = ClientOpData.slot(type, s);
                addRenderableWidget(Button.builder(
                        Component.literal(pv == null ? "—" : pv),
                        b -> minecraft.setScreen(new ParamPickerScreen(type, slot)))
                        .bounds(slotX + s * (SLOT_W + 2), ry, SLOT_W, SLOT_H).build());
            }
        }

        int by = panelY + panelH - 28;
        addRenderableWidget(Button.builder(
                Component.literal("Annotate"),
                b -> { ClientOpData.setAnnotationActive(true); onClose(); })
                .bounds(panelX + 14, by, 140, 20).build());
        addRenderableWidget(Button.builder(
                Component.literal("Back"),
                b -> minecraft.setScreen(new AnnotateOpScreen()))
                .bounds(panelX + PANEL_W - 14 - 100, by, 100, 20).build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float pt) {
        renderBackground(g);
        g.fill(panelX, panelY, panelX + PANEL_W, panelY + panelH, C_BG);
        g.fill(panelX, panelY, panelX + PANEL_W, panelY + 1, C_BORDER);
        g.fill(panelX, panelY + panelH - 1, panelX + PANEL_W, panelY + panelH, C_BORDER);
        g.fill(panelX, panelY, panelX + 1, panelY + panelH, C_BORDER);
        g.fill(panelX + PANEL_W - 1, panelY, panelX + PANEL_W, panelY + panelH, C_BORDER);

        g.drawString(font, "Edit Shown OP", panelX + 14, panelY + 12, C_TITLE, false);
        g.drawString(font, "click a slot to choose a parameter", panelX + 14, panelY + 26, C_DIM, false);

        // Device-type labels, aligned with each row's slot buttons.
        int rows = Math.min(types.size(), Math.max(1, (this.height - 140) / ROW_H));
        for (int r = 0; r < rows; r++) {
            String label = ClientOpData.labelFor(types.get(r));
            label = trim(label, LABEL_W);
            int ry = contentY + r * ROW_H;
            g.drawString(font, label, panelX + 14, ry + 6, C_TEXT, false);
        }
        if (types.size() > rows) {
            g.drawString(font, "(+" + (types.size() - rows) + " more not shown)",
                    panelX + 14, contentY + rows * ROW_H, C_DIM, false);
        }

        super.render(g, mouseX, mouseY, pt);
    }

    /** Truncates {@code text} with an ellipsis so it fits in {@code maxW} px. */
    private String trim(String text, int maxW) {
        if (font.width(text) <= maxW) return text;
        while (text.length() > 1 && font.width(text + "…") > maxW) {
            text = text.substring(0, text.length() - 1);
        }
        return text + "…";
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
