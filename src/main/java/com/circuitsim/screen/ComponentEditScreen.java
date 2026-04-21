package com.circuitsim.screen;

import com.circuitsim.blockentity.ComponentBlockEntity;
import com.circuitsim.client.ClientSetup;
import com.circuitsim.network.ComponentUpdatePacket;
import com.circuitsim.network.ModMessages;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.block.entity.BlockEntity;

public class ComponentEditScreen extends AbstractContainerScreen<ComponentEditMenu> {

    private EditBox valueField;
    private EditBox sourceTypeField;
    private EditBox labelField;
    private Button doneButton;
    private Button cancelButton;
    private String componentType;

    private boolean showValue;
    private boolean showSourceType;
    private boolean showLabel;

    /**
     * Row layout (per field):
     *   [label text  ~10px]
     *   [4px gap          ]
     *   [EditBox     18px ]
     *   [8px bottom pad   ]
     *   = 40px per row
     */
    private static final int LABEL_H  = 10;
    private static final int GAP      = 4;
    private static final int BOX_H    = 18;
    private static final int ROW_PAD  = 8;
    private static final int ROW_H    = LABEL_H + GAP + BOX_H + ROW_PAD; // 40

    private static final int TITLE_COLOR  = 0xFFFFD700;
    private static final int LABEL_COLOR  = 0xFFFFFFFF;
    private static final int FIELD_COLOR  = 0xFFFFFFFF;
    private static final int BG_COLOR     = 0xFF1E1E1E;
    private static final int BORDER_COLOR = 0xFF4A90D9;

    public ComponentEditScreen(ComponentEditMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        this.imageWidth  = 260;
        this.imageHeight = 200;
    }

    @Override
    protected void init() {
        super.init();

        net.minecraft.core.BlockPos pos = ClientSetup.getLastInteractedPos();
        BlockEntity be = Minecraft.getInstance().level.getBlockEntity(pos);

        double currentValue      = 0.0;
        String currentSourceType = "DC";
        String currentLabel      = "";

        if (be instanceof ComponentBlockEntity cbe) {
            currentValue      = cbe.getValue();
            currentSourceType = cbe.getSourceType();
            currentLabel      = cbe.getLabel();
            componentType     = cbe.getComponentType();
        } else {
            componentType = "resistor";
        }

        boolean isProbe        = "probe".equals(componentType);
        boolean isCurrentProbe = "current_probe".equals(componentType);
        boolean isVoltSrc      = "voltage_source".equals(componentType);
        boolean isDiode        = "diode".equals(componentType);

        showValue      = !isProbe && !isCurrentProbe && !isDiode;
        showSourceType = isVoltSrc;
        showLabel      = isProbe || isCurrentProbe;

        int rowCount = 0;
        if (showValue)      rowCount++;
        if (showSourceType) rowCount++;
        if (showLabel)      rowCount++;

        // top(10) + title(10) + underline-gap(10) + rows + buttons-area(36)
        this.imageHeight = 10 + 10 + 10 + (rowCount * ROW_H) + 36;

        int panelX = (this.width  - this.imageWidth)  / 2;
        int panelY = (this.height - this.imageHeight) / 2;

        int fieldX = panelX + 10;
        int fieldW = this.imageWidth - 20;

        // cursorY points at the TOP of the current row (where the label text will go)
        int cursorY = panelY + 30;

        if (showValue) {
            valueField = makeBox(fieldX, cursorY + LABEL_H + GAP, fieldW, formatValue(currentValue), 32);
            cursorY += ROW_H;
        }
        if (showSourceType) {
            sourceTypeField = makeBox(fieldX, cursorY + LABEL_H + GAP, fieldW, currentSourceType, 8);
            cursorY += ROW_H;
        }
        if (showLabel) {
            labelField = makeBox(fieldX, cursorY + LABEL_H + GAP, fieldW, currentLabel, 64);
        }

        int buttonY = panelY + this.imageHeight - 28;
        doneButton = Button.builder(Component.literal("Done"), button -> {
            sendUpdatePacket(pos);
            Minecraft.getInstance().setScreen(null);
        }).bounds(panelX + 20, buttonY, 90, 20).build();
        addRenderableWidget(doneButton);

        cancelButton = Button.builder(Component.literal("Cancel"), button ->
                Minecraft.getInstance().setScreen(null)
        ).bounds(panelX + 150, buttonY, 90, 20).build();
        addRenderableWidget(cancelButton);

        if      (showValue && valueField != null) this.setInitialFocus(valueField);
        else if (showLabel && labelField != null) this.setInitialFocus(labelField);
    }

    private EditBox makeBox(int x, int y, int w, String initial, int maxLen) {
        EditBox box = new EditBox(Minecraft.getInstance().font, x, y, w, BOX_H, Component.empty());
        box.setValue(initial);
        box.setMaxLength(maxLen);
        box.setBordered(true);
        box.setTextColor(FIELD_COLOR);
        addRenderableWidget(box);
        return box;
    }

    // -------------------------------------------------------------------------
    // Rendering
    // -------------------------------------------------------------------------

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        int x = (this.width  - this.imageWidth)  / 2;
        int y = (this.height - this.imageHeight) / 2;

        g.fill(x, y, x + imageWidth, y + imageHeight, BG_COLOR);

        // Border (2px)
        g.fill(x,                  y,                   x + imageWidth, y + 2,              BORDER_COLOR);
        g.fill(x,                  y + imageHeight - 2, x + imageWidth, y + imageHeight,    BORDER_COLOR);
        g.fill(x,                  y,                   x + 2,          y + imageHeight,    BORDER_COLOR);
        g.fill(x + imageWidth - 2, y,                   x + imageWidth, y + imageHeight,    BORDER_COLOR);

        // Separator below title
        g.fill(x + 2, y + 23, x + imageWidth - 2, y + 24, 0xFF444444);
    }

    /**
     * Override to suppress the default "Inventory" and container title labels
     * that AbstractContainerScreen renders automatically.
     */
    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        // Intentionally empty — we draw our own labels in render()
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g);
        super.render(g, mouseX, mouseY, partialTick);

        int panelX = (this.width  - this.imageWidth)  / 2;
        int panelY = (this.height - this.imageHeight) / 2;

        // Title
        g.drawCenteredString(Minecraft.getInstance().font,
                "Edit " + getComponentDisplayName(componentType),
                this.width / 2, panelY + 7, TITLE_COLOR);

        // Labels — must mirror init() cursorY logic exactly
        int cursorY = panelY + 30;
        int labelX  = panelX + 12;

        if (showValue) {
            g.drawString(Minecraft.getInstance().font,
                    getValueLabel(componentType) + ":", labelX, cursorY, LABEL_COLOR);
            cursorY += ROW_H;
        }
        if (showSourceType) {
            g.drawString(Minecraft.getInstance().font,
                    "Source Type (DC / AC):", labelX, cursorY, LABEL_COLOR);
            cursorY += ROW_H;
        }
        if (showLabel) {
            g.drawString(Minecraft.getInstance().font,
                    "Probe Label:", labelX, cursorY, LABEL_COLOR);
        }
    }

    // -------------------------------------------------------------------------
    // SI suffix parsing and formatting
    // -------------------------------------------------------------------------

    /**
     * Parses a string that may include an SI suffix into a double.
     * Supported suffixes (case-sensitive where ambiguous):
     *   f=1e-15, p=1e-12, n=1e-9, u/µ=1e-6, m=1e-3,
     *   k/K=1e3, M/Meg/meg=1e6, G=1e9, T=1e12
     * Plain numbers (with or without exponent notation) are also accepted.
     */
    public static double parseSI(String raw) throws NumberFormatException {
        if (raw == null) throw new NumberFormatException("null input");
        String s = raw.trim();
        if (s.isEmpty()) throw new NumberFormatException("empty input");

        // Try plain double first (handles "1e3", "0.5", etc.)
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException ignored) {}

        // Split at last alphabetic character(s)
        // Regex: a numeric part (optionally including sign and decimal) followed by a suffix
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^([+\\-]?[0-9]*\\.?[0-9]+(?:[eE][+\\-]?[0-9]+)?)(\\s*)([a-zA-Zµ]+)$")
                .matcher(s);
        if (!m.matches()) throw new NumberFormatException("Cannot parse: " + raw);

        double base = Double.parseDouble(m.group(1));
        String suffix = m.group(3);

        double multiplier = switch (suffix) {
            case "f"             -> 1e-15;
            case "p"             -> 1e-12;
            case "n"             -> 1e-9;
            case "u", "µ"        -> 1e-6;
            case "m"             -> 1e-3;
            case "k", "K"        -> 1e3;
            case "M", "Meg", "meg", "MEG" -> 1e6;
            case "G"             -> 1e9;
            case "T"             -> 1e12;
            // Allow unit letters to be appended (e.g. "10kΩ", "4.7uF") — strip them
            default -> {
                // Try again after stripping trailing unit letters (Ω, F, H, V, A, R, etc.)
                String stripped = suffix.replaceAll("[ΩFHVAROhm]+$", "");
                if (stripped.isEmpty()) yield 1.0; // pure unit suffix, no multiplier
                yield switch (stripped) {
                    case "f"             -> 1e-15;
                    case "p"             -> 1e-12;
                    case "n"             -> 1e-9;
                    case "u", "µ"        -> 1e-6;
                    case "m"             -> 1e-3;
                    case "k", "K"        -> 1e3;
                    case "M", "Meg", "meg", "MEG" -> 1e6;
                    case "G"             -> 1e9;
                    case "T"             -> 1e12;
                    default -> throw new NumberFormatException("Unknown suffix: " + suffix);
                };
            }
        };

        return base * multiplier;
    }

    /**
     * Formats a raw double into a compact string with an SI suffix.
     * Used to pre-populate the edit field.
     * Examples: 1000.0 → "1k", 0.000047 → "47u", 5.0 → "5"
     */
    public static String formatValue(double val) {
        if (val == 0.0) return "0";

        double abs = Math.abs(val);

        // Pick the best prefix tier
        double[][] tiers = {
                {1e12,  1e15, 1e12, -1},  // T
                {1e9,   1e12, 1e9,  -1},  // G
                {1e6,   1e9,  1e6,  -1},  // Meg
                {1e3,   1e6,  1e3,  -1},  // k
                {1e0,   1e3,  1e0,  -1},  // (none)
                {1e-3,  1e0,  1e-3, -1},  // m
                {1e-6,  1e-3, 1e-6, -1},  // u
                {1e-9,  1e-6, 1e-9, -1},  // n
                {1e-12, 1e-9, 1e-12,-1},  // p
                {1e-15, 1e-12,1e-15,-1},  // f
        };
        String[] names = {"T", "G", "Meg", "k", "", "m", "u", "n", "p", "f"};

        for (int i = 0; i < tiers.length; i++) {
            if (abs >= tiers[i][0] && abs < tiers[i][1]) {
                double scaled = val / tiers[i][2];
                String number = trimTrailingZeros(String.format("%.6f", scaled));
                return number + names[i];
            }
        }

        // Fallback: use plain scientific notation string
        return String.valueOf(val);
    }

    /** Strips trailing zeros after a decimal point (and the point itself if nothing follows). */
    private static String trimTrailingZeros(String s) {
        if (!s.contains(".")) return s;
        s = s.replaceAll("0+$", "");
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void sendUpdatePacket(net.minecraft.core.BlockPos pos) {
        double value = 0.0;
        if (valueField != null) {
            try {
                value = parseSI(valueField.getValue());
            } catch (NumberFormatException ignored) {
                // Leave value as 0 if the field can't be parsed
            }
        }
        String srcType = "DC";
        if (sourceTypeField != null) {
            srcType = "AC".equalsIgnoreCase(sourceTypeField.getValue()) ? "AC" : "DC";
        }
        String lbl = "";
        if (labelField != null) {
            lbl = labelField.getValue();
        }
        // frequency is no longer user-configurable; pass 0 to preserve packet compatibility
        ModMessages.sendToServer(new ComponentUpdatePacket(pos, value, srcType, 0.0, lbl));
    }

    private String getValueLabel(String type) {
        return switch (type) {
            case "resistor"       -> "Resistance (\u03A9)";
            case "capacitor"      -> "Capacitance (F)";
            case "inductor"       -> "Inductance (H)";
            case "voltage_source" -> "Voltage (V)";
            case "current_source" -> "Current (A)";
            default               -> "Value";
        };
    }

    private String getComponentDisplayName(String type) {
        return switch (type) {
            case "resistor"       -> "Resistor";
            case "capacitor"      -> "Capacitor";
            case "inductor"       -> "Inductor";
            case "voltage_source" -> "Voltage Source";
            case "current_source" -> "Current Source";
            case "diode"          -> "Diode";
            case "probe"          -> "Voltage Probe";
            case "current_probe"  -> "Current Probe";
            default               -> "Component";
        };
    }

    // -------------------------------------------------------------------------
    // Input passthrough
    // -------------------------------------------------------------------------

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
}