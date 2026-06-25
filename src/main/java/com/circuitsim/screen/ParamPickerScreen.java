package com.circuitsim.screen;

import com.circuitsim.client.ClientOpData;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Picks which ngspice param a single slot of a device type shows. Lists every
 * param the device reported (plus a "(none)" option to clear the slot) as a
 * grid of buttons — the simple, driver-safe stand-in for a dropdown. Choosing
 * one writes it into {@link ClientOpData} and returns to
 * {@link EditShownOpScreen}.
 */
public class ParamPickerScreen extends Screen {

    private static final int C_BG     = 0xF01A1A2E;
    private static final int C_BORDER = 0xFF4A90D9;
    private static final int C_TITLE  = 0xFFFFD700;
    private static final int C_DIM    = 0xFF8888AA;

    private static final int BTN_W  = 78;
    private static final int BTN_H  = 18;
    private static final int GAP    = 4;
    private static final int TOP_PAD = 44;   // title + subtitle above the grid
    private static final int BOT_PAD = 32;   // cancel button below the grid

    private final String typeKey;
    private final int    slot;
    private final List<String> params;

    private int panelX, panelY, panelW, panelH;

    public ParamPickerScreen(String typeKey, int slot) {
        super(Component.literal("Choose Parameter"));
        this.typeKey = typeKey;
        this.slot    = slot;
        this.params  = ClientOpData.availableParams(typeKey);
    }

    @Override
    protected void init() {
        super.init();

        int options = params.size() + 1;                 // +1 for "(none)"

        // Size the grid to fit the (GUI-scaled) screen. IC mosfets expose ~65
        // params, so we widen — adding columns until every row fits the height —
        // rather than letting the panel run off the top and bottom.
        int availH   = this.height - 20 - TOP_PAD - BOT_PAD;
        int rowsMax  = Math.max(1, (availH + GAP) / (BTN_H + GAP));
        int maxColsW = Math.max(1, (this.width - 40 + GAP) / (BTN_W + GAP));
        int cols = Math.max(1, (options + rowsMax - 1) / rowsMax);
        cols = Math.max(cols, Math.min(3, options));     // use some width for short lists too
        cols = Math.min(cols, maxColsW);
        int rows = (options + cols - 1) / cols;

        panelW = 28 + cols * (BTN_W + GAP) - GAP;
        panelH = TOP_PAD + rows * (BTN_H + GAP) - GAP + BOT_PAD;
        panelX = (this.width  - panelW) / 2;
        panelY = Math.max(10, (this.height - panelH) / 2);

        int gridX = panelX + 14;
        int gridY = panelY + TOP_PAD;
        String current = ClientOpData.slot(typeKey, slot);

        for (int i = 0; i < options; i++) {
            final String option = (i == 0) ? null : params.get(i - 1);
            int col = i % cols;
            int row = i / cols;
            int x = gridX + col * (BTN_W + GAP);
            int y = gridY + row * (BTN_H + GAP);
            Button b = Button.builder(
                    Component.literal(option == null ? "(none)" : option),
                    btn -> {
                        ClientOpData.setSlot(typeKey, slot, option);
                        minecraft.setScreen(new EditShownOpScreen());
                    })
                    .bounds(x, y, BTN_W, BTN_H).build();
            // The current choice is shown disabled so it's clearly the active one.
            boolean isCurrent = (option == null && current == null)
                    || (option != null && option.equals(current));
            b.active = !isCurrent;
            addRenderableWidget(b);
        }

        addRenderableWidget(Button.builder(
                Component.literal("Cancel"),
                btn -> minecraft.setScreen(new EditShownOpScreen()))
                .bounds(panelX + (panelW - 100) / 2, panelY + panelH - 26, 100, 20).build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float pt) {
        renderBackground(g);
        g.fill(panelX, panelY, panelX + panelW, panelY + panelH, C_BG);
        g.fill(panelX, panelY, panelX + panelW, panelY + 1, C_BORDER);
        g.fill(panelX, panelY + panelH - 1, panelX + panelW, panelY + panelH, C_BORDER);
        g.fill(panelX, panelY, panelX + 1, panelY + panelH, C_BORDER);
        g.fill(panelX + panelW - 1, panelY, panelX + panelW, panelY + panelH, C_BORDER);

        String title = ClientOpData.labelFor(typeKey) + " — slot " + (slot + 1);
        g.drawString(font, title, panelX + 14, panelY + 12, C_TITLE, false);
        if (params.isEmpty()) {
            g.drawString(font, "no parameters reported for this device",
                    panelX + 14, panelY + 30, C_DIM, false);
        }

        super.render(g, mouseX, mouseY, pt);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
