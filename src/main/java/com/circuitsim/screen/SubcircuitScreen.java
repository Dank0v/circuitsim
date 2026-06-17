package com.circuitsim.screen;

import com.circuitsim.subcircuit.SubcircuitChip;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * GUI for the {@link com.circuitsim.block.SubcircuitBlock}. Slot 1 = chip input;
 * slot 2 = a scrollable, read-only view of the loaded subcircuit's
 * {@code .subckt} netlist (with a Copy button); slot 3 = a grayed
 * "preview coming soon" placeholder for the future top-down render.
 */
public class SubcircuitScreen extends net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<SubcircuitMenu> {

    private static final int BG_COLOR     = 0xFF1E1E1E;
    private static final int PANEL_COLOR  = 0xFF2A2A2A;
    private static final int BORDER_COLOR = 0xFF4A90D9;
    private static final int SLOT_BG      = 0xFF101010;
    private static final int LABEL_COLOR  = 0xFFFFFFFF;
    private static final int MUTED_COLOR  = 0xFF777777;

    // Netlist text panel (relative to leftPos/topPos).
    private static final int NET_X0 = 44, NET_Y0 = 32, NET_X1 = 212, NET_Y1 = 134;
    // Deferred render placeholder.
    private static final int REN_X0 = 218, REN_Y0 = 32, REN_X1 = 312, REN_Y1 = 134;
    // Header.
    private static final int NAME_X = 44, NAME_Y = 20;
    private static final int COPY_X = 260, COPY_Y = 16, COPY_W = 50, COPY_H = 14;

    private int scroll = 0;
    private List<String> defLines = new ArrayList<>();
    private Button copyButton;

    public SubcircuitScreen(SubcircuitMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        this.imageWidth = 320;
        this.imageHeight = 238;
    }

    @Override
    protected void init() {
        super.init();
        this.inventoryLabelY = 140;
        this.titleLabelX = 8;
        copyButton = Button.builder(Component.literal("Copy"), b -> {
            String def = SubcircuitChip.getDef(menu.getChipStack());
            if (!def.isEmpty()) Minecraft.getInstance().keyboardHandler.setClipboard(def);
        }).bounds(leftPos + COPY_X, topPos + COPY_Y, COPY_W, COPY_H).build();
        addRenderableWidget(copyButton);
    }

    private void refreshDef() {
        ItemStack chip = menu.getChipStack();
        String def = SubcircuitChip.getDef(chip);
        defLines = new ArrayList<>();
        if (!def.isEmpty()) {
            for (String line : def.split("\n", -1)) defLines.add(line);
        }
        int maxScroll = Math.max(0, defLines.size() - visibleLines());
        if (scroll > maxScroll) scroll = maxScroll;
        if (scroll < 0) scroll = 0;
        copyButton.active = !def.isEmpty();
        copyButton.visible = !def.isEmpty();
    }

    private int visibleLines() {
        return (NET_Y1 - NET_Y0 - 4) / (font.lineHeight + 1);
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        refreshDef();
        int x = leftPos, y = topPos;

        // Outer panel
        g.fill(x, y, x + imageWidth, y + imageHeight, BG_COLOR);
        g.fill(x, y, x + imageWidth, y + 1, BORDER_COLOR);
        g.fill(x, y + imageHeight - 1, x + imageWidth, y + imageHeight, BORDER_COLOR);
        g.fill(x, y, x + 1, y + imageHeight, BORDER_COLOR);
        g.fill(x + imageWidth - 1, y, x + imageWidth, y + imageHeight, BORDER_COLOR);

        // Slot backgrounds (chip + player inventory)
        for (Slot slot : menu.slots) {
            g.fill(x + slot.x - 1, y + slot.y - 1, x + slot.x + 17, y + slot.y + 17, SLOT_BG);
        }

        boolean hasChip = SubcircuitChip.isPresent(menu.getChipStack());

        // Header: chip label + subcircuit name (truncated so it never reaches Copy)
        g.drawString(font, "Chip", x + 14, y + 24, LABEL_COLOR, false);
        String nameText = hasChip
                ? "Subcircuit: " + SubcircuitChip.getName(menu.getChipStack())
                : "Insert a Subcircuit Chip";
        int nameMaxW = COPY_X - NAME_X - 8;
        nameText = font.plainSubstrByWidth(nameText, nameMaxW);
        g.drawString(font, nameText, x + NAME_X, y + NAME_Y, hasChip ? LABEL_COLOR : MUTED_COLOR, false);

        // Netlist panel
        drawPanel(g, x + NET_X0, y + NET_Y0, x + NET_X1, y + NET_Y1, hasChip);
        if (hasChip) {
            g.enableScissor(x + NET_X0 + 2, y + NET_Y0 + 2, x + NET_X1 - 2, y + NET_Y1 - 2);
            int ty = y + NET_Y0 + 3;
            int lh = font.lineHeight + 1;
            for (int i = scroll; i < defLines.size() && ty < y + NET_Y1 - 2; i++, ty += lh) {
                g.drawString(font, defLines.get(i), x + NET_X0 + 4, ty, 0xFFB0E0B0, false);
            }
            g.disableScissor();
            if (defLines.size() > visibleLines()) {
                drawScrollbar(g, x, y);
            }
        }

        // Deferred render placeholder
        drawPanel(g, x + REN_X0, y + REN_Y0, x + REN_X1, y + REN_Y1, false);
        String s1 = "Preview", s2 = "coming soon";
        int rcx = x + (REN_X0 + REN_X1) / 2;
        int rcy = y + (REN_Y0 + REN_Y1) / 2;
        g.drawString(font, s1, rcx - font.width(s1) / 2, rcy - 6, MUTED_COLOR, false);
        g.drawString(font, s2, rcx - font.width(s2) / 2, rcy + 4, MUTED_COLOR, false);
    }

    private void drawScrollbar(GuiGraphics g, int x, int y) {
        int trackX0 = x + NET_X1 - 4, trackX1 = x + NET_X1 - 1;
        int trackY0 = y + NET_Y0 + 2, trackY1 = y + NET_Y1 - 2;
        g.fill(trackX0, trackY0, trackX1, trackY1, 0xFF000000);
        int total = defLines.size();
        int vis = visibleLines();
        int trackH = trackY1 - trackY0;
        int thumbH = Math.max(8, trackH * vis / total);
        int maxScroll = Math.max(1, total - vis);
        int thumbY = trackY0 + (trackH - thumbH) * scroll / maxScroll;
        g.fill(trackX0, thumbY, trackX1, thumbY + thumbH, BORDER_COLOR);
    }

    private void drawPanel(GuiGraphics g, int x0, int y0, int x1, int y1, boolean active) {
        g.fill(x0, y0, x1, y1, PANEL_COLOR);
        int border = active ? BORDER_COLOR : 0xFF444444;
        g.fill(x0, y0, x1, y0 + 1, border);
        g.fill(x0, y1 - 1, x1, y1, border);
        g.fill(x0, y0, x0 + 1, y1, border);
        g.fill(x1 - 1, y0, x1, y1, border);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        int x = leftPos, y = topPos;
        if (mx >= x + NET_X0 && mx <= x + NET_X1 && my >= y + NET_Y0 && my <= y + NET_Y1) {
            int maxScroll = Math.max(0, defLines.size() - visibleLines());
            scroll = Math.max(0, Math.min(maxScroll, scroll - (int) Math.signum(delta)));
            return true;
        }
        return super.mouseScrolled(mx, my, delta);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        super.render(g, mouseX, mouseY, partialTick);
        renderTooltip(g, mouseX, mouseY);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        g.drawString(font, title, titleLabelX, 7, LABEL_COLOR, false);
        g.drawString(font, playerInventoryTitle, titleLabelX, inventoryLabelY, 0xFFBBBBBB, false);
    }
}
