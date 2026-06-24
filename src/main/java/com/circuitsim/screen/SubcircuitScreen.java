package com.circuitsim.screen;

import com.circuitsim.subcircuit.SubcircuitBlueprint;
import com.circuitsim.subcircuit.SubcircuitChip;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.registries.Registries;
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
    // 3D preview panel — spans the full right column down to the inventory's
    // bottom, filling the otherwise-empty lower-right area.
    private static final int REN_X0 = 218, REN_Y0 = 32, REN_X1 = 332, REN_Y1 = 226;
    // Header.
    private static final int NAME_X = 44, NAME_Y = 20;
    private static final int COPY_X = 280, COPY_Y = 16, COPY_W = 50, COPY_H = 14;
    // Zoom slider (vertical, right edge of the preview panel).
    private static final int SLIDER_W = 5;
    private static final float ZOOM_MIN = 0.4f, ZOOM_MAX = 4.0f;

    private int scroll = 0;
    private List<String> defLines = new ArrayList<>();
    private Button copyButton;

    // Top-down 3D preview, cached and recomputed only when the loaded chip changes.
    private List<SubcircuitBlueprint.PreviewBlock> previewBlocks = new ArrayList<>();
    private String previewKey = null;
    private float pCenterX, pCenterY, pCenterZ; // centre of the captured footprint
    private float pSpan = 1;                     // largest horizontal extent
    private float zoom = 1.0f;                   // preview zoom multiplier (1 = fit)
    private boolean draggingZoom = false;

    public SubcircuitScreen(SubcircuitMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        this.imageWidth = 340;
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

    /** Re-parses the chip's blueprint into preview blocks only when it changes. */
    private void refreshPreview() {
        ItemStack chip = menu.getChipStack();
        String key = SubcircuitChip.isPresent(chip)
                ? SubcircuitChip.getName(chip) + "#" + SubcircuitChip.getDef(chip).length()
                : null;
        if (java.util.Objects.equals(key, previewKey)) return;
        previewKey = key;
        previewBlocks = new ArrayList<>();
        pCenterX = pCenterY = pCenterZ = 0; pSpan = 1;
        if (key == null || Minecraft.getInstance().level == null) return;
        var blocks = Minecraft.getInstance().level.holderLookup(Registries.BLOCK);
        previewBlocks = SubcircuitBlueprint.previewBlocks(SubcircuitChip.getBlueprint(chip), blocks);
        if (previewBlocks.isEmpty()) return;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (var b : previewBlocks) {
            minX = Math.min(minX, b.dx()); maxX = Math.max(maxX, b.dx());
            minY = Math.min(minY, b.dy()); maxY = Math.max(maxY, b.dy());
            minZ = Math.min(minZ, b.dz()); maxZ = Math.max(maxZ, b.dz());
        }
        pCenterX = (minX + maxX + 1) / 2f;
        pCenterY = (minY + maxY + 1) / 2f;
        pCenterZ = (minZ + maxZ + 1) / 2f;
        pSpan = Math.max(1, Math.max(maxX - minX + 1, maxZ - minZ + 1));
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

        // Top-down circuit preview
        refreshPreview();
        drawPanel(g, x + REN_X0, y + REN_Y0, x + REN_X1, y + REN_Y1, hasChip && !previewBlocks.isEmpty());
        int rcx = x + (REN_X0 + REN_X1) / 2;
        int rcy = y + (REN_Y0 + REN_Y1) / 2;
        if (!hasChip) {
            String s1 = "Circuit", s2 = "preview";
            g.drawString(font, s1, rcx - font.width(s1) / 2, rcy - 6, MUTED_COLOR, false);
            g.drawString(font, s2, rcx - font.width(s2) / 2, rcy + 4, MUTED_COLOR, false);
        } else if (previewBlocks.isEmpty()) {
            String s = "(no layout)";
            g.drawString(font, s, rcx - font.width(s) / 2, rcy - 4, MUTED_COLOR, false);
        } else {
            drawPreview3D(g, x, y);
        }
    }

    /**
     * Renders the captured circuit with the real block models, viewed from an
     * angled top-down camera (isometric-ish), auto-scaled to fit the slot.
     */
    private void drawPreview3D(GuiGraphics g, int x, int y) {
        // Render area = panel minus a strip on the right for the zoom slider.
        int areaX0 = x + REN_X0 + 2, areaY0 = y + REN_Y0 + 2;
        int areaW = (REN_X1 - REN_X0) - 4 - 10;
        int areaH = (REN_Y1 - REN_Y0) - 4;

        // Scissor keeps the 3D render inside the render area.
        g.enableScissor(areaX0, areaY0, areaX0 + areaW, areaY0 + areaH);

        // Fit: a 45°-yawed square footprint projects to ~span*1.41 across; the
        // pitch squashes the depth axis, so width is the binding dimension.
        float scale = Math.min(areaW, areaH) / (pSpan * 1.9f) * zoom;

        PoseStack pose = g.pose();
        pose.pushPose();
        // Centre of the render area, pushed toward the viewer in Z.
        pose.translate(areaX0 + areaW / 2f, areaY0 + areaH / 2f, 250);
        pose.scale(scale, -scale, scale);          // flip Y so model-up is screen-up
        pose.mulPose(Axis.XP.rotationDegrees(60)); // tilt down (top-down-ish)
        pose.mulPose(Axis.YP.rotationDegrees(45)); // diagonal
        pose.translate(-pCenterX, -pCenterY, -pCenterZ);

        // Bright, even lighting and no fog so the blocks don't get dimmed by the
        // GUI-space depth (chunk render types still apply fog + face shading).
        Lighting.setupForFlatItems();
        float prevFogStart = RenderSystem.getShaderFogStart();
        float prevFogEnd = RenderSystem.getShaderFogEnd();
        RenderSystem.setShaderFogStart(1.0E8f);
        RenderSystem.setShaderFogEnd(1.0E9f);

        MultiBufferSource.BufferSource buffers = g.bufferSource();
        BlockRenderDispatcher dispatcher = Minecraft.getInstance().getBlockRenderer();
        for (var b : previewBlocks) {
            pose.pushPose();
            pose.translate(b.dx(), b.dy(), b.dz());
            dispatcher.renderSingleBlock(b.state(), pose, buffers,
                    LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
            pose.popPose();
        }
        buffers.endBatch();
        pose.popPose();

        RenderSystem.setShaderFogStart(prevFogStart);
        RenderSystem.setShaderFogEnd(prevFogEnd);
        Lighting.setupFor3DItems();

        g.disableScissor();
        drawZoomSlider(g, x, y);
    }

    /** Vertical zoom slider on the right edge of the preview panel (top = zoom in). */
    private void drawZoomSlider(GuiGraphics g, int x, int y) {
        int tx0 = x + REN_X1 - 8, tx1 = tx0 + SLIDER_W;
        int ty0 = y + REN_Y0 + 8, ty1 = y + REN_Y1 - 8;
        g.fill(tx0, ty0, tx1, ty1, 0xFF000000);
        g.fill(tx0, ty0, tx1, ty0 + 1, 0xFF555555);
        g.fill(tx0, ty1 - 1, tx1, ty1, 0xFF555555);

        float t = (zoom - ZOOM_MIN) / (ZOOM_MAX - ZOOM_MIN);
        int trackH = ty1 - ty0, thumbH = 8;
        int thumbY = (int) (ty1 - thumbH - t * (trackH - thumbH));
        g.fill(tx0 - 1, thumbY, tx1 + 1, thumbY + thumbH, BORDER_COLOR);

        g.drawString(font, "+", tx0 - 1, ty0 - 9, MUTED_COLOR, false);
        g.drawString(font, "−", tx0, ty1 + 1, MUTED_COLOR, false);
    }

    private boolean overZoomTrack(double mx, double my) {
        int tx0 = leftPos + REN_X1 - 10, tx1 = leftPos + REN_X1 - 1;
        int ty0 = topPos + REN_Y0 + 6, ty1 = topPos + REN_Y1 - 6;
        return mx >= tx0 && mx <= tx1 && my >= ty0 && my <= ty1;
    }

    private void setZoomFromMouse(double my) {
        int ty0 = topPos + REN_Y0 + 8, ty1 = topPos + REN_Y1 - 8;
        float t = (float) ((ty1 - my) / (double) (ty1 - ty0));
        t = Math.max(0f, Math.min(1f, t));
        zoom = ZOOM_MIN + t * (ZOOM_MAX - ZOOM_MIN);
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
        // Scroll over the preview to zoom too.
        if (mx >= x + REN_X0 && mx <= x + REN_X1 && my >= y + REN_Y0 && my <= y + REN_Y1) {
            zoom = Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, zoom + (float) delta * 0.25f));
            return true;
        }
        return super.mouseScrolled(mx, my, delta);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0 && SubcircuitChip.isPresent(menu.getChipStack()) && overZoomTrack(mx, my)) {
            draggingZoom = true;
            setZoomFromMouse(my);
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (draggingZoom && button == 0) {
            setZoomFromMouse(my);
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (draggingZoom && button == 0) {
            draggingZoom = false;
            return true;
        }
        return super.mouseReleased(mx, my, button);
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
