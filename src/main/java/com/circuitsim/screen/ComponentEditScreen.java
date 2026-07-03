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
    // Voltage-source-only: AC magnitude (paired with valueField which carries DC).
    private EditBox acValueField;
    // Pulse-source-only fields. valueField doubles as V1; frequencyField
    // is reused for the pulse period (stored in the BE's frequency slot).
    private EditBox pulseVLowField;
    private EditBox pulsePwField;
    private EditBox pulseTrField;
    private EditBox pulseTfField;
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
    // Probe "name only" toggle. When set, the probe names/merges its net but is
    // excluded from simulation print/plot.
    private int     noPlotRowY = 0; // absolute Y of the no-plot checkbox row (set during init)
    private boolean probeNoPlot = false;
    // Resistor "noiseless" toggle. When set, the R line carries `noisy=0` so
    // the resistor contributes no thermal noise to .noise analysis.
    private int     rNoiseRowY = 0; // absolute Y of the noiseless checkbox row (set during init)
    private boolean rNoiseless = false;
    // Probe "subcircuit pin" toggle + ordering. When set, the probe's net is
    // exported as a subcircuit terminal on conversion; the order field places it
    // in the .subckt pin list.
    private int     subcktPinRowY = 0; // absolute Y of the subckt-pin checkbox row (set during init)
    private boolean subcktPin = false;
    private EditBox subcktOrderField;

    private boolean showValue;
    private boolean showSourceType;
    private boolean showLabel;
    private boolean showFrequency;
    private boolean showPulse;
    private boolean showSky130;
    private boolean showNf;
    private boolean showMirror;
    private boolean showNumber;
    /** Voltage probe only: show the "name only (no plot)" checkbox row. */
    private boolean showNoPlot;
    /** Voltage probe only: show the "subcircuit pin" checkbox + order field. */
    private boolean showSubcktPin;
    /** Plain resistor only: show the "noiseless (noisy=0)" checkbox row. */
    private boolean showRNoise;
    /** Voltage source: show the AC magnitude field below the DC value field. */
    private boolean showAcValue;
    /** True for CCVS/CCCS: show a "Control voltage source (vnam)" text field,
     *  stored in the BE's modelName slot. */
    private boolean showControlSource;
    /** True for the diode: show an optional ".MODEL name" text field, stored
     *  in the BE's modelName slot. Blank = use the built-in basic SPICE diode. */
    private boolean showDiodeModel;
    /** True for behavioral B-sources: show a free-text expression field
     *  (V=expr / I=expr), stored in the BE's modelName slot. */
    private boolean showBehaviorExpr;
    /** True when the behavioral source is the voltage (V=) form; false for the
     *  current (I=) form. Only meaningful when {@link #showBehaviorExpr}. */
    private boolean behaviorIsVoltage;

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
        double currentAcValue   = 0.0;
        String currentAcValueExpr = "";
        String currentModelName = "";
        double currentPulseVLow = 0.0;
        double currentPulseTr   = 1e-9;
        double currentPulseTf   = 1e-9;
        double currentPulsePw   = 1e-6;
        double currentW = 1.0;
        double currentL = 1.0;
        double currentMult = 1.0;
        double currentNf = 1.0;
        int    currentNumber = 0;
        int    currentSubcktOrder = 0;
        String currentValueExpr = "";
        String currentWExpr = "";
        String currentLExpr = "";
        String currentMultExpr = "";
        String currentNfExpr = "";

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
            currentPulseVLow = cbe.getPulseVLow();
            currentPulseTr   = cbe.getPulseTr();
            currentPulseTf   = cbe.getPulseTf();
            currentPulsePw   = cbe.getPulsePw();
            currentValueExpr = cbe.getValueExpr();
            currentWExpr     = cbe.getWExpr();
            currentLExpr     = cbe.getLExpr();
            currentMultExpr  = cbe.getMultExpr();
            currentNfExpr    = cbe.getNfExpr();
            currentAcValue     = cbe.getAcValue();
            currentAcValueExpr = cbe.getAcValueExpr();
            probeNoPlot        = cbe.isProbeNoPlot();
            subcktPin          = cbe.isSubcktPin();
            currentSubcktOrder = cbe.getSubcktPinOrder();
            rNoiseless         = cbe.isRNoiseless();
            // For pulse sources the period lives in the BE's frequency slot;
            // pre-fill the editor with a sensible default (matches the BE
            // default) if the player just placed the block.
            if ("voltage_source_pulse".equals(cbe.getComponentType()) && currentFrequency <= 0) {
                currentFrequency = 2e-6;
            }
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
        boolean isPulseSrc = "voltage_source_pulse".equals(componentType);
        boolean isDiode = "diode".equals(componentType);
        boolean isSky130  = "ic_resistor3".equals(componentType);
        boolean isIcCap   = "ic_capacitor2".equals(componentType);
        boolean isNmos4   = "ic_nmos4".equals(componentType);
        boolean isPmos4   = "ic_pmos4".equals(componentType);
        boolean isCcvs    = "ccvs".equals(componentType);
        boolean isCccs    = "cccs".equals(componentType);
        boolean isBhvV    = "behavioral_voltage_source".equals(componentType);
        boolean isBhvI    = "behavioral_current_source".equals(componentType);

        showValue = !isProbe && !isCurrentProbe && !isDiode && !isSky130 && !isIcCap && !isNmos4 && !isPmos4
                && !isBhvV && !isBhvI;
        // The DC/AC toggle is gone — voltage_source now exposes DC and AC as
        // independent value fields. Other sources never used the toggle.
        showSourceType = false;
        showAcValue = isVoltSrc;
        // Pulse and sine sources both reuse the BE.frequency slot — sine
        // stores Hz there, pulse stores the period in seconds — but the
        // labelling and surrounding rows are different.
        showFrequency = isSinSrc;
        showPulse     = isPulseSrc;
        showLabel = isProbe || isCurrentProbe;
        // "Name only" mode is a voltage-probe concept: it suppresses the
        // print/plot while still letting the probe name (and merge) its net.
        showNoPlot = isProbe;
        // Subcircuit-pin toggle is also a voltage-probe concept.
        showSubcktPin = isProbe;
        showRNoise = "resistor".equals(componentType);
        showSky130 = isSky130 || isIcCap || isNmos4 || isPmos4;
        showNf = isNmos4 || isPmos4;
        showMirror = isNmos4 || isPmos4;
        // Show the netlist-index field for everything that emits a SPICE element line.
        showNumber = !isProbe && !isCurrentProbe;
        showControlSource = isCcvs || isCccs;
        showDiodeModel = isDiode;
        showBehaviorExpr  = isBhvV || isBhvI;
        behaviorIsVoltage = isBhvV;

        int rowCount = 0;
        if (showNumber) rowCount++;
        if (showValue) rowCount++;
        if (showAcValue) rowCount++;
        if (showSourceType) rowCount++;
        if (showFrequency) rowCount++;
        if (showPulse) rowCount += 5;        // V_2, Period, Time-high, TR, TF (V1 reuses valueField)
        if (showLabel) rowCount++;
        if (showNoPlot) rowCount++;
        if (showSubcktPin) rowCount += 2; // checkbox row + order field row
        if (showRNoise) rowCount++;
        if (showSky130) rowCount += 5 + (showNf ? 1 : 0) + (showMirror ? 1 : 0); // pdk, model, W, L, mult [, NF] [, Mirror]
        if (showControlSource) rowCount++;
        if (showDiodeModel) rowCount++;
        if (showBehaviorExpr) rowCount++;

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
            // When the BE holds a variable name (e.g. "Rs"), show that instead
            // of the numeric value — a parametric block defines the actual
            // number at sim time.
            String initial = !currentValueExpr.isEmpty()
                ? currentValueExpr
                : formatValue(currentValue);
            valueField = makeBox(
                fieldX,
                cursorY + LABEL_H + GAP,
                fieldW,
                initial,
                32
            );
            cursorY += ROW_H;
        }

        if (showRNoise) {
            // No EditBox — a clickable checkbox row rendered in render()/handled
            // in mouseClicked(), mirroring the probe no-plot toggle pattern.
            rNoiseRowY = cursorY;
            cursorY += ROW_H;
        }

        if (showAcValue) {
            String acInitial = !currentAcValueExpr.isEmpty()
                ? currentAcValueExpr
                : formatValue(currentAcValue);
            acValueField = makeBox(
                fieldX,
                cursorY + LABEL_H + GAP,
                fieldW,
                acInitial,
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

        if (showPulse) {
            // V1 is the existing valueField above; layout the remaining
            // five params in the order V_2, Period, Pulse Width, TR, TF so
            // the most-edited (voltage levels + period) sit at the top.
            pulseVLowField = makeBox(
                fieldX,
                cursorY + LABEL_H + GAP,
                fieldW,
                formatValue(currentPulseVLow),
                32
            );
            cursorY += ROW_H;
            // Period reuses the frequencyField slot in the data model — but
            // we use a separate widget here so the label can be tailored.
            frequencyField = makeBox(
                fieldX,
                cursorY + LABEL_H + GAP,
                fieldW,
                formatValue(currentFrequency),
                32
            );
            cursorY += ROW_H;
            pulsePwField = makeBox(
                fieldX,
                cursorY + LABEL_H + GAP,
                fieldW,
                formatValue(currentPulsePw),
                32
            );
            cursorY += ROW_H;
            pulseTrField = makeBox(
                fieldX,
                cursorY + LABEL_H + GAP,
                fieldW,
                formatValue(currentPulseTr),
                32
            );
            cursorY += ROW_H;
            pulseTfField = makeBox(
                fieldX,
                cursorY + LABEL_H + GAP,
                fieldW,
                formatValue(currentPulseTf),
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

        if (showNoPlot) {
            // No EditBox — a clickable checkbox row rendered in render()/handled
            // in mouseClicked(), mirroring the Mirror toggle pattern.
            noPlotRowY = cursorY;
            cursorY += ROW_H;
        }

        if (showSubcktPin) {
            // Checkbox row (toggled in mouseClicked) ...
            subcktPinRowY = cursorY;
            cursorY += ROW_H;
            // ... followed by the pin-order field.
            subcktOrderField = makeBox(
                fieldX,
                cursorY + LABEL_H + GAP,
                fieldW,
                currentSubcktOrder > 0 ? Integer.toString(currentSubcktOrder) : "",
                4
            );
            cursorY += ROW_H;
        }

        if (showControlSource) {
            modelNameField = makeBox(
                fieldX,
                cursorY + LABEL_H + GAP,
                fieldW,
                currentModelName,
                64
            );
            modelNameField.setSuggestion(currentModelName.isEmpty() ? "e.g. V1, VSENS" : "");
            modelNameField.setResponder(
                t -> modelNameField.setSuggestion(t.isEmpty() ? "e.g. V1, VSENS" : "")
            );
            cursorY += ROW_H;
        }

        if (showDiodeModel) {
            modelNameField = makeBox(
                fieldX,
                cursorY + LABEL_H + GAP,
                fieldW,
                currentModelName,
                64
            );
            String hint = "blank = basic diode; or e.g. D1N4148_1";
            modelNameField.setSuggestion(currentModelName.isEmpty() ? hint : "");
            modelNameField.setResponder(
                t -> modelNameField.setSuggestion(t.isEmpty() ? hint : "")
            );
            cursorY += ROW_H;
        }

        if (showBehaviorExpr) {
            modelNameField = makeBox(
                fieldX,
                cursorY + LABEL_H + GAP,
                fieldW,
                currentModelName,
                128
            );
            String hint = behaviorIsVoltage ? "e.g. v(in)*v(in)" : "e.g. v(in)/1k";
            modelNameField.setSuggestion(currentModelName.isEmpty() ? hint : "");
            modelNameField.setResponder(
                t -> modelNameField.setSuggestion(t.isEmpty() ? hint : "")
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
                !currentWExpr.isEmpty() ? currentWExpr : formatLengthForField(currentW),
                32
            );
            cursorY += ROW_H;
            lField = makeBox(
                fieldX,
                cursorY + LABEL_H + GAP,
                fieldW,
                !currentLExpr.isEmpty() ? currentLExpr : formatLengthForField(currentL),
                32
            );
            cursorY += ROW_H;
            multField = makeBox(
                fieldX,
                cursorY + LABEL_H + GAP,
                fieldW,
                !currentMultExpr.isEmpty() ? currentMultExpr : String.valueOf(currentMult),
                32
            );
            cursorY += ROW_H;
            if (showNf) {
                String nfInit = !currentNfExpr.isEmpty()
                        ? currentNfExpr
                        : String.valueOf((int) Math.max(1, Math.round(currentNf)));
                nfField = makeBox(
                    fieldX,
                    cursorY + LABEL_H + GAP,
                    fieldW,
                    nfInit,
                    16
                );
                updateNfEditable();
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
        else if ((showBehaviorExpr || showControlSource || showDiodeModel)
                && modelNameField != null) this.setInitialFocus(modelNameField);
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
        if (showRNoise) {
            g.drawString(Minecraft.getInstance().font, "Thermal noise:", labelX, cursorY, LABEL_COLOR);
            int rnCheckY = cursorY + LABEL_H + GAP;
            drawCheckbox(g, panelX + 12, rnCheckY, rNoiseless);
            g.drawString(
                Minecraft.getInstance().font,
                rNoiseless ? "noiseless (noisy=0)" : "noisy (default)",
                panelX + 26, rnCheckY + 1,
                rNoiseless ? 0xFF4FC3F7 : 0xFF666666
            );
            cursorY += ROW_H;
        }
        if (showAcValue) {
            g.drawString(
                Minecraft.getInstance().font,
                "AC Value (V):",
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
        if (showPulse) {
            g.drawString(Minecraft.getInstance().font,
                    "V2 (V):",      labelX, cursorY, LABEL_COLOR);
            cursorY += ROW_H;
            g.drawString(Minecraft.getInstance().font,
                    "Period (s):",     labelX, cursorY, LABEL_COLOR);
            cursorY += ROW_H;
            g.drawString(Minecraft.getInstance().font,
                    "Pulse Width (s):",  labelX, cursorY, LABEL_COLOR);
            cursorY += ROW_H;
            g.drawString(Minecraft.getInstance().font,
                    "Rise time (s):",  labelX, cursorY, LABEL_COLOR);
            cursorY += ROW_H;
            g.drawString(Minecraft.getInstance().font,
                    "Fall time (s):",  labelX, cursorY, LABEL_COLOR);
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
        if (showNoPlot) {
            g.drawString(Minecraft.getInstance().font, "Plot mode:", labelX, cursorY, LABEL_COLOR);
            int npCheckY = cursorY + LABEL_H + GAP;
            drawCheckbox(g, panelX + 12, npCheckY, probeNoPlot);
            g.drawString(
                Minecraft.getInstance().font,
                probeNoPlot ? "name only (no plot)" : "name + plot net",
                panelX + 26, npCheckY + 1,
                probeNoPlot ? 0xFF4FC3F7 : 0xFF666666
            );
            cursorY += ROW_H;
        }
        if (showSubcktPin) {
            g.drawString(Minecraft.getInstance().font, "Subcircuit:", labelX, cursorY, LABEL_COLOR);
            int spCheckY = cursorY + LABEL_H + GAP;
            drawCheckbox(g, panelX + 12, spCheckY, subcktPin);
            g.drawString(
                Minecraft.getInstance().font,
                subcktPin ? "export net as subcircuit pin" : "not a subcircuit pin",
                panelX + 26, spCheckY + 1,
                subcktPin ? 0xFF4FC3F7 : 0xFF666666
            );
            cursorY += ROW_H;
            g.drawString(Minecraft.getInstance().font, "Pin order:", labelX, cursorY, LABEL_COLOR);
            cursorY += ROW_H;
        }
        if (showControlSource) {
            g.drawString(
                Minecraft.getInstance().font,
                "Control voltage source (vnam):",
                labelX,
                cursorY,
                LABEL_COLOR
            );
            cursorY += ROW_H;
        }
        if (showDiodeModel) {
            g.drawString(
                Minecraft.getInstance().font,
                "Model (blank = basic):",
                labelX,
                cursorY,
                LABEL_COLOR
            );
            cursorY += ROW_H;
        }
        if (showBehaviorExpr) {
            g.drawString(
                Minecraft.getInstance().font,
                behaviorIsVoltage ? "Voltage expr (V=):" : "Current expr (I=):",
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
                "W (" + lengthUnit() + "):",
                labelX,
                cursorY,
                LABEL_COLOR
            );
            cursorY += ROW_H;
            g.drawString(
                Minecraft.getInstance().font,
                "L (" + lengthUnit() + "):",
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

    /**
     * NF only has meaning when the component's PDK supplies a geometry model
     * (sky130A). For "none"/"placeholder" the netlist emits a bare W/L/mult
     * instance with no NF, so the box is disabled and dimmed to match.
     */
    private void updateNfEditable() {
        if (nfField == null) return;
        boolean editable = "sky130A".equals(icPdkName);
        nfField.setEditable(editable);
        nfField.setTextColor(editable ? FIELD_COLOR : 0xFF666666);
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
            String newPdk = null;
            // none checkbox: x=panelX+12, w covers label too (~50px)
            if (hitBox(mx, my, panelX + 12, checkY, 58, 12))       newPdk = "none";
            else if (hitBox(mx, my, panelX + 70, checkY, 68, 12))  newPdk = "sky130A";
            else if (hitBox(mx, my, panelX + 140, checkY, 72, 12)) newPdk = "placeholder";
            if (newPdk != null) {
                String oldPdk = icPdkName;
                icPdkName = newPdk;
                reformatLengthFields(oldPdk, newPdk); // keep W/L physical size across the unit change
                updateNfEditable();                   // NF is only editable under a geometry PDK
                return true;
            }
        }
        if (showMirror && mirrorRowY > 0) {
            int checkY = mirrorRowY + LABEL_H + GAP;
            int panelX = (this.width - this.imageWidth) / 2;
            if (hitBox(mx, my, panelX + 12, checkY, 200, 12)) { icMirrored = !icMirrored; return true; }
        }
        if (showNoPlot && noPlotRowY > 0) {
            int checkY = noPlotRowY + LABEL_H + GAP;
            int panelX = (this.width - this.imageWidth) / 2;
            if (hitBox(mx, my, panelX + 12, checkY, 200, 12)) { probeNoPlot = !probeNoPlot; return true; }
        }
        if (showSubcktPin && subcktPinRowY > 0) {
            int checkY = subcktPinRowY + LABEL_H + GAP;
            int panelX = (this.width - this.imageWidth) / 2;
            if (hitBox(mx, my, panelX + 12, checkY, 200, 12)) { subcktPin = !subcktPin; return true; }
        }
        if (showRNoise && rNoiseRowY > 0) {
            int checkY = rNoiseRowY + LABEL_H + GAP;
            int panelX = (this.width - this.imageWidth) / 2;
            if (hitBox(mx, my, panelX + 12, checkY, 200, 12)) { rNoiseless = !rNoiseless; return true; }
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

        // Skip the Double.parseDouble fast path when the string has a trailing
        // letter — Java treats "300f"/"300d"/"300F"/"300D" as valid float /
        // double type-suffixed literals and returns 300.0, swallowing the SI
        // prefix. We want the regex below to claim those cases instead.
        char last = s.charAt(s.length() - 1);
        boolean hasSuffix = Character.isLetter(last) || last == 'Ω' || last == 'µ';
        if (!hasSuffix) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException ignored) {}
        }

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

    /** SI-suffixed display format — the shared implementation lives in {@link com.circuitsim.simulation.SiFormat}. */
    public static String formatValue(double val) {
        return com.circuitsim.simulation.SiFormat.value(val);
    }

    public static String trimTrailingZeros(String s) {
        return com.circuitsim.simulation.SiFormat.trimTrailingZeros(s);
    }

    private void sendUpdatePacket(net.minecraft.core.BlockPos pos) {
        double value = 0.0;
        String valueExpr = "";
        if (valueField != null) {
            String raw = valueField.getValue().trim();
            if (!raw.isEmpty()) {
                try {
                    value = parseSI(raw);
                } catch (NumberFormatException nfe) {
                    // Not a number — treat as a variable name if it looks like
                    // a plain identifier. Anything else falls through to 0.
                    if (isIdentifier(raw)) valueExpr = raw;
                }
            }
        }
        double acValue = 0.0;
        String acValueExpr = "";
        if (acValueField != null) {
            String raw = acValueField.getValue().trim();
            if (!raw.isEmpty()) {
                try {
                    acValue = parseSI(raw);
                } catch (NumberFormatException nfe) {
                    if (isIdentifier(raw)) acValueExpr = raw;
                }
            }
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
        String wExpr = "", lExpr = "", multExpr = "", nfExpr = "";
        if ((showControlSource || showDiodeModel || showBehaviorExpr) && modelNameField != null) {
            modelName = modelNameField.getValue().trim();
        }
        if (showSky130) {
            if (modelNameField != null) modelName = modelNameField
                .getValue()
                .trim();
            ParsedSlot ws = parseLengthSlot(wField, 1.0);
            w = ws.value; wExpr = ws.expr;
            ParsedSlot ls = parseLengthSlot(lField, 1.0);
            l = ls.value; lExpr = ls.expr;
            ParsedSlot ms = parseSlot(multField, 1.0);
            mult = ms.value; multExpr = ms.expr;
            if (showNf) {
                ParsedSlot ns = parseSlot(nfField, 1.0);
                nf = ns.value; nfExpr = ns.expr;
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

        int subcktOrder = 0;
        if (showSubcktPin && subcktOrderField != null) {
            String raw = subcktOrderField.getValue().trim();
            if (!raw.isEmpty()) {
                try { subcktOrder = Math.max(0, Integer.parseInt(raw)); }
                catch (NumberFormatException ignored) {}
            }
        }

        // Pulse-source extra params (only meaningful when showPulse is set,
        // but always sent so the packet shape is constant). Defaults match
        // the BE defaults so non-pulse blocks don't accidentally wipe them.
        double pulseVLow = 0.0, pulseTr = 1e-9, pulseTf = 1e-9, pulsePw = 1e-6;
        if (showPulse) {
            if (pulseVLowField != null) {
                try { pulseVLow = parseSI(pulseVLowField.getValue()); }
                catch (NumberFormatException ignored) {}
            }
            if (pulsePwField != null) {
                try { pulsePw = parseSI(pulsePwField.getValue()); }
                catch (NumberFormatException ignored) {}
            }
            if (pulseTrField != null) {
                try { pulseTr = parseSI(pulseTrField.getValue()); }
                catch (NumberFormatException ignored) {}
            }
            if (pulseTfField != null) {
                try { pulseTf = parseSI(pulseTfField.getValue()); }
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
                icMirrored,
                pulseVLow,
                pulseTr,
                pulseTf,
                pulsePw,
                valueExpr,
                wExpr,
                lExpr,
                multExpr,
                nfExpr,
                acValue,
                acValueExpr,
                probeNoPlot,
                rNoiseless,
                subcktPin,
                subcktOrder
            )
        );
    }

    /**
     * Result of parsing a per-slot field: a numeric value plus an optional
     * variable name. When the user typed a number, {@code expr} is empty and
     * {@code value} holds the parsed number. When they typed an identifier,
     * {@code expr} holds the name and {@code value} keeps the defaulted
     * numeric (so the BE still has a usable fallback if the variable later
     * disappears).
     */
    static final class ParsedSlot {
        final double value;
        final String expr;
        ParsedSlot(double v, String e) { value = v; expr = e == null ? "" : e; }
    }

    /** Parses a field that accepts either a plain number or a variable name. */
    private static ParsedSlot parseSlot(EditBox box, double defaultVal) {
        if (box == null) return new ParsedSlot(defaultVal, "");
        String raw = box.getValue().trim();
        if (raw.isEmpty()) return new ParsedSlot(defaultVal, "");
        try {
            return new ParsedSlot(Double.parseDouble(raw), "");
        } catch (NumberFormatException nfe) {
            try {
                return new ParsedSlot(parseSI(raw), "");
            } catch (NumberFormatException nfe2) {
                if (isIdentifier(raw)) return new ParsedSlot(defaultVal, raw);
                return new ParsedSlot(defaultVal, "");
            }
        }
    }

    /**
     * W/L unit handling is PDK-dependent. sky130A's ngspice models take W/L in
     * microns, so a bare "5" means 5 µm and is emitted as-is. Every other PDK
     * (none/placeholder, and any future meter-based PDK) follows the SPICE
     * default of meters, so the user writes "5u" for 5 µm and the value is
     * stored/emitted in meters.
     */
    private boolean lengthInMicrons() {
        return "sky130A".equals(icPdkName);
    }

    /** Unit suffix shown in the W/L row labels for the active PDK. */
    private String lengthUnit() {
        return lengthInMicrons() ? "um" : "m";
    }

    /** Formats a stored W/L for its text box per the active unit convention. */
    private String formatLengthForField(double v) {
        return lengthInMicrons()
                ? trimTrailingZeros(String.format("%.6f", v)) // micron magnitude, e.g. "0.15"
                : formatValue(v);                              // SI meters, e.g. "150n"
    }

    /**
     * Parses a W/L field per the active unit: microns take a plain number (no
     * SI scaling — the value already is in µm); meters use the SI parser so
     * "5u" becomes 5e-6. Variable names are honoured in both modes.
     */
    private ParsedSlot parseLengthSlot(EditBox box, double defaultVal) {
        if (!lengthInMicrons()) return parseSlot(box, defaultVal);
        if (box == null) return new ParsedSlot(defaultVal, "");
        String raw = box.getValue().trim();
        if (raw.isEmpty()) return new ParsedSlot(defaultVal, "");
        try {
            return new ParsedSlot(Double.parseDouble(raw), "");
        } catch (NumberFormatException nfe) {
            if (isIdentifier(raw)) return new ParsedSlot(defaultVal, raw);
            return new ParsedSlot(defaultVal, "");
        }
    }

    /**
     * Rewrites the W/L text boxes when the PDK's length unit changes (microns
     * <-> meters) so the physical size is preserved rather than the digits.
     * e.g. "5u" (meters) becomes "5" when switching to sky130A's microns.
     * Variable names and unparseable text are left untouched.
     */
    private void reformatLengthFields(String oldPdk, String newPdk) {
        boolean oldUm = "sky130A".equals(oldPdk);
        boolean newUm = "sky130A".equals(newPdk);
        if (oldUm == newUm) return; // same unit — nothing to convert
        for (EditBox box : new EditBox[]{wField, lField}) {
            if (box == null) continue;
            String raw = box.getValue().trim();
            if (raw.isEmpty() || isIdentifier(raw)) continue;
            double meters;
            try {
                meters = oldUm ? Double.parseDouble(raw) * 1e-6 // µm magnitude -> m
                               : parseSI(raw);                  // SI meters
            } catch (NumberFormatException nfe) {
                continue; // leave whatever the user typed
            }
            box.setValue(newUm
                    ? trimTrailingZeros(String.format("%.6f", meters * 1e6)) // m -> µm magnitude
                    : formatValue(meters));                                   // SI meters
        }
    }

    /** True if the string looks like a variable name (letter, then [A-Za-z0-9_]*). */
    public static boolean isIdentifier(String s) {
        if (s == null || s.isEmpty()) return false;
        char c0 = s.charAt(0);
        if (!Character.isLetter(c0) && c0 != '_') return false;
        for (int i = 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_') return false;
        }
        return true;
    }

    private String getValueLabel(String type) {
        return switch (type) {
            case "resistor" -> "Resistance (\u03A9)";
            case "capacitor" -> "Capacitance (F)";
            case "inductor" -> "Inductance (H)";
            case "voltage_source" -> "DC Value (V)";
            case "voltage_source_sin" -> "Amplitude (V)";
            case "voltage_source_pulse" -> "V1 (V)";
            case "current_source" -> "Current (A)";
            case "ccvs" -> "Transresistance (\u03A9)";
            case "cccs" -> "Current gain";
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
                 "voltage_source_sin",
                 "voltage_source_pulse"      -> "Netlist index V";
            case "current_source"            -> "Netlist index I";
            case "behavioral_voltage_source",
                 "behavioral_current_source" -> "Netlist index B";
            case "diode"                     -> "Netlist index D";
            case "ic_nmos4", "ic_pmos4"      -> "Netlist index XM";
            case "ccvs"                      -> "Netlist index H";
            case "cccs"                      -> "Netlist index F";
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
            case "voltage_source_pulse" -> "Pulse Voltage Source";
            case "current_source" -> "Current Source";
            case "behavioral_voltage_source" -> "Behavioral V Source (B)";
            case "behavioral_current_source" -> "Behavioral I Source (B)";
            case "diode" -> "Diode";
            case "probe" -> "Voltage Probe";
            case "current_probe" -> "Current Probe";
            case "ic_resistor3"  -> "IC Resistor3";
            case "ic_capacitor2" -> "IC Capacitor2";
            case "ic_nmos4"      -> "IC NMOS4";
            case "ic_pmos4"      -> "IC PMOS4";
            case "ccvs"          -> "CCVS (H)";
            case "cccs"          -> "CCCS (F)";
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
