package com.circuitsim.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Full-screen graph that plots one probe's values against the parametric sweep values.
 * Opened client-side when the player clicks a probe link in chat or in the result book.
 */
public class GraphScreen extends Screen {

    private final String probeLabel;
    private final String sweepComponentName;
    private final String sweepUnit;
    private final List<Double> sweepValues;
    private final List<Double> probeValues;
    private final boolean isVoltage;

    // ── layout (relative to panelX/panelY) ───────────────────────────────────
    private static final int PANEL_W = 360;
    private static final int PANEL_H = 230;
    // graph area edges (offsets from panel origin)
    private static final int GL = 52;   // left   (room for Y labels)
    private static final int GR = 345;  // right
    private static final int GT = 30;   // top    (room for title)
    private static final int GB = 190;  // bottom (room for X labels)

    // ── colours ───────────────────────────────────────────────────────────────
    private static final int C_BG       = 0xFF1A1A2E;
    private static final int C_PLOT_BG  = 0xFF16213E;
    private static final int C_BORDER   = 0xFF4A90D9;
    private static final int C_SEP      = 0xFF444466;
    private static final int C_GRID     = 0xFF2A2A4A;
    private static final int C_AXIS     = 0xFF8888AA;
    private static final int C_LINE     = 0xFF4FC3F7;
    private static final int C_DOT_OUT  = 0xFFFFFFFF;
    private static final int C_DOT_IN   = 0xFF4FC3F7;
    private static final int C_TITLE    = 0xFFFFD700;
    private static final int C_LABEL    = 0xFFCCCCCC;
    private static final int C_UNIT     = 0xFF8888AA;
    private static final int C_HOVER_BG = 0xCC1A1A2E;

    private int panelX, panelY;

    // hover tooltip state
    private int hoverIdx = -1;

    public GraphScreen(String probeLabel, String sweepComponentName, String sweepUnit,
                       List<Double> sweepValues, List<Double> probeValues, boolean isVoltage) {
        super(Component.literal("Graph"));
        this.probeLabel         = probeLabel;
        this.sweepComponentName = sweepComponentName;
        this.sweepUnit          = sweepUnit;
        this.sweepValues        = sweepValues;
        this.probeValues        = probeValues;
        this.isVoltage          = isVoltage;
    }

    @Override
    protected void init() {
        super.init();
        panelX = (this.width  - PANEL_W) / 2;
        panelY = (this.height - PANEL_H) / 2;

        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(panelX + PANEL_W - 66, panelY + PANEL_H - 22, 58, 16).build());
    }

    // ── rendering ─────────────────────────────────────────────────────────────

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float pt) {
        renderBackground(g);
        drawPanel(g);
        drawTitle(g);

        if (!sweepValues.isEmpty() && !probeValues.isEmpty()
                && sweepValues.size() == probeValues.size()) {
            drawGraph(g, mouseX, mouseY);
        } else {
            g.drawCenteredString(font, "No data available",
                    panelX + PANEL_W / 2, panelY + (GT + GB) / 2, 0xFFFF4444);
        }

        super.render(g, mouseX, mouseY, pt);
    }

    private void drawPanel(GuiGraphics g) {
        g.fill(panelX, panelY, panelX + PANEL_W, panelY + PANEL_H, C_BG);
        drawRect(g, panelX, panelY, PANEL_W, PANEL_H, 2, C_BORDER);
        g.fill(panelX + 2, panelY + 22, panelX + PANEL_W - 2, panelY + 23, C_SEP);
    }

    private void drawTitle(GuiGraphics g) {
        String yUnit  = isVoltage ? "(V)" : "(A)";
        String title  = probeLabel + "  vs  " + sweepComponentName + " (" + sweepUnit + ")";
        g.drawCenteredString(font, title, panelX + PANEL_W / 2, panelY + 7, C_TITLE);
        g.drawString(font, yUnit, panelX + 2, panelY + GT, C_UNIT);
    }

    private void drawGraph(GuiGraphics g, int mouseX, int mouseY) {
        int gx = panelX + GL;
        int gy = panelY + GT;
        int gw = GR - GL;
        int gh = GB - GT;

        // ── ranges ────────────────────────────────────────────────────────────
        double xMin = sweepValues.stream().mapToDouble(d -> d).min().orElse(0);
        double xMax = sweepValues.stream().mapToDouble(d -> d).max().orElse(1);
        double yMin = probeValues.stream().mapToDouble(d -> d).min().orElse(0);
        double yMax = probeValues.stream().mapToDouble(d -> d).max().orElse(1);
        if (xMax == xMin) { xMin -= 1; xMax += 1; }
        if (yMax == yMin) { yMin -= 1; yMax += 1; }
        double xPad = (xMax - xMin) * 0.05;
        double yPad = (yMax - yMin) * 0.10;
        xMin -= xPad; xMax += xPad;
        yMin -= yPad; yMax += yPad;

        // ── plot background ────────────────────────────────────────────────────
        g.fill(gx, gy, gx + gw, gy + gh, C_PLOT_BG);

        // ── grid + tick labels ─────────────────────────────────────────────────
        final int TICKS = 5;
        for (int i = 0; i <= TICKS; i++) {
            double t = (double) i / TICKS;

            // horizontal grid line + Y label
            int py = gy + (int)((1.0 - t) * gh);
            g.fill(gx, py, gx + gw, py + 1, C_GRID);
            String yLbl = ComponentEditScreen.formatValue(yMin + (yMax - yMin) * t);
            g.drawString(font, yLbl, gx - font.width(yLbl) - 3, py - 4, C_LABEL);

            // vertical grid line + X label
            int px = gx + (int)(t * gw);
            g.fill(px, gy, px + 1, gy + gh, C_GRID);
            String xLbl = ComponentEditScreen.formatValue(xMin + (xMax - xMin) * t);
            g.drawString(font, xLbl, px - font.width(xLbl) / 2, panelY + GB + 3, C_LABEL);
        }

        // ── axes ──────────────────────────────────────────────────────────────
        g.fill(gx,      gy,      gx + 1, gy + gh,     C_AXIS);  // Y axis
        g.fill(gx,      gy + gh, gx + gw, gy + gh + 1, C_AXIS); // X axis

        // ── X-axis label ──────────────────────────────────────────────────────
        g.drawCenteredString(font, sweepComponentName + " (" + sweepUnit + ")",
                panelX + (GL + GR) / 2, panelY + GB + 14, C_UNIT);

        // ── data line + hover detection ───────────────────────────────────────
        int n = sweepValues.size();
        int[] px = new int[n];
        int[] py = new int[n];
        hoverIdx = -1;

        for (int i = 0; i < n; i++) {
            double xf = (sweepValues.get(i) - xMin) / (xMax - xMin);
            double yf = (probeValues.get(i) - yMin) / (yMax - yMin);
            px[i] = clamp(gx + (int)(xf * gw), gx, gx + gw - 1);
            py[i] = clamp(gy + gh - 1 - (int)(yf * (gh - 1)), gy, gy + gh - 1);
            if (Math.abs(mouseX - px[i]) <= 5 && Math.abs(mouseY - py[i]) <= 5) {
                hoverIdx = i;
            }
        }

        // draw line segments
        for (int i = 1; i < n; i++) {
            drawLine(g, px[i - 1], py[i - 1], px[i], py[i], C_LINE);
        }

        // draw dots (outer then inner for a nice ring effect)
        for (int i = 0; i < n; i++) {
            g.fill(px[i] - 2, py[i] - 2, px[i] + 3, py[i] + 3, C_DOT_OUT);
            g.fill(px[i] - 1, py[i] - 1, px[i] + 2, py[i] + 2, C_DOT_IN);
        }

        // ── hover tooltip ─────────────────────────────────────────────────────
        if (hoverIdx >= 0) {
            String xStr = ComponentEditScreen.formatValue(sweepValues.get(hoverIdx)) + sweepUnit;
            String yStr = ComponentEditScreen.formatValue(probeValues.get(hoverIdx))
                    + (isVoltage ? " V" : " A");
            String tip  = xStr + " at " + yStr;
            int tw = font.width(tip) + 6;
            int th = 12;
            int tx = clamp(px[hoverIdx] + 6, gx, gx + gw - tw);
            int ty = clamp(py[hoverIdx] - 16, gy, gy + gh - th);
            g.fill(tx - 1, ty - 1, tx + tw + 1, ty + th + 1, C_BORDER);
            g.fill(tx, ty, tx + tw, ty + th, C_HOVER_BG);
            g.drawString(font, tip, tx + 3, ty + 2, 0xFFFFFFFF);

            // highlighted dot
            g.fill(px[hoverIdx] - 3, py[hoverIdx] - 3, px[hoverIdx] + 4, py[hoverIdx] + 4, 0xFFFFFF00);
            g.fill(px[hoverIdx] - 1, py[hoverIdx] - 1, px[hoverIdx] + 2, py[hoverIdx] + 2, C_DOT_IN);
        }
    }

    // ── drawing helpers ───────────────────────────────────────────────────────

    /** Bresenham-style 1-px line using fill(). */
    private static void drawLine(GuiGraphics g, int x1, int y1, int x2, int y2, int color) {
        int dx = Math.abs(x2 - x1);
        int dy = Math.abs(y2 - y1);
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

    // ── misc ──────────────────────────────────────────────────────────────────

    @Override public boolean isPauseScreen() { return false; }
}