package com.circuitsim.screen;

import com.circuitsim.client.ClientOpData;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The menu opened by pressing K after an .OP run. Two primary actions —
 * "Annotate Operating Points" (turn the floating-OP labels on with the current
 * per-type selections) and "Edit Shown OP" (choose which params each device
 * type shows) — plus a toggle-off and close. Deliberately built from vanilla
 * {@link Button} widgets only (see {@link SimulationOutputScreen}'s note on
 * custom widgets hanging the display driver).
 */
public class AnnotateOpScreen extends Screen {

    private static final int PANEL_W = 260;

    private static final int C_BG     = 0xF01A1A2E;
    private static final int C_BORDER = 0xFF4A90D9;
    private static final int C_TITLE  = 0xFFFFD700;
    private static final int C_DIM    = 0xFF8888AA;
    private static final int C_TEXT   = 0xFFE0E0E0;

    private int panelX, panelY, panelH;

    public AnnotateOpScreen() {
        super(Component.literal("Operating Point Annotation"));
    }

    @Override
    protected void init() {
        super.init();
        boolean sweep = ClientOpData.frameCount() > 1;
        panelH = 176 + (sweep ? 28 : 0);
        panelX = (this.width  - PANEL_W) / 2;
        panelY = (this.height - panelH) / 2;

        int bx = panelX + 30;
        int bw = PANEL_W - 60;
        int y  = panelY + 42;

        // Sweep navigator: step through the per-value frames. Changing the frame
        // updates the live annotations and stays selected after closing.
        if (sweep) {
            int navY = y;
            addRenderableWidget(Button.builder(Component.literal("<"),
                    b -> { ClientOpData.cycleFrame(-1); rebuildWidgets(); })
                    .bounds(bx, navY, 20, 20).build());
            addRenderableWidget(Button.builder(Component.literal(">"),
                    b -> { ClientOpData.cycleFrame(+1); rebuildWidgets(); })
                    .bounds(bx + bw - 20, navY, 20, 20).build());
            y += 28;
        }

        addRenderableWidget(Button.builder(
                Component.literal("Annotate Operating Points"),
                b -> { ClientOpData.setAnnotationActive(true); onClose(); })
                .bounds(bx, y, bw, 20).build());
        y += 26;

        addRenderableWidget(Button.builder(
                Component.literal("Edit Shown OP"),
                b -> minecraft.setScreen(new EditShownOpScreen()))
                .bounds(bx, y, bw, 20).build());
        y += 26;

        Button clear = Button.builder(
                Component.literal("Clear Annotations"),
                b -> { ClientOpData.setAnnotationActive(false); onClose(); })
                .bounds(bx, y, bw, 20).build();
        clear.active = ClientOpData.isAnnotationActive();
        addRenderableWidget(clear);
        y += 30;

        addRenderableWidget(Button.builder(
                Component.literal("Close"),
                b -> onClose())
                .bounds(bx, y, bw, 20).build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float pt) {
        renderBackground(g);
        g.fill(panelX, panelY, panelX + PANEL_W, panelY + panelH, C_BG);
        g.fill(panelX, panelY, panelX + PANEL_W, panelY + 1, C_BORDER);
        g.fill(panelX, panelY + panelH - 1, panelX + PANEL_W, panelY + panelH, C_BORDER);
        g.fill(panelX, panelY, panelX + 1, panelY + panelH, C_BORDER);
        g.fill(panelX + PANEL_W - 1, panelY, panelX + PANEL_W, panelY + panelH, C_BORDER);

        center(g, "Operating Point Annotation", panelY + 14, C_TITLE);
        String status = ClientOpData.isAnnotationActive()
                ? "annotations: ON" : "annotations: off";
        center(g, status, panelY + 28, C_DIM);

        // Sweep navigator label, centred between the ◀ ▶ buttons.
        if (ClientOpData.frameCount() > 1) {
            String nav = "value " + (ClientOpData.currentFrameIndex() + 1) + "/"
                    + ClientOpData.frameCount() + ":  " + ClientOpData.currentFrameLabel();
            center(g, nav, panelY + 48, C_TEXT);
        }

        super.render(g, mouseX, mouseY, pt);
    }

    private void center(GuiGraphics g, String text, int y, int color) {
        g.drawString(font, text, (this.width - font.width(text)) / 2, y, color, false);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
