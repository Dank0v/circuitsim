package com.circuitsim.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Graph viewer for one simulation session. The sidebar groups probes by their
 * base name (the prefix before {@code @} — sweep variants like
 * {@code gain_db@300fF} all collapse into a single "gain_db" entry). Each
 * group has an expand/collapse chevron, and group-level T/B buttons toggle
 * every variant into the matching slot at once.
 *
 * <p>A plot slot can mix probes from different groups, so unrelated
 * quantities can be overlaid on the same axes deliberately (e.g. v(in) with
 * v(out)). When a slot's occupants span more than one group the Y-axis stem
 * is dropped, and the unit is shown only when all occupants agree on it; the
 * sidebar swatches, hover tooltip and cursor readout identify the individual
 * curves. A probe still lives in only one slot (top or bottom) at a time.
 *
 * <p>The T/B buttons cycle each curve (or group) through three states:
 * off → left axis → right axis (shown as "R") → off. Right-axis curves get
 * their own auto-scaled Y axis rendered at the plot's right edge, so
 * quantities with very different ranges — an amplifier's gain in dB and its
 * phase in degrees — can share a plot without squashing each other.
 *
 * <p>When {@code isLogFrequency} is true the shared X axis is rendered on a
 * log10 scale, the standard presentation for AC Bode-style plots.
 *
 * <p>Each plot carries its own top-right toolbar: <b>Log</b> switches that
 * plot's Y axis to a log10 scale (falling back to linear when the data has no
 * positive samples), and <b>Cur</b> shows a pair of vertical cursors. Cursor 1
 * is dragged with the left mouse button and cursor 2 with the right
 * (OrCAD-style), each snapping to the nearest sweep sample. The readout reports
 * X and Y at both cursors plus the dx/dy between them. These toggles are
 * independent for the top and bottom plots.
 */
public class GraphScreen extends Screen {

    private final String              sweepComponentName;
    private final String              sweepUnit;
    private final boolean             isLogFrequency;
    private final List<Double>        sweepValues;
    private final List<String>        probeNames;
    private final List<List<Double>>  probeData;
    private final List<String>        probeUnits;
    /**
     * Session id of the companion FFT result set (ngspice linearize+fft of
     * every probed signal, computed alongside the transient run), or -1 when
     * none exists. When set, each plot's toolbar shows an FFT button that
     * opens the spectrum via the same /circuitsim graph command the chat
     * links use.
     */
    private final int fftSessionId;

    // Slot occupants — ordered set of probe indices. Insertion order picks
    // the palette colour. slot1 = top plot, slot2 = bottom.
    private final Set<Integer> slot1 = new LinkedHashSet<>();
    private final Set<Integer> slot2 = new LinkedHashSet<>();
    /**
     * Probes whose curve reads against the right-hand Y axis of whatever plot
     * they're in. The T/B buttons cycle each curve: off → left axis → right
     * axis → off, so e.g. gain (dB) and phase (deg) can share a plot without
     * one squashing the other. Ignored while it would leave the left axis
     * empty (a lone right-axis curve renders as a normal left-axis plot).
     */
    private final Set<Integer> rightAxis = new HashSet<>();

    // ── group model ─────────────────────────────────────────────────────────
    // Groups are derived from probe names by taking the prefix before '@'.
    // Probes without '@' form a single-variant group named after the whole
    // probe.
    private final List<String> groupOrder = new ArrayList<>();
    private final Map<String, List<Integer>> groupMembers = new LinkedHashMap<>();
    private final Set<String> expandedGroups = new HashSet<>();
    private final List<Row> visibleRows = new ArrayList<>();

    private static final class Row {
        enum Type { GROUP, VARIANT }
        final Type   type;
        final String groupName;
        final int    probeIdx;   // -1 for GROUP rows
        Row(Type t, String g, int idx) { type = t; groupName = g; probeIdx = idx; }
    }

    // Sidebar scroll offset, in pixels.
    private int sidebarScroll = 0;

    // ── panel layout ─────────────────────────────────────────────────────────
    private static final int PANEL_W = 660, PANEL_H = 380;
    private static final int GL = 50, GR = 510;
    private static final int GT = 32, GB = 330;

    // ── sidebar layout ───────────────────────────────────────────────────────
    private static final int SIDEBAR_X     = 520;
    private static final int SIDEBAR_Y     = 32;
    private static final int SIDEBAR_W     = 132;
    private static final int SIDEBAR_H     = 318;
    private static final int ROW_H         = 13;
    private static final int CHEV_W        = 9;
    private static final int BTN_W         = 13;
    private static final int BTN_H         = 11;
    private static final int BTN_GAP       = 2;
    private static final int ROW_PAD_LEFT  = 4;
    // Layout: [chev] [T] [B] [swatch?] groupName / variantName(indented)
    private static final int X_CHEV        = ROW_PAD_LEFT;
    private static final int X_BTN_T       = X_CHEV + CHEV_W;
    private static final int X_BTN_B       = X_BTN_T + BTN_W + BTN_GAP;
    private static final int X_TEXT        = X_BTN_B + BTN_W + BTN_GAP + 2;
    private static final int VARIANT_INDENT = 6;
    private static final int SWATCH_W      = 6;
    private static final int SWATCH_GAP    = 3;

    // ── colours ─────────────────────────────────────────────────────────────
    private static final int C_BG          = 0xFF1A1A2E;
    private static final int C_PLOT_BG     = 0xFF16213E;
    private static final int C_SIDE_BG     = 0xFF12121F;
    private static final int C_BORDER      = 0xFF4A90D9;
    private static final int C_SEP         = 0xFF444466;
    private static final int C_GRID        = 0xFF2A2A4A;
    private static final int C_AXIS        = 0xFF8888AA;
    private static final int C_DOT_OUT     = 0xFFFFFFFF;
    private static final int C_TITLE       = 0xFFFFD700;
    private static final int C_LABEL       = 0xFFCCCCCC;
    private static final int C_UNIT        = 0xFF8888AA;
    private static final int C_HOVER_BG    = 0xCC1A1A2E;
    private static final int C_ROW_HOVER   = 0xFF1A2A4A;
    private static final int C_BTN_OFF     = 0xFF333355;
    private static final int C_BTN_OFF_HV  = 0xFF44447A;
    private static final int C_BTN_PARTIAL = 0xFF6A6A8A;   // group with some-but-not-all
    private static final int C_BTN_TXT_OFF = 0xFFAAAACC;
    private static final int C_BTN_TXT_ON  = 0xFF000000;
    private static final int C_CHEV        = 0xFFAAAACC;
    private static final int[] PALETTE_TOP = {
            0xFF4FC3F7, 0xFFB388FF, 0xFF81C784, 0xFFFFD54F,
            0xFFE57373, 0xFFF06292, 0xFF80CBC4, 0xFFAED581,
    };
    private static final int[] PALETTE_BOT = {
            0xFFFFB347, 0xFFFF8A65, 0xFFCE93D8, 0xFF9FA8DA,
            0xFFFFF59D, 0xFFA1887F, 0xFFC5E1A5, 0xFF90CAF9,
    };

    private int panelX, panelY;

    // ── per-plot Y-axis log toggle and cursors ───────────────────────────────
    // "Top" tracks slot1 (top plot), "Bot" tracks slot2 (bottom plot). Cursors
    // are stored as sweep-sample indices (snapped to data points), max 2 per
    // plot. With two cursors the readout reports dx / dy between them.
    private boolean logYTop = false, logYBot = false;
    private boolean cursorModeTop = false, cursorModeBot = false;
    // {cursor1, cursor2} as sweep-sample indices, or -1 when unplaced. Cursor 1
    // is dragged with the left mouse button, cursor 2 with the right
    // (OrCAD-style); both appear when cursor mode is switched on.
    private final int[] cursorsTop = { -1, -1 };
    private final int[] cursorsBot = { -1, -1 };
    private static final int[] CURSOR_COLOURS = { 0xFFFFEE58, 0xFF40C4FF };

    // Hit rectangles {x, y, w, h} recomputed every render so mouseClicked can
    // map clicks onto the on-plot toggle buttons and the plot interior. Null
    // when the corresponding plot isn't currently drawn.
    private int[] logBtnTop, curBtnTop, fftBtnTop, plotRectTop;
    private int[] logBtnBot, curBtnBot, fftBtnBot, plotRectBot;

    public GraphScreen(String sweepComponentName, String sweepUnit, boolean isLogFrequency,
                       boolean defaultLogY, int fftSessionId,
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
        this.logYTop            = defaultLogY;
        this.logYBot            = defaultLogY;
        this.fftSessionId       = fftSessionId;
        buildGroups();
        if (initialIndex >= 0 && initialIndex < probeNames.size()) {
            this.slot1.add(initialIndex);
            // Expand the group containing the initial selection so the user
            // sees what's underneath it without having to click.
            expandedGroups.add(groupOf(initialIndex));
        }
        rebuildVisibleRows();
    }

    private void buildGroups() {
        for (int i = 0; i < probeNames.size(); i++) {
            String g = baseName(probeNames.get(i));
            groupMembers.computeIfAbsent(g, k -> { groupOrder.add(k); return new ArrayList<>(); })
                    .add(i);
        }
    }

    private static String baseName(String probeName) {
        int at = suffixSplit(probeName);
        return at < 0 ? probeName : probeName.substring(0, at);
    }

    /**
     * Index of the '@' that separates the probe's base name from the suffix
     * we appended (e.g. {@code @20C} or {@code @Wn=9u}), or -1 if there's no
     * such separator. A leading '@' is part of the ngspice device-parameter
     * syntax ({@code @m.xm1.msky130_fd_pr__nfet_01v8_lvt[gm]}) and must NOT
     * be treated as the split point — otherwise every {@code @...[gm]} and
     * {@code @...[vth]} probe collapses into one empty-named group.
     */
    private static int suffixSplit(String probeName) {
        int at = probeName.lastIndexOf('@');
        return at > 0 ? at : -1;
    }

    private String groupOf(int probeIdx) {
        return baseName(probeNames.get(probeIdx));
    }

    private void rebuildVisibleRows() {
        visibleRows.clear();
        for (String g : groupOrder) {
            visibleRows.add(new Row(Row.Type.GROUP, g, -1));
            if (expandedGroups.contains(g) && groupMembers.get(g).size() > 1) {
                for (int idx : groupMembers.get(g)) {
                    visibleRows.add(new Row(Row.Type.VARIANT, g, idx));
                }
            }
        }
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
     * Adds {@code idx} to slot 1 (top). Probes from different groups may share
     * a slot — overlaying unrelated quantities is allowed; the Y-axis label
     * and unit fall back to whatever the occupants have in common. A probe
     * still lives in only one slot at a time, so it's removed from slot 2.
     */
    private void addToSlot1(int idx) {
        slot2.remove(idx);
        slot1.add(idx);
        rightAxis.remove(idx);
    }

    private void addToSlot2(int idx) {
        slot1.remove(idx);
        slot2.add(idx);
        rightAxis.remove(idx);
    }

    /** Cycle a single variant through the top slot: off → left → right → off. */
    private void toggleVariantSlot1(int idx) {
        if (!slot1.contains(idx))            addToSlot1(idx);
        else if (!rightAxis.contains(idx))   rightAxis.add(idx);
        else                                 { slot1.remove(idx); rightAxis.remove(idx); }
    }

    private void toggleVariantSlot2(int idx) {
        if (!slot2.contains(idx))            addToSlot2(idx);
        else if (!rightAxis.contains(idx))   rightAxis.add(idx);
        else                                 { slot2.remove(idx); rightAxis.remove(idx); }
    }

    /**
     * Cycles every variant of {@code group} through the top slot together:
     * (none/partial) → all on the left axis → all on the right axis → off.
     */
    private void toggleGroupSlot1(String group) {
        cycleGroup(group, slot1, slot2);
    }

    private void toggleGroupSlot2(String group) {
        cycleGroup(group, slot2, slot1);
    }

    private void cycleGroup(String group, Set<Integer> slot, Set<Integer> other) {
        List<Integer> members = groupMembers.get(group);
        if (members == null || members.isEmpty()) return;
        boolean allIn = true, allRight = true;
        for (int idx : members) {
            if (!slot.contains(idx))      { allIn = false; break; }
            if (!rightAxis.contains(idx)) allRight = false;
        }
        if (allIn && allRight) {
            for (int idx : members) { slot.remove(idx); rightAxis.remove(idx); }
        } else if (allIn) {
            rightAxis.addAll(members);
        } else {
            for (int idx : members) {
                other.remove(idx);
                slot.add(idx);
                rightAxis.remove(idx);
            }
        }
    }

    /**
     * Returns the group name shared by every occupant of this slot, or null
     * when the slot is empty or holds a mix of groups — callers use it for
     * the slot summary and Y-axis stem, which have no single honest name in
     * the mixed case.
     */
    private String slotGroup(java.util.Collection<Integer> slot) {
        String common = null;
        for (int idx : slot) {
            String g = groupOf(idx);
            if (common == null) common = g;
            else if (!common.equals(g)) return null;
        }
        return common;
    }

    /** Returns the palette colour assigned to probe {@code idx} in its slot, or 0. */
    private int colourOf(int idx) {
        int order = indexIn(slot1, idx);
        if (order >= 0) return PALETTE_TOP[order % PALETTE_TOP.length];
        order = indexIn(slot2, idx);
        if (order >= 0) return PALETTE_BOT[order % PALETTE_BOT.length];
        return 0;
    }

    private static int indexIn(Set<Integer> set, int idx) {
        int i = 0;
        for (Integer v : set) {
            if (v == idx) return i;
            i++;
        }
        return -1;
    }

    /**
     * "all", "right" (all present and all on the right axis), "some", or
     * "none" of this group's variants in the given slot.
     */
    private String groupSlotState(String group, Set<Integer> slot) {
        List<Integer> members = groupMembers.get(group);
        if (members == null || members.isEmpty()) return "none";
        int hits = 0, right = 0;
        for (int idx : members) {
            if (slot.contains(idx)) hits++;
            if (rightAxis.contains(idx)) right++;
        }
        if (hits == 0) return "none";
        if (hits == members.size()) return right == members.size() ? "right" : "all";
        return "some";
    }

    // ── input handling ──────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        if (btn == 0 && handlePlotButtons(mx, my)) return true;
        // Left button drives cursor 1, right button cursor 2 (OrCAD-style).
        if (btn == 0 && updateCursor(mx, my, 0)) return true;
        if (btn == 1 && updateCursor(mx, my, 1)) return true;
        if (btn == 0) {
            int sx = panelX + SIDEBAR_X;
            int sy = panelY + SIDEBAR_Y;
            if (mx >= sx && mx < sx + SIDEBAR_W && my >= sy && my < sy + SIDEBAR_H) {
                int rel = (int)(my - sy) + sidebarScroll;
                int rowIdx = rel / ROW_H;
                if (rowIdx >= 0 && rowIdx < visibleRows.size()) {
                    Row row = visibleRows.get(rowIdx);
                    int rowYAbs = sy + rowIdx * ROW_H - sidebarScroll;
                    int btnTop = rowYAbs + (ROW_H - BTN_H) / 2;
                    int tX = sx + X_BTN_T;
                    int bX = sx + X_BTN_B;

                    // T button
                    if (mx >= tX && mx < tX + BTN_W && my >= btnTop && my < btnTop + BTN_H) {
                        if (row.type == Row.Type.GROUP) toggleGroupSlot1(row.groupName);
                        else                            toggleVariantSlot1(row.probeIdx);
                        return true;
                    }
                    // B button
                    if (mx >= bX && mx < bX + BTN_W && my >= btnTop && my < btnTop + BTN_H) {
                        if (row.type == Row.Type.GROUP) toggleGroupSlot2(row.groupName);
                        else                            toggleVariantSlot2(row.probeIdx);
                        return true;
                    }
                    // Chevron — only on group rows that actually have variants.
                    int cX = sx + X_CHEV;
                    if (row.type == Row.Type.GROUP && groupMembers.get(row.groupName).size() > 1
                            && mx >= cX && mx < cX + CHEV_W
                            && my >= rowYAbs && my < rowYAbs + ROW_H) {
                        toggleExpanded(row.groupName);
                        return true;
                    }
                    // Click on name text — group: toggle expand; variant: shortcut into slot 1.
                    if (mx >= sx + X_TEXT && mx < sx + SIDEBAR_W) {
                        if (row.type == Row.Type.GROUP) {
                            if (groupMembers.get(row.groupName).size() > 1) {
                                toggleExpanded(row.groupName);
                            } else {
                                int idx = groupMembers.get(row.groupName).get(0);
                                if (slot1.contains(idx)) toggleVariantSlot2(idx);
                                else                     toggleVariantSlot1(idx);
                            }
                        } else {
                            if (slot1.contains(row.probeIdx)) toggleVariantSlot2(row.probeIdx);
                            else                              toggleVariantSlot1(row.probeIdx);
                        }
                        return true;
                    }
                }
            }
        }
        return super.mouseClicked(mx, my, btn);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) {
        // Drag cursor 1 with the left button, cursor 2 with the right.
        if (btn == 0 && updateCursor(mx, my, 0)) return true;
        if (btn == 1 && updateCursor(mx, my, 1)) return true;
        return super.mouseDragged(mx, my, btn, dx, dy);
    }

    /**
     * Handles clicks on the on-plot Log / Cursor toggle buttons. Returns true
     * when the click landed on a button. Toggling Cursor on places both
     * cursors at the sweep endpoints; toggling it off clears them.
     */
    private boolean handlePlotButtons(double mx, double my) {
        if (hitRect(logBtnTop, mx, my)) { logYTop = !logYTop; return true; }
        if (hitRect(curBtnTop, mx, my)) {
            cursorModeTop = !cursorModeTop;
            if (cursorModeTop) initCursors(cursorsTop); else clearCursors(cursorsTop);
            return true;
        }
        if (hitRect(fftBtnTop, mx, my)) { openFft(); return true; }
        if (hitRect(logBtnBot, mx, my)) { logYBot = !logYBot; return true; }
        if (hitRect(curBtnBot, mx, my)) {
            cursorModeBot = !cursorModeBot;
            if (cursorModeBot) initCursors(cursorsBot); else clearCursors(cursorsBot);
            return true;
        }
        if (hitRect(fftBtnBot, mx, my)) { openFft(); return true; }
        return false;
    }

    /**
     * Opens the companion FFT session — the spectra ngspice computed
     * (linearize + fft) for every probed signal of this transient run. Goes
     * through the same /circuitsim graph command the chat links use, so the
     * server ships the spectrum data and replaces this screen with its
     * GraphScreen.
     */
    private void openFft() {
        if (fftSessionId < 0) return;
        var player = net.minecraft.client.Minecraft.getInstance().player;
        if (player == null) return;
        player.connection.sendCommand("circuitsim graph " + fftSessionId + " 0");
    }

    /**
     * Moves one cursor ({@code slot} 0 = cursor 1, 1 = cursor 2) to the sweep
     * sample nearest the pointer, provided cursor mode is on and the pointer is
     * over that plot. Returns true when a cursor was updated.
     */
    private boolean updateCursor(double mx, double my, int slot) {
        if (cursorModeTop && hitRect(plotRectTop, mx, my)) {
            cursorsTop[slot] = nearestSample(plotRectTop, mx);
            return true;
        }
        if (cursorModeBot && hitRect(plotRectBot, mx, my)) {
            cursorsBot[slot] = nearestSample(plotRectBot, mx);
            return true;
        }
        return false;
    }

    /** Index of the sweep sample whose plotted X is closest to {@code mx}. */
    private int nearestSample(int[] rect, double mx) {
        int n = sweepValues.size();
        if (n == 0) return -1;
        int gx = rect[0], gw = rect[2];
        double[] xr = xRange();
        double xMin = xr[0], xMax = xr[1];
        int best = 0;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            double xf = (scaleX(sweepValues.get(i)) - xMin) / (xMax - xMin);
            int px = clamp(gx + (int)(xf * gw), gx, gx + gw - 1);
            double d = Math.abs(mx - px);
            if (d < bestD) { bestD = d; best = i; }
        }
        return best;
    }

    private void initCursors(int[] cursors) {
        int n = sweepValues.size();
        cursors[0] = n > 0 ? 0 : -1;
        cursors[1] = n > 0 ? n - 1 : -1;
    }

    private static void clearCursors(int[] cursors) { cursors[0] = cursors[1] = -1; }

    private static boolean hitRect(int[] r, double mx, double my) {
        return r != null && mx >= r[0] && mx < r[0] + r[2]
                && my >= r[1] && my < r[1] + r[3];
    }

    private void toggleExpanded(String group) {
        if (!expandedGroups.add(group)) expandedGroups.remove(group);
        rebuildVisibleRows();
        sidebarScroll = clamp(sidebarScroll, 0, maxSidebarScroll());
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
        int total = visibleRows.size() * ROW_H;
        return Math.max(0, total - SIDEBAR_H);
    }

    // ── render ──────────────────────────────────────────────────────────────

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float pt) {
        renderBackground(g);
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
        String topLbl = summariseSlot(slot1);
        String botLbl = summariseSlot(slot2);
        if (!topLbl.isEmpty()) t.append(topLbl);
        if (!botLbl.isEmpty()) {
            if (t.length() > 0) t.append("  +  ");
            t.append(botLbl);
        }
        if (t.length() == 0) t.append("(no probe selected)");
        t.append("  vs  ").append(sweepComponentName).append(" (").append(sweepUnit).append(")");
        if (isLogFrequency) t.append("  [log]");
        String title = ellipsize(t.toString(), PANEL_W - 16);
        g.drawCenteredString(font, title, panelX + PANEL_W / 2, panelY + 7, C_TITLE);
    }

    /** "name" for a 1-variant slot; "stem (N curves)" otherwise. */
    private String summariseSlot(Set<Integer> slot) {
        if (slot.isEmpty()) return "";
        if (slot.size() == 1) {
            for (int i : slot) return probeNames.get(i);
        }
        String stem = slotGroup(slot);
        return (stem != null ? stem + " " : "") + "(" + slot.size() + " curves)";
    }

    // ── sidebar ─────────────────────────────────────────────────────────────

    private void drawSidebar(GuiGraphics g, int mouseX, int mouseY) {
        int sx = panelX + SIDEBAR_X;
        int sy = panelY + SIDEBAR_Y;
        g.fill(sx, sy, sx + SIDEBAR_W, sy + SIDEBAR_H, C_SIDE_BG);
        drawRect(g, sx - 1, sy - 1, SIDEBAR_W + 2, SIDEBAR_H + 2, 1, C_SEP);

        // Place the header just below the title-bar separator (panelY + 22-23)
        // rather than above it; sy-11 clipped the text into the gray border.
        int hdrY = sy - 9;
        g.drawString(font, "T", sx + X_BTN_T + 3, hdrY, C_UNIT);
        g.drawString(font, "B", sx + X_BTN_B + 3, hdrY, C_UNIT);
        g.drawString(font, "probe", sx + X_TEXT, hdrY, C_UNIT);

        for (int i = 0; i < visibleRows.size(); i++) {
            Row row = visibleRows.get(i);
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
            boolean drawBtns = btnY + BTN_H > sy && btnY < sy + SIDEBAR_H;

            if (row.type == Row.Type.GROUP) {
                List<Integer> members = groupMembers.get(row.groupName);
                boolean expandable = members.size() > 1;

                // Chevron (only when expandable)
                int chevX = sx + X_CHEV;
                if (expandable && rowY + ROW_H > sy && rowY < sy + SIDEBAR_H) {
                    String chev = expandedGroups.contains(row.groupName) ? "v" : ">";
                    g.drawString(font, chev, chevX, rowY + (ROW_H - 8) / 2, C_CHEV);
                }

                // T/B group buttons with all/some/none state
                if (drawBtns) {
                    drawGroupButton(g, sx + X_BTN_T, btnY,
                            groupSlotState(row.groupName, slot1), PALETTE_TOP[0], "T",
                            mouseX, mouseY);
                    drawGroupButton(g, sx + X_BTN_B, btnY,
                            groupSlotState(row.groupName, slot2), PALETTE_BOT[0], "B",
                            mouseX, mouseY);
                }

                // Group name (bold-ish via white colour when selected)
                String state1 = groupSlotState(row.groupName, slot1);
                String state2 = groupSlotState(row.groupName, slot2);
                int nameColour = !"none".equals(state1) ? PALETTE_TOP[0]
                              : !"none".equals(state2) ? PALETTE_BOT[0]
                              : C_LABEL;
                String name = row.groupName;
                int textX = sx + X_TEXT;
                int nameMaxW = SIDEBAR_W - X_TEXT - 4;
                String shown = ellipsize(name, nameMaxW);
                int nameY = rowY + (ROW_H - 8) / 2;
                if (nameY + 8 > sy && nameY < sy + SIDEBAR_H) {
                    g.drawString(font, shown, textX, nameY, nameColour);
                }

            } else { // VARIANT row
                boolean inTop = slot1.contains(row.probeIdx);
                boolean inBot = slot2.contains(row.probeIdx);
                boolean onRight = rightAxis.contains(row.probeIdx);
                int curveColour = colourOf(row.probeIdx);
                if (drawBtns) {
                    drawSlotButton(g, sx + X_BTN_T, btnY, inTop, curveColour,
                            inTop && onRight ? "R" : "T", mouseX, mouseY);
                    drawSlotButton(g, sx + X_BTN_B, btnY, inBot, curveColour,
                            inBot && onRight ? "R" : "B", mouseX, mouseY);
                }
                int textX = sx + X_TEXT + VARIANT_INDENT;
                // Swatch when in a slot
                if ((inTop || inBot) && curveColour != 0) {
                    int swatchY = rowY + (ROW_H - 6) / 2;
                    if (swatchY + 6 > sy && swatchY < sy + SIDEBAR_H) {
                        g.fill(textX, swatchY, textX + SWATCH_W, swatchY + 6, curveColour);
                    }
                    textX += SWATCH_W + SWATCH_GAP;
                }
                // Variant label = the part after the suffix '@' (the one we
                // appended), preserving the leading ngspice-style '@...[gm]'
                // as part of the base, not the variant suffix.
                String full = probeNames.get(row.probeIdx);
                int at = suffixSplit(full);
                String suffix = at >= 0 ? full.substring(at) : full;
                int nameMaxW = SIDEBAR_W - (textX - sx) - 4;
                String shown = ellipsize(suffix, nameMaxW);
                int colour = (inTop || inBot) ? curveColour : C_LABEL;
                int nameY = rowY + (ROW_H - 8) / 2;
                if (nameY + 8 > sy && nameY < sy + SIDEBAR_H) {
                    g.drawString(font, shown, textX, nameY, colour);
                }
            }
        }

        // Hover tooltip — full name of the hovered probe / group.
        int hoveredRow = sidebarRowAt(mouseX, mouseY);
        if (hoveredRow >= 0 && hoveredRow < visibleRows.size()) {
            Row row = visibleRows.get(hoveredRow);
            String tip;
            if (row.type == Row.Type.GROUP) {
                int n = groupMembers.get(row.groupName).size();
                tip = row.groupName + (n > 1 ? "  (" + n + " variants)" : "");
            } else {
                String full = probeNames.get(row.probeIdx);
                String unit = probeUnits.get(row.probeIdx);
                tip = unit.isEmpty() ? full : (full + " (" + unit + ")");
            }
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
        return (row >= 0 && row < visibleRows.size()) ? row : -1;
    }

    private void drawSlotButton(GuiGraphics g, int x, int y, boolean on, int onColour, String label,
                                 int mouseX, int mouseY) {
        boolean hover = mouseX >= x && mouseX < x + BTN_W && mouseY >= y && mouseY < y + BTN_H;
        int bg = on
                ? (onColour != 0 ? onColour : C_BTN_OFF)
                : (hover ? C_BTN_OFF_HV : C_BTN_OFF);
        drawButton(g, x, y, bg, label, on);
    }

    private void drawGroupButton(GuiGraphics g, int x, int y, String state, int onColour, String label,
                                  int mouseX, int mouseY) {
        boolean hover = mouseX >= x && mouseX < x + BTN_W && mouseY >= y && mouseY < y + BTN_H;
        int bg;
        switch (state) {
            case "all", "right" -> bg = onColour;
            case "some"         -> bg = C_BTN_PARTIAL;
            default             -> bg = hover ? C_BTN_OFF_HV : C_BTN_OFF;
        }
        // "R" marks a group reading against the right-hand Y axis.
        drawButton(g, x, y, bg, "right".equals(state) ? "R" : label, !"none".equals(state));
    }

    private void drawButton(GuiGraphics g, int x, int y, int bg, String label, boolean on) {
        g.fill(x, y, x + BTN_W, y + BTN_H, bg);
        g.fill(x, y, x + BTN_W, y + 1, C_SEP);
        g.fill(x, y + BTN_H - 1, x + BTN_W, y + BTN_H, C_SEP);
        g.fill(x, y, x + 1, y + BTN_H, C_SEP);
        g.fill(x + BTN_W - 1, y, x + BTN_W, y + BTN_H, C_SEP);
        int color = on ? C_BTN_TXT_ON : C_BTN_TXT_OFF;
        int tx = x + (BTN_W - font.width(label)) / 2;
        int ty = y + (BTN_H - 8) / 2;
        g.drawString(font, label, tx, ty, color);
    }

    // ── plot area ──────────────────────────────────────────────────────────

    private void drawPlots(GuiGraphics g, int mouseX, int mouseY) {
        // Clear last frame's hit rectangles; each plot re-registers its own as
        // it draws. Plots that aren't shown this frame leave theirs null.
        logBtnTop = curBtnTop = fftBtnTop = plotRectTop = null;
        logBtnBot = curBtnBot = fftBtnBot = plotRectBot = null;

        boolean haveTop = !slot1.isEmpty();
        boolean haveBot = !slot2.isEmpty();
        int plotTop    = panelY + GT;
        int plotBottom = panelY + GB;
        int plotLeft   = panelX + GL;
        int plotRight  = panelX + GR;
        int gw         = plotRight - plotLeft;

        if (!haveTop && !haveBot) {
            g.fill(plotLeft, plotTop, plotRight, plotBottom, C_PLOT_BG);
            g.drawCenteredString(font, "Pick a probe on the right (T = top plot, B = bottom plot)",
                    (plotLeft + plotRight) / 2, (plotTop + plotBottom) / 2 - 4, 0xFFAAAAAA);
            return;
        }

        if (haveTop && haveBot) {
            // Gap fits the bottom plot's Y-label (~9 px) between the two
            // plots, plus a couple of pixels of breathing room. Smaller gaps
            // caused the bottom-plot label to overlay onto the top plot.
            int half = (plotBottom - plotTop) / 2;
            int gap  = 18;
            int top1Bottom = plotTop + half - gap / 2;
            int top2Top    = plotTop + half + gap / 2;
            drawMultiCurvePlot(g, plotLeft, plotTop,    gw, top1Bottom - plotTop,
                    slot1, PALETTE_TOP, false, true, mouseX, mouseY);
            drawMultiCurvePlot(g, plotLeft, top2Top,    gw, plotBottom  - top2Top,
                    slot2, PALETTE_BOT, true, false,  mouseX, mouseY);
        } else if (haveTop) {
            drawMultiCurvePlot(g, plotLeft, plotTop, gw, plotBottom - plotTop,
                    slot1, PALETTE_TOP, true, true, mouseX, mouseY);
        } else {
            drawMultiCurvePlot(g, plotLeft, plotTop, gw, plotBottom - plotTop,
                    slot2, PALETTE_BOT, true, false, mouseX, mouseY);
        }
    }

    /** Shared X-axis range (log-scaled and padded as appropriate). */
    private double[] xRange() {
        double xMinRaw = sweepValues.stream().mapToDouble(d -> d).min().orElse(1);
        double xMaxRaw = sweepValues.stream().mapToDouble(d -> d).max().orElse(2);
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
        return new double[]{ xMin, xMax };
    }

    /** Maps a raw sweep value into scaled X space (log10 when on a log axis). */
    private double scaleX(double rawX) {
        return isLogFrequency ? Math.log10(Math.max(rawX, 1e-30)) : rawX;
    }

    /**
     * @param drawXAxis when true the X-axis tick labels and X-title are
     *        rendered below the plot. Suppressed on the top plot in split
     *        view since the bottom plot owns the shared X axis.
     * @param isTopSlot true for the slot1/top plot, false for slot2/bottom.
     *        Selects which plot's log-Y toggle and cursor state to use, and
     *        which hit-rect fields to register.
     */
    private void drawMultiCurvePlot(GuiGraphics g, int gx, int gy, int gw, int gh,
                                     Set<Integer> slot, int[] palette,
                                     boolean drawXAxis, boolean isTopSlot,
                                     int mouseX, int mouseY) {
        if (slot.isEmpty()) return;

        boolean logY       = isTopSlot ? logYTop      : logYBot;
        boolean cursorMode = isTopSlot ? cursorModeTop : cursorModeBot;
        int[]   cursors    = isTopSlot ? cursorsTop   : cursorsBot;

        List<Integer> active = new ArrayList<>();
        for (int idx : slot) {
            if (probeData.get(idx).size() == sweepValues.size()) active.add(idx);
        }
        if (active.isEmpty() || sweepValues.isEmpty()) {
            g.fill(gx, gy, gx + gw, gy + gh, C_PLOT_BG);
            g.drawCenteredString(font, "No data", gx + gw / 2, gy + gh / 2 - 4, 0xFFFF4444);
            return;
        }

        // Split the occupants by Y axis. A right-axis assignment only takes
        // effect while the left axis keeps at least one curve — a plot of
        // only "right" curves renders as a normal left-axis plot.
        List<Integer> activeL = new ArrayList<>(), activeR = new ArrayList<>();
        for (int idx : active) (rightAxis.contains(idx) ? activeR : activeL).add(idx);
        if (activeL.isEmpty()) { activeL = activeR; activeR = List.of(); }
        boolean dual = !activeR.isEmpty();
        // The sidebar sits immediately right of the plot, so the right axis'
        // tick labels can't hang outside — reserve a gutter inside instead.
        if (dual) gw -= 42;

        double[] axL = computeYAxis(activeL, logY);
        double[] axR = dual ? computeYAxis(activeR, logY) : null;
        double  yMin    = axL[0], yMax = axL[1];
        boolean effLogY = axL[2] != 0;
        double  yFloor  = axL[3];

        double[] xr = xRange();
        double xMin = xr[0], xMax = xr[1];

        g.fill(gx, gy, gx + gw, gy + gh, C_PLOT_BG);

        if (effLogY) {
            drawLogYTicks(g, gx, gy, gw, gh, yMin, yMax);
        } else {
            int yTicks = gh < 160 ? 4 : 6;
            double yStep = niceTickStep(yMax - yMin, yTicks);
            // Snap to multiples of yStep so labels land on round numbers (e.g.
            // 0, 1, 2, ...) instead of whatever value falls at each evenly-
            // spaced position. The tiny epsilon protects the final tick from
            // floating-point drift dropping it below yMax.
            double yFirst = Math.ceil(yMin / yStep) * yStep;
            for (double yVal = yFirst; yVal <= yMax + yStep * 1e-9; yVal += yStep) {
                double t  = (yVal - yMin) / (yMax - yMin);
                int    py = gy + (int)((1.0 - t) * gh);
                g.fill(gx, py, gx + gw, py + 1, C_GRID);
                String yLbl = fmtAxis(yVal);
                g.drawString(font, yLbl, gx - font.width(yLbl) - 3, py - 4, C_LABEL);
            }
        }

        if (isLogFrequency) drawLogXTicks(g, gx, gy, gw, gh, xMin, xMax, drawXAxis);
        else                drawLinearXTicks(g, gx, gy, gw, gh, xMin, xMax, drawXAxis);

        g.fill(gx,      gy,      gx + 1, gy + gh,      C_AXIS);
        g.fill(gx,      gy + gh, gx + gw, gy + gh + 1, C_AXIS);

        if (dual) drawRightAxis(g, gx, gy, gw, gh, axR);

        String stem = slotGroup(activeL);
        String yUnitLabel = commonUnit(activeL);
        String yLabel = (stem == null ? "" : stem)
                + (yUnitLabel.isEmpty() ? "" : "  (" + yUnitLabel + ")")
                + (effLogY ? "  [log]" : "");
        if (!yLabel.isEmpty()) {
            int yColour = activeL.size() == 1 ? colourOf(activeL.get(0)) : C_LABEL;
            g.drawString(font, ellipsize(yLabel, dual ? gw / 2 : gw - 4),
                    gx + 2, gy - 9, yColour);
        }
        if (dual) {
            String stemR = slotGroup(activeR);
            String unitR = commonUnit(activeR);
            String rLabel = (stemR == null ? "" : stemR)
                    + (unitR.isEmpty() ? "" : "  (" + unitR + ")")
                    + (axR[2] != 0 ? "  [log]" : "");
            if (!rLabel.isEmpty()) {
                int rColour = activeR.size() == 1 ? colourOf(activeR.get(0)) : C_LABEL;
                String shown = ellipsize(rLabel, gw / 2);
                g.drawString(font, shown, gx + gw + 42 - font.width(shown), gy - 9, rColour);
            }
        }

        if (drawXAxis) {
            g.drawCenteredString(font,
                    sweepComponentName + " (" + sweepUnit + ")"
                            + (isLogFrequency ? " - log scale" : ""),
                    gx + gw / 2, gy + gh + 10, C_UNIT);
        }

        int n = sweepValues.size();
        boolean drawDots = active.size() == 1 && n <= 200;
        int hoverCurve = -1;
        int hoverIdx   = -1;

        // Cursor readout placement: instead of always covering the top-left
        // (where flat Bode passbands and multi-curve param sweeps usually
        // live), tally how many curve samples fall under each candidate
        // corner while the curves are being mapped, then put the box in the
        // emptiest one. The top-right candidate sits below the Log/Cur
        // toolbar.
        boolean anyCursor = cursorMode
                && ((cursors[0] >= 0 && cursors[0] < n) || (cursors[1] >= 0 && cursors[1] < n));
        List<String> roLines = anyCursor ? cursorReadout(active, cursors) : null;
        int[][] roCand = null;
        int[]   roCounts = null;
        if (roLines != null && !roLines.isEmpty()) {
            int[] sz = readoutSize(roLines, gw);
            int byBot = Math.max(gy + 3, gy + gh - sz[1] - 3);
            roCand = new int[][]{
                { gx + 3,              gy + 3,  sz[0], sz[1] },
                { gx + gw - sz[0] - 3, gy + 16, sz[0], sz[1] },
                { gx + 3,              byBot,   sz[0], sz[1] },
                { gx + gw - sz[0] - 3, byBot,   sz[0], sz[1] },
            };
            roCounts = new int[4];
        }

        int order = 0;
        for (int probeIdx : active) {
            int colour = palette[order % palette.length];
            order++;
            // Map against whichever Y axis this curve reads on.
            boolean onR   = dual && activeR.contains(probeIdx);
            double  aMin  = onR ? axR[0] : yMin;
            double  aMax  = onR ? axR[1] : yMax;
            boolean aLog  = onR ? axR[2] != 0 : effLogY;
            double  aFloor = onR ? axR[3] : yFloor;
            List<Double> series = probeData.get(probeIdx);
            int[] px = new int[n];
            int[] py = new int[n];
            for (int i = 0; i < n; i++) {
                double xf   = (scaleX(sweepValues.get(i)) - xMin) / (xMax - xMin);
                double yf   = (scaleY(series.get(i), aLog, aFloor) - aMin) / (aMax - aMin);
                px[i] = clamp(gx + (int)(xf * gw), gx, gx + gw - 1);
                py[i] = clamp(gy + gh - 1 - (int)(yf * (gh - 1)), gy, gy + gh - 1);
                if (Math.abs(mouseX - px[i]) <= 5 && Math.abs(mouseY - py[i]) <= 5) {
                    hoverCurve = probeIdx;
                    hoverIdx   = i;
                }
                if (roCand != null) {
                    for (int c = 0; c < roCand.length; c++) {
                        int[] r = roCand[c];
                        if (px[i] >= r[0] - 2 && px[i] < r[0] + r[2] + 2
                                && py[i] >= r[1] - 2 && py[i] < r[1] + r[3] + 2) {
                            roCounts[c]++;
                        }
                    }
                }
            }
            for (int i = 1; i < n; i++) drawLine(g, px[i-1], py[i-1], px[i], py[i], colour);
            if (drawDots) {
                for (int i = 0; i < n; i++) {
                    g.fill(px[i] - 2, py[i] - 2, px[i] + 3, py[i] + 3, C_DOT_OUT);
                    g.fill(px[i] - 1, py[i] - 1, px[i] + 2, py[i] + 2, colour);
                }
            }
        }

        // Cursors and their readout sit above the curves but below the hover
        // highlight so a hovered point stays legible.
        int[] roRect = null;
        if (roCand != null) {
            int best = 0;
            for (int c = 1; c < roCand.length; c++) {
                if (roCounts[c] < roCounts[best]) best = c;
            }
            roRect = roCand[best];
        }
        drawCursors(g, gx, gy, gw, gh, xMin, xMax, cursors, roLines, roRect);

        if (hoverIdx >= 0 && hoverCurve >= 0) {
            List<Double> series = probeData.get(hoverCurve);
            double rawXH = sweepValues.get(hoverIdx);
            String xStr = isLogFrequency
                    ? ComponentEditScreen.formatValue(rawXH) + "Hz"
                    : ComponentEditScreen.formatValue(rawXH) + sweepUnit;
            String yUnitH = probeUnits.get(hoverCurve);
            String yStr = ComponentEditScreen.formatValue(series.get(hoverIdx))
                    + (yUnitH.isEmpty() ? "" : " " + yUnitH);
            String name = probeNames.get(hoverCurve);
            String tip  = name + ": " + yStr + " at " + xStr;
            int tw = font.width(tip) + 6, th = 12;
            boolean hR    = dual && activeR.contains(hoverCurve);
            double  hMin  = hR ? axR[0] : yMin;
            double  hMax  = hR ? axR[1] : yMax;
            boolean hLog  = hR ? axR[2] != 0 : effLogY;
            double  hFloor = hR ? axR[3] : yFloor;
            double xf = (scaleX(rawXH) - xMin) / (xMax - xMin);
            double yf = (scaleY(series.get(hoverIdx), hLog, hFloor) - hMin) / (hMax - hMin);
            int hx = clamp(gx + (int)(xf * gw), gx, gx + gw - 1);
            int hy = clamp(gy + gh - 1 - (int)(yf * (gh - 1)), gy, gy + gh - 1);
            int tx = clamp(hx + 6, gx, gx + gw - tw);
            int ty = clamp(hy - 16, gy, gy + gh - th);
            g.fill(tx - 1, ty - 1, tx + tw + 1, ty + th + 1, C_BORDER);
            g.fill(tx, ty, tx + tw, ty + th, C_HOVER_BG);
            g.drawString(font, tip, tx + 3, ty + 2, 0xFFFFFFFF);

            g.fill(hx - 3, hy - 3, hx + 4, hy + 4, 0xFFFFFF00);
            int orderHover = indexIn(slot, hoverCurve);
            int colourHover = orderHover >= 0 ? palette[orderHover % palette.length] : C_LABEL;
            g.fill(hx - 1, hy - 1, hx + 2, hy + 2, colourHover);
        }

        // On-plot toggle toolbar (top-right) plus the interior hit-rect that
        // catches cursor-placement clicks. Drawn last so it sits on top.
        int[] curBtn = drawToggle(g, gx + gw - 2,    gy + 2, "Cur", cursorMode, mouseX, mouseY);
        int[] logBtn = drawToggle(g, curBtn[0] - 3,  gy + 2, "Log", logY,       mouseX, mouseY);
        // FFT appears only when this transient session carries an
        // ngspice-computed companion spectrum to jump to.
        int[] fftBtn = fftSessionId >= 0
                ? drawToggle(g, logBtn[0] - 3, gy + 2, "FFT", false, mouseX, mouseY)
                : null;
        if (isTopSlot) {
            curBtnTop = curBtn; logBtnTop = logBtn; fftBtnTop = fftBtn;
            plotRectTop = new int[]{ gx, gy, gw, gh };
        } else {
            curBtnBot = curBtn; logBtnBot = logBtn; fftBtnBot = fftBtn;
            plotRectBot = new int[]{ gx, gy, gw, gh };
        }
    }

    /** Maps a raw value into scaled Y space (log10 above a floor when logarithmic). */
    private static double scaleY(double v, boolean logY, double floor) {
        return logY ? Math.log10(Math.max(v, floor)) : v;
    }

    /**
     * Scale of one Y axis over {@code curves}: {yMin, yMax, effLog (0/1),
     * floor}, in scaled-Y space, padded (linear) or decade-snapped (log).
     * Log Y needs a positive floor; if the data has no positive samples a log
     * axis is meaningless, so it quietly falls back to linear for this draw.
     */
    private double[] computeYAxis(List<Integer> curves, boolean logY) {
        double yFloor = 1e-30;
        boolean effLogY = logY;
        if (effLogY) {
            double posMin = Double.POSITIVE_INFINITY;
            for (int idx : curves)
                for (double v : probeData.get(idx))
                    if (v > 0 && v < posMin) posMin = v;
            if (Double.isFinite(posMin)) yFloor = posMin;
            else effLogY = false;
        }

        double yMin = Double.POSITIVE_INFINITY, yMax = Double.NEGATIVE_INFINITY;
        for (int idx : curves) {
            for (double v : probeData.get(idx)) {
                double s = scaleY(v, effLogY, yFloor);
                if (s < yMin) yMin = s;
                if (s > yMax) yMax = s;
            }
        }
        if (!Double.isFinite(yMin) || !Double.isFinite(yMax)) { yMin = 0; yMax = 1; }
        if (yMax == yMin) { yMin -= 1; yMax += 1; }
        if (effLogY) {
            // Snap the range out to whole decades so gridlines and labels land
            // on clean powers of ten.
            yMin = Math.floor(yMin);
            yMax = Math.ceil(yMax);
            if (yMax == yMin) yMax += 1;
        } else {
            double yPad = (yMax - yMin) * 0.10;
            yMin -= yPad; yMax += yPad;
        }
        return new double[]{ yMin, yMax, effLogY ? 1 : 0, yFloor };
    }

    /**
     * The right-hand Y axis: a vertical line at the plot's right edge with
     * tick dashes and labels in the reserved gutter. No gridlines — the grid
     * belongs to the left axis, and a second overlaid grid at unrelated
     * positions reads as noise.
     */
    private void drawRightAxis(GuiGraphics g, int gx, int gy, int gw, int gh, double[] ax) {
        double yMin = ax[0], yMax = ax[1];
        boolean logR = ax[2] != 0;
        int x = gx + gw;
        g.fill(x, gy, x + 1, gy + gh, C_AXIS);
        if (logR) {
            int decLo = (int) Math.floor(yMin);
            int decHi = (int) Math.ceil(yMax);
            for (int d = decLo; d <= decHi; d++) {
                double yf = (d - yMin) / (yMax - yMin);
                if (yf < 0 || yf > 1) continue;
                int py = gy + (int)((1.0 - yf) * gh);
                g.fill(x, py, x + 4, py + 1, C_AXIS);
                g.drawString(font, fmtAxis(Math.pow(10, d)), x + 6, py - 4, C_LABEL);
            }
        } else {
            int yTicks = gh < 160 ? 4 : 6;
            double yStep = niceTickStep(yMax - yMin, yTicks);
            double yFirst = Math.ceil(yMin / yStep) * yStep;
            for (double yVal = yFirst; yVal <= yMax + yStep * 1e-9; yVal += yStep) {
                double t  = (yVal - yMin) / (yMax - yMin);
                int    py = gy + (int)((1.0 - t) * gh);
                g.fill(x, py, x + 4, py + 1, C_AXIS);
                g.drawString(font, fmtAxis(yVal), x + 6, py - 4, C_LABEL);
            }
        }
    }

    /** Draws a small right-aligned toggle button, returning its {x,y,w,h} rect. */
    private int[] drawToggle(GuiGraphics g, int rightX, int y, String label, boolean on,
                              int mouseX, int mouseY) {
        int w = font.width(label) + 6, h = 11;
        int x = rightX - w;
        boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        int bg = on ? C_BORDER : (hover ? C_BTN_OFF_HV : C_BTN_OFF);
        g.fill(x, y, x + w, y + h, bg);
        drawRect(g, x, y, w, h, 1, C_SEP);
        g.drawString(font, label, x + 3, y + (h - 8) / 2, on ? 0xFFFFFFFF : C_BTN_TXT_OFF);
        return new int[]{ x, y, w, h };
    }

    /** Vertical decade gridlines + labels for a logarithmic Y axis. */
    private void drawLogYTicks(GuiGraphics g, int gx, int gy, int gw, int gh,
                                double yMin, double yMax) {
        int decLo = (int) Math.floor(yMin);
        int decHi = (int) Math.ceil(yMax);
        // Minor (2..9) lines first so the decade lines paint over them.
        for (int d = decLo; d < decHi; d++) {
            for (int m = 2; m <= 9; m++) {
                double yf = (Math.log10(m) + d - yMin) / (yMax - yMin);
                if (yf < 0 || yf > 1) continue;
                int py = gy + (int)((1.0 - yf) * gh);
                g.fill(gx, py, gx + gw, py + 1, 0xFF20203A);
            }
        }
        for (int d = decLo; d <= decHi; d++) {
            double yf = (d - yMin) / (yMax - yMin);
            if (yf < 0 || yf > 1) continue;
            int py = gy + (int)((1.0 - yf) * gh);
            g.fill(gx, py, gx + gw, py + 1, C_GRID);
            String lbl = fmtAxis(Math.pow(10, d));
            g.drawString(font, lbl, gx - font.width(lbl) - 3, py - 4, C_LABEL);
        }
    }

    /**
     * Draws the cursor lines and the X/Y (and dx/dy) readout. Cursors are
     * sweep-sample indices in {@code {cursor1, cursor2}} order, -1 when unset;
     * with both placed the readout reports absolute values for each plus deltas.
     */
    private void drawCursors(GuiGraphics g, int gx, int gy, int gw, int gh,
                              double xMin, double xMax, int[] cursors,
                              List<String> readoutLines, int[] readoutRect) {
        int n = sweepValues.size();
        for (int k = 0; k < cursors.length; k++) {
            int ci = cursors[k];
            if (ci < 0 || ci >= n) continue;
            double xf = (scaleX(sweepValues.get(ci)) - xMin) / (xMax - xMin);
            int px = clamp(gx + (int)(xf * gw), gx, gx + gw - 1);
            int col = CURSOR_COLOURS[k % CURSOR_COLOURS.length];
            g.fill(px, gy, px + 1, gy + gh, col);
            String tag = String.valueOf(k + 1);
            int tagX = clamp(px - 4, gx, gx + gw - 9);
            g.fill(tagX, gy, tagX + 9, gy + 9, 0xCC000000);
            g.drawString(font, tag, tagX + (9 - font.width(tag)) / 2, gy + 1, col);
        }
        drawReadout(g, readoutRect, readoutLines);
    }

    /**
     * Builds the cursor readout. With both cursors placed each line packs the
     * cursor-1 value, cursor-2 value, and their delta side by side (X on the
     * first line, then one line of Y per curve). With a single cursor it falls
     * back to plain x / y.
     */
    private List<String> cursorReadout(List<Integer> active, int[] cursors) {
        List<String> lines = new ArrayList<>();
        int c1 = cursors[0], c2 = cursors[1];
        boolean has1 = c1 >= 0, has2 = c2 >= 0;
        boolean single = active.size() == 1;
        // With curves from several groups on one plot the @-suffix alone is
        // ambiguous (gain_db@300fF and gain_ph@300fF both read "300fF") —
        // fall back to full probe names.
        boolean mixed = slotGroup(active) == null;

        if (has1 && has2) {
            lines.add(col("x", fmtXVal(sweepValues.get(c1)),
                              fmtXVal(sweepValues.get(c2)),
                              fmtXVal(sweepValues.get(c2) - sweepValues.get(c1))));
            for (int idx : active) {
                List<Double> series = probeData.get(idx);
                String unit = probeUnits.get(idx);
                String prefix = single ? ""
                        : (mixed ? probeNames.get(idx) : variantLabel(idx)) + ": ";
                double y1 = series.get(c1), y2 = series.get(c2);
                lines.add(prefix + col("y", fmtYVal(y1, unit), fmtYVal(y2, unit),
                                            fmtYVal(y2 - y1, unit)));
            }
        } else {
            int c = has1 ? c1 : c2;
            lines.add("x = " + fmtXVal(sweepValues.get(c)));
            for (int idx : active) {
                String nm = single ? "y" : (mixed ? probeNames.get(idx) : variantLabel(idx));
                lines.add(nm + " = " + fmtYVal(probeData.get(idx).get(c), probeUnits.get(idx)));
            }
        }
        return lines;
    }


    /** Packs a label and its cursor-1 / cursor-2 / delta values into one line. */
    private static String col(String tag, String v1, String v2, String dv) {
        return tag + "1=" + v1 + "  " + tag + "2=" + v2 + "  d" + tag + "=" + dv;
    }

    /** Short label for a probe in a multi-curve readout — the @-suffix, else the name. */
    private String variantLabel(int idx) {
        String full = probeNames.get(idx);
        int at = suffixSplit(full);
        return at >= 0 ? full.substring(at + 1) : full;
    }

    // Cursor values use the same 2-decimal SI formatting as the axis labels
    // (fmtAxis) rather than the higher-precision ComponentEditScreen.formatValue.
    private String fmtXVal(double v) {
        return fmtAxis(v) + (isLogFrequency ? "Hz" : sweepUnit);
    }

    private static String fmtYVal(double v, String unit) {
        return fmtAxis(v) + (unit.isEmpty() ? "" : " " + unit);
    }

    /** Translucent readout box anchored to the plot's top-left interior. */
    private static final int READOUT_PAD = 3, READOUT_LH = 9;

    /** {boxW, boxH} of the readout for {@code lines}, capped to the plot width. */
    private int[] readoutSize(List<String> lines, int gw) {
        int textW = 0;
        for (String s : lines) textW = Math.max(textW, font.width(s));
        return new int[]{ Math.min(textW + READOUT_PAD * 2, gw - 4),
                          lines.size() * READOUT_LH + READOUT_PAD };
    }

    private void drawReadout(GuiGraphics g, int[] rect, List<String> lines) {
        if (rect == null || lines == null || lines.isEmpty()) return;
        int bx = rect[0], by = rect[1], boxW = rect[2], boxH = rect[3];
        g.fill(bx - 1, by - 1, bx + boxW + 1, by + boxH + 1, C_BORDER);
        g.fill(bx, by, bx + boxW, by + boxH, 0xE6101022);
        int ty = by + READOUT_PAD - 1;
        for (String s : lines) {
            g.drawString(font, ellipsize(s, boxW - READOUT_PAD * 2), bx + READOUT_PAD, ty, 0xFFFFFFFF);
            ty += READOUT_LH;
        }
    }

    private String commonUnit(List<Integer> active) {
        String unit = null;
        for (int idx : active) {
            String u = probeUnits.get(idx);
            if (unit == null) unit = u;
            else if (!unit.equals(u)) return "";
        }
        return unit == null ? "" : unit;
    }

    // ── tick helpers ────────────────────────────────────────────────────────

    private void drawLinearXTicks(GuiGraphics g, int gx, int gy, int gw, int gh,
                                   double xMin, double xMax, boolean labels) {
        final int X_TICKS = 6;
        double step = niceTickStep(xMax - xMin, X_TICKS);
        double first = Math.ceil(xMin / step) * step;
        for (double xReal = first; xReal <= xMax + step * 1e-9; xReal += step) {
            double t  = (xReal - xMin) / (xMax - xMin);
            int    px = gx + (int)(t * gw);
            g.fill(px, gy, px + 1, gy + gh, C_GRID);
            if (!labels) continue;
            String xLbl = fmtAxis(xReal);
            g.drawString(font, xLbl, px - font.width(xLbl) / 2, gy + gh + 1, C_LABEL);
        }
    }

    /**
     * Picks a "nice" tick spacing for a linear axis: snaps the raw
     * range/targetCount step to the nearest 1, 2, or 5 × 10^k so labels land
     * on round numbers (0, 500, 1k, 1.5k, ...) instead of whatever value
     * happens to fall at evenly-divided positions.
     */
    private static double niceTickStep(double range, int targetCount) {
        if (!(range > 0) || targetCount < 1) return 1.0;
        double rawStep = range / targetCount;
        double exp     = Math.pow(10, Math.floor(Math.log10(rawStep)));
        double frac    = rawStep / exp;
        double niceFrac;
        if (frac < 1.5)     niceFrac = 1;
        else if (frac < 3)  niceFrac = 2;
        else if (frac < 7)  niceFrac = 5;
        else                niceFrac = 10;
        return niceFrac * exp;
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
        if (freq >= 1e12) return trimZeros(String.format("%.2f", freq / 1e12)) + "T";
        if (freq >= 1e9)  return trimZeros(String.format("%.2f", freq / 1e9))  + "G";
        if (freq >= 1e6)  return trimZeros(String.format("%.2f", freq / 1e6))  + "M";
        if (freq >= 1e3)  return trimZeros(String.format("%.2f", freq / 1e3))  + "k";
        if (freq >= 1)    return trimZeros(String.format("%.2f", freq));
        if (freq >= 1e-3) return trimZeros(String.format("%.2f", freq / 1e-3)) + "m";
        if (freq >= 1e-6) return trimZeros(String.format("%.2f", freq / 1e-6)) + "u";
        if (freq >= 1e-9) return trimZeros(String.format("%.2f", freq / 1e-9)) + "n";
        return trimZeros(String.format("%.2g", freq));
    }

    private static String trimZeros(String s) {
        if (!s.contains(".")) return s;
        s = s.replaceAll("0+$", "");
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private String ellipsize(String s, int maxPx) {
        if (font.width(s) <= maxPx) return s;
        String ell = "...";
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
