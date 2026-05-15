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
 *
 * Rendering is intentionally stripped down — a single background fill plus the
 * vanilla {@link EditBox} / {@link Button} widgets. An earlier version with a
 * custom section sidebar, custom scrollbar, and per-frame hover highlights
 * was reliably hanging the NVIDIA display driver (TDR) on RTX 50-series cards
 * when opened with parametric-sim output. Keep this screen boring.
 */
public class SimulationOutputScreen extends Screen {

    private final String       headerTitle;
    private final List<String> sourceLines;

    private static final int PANEL_W = 520;
    private static final int PANEL_H = 340;

    private static final int C_BG     = 0xFF1A1A2E;
    private static final int C_TITLE  = 0xFFFFD700;
    private static final int C_TEXT   = 0xFFE0E0E0;
    private static final int C_DIM    = 0xFF8888AA;
    private static final int C_HEADER = 0xFF4FC3F7;
    private static final int C_ERROR  = 0xFFFF6464;
    private static final int C_MATCH  = 0x80B58900;
    private static final int C_HIT    = 0xC0FF8800;
    private static final int C_BORDER = 0xFF4A90D9;

    private int panelX, panelY;
    private int contentX, contentY, contentW, contentH;
    private int lineHeight;

    private EditBox searchBox;

    private final List<FormattedCharSequence> displayLines = new ArrayList<>();
    private final List<Integer>               srcOfDisplay = new ArrayList<>();

    private int scrollLine    = 0;
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

        contentX = panelX + 10;
        contentY = panelY + 64;
        contentW = PANEL_W - 20;
        contentH = PANEL_H - 64 - 30;

        searchBox = new EditBox(font,
                panelX + 60, panelY + 36, PANEL_W - 220, 16,
                Component.literal("Search"));
        searchBox.setMaxLength(256);
        searchBox.setBordered(true);
        searchBox.setResponder(this::onSearchChanged);
        addRenderableWidget(searchBox);

        addRenderableWidget(Button.builder(Component.literal("<"),
                b -> jumpMatch(-1))
                .bounds(panelX + PANEL_W - 152, panelY + 34, 18, 20).build());
        addRenderableWidget(Button.builder(Component.literal(">"),
                b -> jumpMatch(+1))
                .bounds(panelX + PANEL_W - 132, panelY + 34, 18, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Copy"),
                b -> copyAll())
                .bounds(panelX + PANEL_W - 108, panelY + 8, 50, 18).build());
        addRenderableWidget(Button.builder(Component.literal("X"),
                b -> onClose())
                .bounds(panelX + PANEL_W - 28, panelY + 8, 20, 18).build());

        rewrap();
    }

    private void rewrap() {
        displayLines.clear();
        srcOfDisplay.clear();
        for (int i = 0; i < sourceLines.size(); i++) {
            String src = sourceLines.get(i);
            if (src == null || src.isEmpty()) {
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
        if (scrollLine < 0) scrollLine = 0;
        if (scrollLine > maxScroll) scrollLine = maxScroll;
    }

    private void copyAll() {
        Minecraft.getInstance().keyboardHandler.setClipboard(
                String.join("\n", sourceLines));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float pt) {
        renderBackground(g);

        // Single panel background + thin border. No nested panels, no
        // custom hover regions, no decorative separators.
        g.fill(panelX, panelY, panelX + PANEL_W, panelY + PANEL_H, C_BG);
        g.fill(panelX, panelY, panelX + PANEL_W, panelY + 1, C_BORDER);
        g.fill(panelX, panelY + PANEL_H - 1, panelX + PANEL_W, panelY + PANEL_H, C_BORDER);
        g.fill(panelX, panelY, panelX + 1, panelY + PANEL_H, C_BORDER);
        g.fill(panelX + PANEL_W - 1, panelY, panelX + PANEL_W, panelY + PANEL_H, C_BORDER);

        g.drawString(font, headerTitle, panelX + 10, panelY + 12, C_TITLE, false);
        g.drawString(font, "Search:", panelX + 10, panelY + 40, C_DIM, false);

        String matchInfo;
        if (matchSourceLines.isEmpty()) {
            matchInfo = (searchBox != null && !searchBox.getValue().isEmpty())
                    ? "0 matches" : "";
        } else {
            matchInfo = (currentMatch + 1) + " / " + matchSourceLines.size();
        }
        g.drawString(font, matchInfo, panelX + PANEL_W - 110, panelY + 40, C_DIM, false);

        drawText(g);

        String status = "lines " + (sourceLines.isEmpty() ? 0 : 1)
                + "-" + sourceLines.size()
                + "  |  scroll: wheel / arrows  |  Ctrl+F search  |  Ctrl+C copy";
        g.drawString(font, status, panelX + 10, panelY + PANEL_H - 16, C_DIM, false);

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

        int end = Math.min(displayLines.size(), scrollLine + visible);
        for (int idx = scrollLine; idx < end; idx++) {
            int srcIdx = srcOfDisplay.get(idx);
            String src = sourceLines.get(srcIdx);

            if (hasQ && src.toLowerCase().contains(q)) {
                int bg = (srcIdx == currentSrc) ? C_HIT : C_MATCH;
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

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (mx >= contentX && mx <= contentX + contentW
                && my >= contentY && my <= contentY + contentH) {
            scrollLine -= (int) Math.signum(delta) * 3;
            clampScroll();
            return true;
        }
        return super.mouseScrolled(mx, my, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        boolean ctrl = (modifiers & GLFW.GLFW_MOD_CONTROL) != 0;
        if (ctrl && keyCode == GLFW.GLFW_KEY_F) {
            setFocused(searchBox);
            if (searchBox != null) searchBox.setFocused(true);
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
}
