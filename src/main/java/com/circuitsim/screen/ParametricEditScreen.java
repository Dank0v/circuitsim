package com.circuitsim.screen;

import com.circuitsim.block.*;
import com.circuitsim.blockentity.ComponentBlockEntity;
import com.circuitsim.client.ClientSetup;
import com.circuitsim.network.ModMessages;
import com.circuitsim.network.ParametricSimulatePacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public class ParametricEditScreen extends AbstractContainerScreen<ParametricEditMenu> {

    private EditBox paramField;
    private Button simulateButton;
    private Button cancelButton;

    private String targetComponentName = "Unknown";
    private BlockPos pos;

    // ---- colours (match ComponentEditScreen palette) ----
    private static final int TITLE_COLOR  = 0xFFFFD700;
    private static final int LABEL_COLOR  = 0xFFFFFFFF;
    private static final int HINT_COLOR   = 0xFFAAAAAA;
    private static final int FIELD_COLOR  = 0xFFFFFFFF;
    private static final int BG_COLOR     = 0xFF1E1E1E;
    private static final int BORDER_COLOR = 0xFF4A90D9;
    private static final int TARGET_COLOR = 0xFF88FF88;

    public ParametricEditScreen(ParametricEditMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        this.imageWidth  = 280;
        this.imageHeight = 140;
    }

    @Override
    protected void init() {
        super.init();

        pos = ClientSetup.getLastInteractedPos();

        // Determine target component from facing direction
        BlockState paramState = Minecraft.getInstance().level.getBlockState(pos);
        if (paramState.hasProperty(BaseComponentBlock.FACING)) {
            Direction facing = paramState.getValue(BaseComponentBlock.FACING);
            BlockPos targetPos = pos.relative(facing);
            BlockState targetState = Minecraft.getInstance().level.getBlockState(targetPos);
            targetComponentName = getComponentDisplayName(targetState.getBlock());
        }

        // Pre-populate with stored param string (kept in ComponentBlockEntity.label)
        String stored = "";
        BlockEntity be = Minecraft.getInstance().level.getBlockEntity(pos);
        if (be instanceof ComponentBlockEntity cbe) {
            stored = cbe.getLabel();
        }

        int panelX = (this.width  - this.imageWidth)  / 2;
        int panelY = (this.height - this.imageHeight) / 2;

        // Sweep-values edit box
        paramField = new EditBox(
                Minecraft.getInstance().font,
                panelX + 10, panelY + 68,
                this.imageWidth - 20, 18,
                Component.empty()
        );
        paramField.setValue(stored);
        paramField.setMaxLength(256);
        paramField.setBordered(true);
        paramField.setTextColor(FIELD_COLOR);
        addRenderableWidget(paramField);
        setInitialFocus(paramField);

        int btnY = panelY + this.imageHeight - 26;

        simulateButton = Button.builder(Component.literal("▶ Simulate"), button -> {
            ModMessages.sendToServer(new ParametricSimulatePacket(pos, paramField.getValue()));
            Minecraft.getInstance().setScreen(null);
        }).bounds(panelX + 20, btnY, 110, 20).build();
        addRenderableWidget(simulateButton);

        cancelButton = Button.builder(Component.literal("Cancel"), button ->
                Minecraft.getInstance().setScreen(null)
        ).bounds(panelX + 150, btnY, 110, 20).build();
        addRenderableWidget(cancelButton);
    }

    // ---- rendering -------------------------------------------------------

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        int x = (this.width  - this.imageWidth)  / 2;
        int y = (this.height - this.imageHeight) / 2;

        g.fill(x, y, x + imageWidth, y + imageHeight, BG_COLOR);

        // Border
        g.fill(x,                  y,                   x + imageWidth, y + 2,           BORDER_COLOR);
        g.fill(x,                  y + imageHeight - 2, x + imageWidth, y + imageHeight, BORDER_COLOR);
        g.fill(x,                  y,                   x + 2,          y + imageHeight, BORDER_COLOR);
        g.fill(x + imageWidth - 2, y,                   x + imageWidth, y + imageHeight, BORDER_COLOR);

        // Separator under title
        g.fill(x + 2, y + 23, x + imageWidth - 2, y + 24, 0xFF444444);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        // Suppress default inventory/container labels
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g);
        super.render(g, mouseX, mouseY, partialTick);

        int panelX = (this.width  - this.imageWidth)  / 2;
        int panelY = (this.height - this.imageHeight) / 2;
        var font    = Minecraft.getInstance().font;

        // Title
        g.drawCenteredString(font, "Parametric Analysis",
                this.width / 2, panelY + 7, TITLE_COLOR);

        // Target component
        g.drawString(font, "Target component: ", panelX + 12, panelY + 30, LABEL_COLOR);
        g.drawString(font, targetComponentName,
                panelX + 12 + font.width("Target component: "), panelY + 30, TARGET_COLOR);

        // Field label
        g.drawString(font, "Sweep values:", panelX + 12, panelY + 50, LABEL_COLOR);

        // Hint
        g.drawString(font, "list: 1k,2k,5k,10k   or   range: 100:1k:100",
                panelX + 12, panelY + 60, HINT_COLOR);
    }

    // ---- input pass-through ----------------------------------------------

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (this.getFocused() instanceof EditBox eb) {
            if (eb.keyPressed(keyCode, scanCode, modifiers)) return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (this.getFocused() instanceof EditBox eb) {
            if (eb.charTyped(codePoint, modifiers)) return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean shouldCloseOnEsc() { return true; }

    // ---- helpers ---------------------------------------------------------

    private static String getComponentDisplayName(Block block) {
        if (block instanceof ResistorBlock)      return "Resistor";
        if (block instanceof CapacitorBlock)     return "Capacitor";
        if (block instanceof InductorBlock)      return "Inductor";
        if (block instanceof VoltageSourceBlock) return "Voltage Source";
        if (block instanceof CurrentSourceBlock) return "Current Source";
        return "Unknown (face a component)";
    }
}