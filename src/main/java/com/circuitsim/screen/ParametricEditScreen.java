package com.circuitsim.screen;

import com.circuitsim.block.*;
import com.circuitsim.blockentity.ComponentBlockEntity;
import com.circuitsim.client.ClientSetup;
import com.circuitsim.network.ComponentUpdatePacket;
import com.circuitsim.network.ModMessages;
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

import java.util.ArrayList;
import java.util.List;

public class ParametricEditScreen extends AbstractContainerScreen<ParametricEditMenu> {

    private final List<ParamField> fields = new ArrayList<>();
    private Button doneButton;
    private Button cancelButton;

    private String   targetComponentName = "Unknown (face a component)";
    private Block    targetBlock         = null;
    private BlockPos pos;
    private String   errorMessage        = "";

    // ---- colours ----
    private static final int TITLE_COLOR  = 0xFFFFD700;
    private static final int LABEL_COLOR  = 0xFFFFFFFF;
    private static final int HINT_COLOR   = 0xFFAAAAAA;
    private static final int FIELD_COLOR  = 0xFFFFFFFF;
    private static final int BG_COLOR     = 0xFF1E1E1E;
    private static final int BORDER_COLOR = 0xFF4A90D9;
    private static final int TARGET_COLOR = 0xFF88FF88;
    private static final int ERROR_COLOR  = 0xFFFF6060;

    // ---- layout ----
    private static final int LABEL_H     = 10;
    private static final int GAP         = 4;
    private static final int BOX_H       = 18;
    private static final int FIELD_ROW_H = LABEL_H + GAP + BOX_H + 4; // 36
    private static final int HEADER_H    = 70; // title + target + hint
    private static final int FOOTER_H    = 36; // button area + error space

    private static class ParamField {
        final String name;   // "value", "W", "L", "mult", "nf"
        final String label;
        EditBox box;
        ParamField(String name, String label) {
            this.name  = name;
            this.label = label;
        }
    }

    public ParametricEditScreen(ParametricEditMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        this.imageWidth  = 280;
        this.imageHeight = 200;
    }

    @Override
    protected void init() {
        super.init();

        pos = ClientSetup.getLastInteractedPos();

        // Determine target component from facing direction
        BlockState paramState = Minecraft.getInstance().level.getBlockState(pos);
        if (paramState.hasProperty(BaseComponentBlock.FACING)) {
            Direction facing       = paramState.getValue(BaseComponentBlock.FACING);
            BlockPos  targetPos    = pos.relative(facing);
            BlockState targetState = Minecraft.getInstance().level.getBlockState(targetPos);
            targetBlock           = targetState.getBlock();
            targetComponentName   = getComponentDisplayName(targetBlock);
        }

        // Build the field list for this target type
        fields.clear();
        addFieldsForTarget(targetBlock);

        // Read stored sweep spec ("paramName=sweepString" or legacy bare sweep)
        String storedLabel = "";
        BlockEntity be = Minecraft.getInstance().level.getBlockEntity(pos);
        if (be instanceof ComponentBlockEntity cbe) {
            storedLabel = cbe.getLabel();
        }
        String[] parsed     = parseParametricSpec(storedLabel);
        String   storedParam = parsed[0];
        String   storedSweep = parsed[1];

        // Dynamic height based on field count
        int rows = Math.max(1, fields.size());
        this.imageHeight = HEADER_H + rows * FIELD_ROW_H + FOOTER_H;

        int panelX = (this.width  - this.imageWidth)  / 2;
        int panelY = (this.height - this.imageHeight) / 2;
        int fieldX = panelX + 10;
        int fieldW = this.imageWidth - 20;

        // Field rows
        int cursorY = panelY + HEADER_H;
        for (ParamField f : fields) {
            int boxY = cursorY + LABEL_H + GAP;
            f.box = new EditBox(
                    Minecraft.getInstance().font,
                    fieldX, boxY,
                    fieldW, BOX_H,
                    Component.empty()
            );
            f.box.setValue(f.name.equals(storedParam) ? storedSweep : "");
            f.box.setMaxLength(256);
            f.box.setBordered(true);
            f.box.setTextColor(FIELD_COLOR);
            addRenderableWidget(f.box);
            cursorY += FIELD_ROW_H;
        }
        if (!fields.isEmpty()) setInitialFocus(fields.get(0).box);

        // Buttons
        int btnY = panelY + this.imageHeight - 28;
        doneButton = Button.builder(Component.literal("Done"), button -> {
            if (saveSweepString()) {
                Minecraft.getInstance().setScreen(null);
            }
        }).bounds(panelX + 20, btnY, 110, 20).build();
        addRenderableWidget(doneButton);

        cancelButton = Button.builder(Component.literal("Cancel"), button ->
                Minecraft.getInstance().setScreen(null)
        ).bounds(panelX + 150, btnY, 110, 20).build();
        addRenderableWidget(cancelButton);
    }

    /** Populates {@code fields} based on the target block type. */
    private void addFieldsForTarget(Block b) {
        if (b instanceof IcNmos4Block || b instanceof IcPmos4Block) {
            fields.add(new ParamField("W",    "W (\u00b5m):"));
            fields.add(new ParamField("L",    "L (\u00b5m):"));
            fields.add(new ParamField("mult", "mult:"));
            fields.add(new ParamField("nf",   "NF:"));
        } else if (b instanceof IcResistorBlock) {
            fields.add(new ParamField("W",    "W (\u00b5m):"));
            fields.add(new ParamField("L",    "L (\u00b5m):"));
            fields.add(new ParamField("mult", "mult:"));
        } else if (b instanceof IcCapacitorBlock) {
            fields.add(new ParamField("W",    "W (\u00b5m):"));
            fields.add(new ParamField("L",    "L (\u00b5m):"));
            fields.add(new ParamField("mult", "MF:"));
        } else if (b instanceof ResistorBlock) {
            fields.add(new ParamField("value", "Resistance (\u03A9):"));
        } else if (b instanceof CapacitorBlock) {
            fields.add(new ParamField("value", "Capacitance (F):"));
        } else if (b instanceof InductorBlock) {
            fields.add(new ParamField("value", "Inductance (H):"));
        } else if (b instanceof VoltageSourceBlock || b instanceof VoltageSourceSinBlock) {
            fields.add(new ParamField("value", "Voltage (V):"));
        } else if (b instanceof CurrentSourceBlock) {
            fields.add(new ParamField("value", "Current (A):"));
        }
    }

    /** Validates exactly-one-field-filled, then saves spec as "paramName=sweepStr". */
    private boolean saveSweepString() {
        errorMessage = "";

        List<ParamField> filled = new ArrayList<>();
        for (ParamField f : fields) {
            if (f.box != null && !f.box.getValue().trim().isEmpty()) filled.add(f);
        }
        if (filled.isEmpty()) {
            errorMessage = "Enter values in one field";
            return false;
        }
        if (filled.size() > 1) {
            errorMessage = "Only one parameter can be swept at a time";
            return false;
        }

        ParamField sel = filled.get(0);
        String label  = sel.name + "=" + sel.box.getValue().trim();

        BlockEntity be      = Minecraft.getInstance().level.getBlockEntity(pos);
        double currentValue = 0.0;
        String currentSrc   = "DC";
        double currentFreq  = 0.0;
        if (be instanceof ComponentBlockEntity cbe) {
            currentValue = cbe.getValue();
            currentSrc   = cbe.getSourceType();
            currentFreq  = cbe.getFrequency();
        }
        ModMessages.sendToServer(new ComponentUpdatePacket(
                pos, currentValue, currentSrc, currentFreq, label));
        return true;
    }

    /** Parses "paramName=sweepStr". Returns ["value", raw] for legacy bare sweep strings. */
    public static String[] parseParametricSpec(String raw) {
        if (raw == null) return new String[]{"value", ""};
        int idx = raw.indexOf('=');
        if (idx < 0) return new String[]{"value", raw};
        String name  = raw.substring(0, idx).trim();
        String sweep = raw.substring(idx + 1);
        if (name.isEmpty()) name = "value";
        return new String[]{name, sweep};
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

        // Title separator
        g.fill(x + 2, y + 23, x + imageWidth - 2, y + 24, 0xFF444444);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        // Suppress default container labels
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g);
        super.render(g, mouseX, mouseY, partialTick);

        int panelX = (this.width  - this.imageWidth)  / 2;
        int panelY = (this.height - this.imageHeight) / 2;
        var font   = Minecraft.getInstance().font;

        // Title
        g.drawCenteredString(font, "Parametric Analysis",
                this.width / 2, panelY + 7, TITLE_COLOR);

        // Target component
        g.drawString(font, "Target component: ",
                panelX + 12, panelY + 30, LABEL_COLOR);
        g.drawString(font, targetComponentName,
                panelX + 12 + font.width("Target component: "), panelY + 30, TARGET_COLOR);

        // Hint (placed clearly above all fields)
        g.drawString(font, "Fill ONE field.  List: 1k,2k,5k    Range: 100:1k:100",
                panelX + 12, panelY + 50, HINT_COLOR);

        // Field labels
        int cursorY = panelY + HEADER_H;
        for (ParamField f : fields) {
            g.drawString(font, f.label, panelX + 12, cursorY, LABEL_COLOR);
            cursorY += FIELD_ROW_H;
        }

        // Inline error (if any)
        if (!errorMessage.isEmpty()) {
            int errY = panelY + this.imageHeight - 44;
            g.drawCenteredString(font, errorMessage,
                    this.width / 2, errY, ERROR_COLOR);
        }
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
        if (block instanceof ResistorBlock)         return "Resistor";
        if (block instanceof CapacitorBlock)        return "Capacitor";
        if (block instanceof InductorBlock)         return "Inductor";
        if (block instanceof VoltageSourceBlock)    return "Voltage Source";
        if (block instanceof VoltageSourceSinBlock) return "SIN Voltage Source";
        if (block instanceof CurrentSourceBlock)    return "Current Source";
        if (block instanceof IcResistorBlock)       return "IC Resistor";
        if (block instanceof IcCapacitorBlock)      return "IC Capacitor";
        if (block instanceof IcNmos4Block)          return "IC NMOS4";
        if (block instanceof IcPmos4Block)          return "IC PMOS4";
        return "Unknown (face a component)";
    }
}
