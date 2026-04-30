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
    private String  sourceType = "DC";          // replaces sourceTypeField
    private Button  sourceTypeToggle;
    private EditBox labelField;
    private EditBox frequencyField;
    private Button  doneButton;
    private Button  cancelButton;
    private String  componentType;

    private boolean showValue;
    private boolean showSourceType;
    private boolean showLabel;
    private boolean showFrequency;

    private static final int LABEL_H  = 10;
    private static final int GAP      = 4;
    private static final int BOX_H    = 18;
    private static final int ROW_PAD  = 8;
    private static final int ROW_H    = LABEL_H + GAP + BOX_H + ROW_PAD;

    private static final int TITLE_COLOR  = 0xFFFFD700;
    private static final int LABEL_COLOR  = 0xFFFFFFFF;
    private static final int FIELD_COLOR  = 0xFFFFFFFF;
    private static final int BG_COLOR     = 0xFF1E1E1E;
    private static final int BORDER_COLOR = 0xFF4A90D9;

    // Toggle button colours
    private static final int TOGGLE_DC_COLOR = 0xFF2255AA;   // blue-ish for DC
    private static final int TOGGLE_AC_COLOR = 0xFF228844;   // green-ish for AC

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
        double currentFrequency  = 1000.0;

        if (be instanceof ComponentBlockEntity cbe) {
            currentValue      = cbe.getValue();
            currentSourceType = cbe.getSourceType();
            currentLabel      = cbe.getLabel();
            currentFrequency  = cbe.getFrequency();
            componentType     = cbe.getComponentType();
        } else {
            componentType = "resistor";
        }

        // Initialise toggle state from the stored value
        sourceType = "AC".equalsIgnoreCase(currentSourceType) ? "AC" : "DC";

        boolean isProbe        = "probe".equals(componentType);
        boolean isCurrentProbe = "current_probe".equals(componentType);
        boolean isVoltSrc      = "voltage_source".equals(componentType);
        boolean isSinSrc       = "voltage_source_sin".equals(componentType);
        boolean isDiode        = "diode".equals(componentType);

        showValue      = !isProbe && !isCurrentProbe && !isDiode;
        showSourceType = isVoltSrc;
        showFrequency  = isSinSrc;
        showLabel      = isProbe || isCurrentProbe;

        int rowCount = 0;
        if (showValue)      rowCount++;
        if (showSourceType) rowCount++;
        if (showFrequency)  rowCount++;
        if (showLabel)      rowCount++;

        this.imageHeight = 10 + 10 + 10 + (rowCount * ROW_H) + 36;

        int panelX = (this.width  - this.imageWidth)  / 2;
        int panelY = (this.height - this.imageHeight) / 2;

        int fieldX = panelX + 10;
        int fieldW = this.imageWidth - 20;

        int cursorY = panelY + 30;

        if (showValue) {
            valueField = makeBox(fieldX, cursorY + LABEL_H + GAP, fieldW, formatValue(currentValue), 32);
            cursorY += ROW_H;
        }

        if (showSourceType) {
            // Toggle button occupies the same vertical slot as an EditBox would
            int btnY = cursorY + LABEL_H + GAP;
            sourceTypeToggle = Button.builder(
                    Component.literal(sourceType),
                    btn -> {
                        sourceType = "DC".equals(sourceType) ? "AC" : "DC";
                        btn.setMessage(Component.literal(sourceType));
                    }
            ).bounds(fieldX, btnY, fieldW, BOX_H).build();
            addRenderableWidget(sourceTypeToggle);
            cursorY += ROW_H;
        }

        if (showFrequency) {
            frequencyField = makeBox(fieldX, cursorY + LABEL_H + GAP, fieldW,
                    formatValue(currentFrequency), 32);
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

        if      (showValue && valueField != null)         this.setInitialFocus(valueField);
        else if (showFrequency && frequencyField != null) this.setInitialFocus(frequencyField);
        else if (showLabel && labelField != null)         this.setInitialFocus(labelField);
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

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        int x = (this.width  - this.imageWidth)  / 2;
        int y = (this.height - this.imageHeight) / 2;

        g.fill(x, y, x + imageWidth, y + imageHeight, BG_COLOR);

        g.fill(x,                  y,                   x + imageWidth, y + 2,              BORDER_COLOR);
        g.fill(x,                  y + imageHeight - 2, x + imageWidth, y + imageHeight,    BORDER_COLOR);
        g.fill(x,                  y,                   x + 2,          y + imageHeight,    BORDER_COLOR);
        g.fill(x + imageWidth - 2, y,                   x + imageWidth, y + imageHeight,    BORDER_COLOR);

        g.fill(x + 2, y + 23, x + imageWidth - 2, y + 24, 0xFF444444);

        // Tint the toggle button to reflect DC vs AC
        if (showSourceType && sourceTypeToggle != null) {
            int color = "AC".equals(sourceType) ? TOGGLE_AC_COLOR : TOGGLE_DC_COLOR;
            g.fill(sourceTypeToggle.getX(),
                   sourceTypeToggle.getY(),
                   sourceTypeToggle.getX() + sourceTypeToggle.getWidth(),
                   sourceTypeToggle.getY() + sourceTypeToggle.getHeight(),
                   color);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        // Intentionally empty
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g);
        super.render(g, mouseX, mouseY, partialTick);

        int panelX = (this.width  - this.imageWidth)  / 2;
        int panelY = (this.height - this.imageHeight) / 2;

        g.drawCenteredString(Minecraft.getInstance().font,
                "Edit " + getComponentDisplayName(componentType),
                this.width / 2, panelY + 7, TITLE_COLOR);

        int cursorY = panelY + 30;
        int labelX  = panelX + 12;

        if (showValue) {
            g.drawString(Minecraft.getInstance().font,
                    getValueLabel(componentType) + ":", labelX, cursorY, LABEL_COLOR);
            cursorY += ROW_H;
        }
        if (showSourceType) {
            g.drawString(Minecraft.getInstance().font,
                    "Source Type:", labelX, cursorY, LABEL_COLOR);
            cursorY += ROW_H;
        }
        if (showFrequency) {
            g.drawString(Minecraft.getInstance().font,
                    "Frequency (Hz):", labelX, cursorY, LABEL_COLOR);
            cursorY += ROW_H;
        }
        if (showLabel) {
            g.drawString(Minecraft.getInstance().font,
                    "Probe Label:", labelX, cursorY, LABEL_COLOR);
        }
    }

    public static double parseSI(String raw) throws NumberFormatException {
        if (raw == null) throw new NumberFormatException("null input");
        String s = raw.trim();
        if (s.isEmpty()) throw new NumberFormatException("empty input");

        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException ignored) {}

        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^([+\\-]?[0-9]*\\.?[0-9]+(?:[eE][+\\-]?[0-9]+)?)(\\s*)([a-zA-Zµ]+)$")
                .matcher(s);
        if (!m.matches()) throw new NumberFormatException("Cannot parse: " + raw);

        double base   = Double.parseDouble(m.group(1));
        String suffix = m.group(3);

        double multiplier = switch (suffix) {
            case "f"                          -> 1e-15;
            case "p"                          -> 1e-12;
            case "n"                          -> 1e-9;
            case "u", "µ"                     -> 1e-6;
            case "m"                          -> 1e-3;
            case "k", "K"                     -> 1e3;
            case "M", "Meg", "meg", "MEG"     -> 1e6;
            case "G"                          -> 1e9;
            case "T"                          -> 1e12;
            default -> {
                String stripped = suffix.replaceAll("[ΩFHVAROhm]+$", "");
                if (stripped.isEmpty()) yield 1.0;
                yield switch (stripped) {
                    case "f"                      -> 1e-15;
                    case "p"                      -> 1e-12;
                    case "n"                      -> 1e-9;
                    case "u", "µ"                 -> 1e-6;
                    case "m"                      -> 1e-3;
                    case "k", "K"                 -> 1e3;
                    case "M", "Meg", "meg", "MEG" -> 1e6;
                    case "G"                      -> 1e9;
                    case "T"                      -> 1e12;
                    default -> throw new NumberFormatException("Unknown suffix: " + suffix);
                };
            }
        };

        return base * multiplier;
    }

    public static String formatValue(double val) {
        if (val == 0.0) return "0";

        double abs = Math.abs(val);

        double[][] tiers = {
                {1e12,  1e15,  1e12,  -1},
                {1e9,   1e12,  1e9,   -1},
                {1e6,   1e9,   1e6,   -1},
                {1e3,   1e6,   1e3,   -1},
                {1e0,   1e3,   1e0,   -1},
                {1e-3,  1e0,   1e-3,  -1},
                {1e-6,  1e-3,  1e-6,  -1},
                {1e-9,  1e-6,  1e-9,  -1},
                {1e-12, 1e-9,  1e-12, -1},
                {1e-15, 1e-12, 1e-15, -1},
        };
        String[] names = {"T", "G", "Meg", "k", "", "m", "u", "n", "p", "f"};

        for (int i = 0; i < tiers.length; i++) {
            if (abs >= tiers[i][0] && abs < tiers[i][1]) {
                double scaled = val / tiers[i][2];
                String number = trimTrailingZeros(String.format("%.6f", scaled));
                return number + names[i];
            }
        }

        return String.valueOf(val);
    }

    private static String trimTrailingZeros(String s) {
        if (!s.contains(".")) return s;
        s = s.replaceAll("0+$", "");
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private void sendUpdatePacket(net.minecraft.core.BlockPos pos) {
        double value = 0.0;
        if (valueField != null) {
            try { value = parseSI(valueField.getValue()); }
            catch (NumberFormatException ignored) {}
        }
        // sourceType is now tracked directly in the field, not from an EditBox
        double freq = 0.0;
        if (frequencyField != null) {
            try { freq = parseSI(frequencyField.getValue()); }
            catch (NumberFormatException ignored) {}
        }
        String lbl = "";
        if (labelField != null) {
            lbl = labelField.getValue();
        }
        ModMessages.sendToServer(new ComponentUpdatePacket(pos, value, sourceType, freq, lbl));
    }

    private String getValueLabel(String type) {
        return switch (type) {
            case "resistor"           -> "Resistance (\u03A9)";
            case "capacitor"          -> "Capacitance (F)";
            case "inductor"           -> "Inductance (H)";
            case "voltage_source"     -> "Voltage (V)";
            case "voltage_source_sin" -> "Amplitude (V)";
            case "current_source"     -> "Current (A)";
            default                   -> "Value";
        };
    }

    private String getComponentDisplayName(String type) {
        return switch (type) {
            case "resistor"           -> "Resistor";
            case "capacitor"          -> "Capacitor";
            case "inductor"           -> "Inductor";
            case "voltage_source"     -> "Voltage Source";
            case "voltage_source_sin" -> "SIN Voltage Source";
            case "current_source"     -> "Current Source";
            case "diode"              -> "Diode";
            case "probe"              -> "Voltage Probe";
            case "current_probe"      -> "Current Probe";
            default                   -> "Component";
        };
    }

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
