package com.circuitsim.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Scrollable, searchable viewer for ngspice simulation output.
 * Opened automatically by {@link com.circuitsim.network.SimulationOutputPacket}
 * after a simulation finishes; can be reopened via the cached results.
 */
public class SimulationOutputScreen extends Screen {

    private final String       headerTitle;
    private final List<String> sourceLines;

    private static final int PANEL_W = 540;
    private static final int PANEL_H = 360;
    private static final int SIDE_W  = 130;

    private static final int C_BG        = 0xFF1A1A2E;
    private static final int C_PANE_BG   = 0xFF16213E;
    private static final int C_BORDER    = 0xFF4A90D9;
    private static final int C_SEP       = 0xFF444466;
    private static final int C_TITLE     = 0xFFFFD700;
    private static final int C_TEXT      = 0xFFE0E0E0;
    private static final int C_DIM       = 0xFF8888AA;
    private static final int C_HEADER    = 0xFF4FC3F7;
    private static final int C_ERROR     = 0xFFFF6464;
    private static final int C_MATCH_BG  = 0x80B58900;
    private static final int C_HIT_BG    = 0xC0FF8800;
    private static final int C_HOVER_BG  = 0x40FFFFFF;
    private static final int C_SCROLL    = 0xFF4A90D9;
    private static final int C_SCROLL_BG = 0xFF2A2A4A;

    private int panelX, panelY;
    private int paneX, paneY, paneW, paneH;
    private int sideX, sideY, sideW, sideH;
    private int contentX, contentY, contentW, contentH;
    private int lineHeight;

    private EditBox searchBox;

    private final List<FormattedCharSequence> displayLines = new ArrayList<>();
    private final List<Integer>               srcOfDisplay = new ArrayList<>();
    private final List<int[]>                 sections     = new ArrayList<>();

    private int scrollLine    = 0;
    private int sectionScroll = 0;
    private int currentMatch  = -1;
    private final List<Integer> matchSourceLines = new ArrayList<>();

    public SimulationOutputScreen(String title, List<String> lines) {
        super(Component.literal("Simulation Output"));
        this.headerTitle = title == null || title.isEmpty() ? "Simulation Output" : title;
        this.sourceLines = lines == null ? Collections.emptyList() : lines;
    }

    @Override
    protected void init() {
        super.init();
        lineHeight = font.lineHeight + 2;
        panelX = (this.width  - PANEL_W) / 2;
        panelY = (this.height - PANEL_H) / 2;

        sideX = panelX + 8;
        sideY = panelY + 70;
        sideW = SIDE_W - 4;
        sideH = PANEL_H - 70 - 32;

        paneX = panelX + SIDE_W + 8;
        paneY = panelY + 70;
        paneW = PANEL_W - SIDE_W - 16;
        paneH = PANEL_H - 70 - 32;

        contentX = paneX + 6;
        contentY = paneY + 4;
        contentW = paneW - 14;            // leave 8px for scrollbar
        contentH = paneH - 8;

        searchBox = new EditBox(font,
                panelX + 60, panelY + 38, PANEL_W - 220, 16,
                Component.literal("Search"));
        searchBox.setMaxLength(256);
        searchBox.setBordered(true);
        searchBox.setResponder(this::onSearchChanged);
        addRenderableWidget(searchBox);

        addRenderableWidget(Button.builder(Component.literal("<"),
                b -> jumpMatch(-1))
                .bounds(panelX + PANEL_W - 152, panelY + 36, 18, 20).build());
        addRenderableWidget(Button.builder(Component.literal(">"),
                b -> jumpMatch(+1))
                .bounds(panelX + PANEL_W - 132, panelY + 36, 18, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Copy All"),
                b -> copyAll())
                .bounds(panelX + PANEL_W - 110, panelY + 8, 60, 18).build());
        addRenderableWidget(Button.builder(Component.literal("X"),
                b -> onClose())
                .bounds(panelX + PANEL_W - 28, panelY + 8, 20, 18).build());

        rewrap();
        rebuildSections();
    }

    private void rewrap() {
        displayLines.clear();
        srcOfDisplay.clear();
        for (int i = 0; i < sourceLines.size(); i++) {
            String src = sourceLines.get(i);
            if (src.isEmpty()) {
                displayLines.add(FormattedCharSequence.EMPTY);
                srcOfDisplay.add(i);
                continue;
            }
            List<FormattedCharSequence> wrapped =
                    font.split(Component.literal(src), contentW);
            if (wrapped.isEmpty()) {
                displayLines.add(FormattedCharSequence.EMPTY);
                srcOfDisplay.add(i);
            } else {
                for (FormattedCharSequence fcs : wrapped) {
                    displayLines.add(fcs);
                    srcOfDisplay.add(i);
                }
            }
        }
    }

    private void rebuildSections() {
        sections.clear();
        for (int i = 0; i < sourceLines.size(); i++) {
            String s = sourceLines.get(i).trim();
            if (s.startsWith("===") && s.endsWith("===") && s.length() > 6) {
                int displayIdx = -1;
                for (int d = 0; d < srcOfDisplay.size(); d++) {
                    if (srcOfDisplay.get(d) == i) { displayIdx = d; break; }
                }
                if (displayIdx < 0) displayIdx = 0;
                sections.add(new int[]{ i, displayIdx });
            }
        }
    }

    private void onSearchChanged(String s) {
        recomputeMatches();
        if (!matchSourceLines.isEmpty()) {
            currentMatch = 0;
            scrollToSource(matchSourceLines.get(0));
        } else {
            currentMatch = -1;
        }
    }

    private void recomputeMatches() {
        matchSourceLines.clear();
        String q = searchBox == null ? "" : searchBox.getValue();
        if (q == null || q.isEmpty()) return;
        String lower = q.toLowerCase();
        for (int i = 0; i < sourceLines.size(); i++) {
            if (sourceLines.get(i).toLowerCase().contains(lower)) {
                matchSourceLines.add(i);
            }
        }
    }

    private void jumpMatch(int dir) {
        if (matchSourceLines.isEmpty()) return;
        if (currentMatch < 0) currentMatch = 0;
        currentMatch = (currentMatch + dir + matchSourceLines.size())
                % matchSourceLines.size();
        scrollToSource(matchSourceLines.get(currentMatch));
    }

    private void scrollToSource(int sourceIdx) {
        for (int d = 0; d < srcOfDisplay.size(); d++) {
            if (srcOfDisplay.get(d) == sourceIdx) {
                scrollLine = Math.max(0, d - 2);
                clampScroll();
                return;
            }
        }
    }

    private int visibleLineCount() {
        return Math.max(1, contentH / lineHeight);
    }

    private void clampScroll() {
        int maxScroll = Math.max(0, displayLines.size() - visibleLineCount());
        scrollLine = Math.max(0, Math.min(scrollLine, maxScroll));
    }

    private void copyAll() {
        Minecraft.getInstance().keyboardHandler.setClipboard(
                String.join("\n", sourceLines));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float pt) {
        renderBackground(g);

        g.fill(panelX, panelY, panelX + PANEL_W, panelY + PANEL_H, C_BG);
        drawBorder(g, panelX, panelY, PANEL_W, PANEL_H, 2, C_BORDER);

        g.drawString(font, headerTitle, panelX + 10, panelY + 12, C_TITLE);
        g.fill(panelX + 2, panelY + 28, panelX + PANEL_W - 2, panelY + 29, C_SEP);

        g.drawString(font, "Search:", panelX + 10, panelY + 42, C_DIM);
        String matchInfo;
        if (matchSourceLines.isEmpty()) {
            matchInfo = (searchBox != null && !searchBox.getValue().isEmpty())
                    ? "0 matches" : "";
        } else {
            matchInfo = (currentMatch + 1) + " / " + matchSourceLines.size();
        }
        g.drawString(font, matchInfo, panelX + PANEL_W - 110, panelY + 42, C_DIM);

        g.fill(sideX, sideY, sideX + sideW, sideY + sideH, C_PANE_BG);
        drawBorder(g, sideX, sideY, sideW, sideH, 1, C_SEP);
        g.drawString(font, "Sections", sideX + 4, sideY + 4, C_TITLE);
        drawSections(g, mouseX, mouseY, sideX, sideY + 16, sideW, sideH - 16);

        g.fill(paneX, paneY, paneX + paneW, paneY + paneH, C_PANE_BG);
        drawBorder(g, paneX, paneY, paneW, paneH, 1, C_SEP);
        drawText(g);
        drawScrollbar(g, paneX + paneW - 6, paneY + 2, paneH - 4);

        String status = "lines " + (sourceLines.isEmpty() ? 0 : 1)
                + "-" + sourceLines.size()
                + "  |  PgUp/PgDn scroll  |  Ctrl+F search  |  Ctrl+C copy";
        g.drawString(font, status, panelX + 10, panelY + PANEL_H - 18, C_DIM);

        super.render(g, mouseX, mouseY, pt);
    }

    private void drawText(GuiGraphics g) {
        clampScroll();
        int visible = visibleLineCount();
        int y = contentY;

        String q = searchBox == null ? "" : searchBox.getValue().toLowerCase();
        boolean hasQ = !q.isEmpty();
        int currentSrc = (currentMatch >= 0 && currentMatch < matchSourceLines.size())
                ? matchSourceLines.get(currentMatch) : -1;

        for (int i = 0; i < visible && (scrollLine + i) < displayLines.size(); i++) {
            int idx = scrollLine + i;
            int srcIdx = srcOfDisplay.get(idx);
            String src = sourceLines.get(srcIdx);

            if (hasQ && src.toLowerCase().contains(q)) {
                int bg = (srcIdx == currentSrc) ? C_HIT_BG : C_MATCH_BG;
                g.fill(contentX - 2, y - 1, contentX + contentW, y + lineHeight - 2, bg);
            }

            int color = C_TEXT;
            String trimmed = src.trim();
            if (trimmed.startsWith("===") && trimmed.endsWith("===")) color = C_HEADER;
            else if (trimmed.startsWith("Error:") || trimmed.startsWith("Simulation Error:"))
                color = C_ERROR;

            g.drawString(font, displayLines.get(idx), contentX, y, color, false);
            y += lineHeight;
        }
    }

    private void drawScrollbar(GuiGraphics g, int x, int y, int h) {
        int total = Math.max(displayLines.size(), 1);
        int visible = visibleLineCount();
        if (total <= visible) return;
        g.fill(x, y, x + 4, y + h, C_SCROLL_BG);
        int barH = Math.max(12, (int) (h * (double) visible / total));
        double frac = scrollLine / (double) Math.max(1, total - visible);
        int barY = y + (int) ((h - barH) * frac);
        g.fill(x, barY, x + 4, barY + barH, C_SCROLL);
    }

    private void drawSections(GuiGraphics g, int mouseX, int mouseY,
                              int x, int y, int w, int h) {
        if (sections.isEmpty()) {
            g.drawString(font, "(no sections)", x + 4, y + 4, C_DIM);
            return;
        }
        int rowH = lineHeight;
        int visible = Math.max(1, h / rowH);
        sectionScroll = Math.max(0, Math.min(sectionScroll,
                Math.max(0, sections.size() - visible)));

        int yy = y + 2;
        int maxLabelW = w - 8;
        for (int i = 0; i < visible && (sectionScroll + i) < sections.size(); i++) {
            int[] sec = sections.get(sectionScroll + i);
            String label = sourceLines.get(sec[0]).replace("===", "").trim();
            while (font.width(label) > maxLabelW && label.length() > 1) {
                label = label.substring(0, label.length() - 2) + "..";
            }
            boolean hover = mouseX >= x + 2 && mouseX <= x + w - 2
                         && mouseY >= yy - 1 && mouseY < yy + rowH - 1;
            if (hover) g.fill(x + 2, yy - 1, x + w - 2, yy + rowH - 1, C_HOVER_BG);
            g.drawString(font, label, x + 4, yy, hover ? 0xFFFFFFFF : C_HEADER);
            yy += rowH;
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        // section sidebar click -> jump to that section
        int listY = sideY + 16;
        int listH = sideH - 16;
        if (mx >= sideX + 2 && mx <= sideX + sideW - 2
                && my >= listY + 2 && my < listY + listH) {
            int rowH = lineHeight;
            int idx = (int) ((my - listY - 2) / rowH) + sectionScroll;
            if (idx >= 0 && idx < sections.size()) {
                scrollLine = sections.get(idx)[1];
                clampScroll();
                return true;
            }
        }
        return super.mouseClicked(mx, my, btn);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (mx >= contentX && mx <= contentX + contentW
                && my >= contentY && my <= contentY + contentH) {
            scrollLine -= (int) Math.signum(delta) * 3;
            clampScroll();
            return true;
        }
        if (mx >= sideX && mx <= sideX + sideW
                && my >= sideY && my <= sideY + sideH) {
            sectionScroll -= (int) Math.signum(delta);
            sectionScroll = Math.max(0, sectionScroll);
            return true;
        }
        return super.mouseScrolled(mx, my, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        boolean ctrl = (modifiers & GLFW.GLFW_MOD_CONTROL) != 0;
        if (ctrl && keyCode == GLFW.GLFW_KEY_F) {
            setFocused(searchBox);
            searchBox.setFocused(true);
            return true;
        }
        if (ctrl && keyCode == GLFW.GLFW_KEY_C && getFocused() != searchBox) {
            copyAll();
            return true;
        }

        if (getFocused() instanceof EditBox eb
                && eb.keyPressed(keyCode, scanCode, modifiers)) {
            if (keyCode == GLFW.GLFW_KEY_ENTER) jumpMatch(+1);
            return true;
        }

        switch (keyCode) {
            case GLFW.GLFW_KEY_UP        -> { scrollLine -= 1; clampScroll(); return true; }
            case GLFW.GLFW_KEY_DOWN      -> { scrollLine += 1; clampScroll(); return true; }
            case GLFW.GLFW_KEY_PAGE_UP   -> { scrollLine -= visibleLineCount(); clampScroll(); return true; }
            case GLFW.GLFW_KEY_PAGE_DOWN -> { scrollLine += visibleLineCount(); clampScroll(); return true; }
            case GLFW.GLFW_KEY_HOME      -> { scrollLine = 0; return true; }
            case GLFW.GLFW_KEY_END       -> { scrollLine = displayLines.size(); clampScroll(); return true; }
            case GLFW.GLFW_KEY_ENTER     -> { jumpMatch(+1); return true; }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char c, int modifiers) {
        if (getFocused() instanceof EditBox eb && eb.charTyped(c, modifiers)) return true;
        return super.charTyped(c, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static void drawBorder(GuiGraphics g, int x, int y, int w, int h, int t, int color) {
        g.fill(x, y, x + w, y + t, color);
        g.fill(x, y + h - t, x + w, y + h, color);
        g.fill(x, y, x + t, y + h, color);
        g.fill(x + w - t, y, x + w, y + h, color);
    }
}
