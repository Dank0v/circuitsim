package com.circuitsim.screen;

import com.circuitsim.block.BaseComponentBlock;
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

public class ComponentEditScreen
    extends AbstractContainerScreen<ComponentEditMenu>
{

    private EditBox valueField;
    private String sourceType = "DC";
    private Button sourceTypeToggle;
    private EditBox labelField;
    private EditBox frequencyField;
    private EditBox numberField;
    private Button doneButton;
    private Button cancelButton;
    private String componentType;

    // sky130 resistor/mosfet fields
    private EditBox modelNameField;
    private EditBox wField;
    private EditBox lField;
    private EditBox multField;
    private EditBox nfField;
    private String  icPdkName = "none";
    private int     pdkRowY   = 0; // absolute Y of the PDK radio row (set during init)
    private int     mirrorRowY = 0; // absolute Y of the Mirror checkbox row (set during init)
    private boolean icMirrored = false;

    private boolean showValue;
    private boolean showSourceType;
    private boolean showLabel;
    private boolean showFrequency;
    private boolean showSky130;
    private boolean showNf;
    private boolean showMirror;
    private boolean showNumber;

    private static final int LABEL_H = 10;
    private static final int GAP = 4;
    private static final int BOX_H = 18;
    private static final int ROW_PAD = 8;
    private static final int ROW_H = LABEL_H + GAP + BOX_H + ROW_PAD;

    private static final int TITLE_COLOR = 0xFFFFD700;
    private static final int LABEL_COLOR = 0xFFFFFFFF;
    private static final int FIELD_COLOR = 0xFFFFFFFF;
    private static final int BG_COLOR = 0xFF1E1E1E;
    private static final int BORDER_COLOR = 0xFF4A90D9;

    private static final int TOGGLE_DC_COLOR = 0xFF2255AA;
    private static final int TOGGLE_AC_COLOR = 0xFF228844;

    public ComponentEditScreen(
        ComponentEditMenu menu,
        Inventory inv,
        Component title
    ) {
        super(menu, inv, title);
        this.imageWidth = 260;
        this.imageHeight = 200;
    }

    @Override
    protected void init() {
        super.init();

        net.minecraft.core.BlockPos pos = ClientSetup.getLastInteractedPos();
        BlockEntity be = Minecraft.getInstance().level.getBlockEntity(pos);

        double currentValue = 0.0;
        String currentSourceType = "DC";
        String currentLabel = "";
        double currentFrequency = 1000.0;
        String currentModelName = "";
        double currentW = 1.0;
        double currentL = 1.0;
        double currentMult = 1.0;
        double currentNf = 1.0;
        int    currentNumber = 0;

        if (be instanceof ComponentBlockEntity cbe) {
            currentValue = cbe.getValue();
            currentSourceType = cbe.getSourceType();
            currentLabel = cbe.getLabel();
            currentFrequency = cbe.getFrequency();
            componentType = cbe.getComponentType();
            currentModelName = cbe.getModelName();
            currentW = cbe.getWParam();
            currentL = cbe.getLParam();
            currentMult = cbe.getMultParam();
            currentNf = cbe.getNfParam();
            currentNumber = cbe.getComponentNumber();
            icPdkName = cbe.getPdkName() != null ? cbe.getPdkName() : "none";
            var bs = cbe.getBlockState();
            if (bs.hasProperty(BaseComponentBlock.MIRRORED)) {
                icMirrored = bs.getValue(BaseComponentBlock.MIRRORED);
            }
        } else {
            componentType = "resistor";
        }

        sourceType = "AC".equalsIgnoreCase(currentSourceType) ? "AC" : "DC";

        boolean isProbe = "probe".equals(componentType);
        boolean isCurrentProbe = "current_probe".equals(componentType);
        boolean isVoltSrc = "voltage_source".equals(componentType);
        boolean isSinSrc = "voltage_source_sin".equals(componentType);
        boolean isDiode = "diode".equals(componentType);
        boolean isSky130  = "ic_resistor3".equals(componentType);
        boolean isIcCap   = "ic_capacitor2".equals(componentType);
        boolean isNmos4   = "ic_nmos4".equals(componentType);
        boolean isPmos4   = "ic_pmos4".equals(componentType);

        showValue = !isProbe && !isCurrentProbe && !isDiode && !isSky130 && !isIcCap && !isNmos4 && !isPmos4;
        showSourceType = isVoltSrc;
        showFrequency = isSinSrc;
        showLabel = isProbe || isCurrentProbe;
        showSky130 = isSky130 || isIcCap || isNmos4 || isPmos4;
        showNf = isNmos4 || isPmos4;
        showMirror = isNmos4 || isPmos4;
        // Show the netlist-index field for everything that emits a SPICE element line.
        showNumber = !isProbe && !isCurrentProbe;

        int rowCount = 0;
        if (showNumber) rowCount++;
        if (showValue) rowCount++;
        if (showSourceType) rowCount++;
        if (showFrequency) rowCount++;
        if (showLabel) rowCount++;
        if (showSky130) rowCount += 5 + (showNf ? 1 : 0) + (showMirror ? 1 : 0); // pdk, model, W, L, mult [, NF] [, Mirror]

        this.imageHeight = 10 + 10 + 10 + (rowCount * ROW_H) + 36;

        int panelX = (this.width - this.imageWidth) / 2;
        int panelY = (this.height - this.imageHeight) / 2;

        int fieldX = panelX + 10;
        int fieldW = this.imageWidth - 20;

        int cursorY = panelY + 30;

        if (showNumber) {
            numberField = makeBox(
                fieldX,
                cursorY + LABEL_H + GAP,
                fieldW,
                currentNumber > 0 ? Integer.toString(currentNumber) : "",
                4
            );
            cursorY += ROW_H;
        }

        if (showValue) {
            valueField = makeBox(
                fieldX,
                cursorY + LABEL_H + GAP,
                fieldW,
                formatValue(currentValue),
                32
            );
            cursorY += ROW_H;
        }

        if (showSourceType) {
            int btnY = cursorY + LABEL_H + GAP;
            sourceTypeToggle = Button.builder(
                Component.literal(sourceType),
                btn -> {
                    sourceType = "DC".equals(sourceType) ? "AC" : "DC";
                    btn.setMessage(Component.literal(sourceType));
                }
            )
                .bounds(fieldX, btnY, fieldW, BOX_H)
                .build();
            addRenderableWidget(sourceTypeToggle);
            cursorY += ROW_H;
        }

        if (showFrequency) {
            frequencyField = makeBox(
                fieldX,
                cursorY + LABEL_H + GAP,
                fieldW,
                formatValue(currentFrequency),
                32
            );
            cursorY += ROW_H;
        }

        if (showLabel) {
            labelField = makeBox(
                fieldX,
                cursorY + LABEL_H + GAP,
                fieldW,
                currentLabel,
                64
            );
            cursorY += ROW_H;
        }

        if (showSky130) {
            // PDK selection row — no EditBox, just record Y for rendering/clicking
            pdkRowY = cursorY;
            cursorY += ROW_H;

            String modelDefault = "ic_capacitor2".equals(componentType) ? "cap_mim_m3_1"
                    : "ic_pmos4".equals(componentType) ? "pfet_01v8"
                    : "ic_nmos4".equals(componentType) ? "nfet_01v8"
                    : "res_high_po";
            modelNameField = makeBox(
                fieldX,
                cursorY + LABEL_H + GAP,
                fieldW,
                currentModelName.isEmpty() ? modelDefault : currentModelName,
                64
            );
            cursorY += ROW_H;
            wField = makeBox(
                fieldX,
                cursorY + LABEL_H + GAP,
                fieldW,
                String.valueOf(currentW),
                32
            );
            cursorY += ROW_H;
            lField = makeBox(
                fieldX,
                cursorY + LABEL_H + GAP,
                fieldW,
                String.valueOf(currentL),
                32
            );
            cursorY += ROW_H;
            multField = makeBox(
                fieldX,
                cursorY + LABEL_H + GAP,
                fieldW,
                String.valueOf(currentMult),
                32
            );
            cursorY += ROW_H;
            if (showNf) {
                nfField = makeBox(
                    fieldX,
                    cursorY + LABEL_H + GAP,
                    fieldW,
                    String.valueOf((int) Math.max(1, Math.round(currentNf))),
                    16
                );
                cursorY += ROW_H;
            }
            if (showMirror) {
                mirrorRowY = cursorY;
                cursorY += ROW_H;
            }
        }

        int buttonY = panelY + this.imageHeight - 28;
        doneButton = Button.builder(Component.literal("Done"), button -> {
            sendUpdatePacket(pos);
            Minecraft.getInstance().setScreen(null);
        })
            .bounds(panelX + 20, buttonY, 90, 20)
            .build();
        addRenderableWidget(doneButton);

        cancelButton = Button.builder(Component.literal("Cancel"), button ->
            Minecraft.getInstance().setScreen(null)
        )
            .bounds(panelX + 150, buttonY, 90, 20)
            .build();
        addRenderableWidget(cancelButton);

        if (showValue && valueField != null) this.setInitialFocus(valueField);
        else if (showSky130 && modelNameField != null) this.setInitialFocus(
            modelNameField
        );
        else if (showFrequency && frequencyField != null) this.setInitialFocus(
            frequencyField
        );
        else if (showLabel && labelField != null) this.setInitialFocus(
            labelField
        );
    }

    private EditBox makeBox(int x, int y, int w, String initial, int maxLen) {
        EditBox box = new EditBox(
            Minecraft.getInstance().font,
            x,
            y,
            w,
            BOX_H,
            Component.empty()
        );
        // setMaxLength before setValue — EditBox defaults to 32 and setValue
        // truncates to the current limit, which silently cuts long saved
        // values when the dialog reopens.
        box.setMaxLength(maxLen);
        box.setValue(initial);
        box.setBordered(true);
        box.setTextColor(FIELD_COLOR);
        addRenderableWidget(box);
        return box;
    }

    @Override
    protected void renderBg(
        GuiGraphics g,
        float partialTick,
        int mouseX,
        int mouseY
    ) {
        int x = (this.width - this.imageWidth) / 2;
        int y = (this.height - this.imageHeight) / 2;

        g.fill(x, y, x + imageWidth, y + imageHeight, BG_COLOR);

        g.fill(x, y, x + imageWidth, y + 2, BORDER_COLOR);
        g.fill(
            x,
            y + imageHeight - 2,
            x + imageWidth,
            y + imageHeight,
            BORDER_COLOR
        );
        g.fill(x, y, x + 2, y + imageHeight, BORDER_COLOR);
        g.fill(
            x + imageWidth - 2,
            y,
            x + imageWidth,
            y + imageHeight,
            BORDER_COLOR
        );

        g.fill(x + 2, y + 23, x + imageWidth - 2, y + 24, 0xFF444444);

        if (showSourceType && sourceTypeToggle != null) {
            int color = "AC".equals(sourceType)
                ? TOGGLE_AC_COLOR
                : TOGGLE_DC_COLOR;
            g.fill(
                sourceTypeToggle.getX(),
                sourceTypeToggle.getY(),
                sourceTypeToggle.getX() + sourceTypeToggle.getWidth(),
                sourceTypeToggle.getY() + sourceTypeToggle.getHeight(),
                color
            );
        }
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        // Intentionally empty
    }

    @Override
    public void render(
        GuiGraphics g,
        int mouseX,
        int mouseY,
        float partialTick
    ) {
        this.renderBackground(g);
        super.render(g, mouseX, mouseY, partialTick);

        int panelX = (this.width - this.imageWidth) / 2;
        int panelY = (this.height - this.imageHeight) / 2;

        g.drawCenteredString(
            Minecraft.getInstance().font,
            "Edit " + getComponentDisplayName(componentType),
            this.width / 2,
            panelY + 7,
            TITLE_COLOR
        );

        int cursorY = panelY + 30;
        int labelX = panelX + 12;

        if (showNumber) {
            g.drawString(
                Minecraft.getInstance().font,
                getNumberLabel(componentType) + " (blank = auto):",
                labelX,
                cursorY,
                LABEL_COLOR
            );
            cursorY += ROW_H;
        }

        if (showValue) {
            g.drawString(
                Minecraft.getInstance().font,
                getValueLabel(componentType) + ":",
                labelX,
                cursorY,
                LABEL_COLOR
            );
            cursorY += ROW_H;
        }
        if (showSourceType) {
            g.drawString(
                Minecraft.getInstance().font,
                "Source Type:",
                labelX,
                cursorY,
                LABEL_COLOR
            );
            cursorY += ROW_H;
        }
        if (showFrequency) {
            g.drawString(
                Minecraft.getInstance().font,
                "Frequency (Hz):",
                labelX,
                cursorY,
                LABEL_COLOR
            );
            cursorY += ROW_H;
        }
        if (showLabel) {
            g.drawString(
                Minecraft.getInstance().font,
                "Probe Label:",
                labelX,
                cursorY,
                LABEL_COLOR
            );
            cursorY += ROW_H;
        }
        if (showSky130) {
            // PDK row
            g.drawString(Minecraft.getInstance().font, "PDK:", labelX, cursorY, LABEL_COLOR);
            int checkY = cursorY + LABEL_H + GAP;
            boolean isNone        = "none".equals(icPdkName);
            boolean isSky130A     = "sky130A".equals(icPdkName);
            boolean isPlaceholder = "placeholder".equals(icPdkName);
            int SEL = 0xFF4FC3F7;
            int DIM = 0xFF666666;
            drawCheckbox(g, panelX + 12, checkY, isNone);
            g.drawString(Minecraft.getInstance().font, "none",        panelX + 26, checkY + 1, isNone        ? SEL : DIM);
            drawCheckbox(g, panelX + 70, checkY, isSky130A);
            g.drawString(Minecraft.getInstance().font, "sky130A",     panelX + 84, checkY + 1, isSky130A     ? SEL : DIM);
            drawCheckbox(g, panelX + 140, checkY, isPlaceholder);
            g.drawString(Minecraft.getInstance().font, "placeholder", panelX + 154, checkY + 1, isPlaceholder ? SEL : DIM);
            cursorY += ROW_H;

            g.drawString(
                Minecraft.getInstance().font,
                "Model:",
                labelX,
                cursorY,
                LABEL_COLOR
            );
            cursorY += ROW_H;
            g.drawString(
                Minecraft.getInstance().font,
                "W (um):",
                labelX,
                cursorY,
                LABEL_COLOR
            );
            cursorY += ROW_H;
            g.drawString(
                Minecraft.getInstance().font,
                "L (um):",
                labelX,
                cursorY,
                LABEL_COLOR
            );
            cursorY += ROW_H;
            g.drawString(
                Minecraft.getInstance().font,
                "ic_capacitor2".equals(componentType) ? "MF:" : "mult:",
                labelX,
                cursorY,
                LABEL_COLOR
            );
            cursorY += ROW_H;
            if (showNf) {
                g.drawString(
                    Minecraft.getInstance().font,
                    "NF:",
                    labelX,
                    cursorY,
                    LABEL_COLOR
                );
                cursorY += ROW_H;
            }
            if (showMirror) {
                g.drawString(Minecraft.getInstance().font, "Mirror:", labelX, cursorY, LABEL_COLOR);
                int mCheckY = cursorY + LABEL_H + GAP;
                drawCheckbox(g, panelX + 12, mCheckY, icMirrored);
                g.drawString(
                    Minecraft.getInstance().font,
                    icMirrored ? "mirrored (gate on east)" : "default (gate on west)",
                    panelX + 26, mCheckY + 1,
                    icMirrored ? 0xFF4FC3F7 : 0xFF666666
                );
            }
        }
    }

    private void drawCheckbox(GuiGraphics g, int x, int y, boolean sel) {
        g.fill(x, y, x + 10, y + 10, 0xFF888888);
        g.fill(x + 1, y + 1, x + 9, y + 9, BG_COLOR);
        if (sel) g.fill(x + 2, y + 2, x + 8, y + 8, 0xFF4FC3F7);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        if (showSky130 && pdkRowY > 0) {
            int checkY = pdkRowY + LABEL_H + GAP;
            int panelX = (this.width - this.imageWidth) / 2;
            // none checkbox: x=panelX+12, w covers label too (~50px)
            if (hitBox(mx, my, panelX + 12, checkY, 58, 12)) { icPdkName = "none";        return true; }
            // sky130A checkbox
            if (hitBox(mx, my, panelX + 70, checkY, 68, 12)) { icPdkName = "sky130A";     return true; }
            // placeholder checkbox
            if (hitBox(mx, my, panelX + 140, checkY, 72, 12)) { icPdkName = "placeholder"; return true; }
        }
        if (showMirror && mirrorRowY > 0) {
            int checkY = mirrorRowY + LABEL_H + GAP;
            int panelX = (this.width - this.imageWidth) / 2;
            if (hitBox(mx, my, panelX + 12, checkY, 200, 12)) { icMirrored = !icMirrored; return true; }
        }
        return super.mouseClicked(mx, my, btn);
    }

    private static boolean hitBox(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    public static double parseSI(String raw) throws NumberFormatException {
        if (raw == null) throw new NumberFormatException("null input");
        String s = raw.trim();
        if (s.isEmpty()) throw new NumberFormatException("empty input");

        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException ignored) {}

        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
            "^([+\\-]?[0-9]*\\.?[0-9]+(?:[eE][+\\-]?[0-9]+)?)(\\s*)([a-zA-Zµ]+)$"
        ).matcher(s);
        if (!m.matches()) throw new NumberFormatException(
            "Cannot parse: " + raw
        );

        double base = Double.parseDouble(m.group(1));
        String suffix = m.group(3);

        double multiplier = switch (suffix) {
            case "f" -> 1e-15;
            case "p" -> 1e-12;
            case "n" -> 1e-9;
            case "u", "µ" -> 1e-6;
            case "m" -> 1e-3;
            case "k", "K" -> 1e3;
            case "M", "Meg", "meg", "MEG" -> 1e6;
            case "G" -> 1e9;
            case "T" -> 1e12;
            default -> {
                String stripped = suffix.replaceAll("[\u03A9FHVAROhm]+$", "");
                if (stripped.isEmpty()) yield 1.0;
                yield switch (stripped) {
                    case "f" -> 1e-15;
                    case "p" -> 1e-12;
                    case "n" -> 1e-9;
                    case "u", "µ" -> 1e-6;
                    case "m" -> 1e-3;
                    case "k", "K" -> 1e3;
                    case "M", "Meg", "meg", "MEG" -> 1e6;
                    case "G" -> 1e9;
                    case "T" -> 1e12;
                    default -> throw new NumberFormatException(
                        "Unknown suffix: " + suffix
                    );
                };
            }
        };

        return base * multiplier;
    }

    public static String formatValue(double val) {
        if (val == 0.0) return "0";

        double abs = Math.abs(val);

        double[][] tiers = {
            { 1e12, 1e15, 1e12, -1 },
            { 1e9, 1e12, 1e9, -1 },
            { 1e6, 1e9, 1e6, -1 },
            { 1e3, 1e6, 1e3, -1 },
            { 1e0, 1e3, 1e0, -1 },
            { 1e-3, 1e0, 1e-3, -1 },
            { 1e-6, 1e-3, 1e-6, -1 },
            { 1e-9, 1e-6, 1e-9, -1 },
            { 1e-12, 1e-9, 1e-12, -1 },
            { 1e-15, 1e-12, 1e-15, -1 },
        };
        String[] names = { "T", "G", "Meg", "k", "", "m", "u", "n", "p", "f" };

        for (int i = 0; i < tiers.length; i++) {
            if (abs >= tiers[i][0] && abs < tiers[i][1]) {
                double scaled = val / tiers[i][2];
                String number = trimTrailingZeros(
                    String.format("%.6f", scaled)
                );
                return number + names[i];
            }
        }

        return String.valueOf(val);
    }

    public static String trimTrailingZeros(String s) {
        if (!s.contains(".")) return s;
        s = s.replaceAll("0+$", "");
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private void sendUpdatePacket(net.minecraft.core.BlockPos pos) {
        double value = 0.0;
        if (valueField != null) {
            try {
                value = parseSI(valueField.getValue());
            } catch (NumberFormatException ignored) {}
        }
        double freq = 0.0;
        if (frequencyField != null) {
            try {
                freq = parseSI(frequencyField.getValue());
            } catch (NumberFormatException ignored) {}
        }
        String lbl = "";
        if (labelField != null) {
            lbl = labelField.getValue();
        }

        String modelName = "";
        double w = 1.0,
            l = 1.0,
            mult = 1.0,
            nf = 1.0;
        if (showSky130) {
            if (modelNameField != null) modelName = modelNameField
                .getValue()
                .trim();
            try {
                w = Double.parseDouble(
                    wField != null ? wField.getValue().trim() : "1"
                );
            } catch (Exception ignored) {}
            try {
                l = Double.parseDouble(
                    lField != null ? lField.getValue().trim() : "1"
                );
            } catch (Exception ignored) {}
            try {
                mult = Double.parseDouble(
                    multField != null ? multField.getValue().trim() : "1"
                );
            } catch (Exception ignored) {}
            if (showNf) {
                try {
                    nf = Double.parseDouble(
                        nfField != null ? nfField.getValue().trim() : "1"
                    );
                } catch (Exception ignored) {}
            }
        }

        int number = 0;
        if (showNumber && numberField != null) {
            String raw = numberField.getValue().trim();
            if (!raw.isEmpty()) {
                try { number = Math.max(0, Integer.parseInt(raw)); }
                catch (NumberFormatException ignored) {}
            }
        }

        ModMessages.sendToServer(
            new ComponentUpdatePacket(
                pos,
                value,
                sourceType,
                freq,
                lbl,
                modelName,
                w,
                l,
                mult,
                nf,
                icPdkName,
                number,
                icMirrored
            )
        );
    }

    private String getValueLabel(String type) {
        return switch (type) {
            case "resistor" -> "Resistance (\u03A9)";
            case "capacitor" -> "Capacitance (F)";
            case "inductor" -> "Inductance (H)";
            case "voltage_source" -> "Voltage (V)";
            case "voltage_source_sin" -> "Amplitude (V)";
            case "current_source" -> "Current (A)";
            default -> "Value";
        };
    }

    /** Prefix used for this block's netlist line; the user picks the trailing number. */
    private String getNumberLabel(String type) {
        return switch (type) {
            case "resistor", "ic_resistor3"  -> "Netlist index R";
            case "capacitor", "ic_capacitor2"-> "Netlist index C";
            case "inductor"                  -> "Netlist index L";
            case "voltage_source",
                 "voltage_source_sin"        -> "Netlist index V";
            case "current_source"            -> "Netlist index I";
            case "diode"                     -> "Netlist index D";
            case "ic_nmos4", "ic_pmos4"      -> "Netlist index XM";
            default                          -> "Netlist index";
        };
    }

    private String getComponentDisplayName(String type) {
        return switch (type) {
            case "resistor" -> "Resistor";
            case "capacitor" -> "Capacitor";
            case "inductor" -> "Inductor";
            case "voltage_source" -> "Voltage Source";
            case "voltage_source_sin" -> "SIN Voltage Source";
            case "current_source" -> "Current Source";
            case "diode" -> "Diode";
            case "probe" -> "Voltage Probe";
            case "current_probe" -> "Current Probe";
            case "ic_resistor3"  -> "IC Resistor3";
            case "ic_capacitor2" -> "IC Capacitor2";
            case "ic_nmos4"      -> "IC NMOS4";
            case "ic_pmos4"      -> "IC PMOS4";
            default -> "Component";
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
    public boolean shouldCloseOnEsc() {
        return true;
    }
}
