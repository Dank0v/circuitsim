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
 * <p>A plot slot can only hold variants from one group at a time — adding a
 * probe from a different group auto-clears the slot first. This prevents
 * accidentally overlaying unrelated quantities (e.g. gain_db with gain_ph)
 * on the same axes.
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

    // Slot occupants — ordered set of probe indices. Insertion order picks
    // the palette colour. slot1 = top plot, slot2 = bottom.
    private final Set<Integer> slot1 = new LinkedHashSet<>();
    private final Set<Integer> slot2 = new LinkedHashSet<>();

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
     * Adds {@code idx} to slot 1 (top). If the slot currently holds probes
     * from a different group they're cleared first — a slot only holds one
     * group's variants at a time. Removes from slot 2 if it's there.
     */
    private void addToSlot1(int idx) {
        String g = groupOf(idx);
        if (slotGroup(slot1) != null && !slotGroup(slot1).equals(g)) slot1.clear();
        slot2.remove(idx);
        slot1.add(idx);
    }

    private void addToSlot2(int idx) {
        String g = groupOf(idx);
        if (slotGroup(slot2) != null && !slotGroup(slot2).equals(g)) slot2.clear();
        slot1.remove(idx);
        slot2.add(idx);
    }

    private void removeFromSlot1(int idx) { slot1.remove(idx); }
    private void removeFromSlot2(int idx) { slot2.remove(idx); }

    /** Toggle a single variant in the top slot. */
    private void toggleVariantSlot1(int idx) {
        if (slot1.contains(idx)) removeFromSlot1(idx);
        else                     addToSlot1(idx);
    }

    private void toggleVariantSlot2(int idx) {
        if (slot2.contains(idx)) removeFromSlot2(idx);
        else                     addToSlot2(idx);
    }

    /**
     * Toggles every variant of {@code group} in/out of the top slot. If every
     * variant is already there, remove them all; otherwise replace the slot's
     * contents with this group's full set.
     */
    private void toggleGroupSlot1(String group) {
        List<Integer> members = groupMembers.get(group);
        if (members == null) return;
        boolean allIn = !members.isEmpty();
        for (int idx : members) if (!slot1.contains(idx)) { allIn = false; break; }
        if (allIn) {
            for (int idx : members) slot1.remove(idx);
        } else {
            String existing = slotGroup(slot1);
            if (existing != null && !existing.equals(group)) slot1.clear();
            for (int idx : members) {
                slot2.remove(idx);
                slot1.add(idx);
            }
        }
    }

    private void toggleGroupSlot2(String group) {
        List<Integer> members = groupMembers.get(group);
        if (members == null) return;
        boolean allIn = !members.isEmpty();
        for (int idx : members) if (!slot2.contains(idx)) { allIn = false; break; }
        if (allIn) {
            for (int idx : members) slot2.remove(idx);
        } else {
            String existing = slotGroup(slot2);
            if (existing != null && !existing.equals(group)) slot2.clear();
            for (int idx : members) {
                slot1.remove(idx);
                slot2.add(idx);
            }
        }
    }

    /** Returns the (single) group name occupying this slot, or null if empty. */
    private String slotGroup(Set<Integer> slot) {
        if (slot.isEmpty()) return null;
        return groupOf(slot.iterator().next());
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

    /** "all", "some", or "none" of this group's variants in the given slot. */
    private String groupSlotState(String group, Set<Integer> slot) {
        List<Integer> members = groupMembers.get(group);
        if (members == null || members.isEmpty()) return "none";
        int hits = 0;
        for (int idx : members) if (slot.contains(idx)) hits++;
        if (hits == 0) return "none";
        if (hits == members.size()) return "all";
        return "some";
    }

    // ── input handling ──────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
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
                int curveColour = colourOf(row.probeIdx);
                if (drawBtns) {
                    drawSlotButton(g, sx + X_BTN_T, btnY, inTop, curveColour, "T",
                            mouseX, mouseY);
                    drawSlotButton(g, sx + X_BTN_B, btnY, inBot, curveColour, "B",
                            mouseX, mouseY);
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
            case "all"  -> bg = onColour;
            case "some" -> bg = C_BTN_PARTIAL;
            default     -> bg = hover ? C_BTN_OFF_HV : C_BTN_OFF;
        }
        drawButton(g, x, y, bg, label, !"none".equals(state));
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
                    slot1, PALETTE_TOP, false, mouseX, mouseY);
            drawMultiCurvePlot(g, plotLeft, top2Top,    gw, plotBottom  - top2Top,
                    slot2, PALETTE_BOT, true,  mouseX, mouseY);
        } else if (haveTop) {
            drawMultiCurvePlot(g, plotLeft, plotTop, gw, plotBottom - plotTop,
                    slot1, PALETTE_TOP, true, mouseX, mouseY);
        } else {
            drawMultiCurvePlot(g, plotLeft, plotTop, gw, plotBottom - plotTop,
                    slot2, PALETTE_BOT, true, mouseX, mouseY);
        }
    }

    /**
     * @param drawXAxis when true the X-axis tick labels and X-title are
     *        rendered below the plot. Suppressed on the top plot in split
     *        view since the bottom plot owns the shared X axis.
     */
    private void drawMultiCurvePlot(GuiGraphics g, int gx, int gy, int gw, int gh,
                                     Set<Integer> slot, int[] palette,
                                     boolean drawXAxis, int mouseX, int mouseY) {
        if (slot.isEmpty()) return;

        List<Integer> active = new ArrayList<>();
        for (int idx : slot) {
            if (probeData.get(idx).size() == sweepValues.size()) active.add(idx);
        }
        if (active.isEmpty() || sweepValues.isEmpty()) {
            g.fill(gx, gy, gx + gw, gy + gh, C_PLOT_BG);
            g.drawCenteredString(font, "No data", gx + gw / 2, gy + gh / 2 - 4, 0xFFFF4444);
            return;
        }

        double xMinRaw = sweepValues.stream().mapToDouble(d -> d).min().orElse(1);
        double xMaxRaw = sweepValues.stream().mapToDouble(d -> d).max().orElse(2);
        double yMin = Double.POSITIVE_INFINITY, yMax = Double.NEGATIVE_INFINITY;
        for (int idx : active) {
            for (double v : probeData.get(idx)) {
                if (v < yMin) yMin = v;
                if (v > yMax) yMax = v;
            }
        }
        if (!Double.isFinite(yMin) || !Double.isFinite(yMax)) { yMin = 0; yMax = 1; }
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

        g.fill(gx, gy, gx + gw, gy + gh, C_PLOT_BG);

        int yTicks = gh < 160 ? 4 : 6;
        for (int i = 0; i <= yTicks; i++) {
            double t  = (double) i / yTicks;
            int    py = gy + (int)((1.0 - t) * gh);
            g.fill(gx, py, gx + gw, py + 1, C_GRID);
            double yVal = yMin + (yMax - yMin) * t;
            String yLbl = fmtAxis(yVal);
            g.drawString(font, yLbl, gx - font.width(yLbl) - 3, py - 4, C_LABEL);
        }

        if (isLogFrequency) drawLogXTicks(g, gx, gy, gw, gh, xMin, xMax, drawXAxis);
        else                drawLinearXTicks(g, gx, gy, gw, gh, xMin, xMax, drawXAxis);

        g.fill(gx,      gy,      gx + 1, gy + gh,      C_AXIS);
        g.fill(gx,      gy + gh, gx + gw, gy + gh + 1, C_AXIS);

        String stem = slotGroup(slot);
        String yUnitLabel = commonUnit(active);
        String yLabel = (stem == null ? "" : stem)
                + (yUnitLabel.isEmpty() ? "" : "  (" + yUnitLabel + ")");
        if (!yLabel.isEmpty()) {
            int yColour = active.size() == 1 ? palette[0] : C_LABEL;
            g.drawString(font, ellipsize(yLabel, gw - 4), gx + 2, gy - 9, yColour);
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

        int order = 0;
        for (int probeIdx : active) {
            int colour = palette[order % palette.length];
            order++;
            List<Double> series = probeData.get(probeIdx);
            int[] px = new int[n];
            int[] py = new int[n];
            for (int i = 0; i < n; i++) {
                double rawX = sweepValues.get(i);
                double xScaled = isLogFrequency ? Math.log10(Math.max(rawX, 1e-30)) : rawX;
                double xf   = (xScaled - xMin) / (xMax - xMin);
                double yf   = (series.get(i) - yMin) / (yMax - yMin);
                px[i] = clamp(gx + (int)(xf * gw), gx, gx + gw - 1);
                py[i] = clamp(gy + gh - 1 - (int)(yf * (gh - 1)), gy, gy + gh - 1);
                if (Math.abs(mouseX - px[i]) <= 5 && Math.abs(mouseY - py[i]) <= 5) {
                    hoverCurve = probeIdx;
                    hoverIdx   = i;
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
            double xf = isLogFrequency
                    ? Math.log10(Math.max(rawXH, 1e-30)) : rawXH;
            xf = (xf - xMin) / (xMax - xMin);
            double yf = (series.get(hoverIdx) - yMin) / (yMax - yMin);
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
