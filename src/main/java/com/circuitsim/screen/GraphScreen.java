package com.circuitsim.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Graph viewer for one simulation session. Up to two probes can be plotted
 * simultaneously, stacked vertically — slot 1 on top, slot 2 below. The
 * right-hand sidebar lists every probe in the session; each row has a [T] /
 * [B] toggle pair that assigns the probe to a slot. A probe can be in at
 * most one slot at a time; reassigning automatically clears its other slot.
 *
 * <p>When {@code isLogFrequency} is true the shared X axis is rendered on a
 * log10 scale, the standard presentation for AC Bode-style plots.
 */
public class GraphScreen extends Screen {

    private final String              sweepComponentName;
    private final String              sweepUnit;
    private final boolean             isLogFrequency;
    private final List<Double>        sweepValues;
    private final List<String>        probeNames;
    private final List<List<Double>>  probeData;
    private final List<String>        probeUnits;

    // Slot occupants — -1 means empty. Each slot holds an index into
    // probeNames. slot1 is the top plot, slot2 the bottom.
    private int slot1 = -1;
    private int slot2 = -1;

    // Sidebar scroll offset, in pixels. Clamped to [0, maxScroll] each frame
    // to follow probe-count changes (none expected during a session but
    // cheap to keep robust).
    private int sidebarScroll = 0;

    // Hovered point index in each plot, refreshed every render frame.
    private int hoverIdxTop = -1;
    private int hoverIdxBot = -1;

    // ── panel layout ─────────────────────────────────────────────────────────
    private static final int PANEL_W = 660, PANEL_H = 380;
    // Plot area (relative to panelX/panelY). The sidebar lives to the right
    // of GR; the plot width must not overlap it.
    private static final int GL = 50, GR = 510;
    private static final int GT = 32, GB = 330;

    // ── sidebar layout ───────────────────────────────────────────────────────
    private static final int SIDEBAR_X     = 520;
    private static final int SIDEBAR_Y     = 32;
    private static final int SIDEBAR_W     = 132;
    private static final int SIDEBAR_H     = 318;
    private static final int ROW_H         = 13;
    private static final int BTN_W         = 13;        // T/B button width
    private static final int BTN_H         = 11;
    private static final int BTN_GAP       = 2;
    private static final int ROW_PAD_LEFT  = 4;
    private static final int ROW_TEXT_X    = ROW_PAD_LEFT + 2 * BTN_W + BTN_GAP + 4;

    // ── colours ─────────────────────────────────────────────────────────────
    private static final int C_BG          = 0xFF1A1A2E;
    private static final int C_PLOT_BG     = 0xFF16213E;
    private static final int C_SIDE_BG     = 0xFF12121F;
    private static final int C_BORDER      = 0xFF4A90D9;
    private static final int C_SEP         = 0xFF444466;
    private static final int C_GRID        = 0xFF2A2A4A;
    private static final int C_AXIS        = 0xFF8888AA;
    private static final int C_LINE_TOP    = 0xFF4FC3F7; // cyan-ish
    private static final int C_LINE_BOT    = 0xFFFFB347; // orange — different colour for slot 2
    private static final int C_DOT_OUT     = 0xFFFFFFFF;
    private static final int C_TITLE       = 0xFFFFD700;
    private static final int C_LABEL       = 0xFFCCCCCC;
    private static final int C_UNIT        = 0xFF8888AA;
    private static final int C_HOVER_BG    = 0xCC1A1A2E;
    private static final int C_ROW_HOVER   = 0xFF1A2A4A;
    private static final int C_BTN_OFF     = 0xFF333355;
    private static final int C_BTN_OFF_HV  = 0xFF44447A;
    private static final int C_BTN_ON_TOP  = 0xFF4FC3F7;
    private static final int C_BTN_ON_BOT  = 0xFFFFB347;
    private static final int C_BTN_TXT_OFF = 0xFFAAAACC;
    private static final int C_BTN_TXT_ON  = 0xFF000000;

    private int panelX, panelY;

    public GraphScreen(String sweepComponentName, String sweepUnit, boolean isLogFrequency,
                       List<Double> sweepValues, List<String> probeNames,
                       List<List<Double>> probeData, List<String> probeUnits,
                       int initialIndex) {
        super(Component.literal("Graph"));
        this.sweepComponentName = sweepComponentName;
        this.sweepUnit          = sweepUnit == null ? "" : sweepUnit;
        this.isLogFrequency     = isLogFrequency;
        this.sweepValues        = sweepValues;
        this.probeNames         = probeNames;
        this.probeData          = probeData;
        this.probeUnits         = probeUnits;
        if (initialIndex >= 0 && initialIndex < probeNames.size()) this.slot1 = initialIndex;
    }

    @Override
    protected void init() {
        super.init();
        panelX = (this.width  - PANEL_W) / 2;
        panelY = (this.height - PANEL_H) / 2;
        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(panelX + PANEL_W - 70, panelY + PANEL_H - 26, 62, 20).build());
    }

    // ── slot assignment ─────────────────────────────────────────────────────

    /**
     * Toggles probe {@code idx} in or out of slot 1. If the probe is already
     * in slot 2, slot 2 is cleared first — a probe can be in at most one
     * slot. Clicking a slot's current occupant clears that slot.
     */
    private void toggleSlot1(int idx) {
        if (slot1 == idx) { slot1 = -1; return; }
        if (slot2 == idx) slot2 = -1;
        slot1 = idx;
    }

    private void toggleSlot2(int idx) {
        if (slot2 == idx) { slot2 = -1; return; }
        if (slot1 == idx) slot1 = -1;
        slot2 = idx;
    }

    // ── input handling ──────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        if (btn == 0) {
            // Sidebar rows
            int sx = panelX + SIDEBAR_X;
            int sy = panelY + SIDEBAR_Y;
            if (mx >= sx && mx < sx + SIDEBAR_W && my >= sy && my < sy + SIDEBAR_H) {
                int rel = (int)(my - sy) + sidebarScroll;
                int row = rel / ROW_H;
                if (row >= 0 && row < probeNames.size()) {
                    int rowYAbs = sy + row * ROW_H - sidebarScroll;
                    // Top button hit-test
                    int tX = sx + ROW_PAD_LEFT;
                    int bX = tX + BTN_W + BTN_GAP;
                    int btnTop = rowYAbs + (ROW_H - BTN_H) / 2;
                    if (mx >= tX && mx < tX + BTN_W && my >= btnTop && my < btnTop + BTN_H) {
                        toggleSlot1(row);
                        return true;
                    }
                    if (mx >= bX && mx < bX + BTN_W && my >= btnTop && my < btnTop + BTN_H) {
                        toggleSlot2(row);
                        return true;
                    }
                    // Click on the probe name: convenience shortcut — push to
                    // slot 1, or to slot 2 if slot 1 already holds it.
                    if (mx >= sx + ROW_TEXT_X && mx < sx + SIDEBAR_W) {
                        if (slot1 == row) toggleSlot2(row);
                        else              toggleSlot1(row);
                        return true;
                    }
                }
            }
        }
        return super.mouseClicked(mx, my, btn);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        int sx = panelX + SIDEBAR_X;
        int sy = panelY + SIDEBAR_Y;
        if (mx >= sx && mx < sx + SIDEBAR_W && my >= sy && my < sy + SIDEBAR_H) {
            sidebarScroll = clamp(sidebarScroll - (int)(delta * ROW_H * 2), 0, maxSidebarScroll());
            return true;
        }
        return super.mouseScrolled(mx, my, delta);
    }

    private int maxSidebarScroll() {
        int total = probeNames.size() * ROW_H;
        return Math.max(0, total - SIDEBAR_H);
    }

    // ── render ──────────────────────────────────────────────────────────────

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float pt) {
        renderBackground(g);

        // Clamp scroll first — defensive against probeNames shrinking.
        sidebarScroll = clamp(sidebarScroll, 0, maxSidebarScroll());

        drawPanel(g);
        drawTitle(g);
        drawSidebar(g, mouseX, mouseY);
        drawPlots(g, mouseX, mouseY);

        super.render(g, mouseX, mouseY, pt);
    }

    private void drawPanel(GuiGraphics g) {
        g.fill(panelX, panelY, panelX + PANEL_W, panelY + PANEL_H, C_BG);
        drawRect(g, panelX, panelY, PANEL_W, PANEL_H, 2, C_BORDER);
        g.fill(panelX + 2, panelY + 22, panelX + PANEL_W - 2, panelY + 23, C_SEP);
    }

    private void drawTitle(GuiGraphics g) {
        StringBuilder t = new StringBuilder();
        if (slot1 >= 0) t.append(probeNames.get(slot1));
        if (slot2 >= 0) {
            if (t.length() > 0) t.append("  +  ");
            t.append(probeNames.get(slot2));
        }
        if (t.length() == 0) t.append("(no probe selected)");
        t.append("  vs  ").append(sweepComponentName).append(" (").append(sweepUnit).append(")");
        if (isLogFrequency) t.append("  [log]");
        // Title can grow long with two probes; truncate with ellipsis so it
        // doesn't bleed into the close-button row.
        String title = ellipsize(t.toString(), PANEL_W - 16);
        g.drawCenteredString(font, title, panelX + PANEL_W / 2, panelY + 7, C_TITLE);
    }

    // ── sidebar ─────────────────────────────────────────────────────────────

    private void drawSidebar(GuiGraphics g, int mouseX, int mouseY) {
        int sx = panelX + SIDEBAR_X;
        int sy = panelY + SIDEBAR_Y;
        g.fill(sx, sy, sx + SIDEBAR_W, sy + SIDEBAR_H, C_SIDE_BG);
        drawRect(g, sx - 1, sy - 1, SIDEBAR_W + 2, SIDEBAR_H + 2, 1, C_SEP);

        // Header inside the sidebar — labels which column is T and which is
        // B, otherwise the buttons read as anonymous coloured squares.
        int hdrY = sy - 11;
        g.drawString(font, "T", sx + ROW_PAD_LEFT + 3,                hdrY, C_UNIT);
        g.drawString(font, "B", sx + ROW_PAD_LEFT + BTN_W + BTN_GAP + 3, hdrY, C_UNIT);
        g.drawString(font, "probe", sx + ROW_TEXT_X, hdrY, C_UNIT);

        // Clip rows manually — Minecraft's GuiGraphics has enableScissor but
        // it's overkill here; we just skip rows that don't overlap the box.
        int rows = probeNames.size();
        for (int i = 0; i < rows; i++) {
            int rowY = sy + i * ROW_H - sidebarScroll;
            if (rowY + ROW_H <= sy) continue;
            if (rowY >= sy + SIDEBAR_H) break;

            boolean rowHover = mouseX >= sx && mouseX < sx + SIDEBAR_W
                    && mouseY >= rowY && mouseY < rowY + ROW_H;
            if (rowHover) {
                g.fill(sx, Math.max(rowY, sy), sx + SIDEBAR_W,
                        Math.min(rowY + ROW_H, sy + SIDEBAR_H), C_ROW_HOVER);
            }

            int btnY = rowY + (ROW_H - BTN_H) / 2;
            // Only draw buttons that are at least partly visible.
            if (btnY + BTN_H > sy && btnY < sy + SIDEBAR_H) {
                drawSlotButton(g, sx + ROW_PAD_LEFT,                  btnY, slot1 == i, true,
                        mouseX, mouseY);
                drawSlotButton(g, sx + ROW_PAD_LEFT + BTN_W + BTN_GAP, btnY, slot2 == i, false,
                        mouseX, mouseY);
            }

            // Probe name (truncated to fit the remaining sidebar width).
            String name = probeNames.get(i);
            int nameMaxW = SIDEBAR_W - ROW_TEXT_X - 4;
            String shown = ellipsize(name, nameMaxW);
            int nameY = rowY + (ROW_H - 8) / 2;
            int color = (slot1 == i) ? C_BTN_ON_TOP
                      : (slot2 == i) ? C_BTN_ON_BOT
                      : C_LABEL;
            if (nameY + 8 > sy && nameY < sy + SIDEBAR_H) {
                g.drawString(font, shown, sx + ROW_TEXT_X, nameY, color);
            }
        }

        // Tooltip for the hovered row — shows the full untruncated name +
        // its Y unit, useful for the long sky130 model-parameter keys.
        int hovered = sidebarRowAt(mouseX, mouseY);
        if (hovered >= 0) {
            String full = probeNames.get(hovered);
            String unit = probeUnits.get(hovered);
            String tip  = unit.isEmpty() ? full : (full + " (" + unit + ")");
            drawTooltip(g, tip, mouseX, mouseY);
        }
    }

    private int sidebarRowAt(int mouseX, int mouseY) {
        int sx = panelX + SIDEBAR_X;
        int sy = panelY + SIDEBAR_Y;
        if (mouseX < sx || mouseX >= sx + SIDEBAR_W) return -1;
        if (mouseY < sy || mouseY >= sy + SIDEBAR_H) return -1;
        int rel = (mouseY - sy) + sidebarScroll;
        int row = rel / ROW_H;
        return (row >= 0 && row < probeNames.size()) ? row : -1;
    }

    private void drawSlotButton(GuiGraphics g, int x, int y, boolean on, boolean isTop,
                                 int mouseX, int mouseY) {
        boolean hover = mouseX >= x && mouseX < x + BTN_W && mouseY >= y && mouseY < y + BTN_H;
        int bg = on
                ? (isTop ? C_BTN_ON_TOP : C_BTN_ON_BOT)
                : (hover ? C_BTN_OFF_HV : C_BTN_OFF);
        g.fill(x, y, x + BTN_W, y + BTN_H, bg);
        g.fill(x, y, x + BTN_W, y + 1, C_SEP);
        g.fill(x, y + BTN_H - 1, x + BTN_W, y + BTN_H, C_SEP);
        g.fill(x, y, x + 1, y + BTN_H, C_SEP);
        g.fill(x + BTN_W - 1, y, x + BTN_W, y + BTN_H, C_SEP);
        String lbl = isTop ? "T" : "B";
        int color = on ? C_BTN_TXT_ON : C_BTN_TXT_OFF;
        int tx = x + (BTN_W - font.width(lbl)) / 2;
        int ty = y + (BTN_H - 8) / 2;
        g.drawString(font, lbl, tx, ty, color);
    }

    // ── plot area ──────────────────────────────────────────────────────────

    private void drawPlots(GuiGraphics g, int mouseX, int mouseY) {
        boolean haveTop = slot1 >= 0;
        boolean haveBot = slot2 >= 0;
        int plotTop    = panelY + GT;
        int plotBottom = panelY + GB;
        int plotLeft   = panelX + GL;
        int plotRight  = panelX + GR;
        int gw         = plotRight - plotLeft;

        if (!haveTop && !haveBot) {
            // Empty placeholder so the area still looks like a graph card.
            g.fill(plotLeft, plotTop, plotRight, plotBottom, C_PLOT_BG);
            g.drawCenteredString(font, "Pick a probe on the right (T = top plot, B = bottom plot)",
                    (plotLeft + plotRight) / 2, (plotTop + plotBottom) / 2 - 4, 0xFFAAAAAA);
            return;
        }

        // When only one slot is filled the plot fills the entire area; with
        // both slots filled the area is split in half with a small gutter.
        if (haveTop && haveBot) {
            int half = (plotBottom - plotTop) / 2;
            int gap  = 6;
            int top1Bottom = plotTop + half - gap / 2;
            int top2Top    = plotTop + half + gap / 2;
            hoverIdxTop = drawSinglePlot(g, plotLeft, plotTop,    gw, top1Bottom - plotTop,
                    slot1, true,  C_LINE_TOP, false, mouseX, mouseY);
            hoverIdxBot = drawSinglePlot(g, plotLeft, top2Top,    gw, plotBottom  - top2Top,
                    slot2, false, C_LINE_BOT, true,  mouseX, mouseY);
        } else {
            int onlySlot = haveTop ? slot1 : slot2;
            int colour   = haveTop ? C_LINE_TOP : C_LINE_BOT;
            int idx = drawSinglePlot(g, plotLeft, plotTop, gw, plotBottom - plotTop,
                    onlySlot, true, colour, true, mouseX, mouseY);
            if (haveTop) { hoverIdxTop = idx; hoverIdxBot = -1; }
            else         { hoverIdxBot = idx; hoverIdxTop = -1; }
        }
    }

    /**
     * Renders one plot inside {@code (gx,gy,gw,gh)}. Returns the index of the
     * data point currently under the mouse (or -1).
     *
     * @param drawXAxis when true the X-axis labels are drawn below this plot
     *        — used to suppress duplicate labels on the top plot when both
     *        slots are occupied.
     * @param drawXTitle when true the {@code "<sweep> (<unit>)"} line below
     *        the plot is rendered (only the bottommost plot gets it).
     */
    private int drawSinglePlot(GuiGraphics g, int gx, int gy, int gw, int gh,
                                int probeIdx, boolean drawXAxis, int colour,
                                boolean drawXTitle, int mouseX, int mouseY) {
        List<Double> series = probeData.get(probeIdx);
        String yUnit = probeUnits.get(probeIdx);
        if (sweepValues.size() != series.size() || sweepValues.isEmpty()) {
            g.fill(gx, gy, gx + gw, gy + gh, C_PLOT_BG);
            g.drawCenteredString(font, "No data", gx + gw / 2, gy + gh / 2 - 4, 0xFFFF4444);
            return -1;
        }

        // ── ranges ────────────────────────────────────────────────────────
        double xMinRaw = sweepValues.stream().mapToDouble(d -> d).min().orElse(1);
        double xMaxRaw = sweepValues.stream().mapToDouble(d -> d).max().orElse(2);
        double yMin    = series.stream().mapToDouble(d -> d).min().orElse(0);
        double yMax    = series.stream().mapToDouble(d -> d).max().orElse(1);

        if (yMax == yMin) { yMin -= 1; yMax += 1; }
        double yPad = (yMax - yMin) * 0.10;
        yMin -= yPad; yMax += yPad;

        double xMin, xMax;
        if (isLogFrequency) {
            xMin = Math.log10(Math.max(xMinRaw, 1e-30));
            xMax = Math.log10(Math.max(xMaxRaw, 1e-30));
        } else {
            xMin = xMinRaw;
            xMax = xMaxRaw;
        }
        if (xMax == xMin) { xMin -= 1; xMax += 1; }
        if (!isLogFrequency) {
            double xPad = (xMax - xMin) * 0.05;
            xMin -= xPad; xMax += xPad;
        }

        // ── plot background ───────────────────────────────────────────────
        g.fill(gx, gy, gx + gw, gy + gh, C_PLOT_BG);

        // ── Y ticks (4 intervals if cramped, 6 otherwise) ─────────────────
        int yTicks = gh < 160 ? 4 : 6;
        for (int i = 0; i <= yTicks; i++) {
            double t  = (double) i / yTicks;
            int    py = gy + (int)((1.0 - t) * gh);
            g.fill(gx, py, gx + gw, py + 1, C_GRID);
            double yVal = yMin + (yMax - yMin) * t;
            String yLbl = fmtAxis(yVal);
            g.drawString(font, yLbl, gx - font.width(yLbl) - 3, py - 4, C_LABEL);
        }

        // ── X ticks ───────────────────────────────────────────────────────
        if (drawXAxis) {
            if (isLogFrequency) drawLogXTicks(g, gx, gy, gw, gh, xMin, xMax);
            else                drawLinearXTicks(g, gx, gy, gw, gh, xMin, xMax);
        } else {
            // Still draw vertical gridlines for visual alignment with the
            // bottom plot, just no labels.
            if (isLogFrequency) drawLogXTicks(g, gx, gy, gw, gh, xMin, xMax, false);
            else                drawLinearXTicks(g, gx, gy, gw, gh, xMin, xMax, false);
        }

        // ── axes ──────────────────────────────────────────────────────────
        g.fill(gx,      gy,      gx + 1, gy + gh,      C_AXIS);
        g.fill(gx,      gy + gh, gx + gw, gy + gh + 1, C_AXIS);

        // ── per-plot Y label (top-left of plot) ───────────────────────────
        String yLabel = yUnit.isEmpty()
                ? probeNames.get(probeIdx)
                : probeNames.get(probeIdx) + "  (" + yUnit + ")";
        g.drawString(font, ellipsize(yLabel, gw - 4), gx + 2, gy - 9, colour);

        // ── X title (below the plot, only on the last one) ────────────────
        if (drawXTitle) {
            g.drawCenteredString(font,
                    sweepComponentName + " (" + sweepUnit + ")"
                            + (isLogFrequency ? " - log scale" : ""),
                    gx + gw / 2, gy + gh + 10, C_UNIT);
        }

        // ── data points ───────────────────────────────────────────────────
        int n = sweepValues.size();
        int[] px = new int[n];
        int[] py = new int[n];
        int hoverIdx = -1;

        for (int i = 0; i < n; i++) {
            double rawX = sweepValues.get(i);
            double logX = isLogFrequency ? Math.log10(Math.max(rawX, 1e-30)) : rawX;
            double xf   = (logX - xMin) / (xMax - xMin);
            double yf   = (series.get(i) - yMin) / (yMax - yMin);
            px[i] = clamp(gx + (int)(xf * gw), gx, gx + gw - 1);
            py[i] = clamp(gy + gh - 1 - (int)(yf * (gh - 1)), gy, gy + gh - 1);
            if (Math.abs(mouseX - px[i]) <= 5 && Math.abs(mouseY - py[i]) <= 5) hoverIdx = i;
        }

        for (int i = 1; i < n; i++) drawLine(g, px[i-1], py[i-1], px[i], py[i], colour);
        if (n <= 200) {
            for (int i = 0; i < n; i++) {
                g.fill(px[i] - 2, py[i] - 2, px[i] + 3, py[i] + 3, C_DOT_OUT);
                g.fill(px[i] - 1, py[i] - 1, px[i] + 2, py[i] + 2, colour);
            }
        }

        if (hoverIdx >= 0) {
            double rawXH = sweepValues.get(hoverIdx);
            String xStr = isLogFrequency
                    ? ComponentEditScreen.formatValue(rawXH) + "Hz"
                    : ComponentEditScreen.formatValue(rawXH) + sweepUnit;
            String yStr = ComponentEditScreen.formatValue(series.get(hoverIdx))
                    + (yUnit.isEmpty() ? "" : " " + yUnit);
            String tip  = yStr + " at " + xStr;
            int tw = font.width(tip) + 6, th = 12;
            int tx = clamp(px[hoverIdx] + 6, gx, gx + gw - tw);
            int ty = clamp(py[hoverIdx] - 16, gy, gy + gh - th);
            g.fill(tx - 1, ty - 1, tx + tw + 1, ty + th + 1, C_BORDER);
            g.fill(tx, ty, tx + tw, ty + th, C_HOVER_BG);
            g.drawString(font, tip, tx + 3, ty + 2, 0xFFFFFFFF);

            g.fill(px[hoverIdx] - 3, py[hoverIdx] - 3, px[hoverIdx] + 4, py[hoverIdx] + 4, 0xFFFFFF00);
            g.fill(px[hoverIdx] - 1, py[hoverIdx] - 1, px[hoverIdx] + 2, py[hoverIdx] + 2, colour);
        }

        return hoverIdx;
    }

    // ── tick helpers ────────────────────────────────────────────────────────

    private void drawLinearXTicks(GuiGraphics g, int gx, int gy, int gw, int gh,
                                   double xMin, double xMax) {
        drawLinearXTicks(g, gx, gy, gw, gh, xMin, xMax, true);
    }

    private void drawLinearXTicks(GuiGraphics g, int gx, int gy, int gw, int gh,
                                   double xMin, double xMax, boolean labels) {
        final int X_TICKS = 6;
        for (int i = 0; i <= X_TICKS; i++) {
            double t     = (double) i / X_TICKS;
            double xReal = xMin + (xMax - xMin) * t;
            int    px    = gx + (int)(t * gw);
            g.fill(px, gy, px + 1, gy + gh, C_GRID);
            if (!labels) continue;
            String xLbl  = fmtAxis(xReal);
            g.drawString(font, xLbl, px - font.width(xLbl) / 2, gy + gh + 1, C_LABEL);
        }
    }

    private void drawLogXTicks(GuiGraphics g, int gx, int gy, int gw, int gh,
                                double xMin, double xMax) {
        drawLogXTicks(g, gx, gy, gw, gh, xMin, xMax, true);
    }

    private void drawLogXTicks(GuiGraphics g, int gx, int gy, int gw, int gh,
                                double xMin, double xMax, boolean labels) {
        int decLo = (int) Math.floor(xMin);
        int decHi = (int) Math.ceil(xMax);

        List<int[]> ticks = new ArrayList<>();
        for (int d = decLo; d <= decHi; d++) {
            double xf = (d - xMin) / (xMax - xMin);
            if (xf < 0 || xf > 1) continue;
            int px = gx + (int)(xf * gw);
            ticks.add(new int[]{px, d});
        }

        int lastLabelEnd = Integer.MIN_VALUE;
        for (int[] tick : ticks) {
            int px = tick[0];
            int d  = tick[1];
            g.fill(px, gy, px + 1, gy + gh, C_GRID);

            if (!labels) continue;
            double freq  = Math.pow(10, d);
            String xLbl  = fmtFreqDecade(freq);
            int    lx    = px - font.width(xLbl) / 2;
            if (lx > lastLabelEnd) {
                g.drawString(font, xLbl, lx, gy + gh + 1, C_LABEL);
                lastLabelEnd = lx + font.width(xLbl) + 2;
            }
        }
    }

    // ── formatting / drawing helpers ────────────────────────────────────────

    private static String fmtAxis(double val) {
        if (val == 0.0) return "0";
        double abs = Math.abs(val);
        double[][] tiers = {
                {1e12,  1e15,  1e12},
                {1e9,   1e12,  1e9},
                {1e6,   1e9,   1e6},
                {1e3,   1e6,   1e3},
                {1e0,   1e3,   1e0},
                {1e-3,  1e0,   1e-3},
                {1e-6,  1e-3,  1e-6},
                {1e-9,  1e-6,  1e-9},
                {1e-12, 1e-9,  1e-12},
        };
        String[] names = {"T", "G", "M", "k", "", "m", "u", "n", "p"};
        for (int i = 0; i < tiers.length; i++) {
            if (abs >= tiers[i][0] && abs < tiers[i][1]) {
                double scaled = val / tiers[i][2];
                return trimZeros(String.format("%.2f", scaled)) + names[i];
            }
        }
        return trimZeros(String.format("%.2f", val));
    }

    private static String fmtFreqDecade(double freq) {
        if (freq >= 1e6) return trimZeros(String.format("%.2f", freq / 1e6)) + "M";
        if (freq >= 1e3) return trimZeros(String.format("%.2f", freq / 1e3)) + "k";
        return trimZeros(String.format("%.2f", freq));
    }

    private static String trimZeros(String s) {
        if (!s.contains(".")) return s;
        s = s.replaceAll("0+$", "");
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s;
    }

    /**
     * Truncates {@code s} with a trailing "…" so its rendered width fits in
     * {@code maxPx}. Returns {@code s} unchanged when it already fits.
     */
    private String ellipsize(String s, int maxPx) {
        if (font.width(s) <= maxPx) return s;
        String ell = "…";
        int ellW = font.width(ell);
        for (int len = s.length() - 1; len > 0; len--) {
            String candidate = s.substring(0, len);
            if (font.width(candidate) + ellW <= maxPx) return candidate + ell;
        }
        return ell;
    }

    private void drawTooltip(GuiGraphics g, String text, int mouseX, int mouseY) {
        int tw = font.width(text) + 6;
        int th = 12;
        int tx = clamp(mouseX + 10, panelX + 4, panelX + PANEL_W - tw - 4);
        int ty = clamp(mouseY + 12, panelY + 4, panelY + PANEL_H - th - 4);
        g.fill(tx - 1, ty - 1, tx + tw + 1, ty + th + 1, C_BORDER);
        g.fill(tx, ty, tx + tw, ty + th, C_HOVER_BG);
        g.drawString(font, text, tx + 3, ty + 2, 0xFFFFFFFF);
    }

    private static void drawLine(GuiGraphics g, int x1, int y1, int x2, int y2, int color) {
        int dx = Math.abs(x2 - x1), dy = Math.abs(y2 - y1);
        int steps = Math.max(dx, dy);
        if (steps == 0) { g.fill(x1, y1, x1 + 1, y1 + 1, color); return; }
        for (int i = 0; i <= steps; i++) {
            int x = x1 + (x2 - x1) * i / steps;
            int y = y1 + (y2 - y1) * i / steps;
            g.fill(x, y, x + 1, y + 1, color);
        }
    }

    private static void drawRect(GuiGraphics g, int x, int y, int w, int h, int t, int color) {
        g.fill(x,         y,         x + w, y + t,     color);
        g.fill(x,         y + h - t, x + w, y + h,     color);
        g.fill(x,         y,         x + t, y + h,     color);
        g.fill(x + w - t, y,         x + w, y + h,     color);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    @Override public boolean isPauseScreen() { return false; }
}
