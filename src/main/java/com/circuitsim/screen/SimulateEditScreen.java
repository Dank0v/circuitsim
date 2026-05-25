package com.circuitsim.screen;

import com.circuitsim.blockentity.ComponentBlockEntity;
import com.circuitsim.network.ModMessages;
import com.circuitsim.network.SimulatePacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntity;

public class SimulateEditScreen extends Screen {

    private final BlockPos pos;
    private String analysis = "OP";

    // PDK state
    private String pdkName    = "none"; // "none", "sky130A", "placeholder"
    private String ngBehavior = "none";
    // Single-line lib path used by non-psa modes (sky130A flow).
    private String pdkLibPath  = "";
    // Newline-separated lib paths used by psa mode (one .INCLUDE per line).
    private String pdkLibPaths = "";
    // Cached param values so widgets survive a rebuild on compat-mode switch.
    private String savedParam1 = "10";
    private String savedParam2 = "1Meg";
    private String savedParam3 = "10";
    private String savedTemp   = "27";
    // DC analysis state — cached so widget rebuilds (analysis/compat switches)
    // don't drop in-flight edits.
    private String  savedDcSrc1   = "V1";
    private String  savedDcStart1 = "0";
    private String  savedDcStop1  = "5";
    private String  savedDcStep1  = "0.1";
    private boolean savedDc2D     = false;
    private String  savedDcSrc2   = "";
    private String  savedDcStart2 = "0";
    private String  savedDcStop2  = "1";
    private String  savedDcStep2  = "0.25";
    // Set after the first init() so subsequent rebuilds preserve in-flight edits
    // instead of re-reading from the block entity.
    private boolean loadedFromBe = false;

    private EditBox pdkLibField;
    private MultiLineEditBox pdkLibPathsField;

    // "none" = strict ngspice, no compatibility tweaks (default for new
    // blocks). The other modes match ngspice's `set ngbehavior=<x>` accepted
    // values for HSPICE / PSPICE / LTspice / Keysight / Verilog-A flavours.
    private static final String[] NG_MODES = {"none", "hsa", "psa", "lt", "ki", "va"};

    private EditBox param1Field;
    private EditBox param2Field;
    private EditBox param3Field;
    private EditBox tempField;
    // DC widgets — populated when DC fields are visible in the layout.
    private EditBox dcSrc1Field, dcStart1Field, dcStop1Field, dcStep1Field;
    private EditBox dcSrc2Field, dcStart2Field, dcStop2Field, dcStep2Field;

    private static final int W = 290,
        H = 408;

    private static final int BG = 0xFF1E1E1E;
    private static final int BORDER = 0xFF4A90D9;
    private static final int TITLE = 0xFFFFD700;
    private static final int LABEL = 0xFFFFFFFF;
    private static final int DIM = 0xFF666666;
    private static final int SEL = 0xFF4FC3F7;
    private static final int CHK_ON = 0xFF4FC3F7;

    // --- Y-axis layout constants (offsets from py) ---
    // Title (7) ─ divider (22)
    // Analysis section
    private static final int Y_ANALYSIS_LABEL = 29;
    private static final int Y_ANALYSIS_OP    = 43;
    private static final int Y_ANALYSIS_DC    = 63;
    private static final int Y_ANALYSIS_AC    = 83;
    private static final int Y_ANALYSIS_TRAN  = 103;
    private static final int Y_DIV_AFTER_ANALYSIS = 122;
    // Analysis-specific params occupy a single shared region between the
    // analysis radios and the temperature row. The DC sub-panel and the
    // AC/TRAN param rows share these Y offsets — `refreshFields()` toggles
    // widget visibility so only one set shows at a time, keeping the dialog
    // at a fixed height regardless of which analysis is selected.
    private static final int Y_PARAM1     = 128;
    private static final int Y_PARAM2     = 151;
    private static final int Y_PARAM3     = 174;
    // DC sub-panel rows. Row 1 is offset down from Y_PARAM1 to leave room
    // for the "Src / Start / Stop / Step" column-header strip above it
    // (without clipping into the analysis-radio area above the divider).
    private static final int Y_DC_ROW1     = 138;
    private static final int Y_DC_2DTOGGLE = 158;
    private static final int Y_DC_ROW2     = 175;
    private static final int Y_PARAM_TEMP = 197;       // temperature override (single value or sweep spec)
    private static final int Y_DIV_AFTER_PARAMS = 217;
    // Compat section
    private static final int Y_COMPAT_LABEL = 224;
    private static final int Y_COMPAT_CHIPS = 238;
    private static final int Y_DIV_AFTER_COMPAT = 261;
    // PDK/lib section (depends on mode)
    private static final int Y_PDK_LABEL  = 268;
    private static final int Y_PDK_CHIPS  = 282;
    private static final int Y_LIB_LABEL  = 300;
    private static final int Y_LIB_FIELD  = 313;
    // psa multi-line field spans the entire mode-dependent slot:
    private static final int Y_PSA_FIELD       = 282;
    private static final int H_PSA_FIELD       = 58;

    public SimulateEditScreen(BlockPos pos) {
        super(Component.literal("Circuit Analysis"));
        this.pos = pos;
    }

    @Override
    protected void init() {
        super.init();
        int px = (width - W) / 2,
            py = (height - H) / 2;

        if (!loadedFromBe) {
            BlockEntity be = Minecraft.getInstance().level.getBlockEntity(pos);
            if (be instanceof ComponentBlockEntity cbe) {
                pdkName     = cbe.getPdkName();
                ngBehavior  = cbe.getNgBehavior();
                analysis    = cbe.getSimAnalysis();
                savedParam1 = cbe.getSimParam1();
                savedParam2 = cbe.getSimParam2();
                savedParam3 = cbe.getSimParam3();
                savedTemp   = cbe.getSimTemp();
                pdkLibPath  = cbe.getPdkLibPath();
                pdkLibPaths = cbe.getPdkLibPaths();
                savedDcSrc1   = cbe.getDcSource1();
                savedDcStart1 = cbe.getDcStart1();
                savedDcStop1  = cbe.getDcStop1();
                savedDcStep1  = cbe.getDcStep1();
                savedDc2D     = cbe.getDc2D();
                savedDcSrc2   = cbe.getDcSource2();
                savedDcStart2 = cbe.getDcStart2();
                savedDcStop2  = cbe.getDcStop2();
                savedDcStep2  = cbe.getDcStep2();
            }
            // Migrate any saved world that still has the now-removed
            // dedicated TEMP analysis to plain OP — the temperature field
            // covers the same use case via a sweep spec like "0:90:30".
            if ("TEMP".equals(analysis)) analysis = "OP";
            loadedFromBe = true;
        }

        boolean psa = "psa".equals(ngBehavior);

        if (psa) {
            String hint = "C:\\path\\to\\OPAMP.LIB\nC:\\path\\to\\models.lib";
            pdkLibPathsField = new MultiLineEditBox(
                Minecraft.getInstance().font,
                px + 16, py + Y_PSA_FIELD, W - 32, H_PSA_FIELD,
                Component.literal(hint),
                Component.literal(".include paths"));
            pdkLibPathsField.setCharacterLimit(8000);
            pdkLibPathsField.setValue(pdkLibPaths);
            addRenderableWidget(pdkLibPathsField);
        } else {
            pdkLibField = box(px + 16, py + Y_LIB_FIELD, W - 32, pdkLibPath);
            String libHint = "C:\\path\\to\\sky130.lib.spice tt";
            pdkLibField.setSuggestion(pdkLibPath.isEmpty() ? libHint : "");
            pdkLibField.setResponder(text -> pdkLibField.setSuggestion(text.isEmpty() ? libHint : ""));
        }

        // Always create the DC widgets; visibility is toggled in refreshFields().
        // Compact fields: name then start, stop, step on one row.
        dcSrc1Field   = small(px + 56,  py + Y_DC_ROW1, 40, savedDcSrc1);
        dcStart1Field = small(px + 116, py + Y_DC_ROW1, 40, savedDcStart1);
        dcStop1Field  = small(px + 176, py + Y_DC_ROW1, 40, savedDcStop1);
        dcStep1Field  = small(px + 236, py + Y_DC_ROW1, 40, savedDcStep1);
        dcSrc2Field   = small(px + 56,  py + Y_DC_ROW2, 40, savedDcSrc2);
        dcStart2Field = small(px + 116, py + Y_DC_ROW2, 40, savedDcStart2);
        dcStop2Field  = small(px + 176, py + Y_DC_ROW2, 40, savedDcStop2);
        dcStep2Field  = small(px + 236, py + Y_DC_ROW2, 40, savedDcStep2);

        param1Field = box(px + 166, py + Y_PARAM1, 90, savedParam1);
        param2Field = box(px + 166, py + Y_PARAM2, 90, savedParam2);
        param3Field = box(px + 166, py + Y_PARAM3, 90, savedParam3);
        tempField   = box(px + 166, py + Y_PARAM_TEMP, 90, savedTemp);
        tempField.setMaxLength(64);
        // Show a hint when empty: single value or sweep spec.
        String tempHint = "27 or 20:40:5";
        tempField.setSuggestion(savedTemp.isEmpty() ? tempHint : "");
        tempField.setResponder(text -> tempField.setSuggestion(text.isEmpty() ? tempHint : ""));
        refreshFields();
        refreshPdkField();

        addRenderableWidget(
            Button.builder(Component.literal("Simulate"), b -> {
                sendPacket();
                onClose();
            })
                .bounds(px + 20, py + H - 28, 110, 20)
                .build()
        );
        addRenderableWidget(
            Button.builder(Component.literal("Cancel"), b -> onClose())
                .bounds(px + 160, py + H - 28, 110, 20)
                .build()
        );
    }

    /** Compact text box used for DC source / range fields. */
    private EditBox small(int x, int y, int w, String init) {
        EditBox b = new EditBox(Minecraft.getInstance().font, x, y, w, 18, Component.empty());
        b.setMaxLength(32);
        b.setValue(init);
        b.setBordered(true);
        addRenderableWidget(b);
        return b;
    }

    /**
     * Source-2 row visibility/editability — visible only when DC is selected,
     * editable only when DC is selected AND the 2D toggle is on. Hidden
     * entirely when DC isn't active so the row doesn't overlap other fields.
     */
    private void refreshDcFields() {
        if (dcSrc2Field == null) return;
        boolean dc = "DC".equals(analysis);
        boolean enabled = dc && savedDc2D;
        for (EditBox f : new EditBox[]{dcSrc2Field, dcStart2Field, dcStop2Field, dcStep2Field}) {
            f.visible = dc;
            f.setEditable(enabled);
            f.setTextColor(enabled ? 0xFFFFFFFF : DIM);
        }
    }

    private EditBox box(int x, int y, int w, String init) {
        EditBox b = new EditBox(
            Minecraft.getInstance().font,
            x,
            y,
            w,
            18,
            Component.empty()
        );
        // setMaxLength MUST come before setValue — EditBox defaults to 32 and
        // setValue silently truncates to the current limit. The .lib path is
        // typically >32 chars; this is what caused stored paths to be cut at
        // exactly position 32 after the dialog was reopened.
        b.setMaxLength(256);
        b.setValue(init);
        b.setBordered(true);
        addRenderableWidget(b);
        return b;
    }

    private void refreshFields() {
        boolean ac    = "AC".equals(analysis);
        boolean tran  = "TRAN".equals(analysis);
        boolean dc    = "DC".equals(analysis);
        boolean acTran = ac || tran;

        // AC/TRAN param row widgets — visible (and editable) only for those
        // analyses; otherwise hidden so the DC fields can occupy the same Y.
        if (param1Field != null) {
            param1Field.visible = acTran;
            param1Field.setEditable(acTran);
            param1Field.setTextColor(acTran ? 0xFFFFFFFF : DIM);
        }
        if (param2Field != null) {
            param2Field.visible = acTran;
            param2Field.setEditable(acTran);
            param2Field.setTextColor(acTran ? 0xFFFFFFFF : DIM);
        }
        if (param3Field != null) {
            param3Field.visible = acTran;
            param3Field.setEditable(ac);
            param3Field.setTextColor(ac ? 0xFFFFFFFF : DIM);
        }

        // DC widgets — visible only when DC is selected.
        for (EditBox f : new EditBox[]{dcSrc1Field, dcStart1Field, dcStop1Field, dcStep1Field}) {
            if (f != null) {
                f.visible = dc;
                f.setEditable(dc);
                f.setTextColor(dc ? 0xFFFFFFFF : DIM);
            }
        }
        refreshDcFields();   // gates the source-2 row on the 2D checkbox

        if (tempField != null) {
            tempField.setEditable(true);
            tempField.setTextColor(0xFFFFFFFF);
        }
    }

    private void refreshPdkField() {
        if (pdkLibField == null) return; // psa mode — no single-line field
        boolean hasPdk = !"none".equals(pdkName);
        pdkLibField.setEditable(hasPdk);
        pdkLibField.setTextColor(hasPdk ? 0xFFFFFFFF : DIM);
    }

    private void setAnalysis(String newAnalysis) {
        if (newAnalysis.equals(analysis)) return;
        captureWidgetState();
        analysis = newAnalysis;
        switch (newAnalysis) {
            case "AC" -> {
                if (param1Field != null) param1Field.setValue("10");
                if (param2Field != null) param2Field.setValue("1Meg");
                if (param3Field != null) param3Field.setValue("10");
                savedParam1 = "10"; savedParam2 = "1Meg"; savedParam3 = "10";
            }
            case "TRAN" -> {
                if (param1Field != null) param1Field.setValue("1u");
                if (param2Field != null) param2Field.setValue("10m");
                if (param3Field != null) param3Field.setValue("");
                savedParam1 = "1u"; savedParam2 = "10m"; savedParam3 = "";
            }
        }
        refreshFields();
    }

    private void setPdk(String name) {
        pdkName = name;
        refreshPdkField();
    }

    /**
     * Switches the active compatibility mode. If the change crosses the
     * psa boundary the dialog must be rebuilt because the lib-path widget
     * type changes (single-line EditBox <-> MultiLineEditBox).
     */
    private void setNgBehavior(String mode) {
        if (mode.equals(ngBehavior)) return;
        boolean wasPsa = "psa".equals(ngBehavior);
        boolean willPsa = "psa".equals(mode);
        // Persist current widget values so rebuildWidgets() can repopulate them.
        captureWidgetState();
        ngBehavior = mode;
        if (wasPsa != willPsa) {
            rebuildWidgets();
        }
    }

    /** Copies current widget contents back into instance fields. */
    private void captureWidgetState() {
        if (param1Field != null) savedParam1 = param1Field.getValue();
        if (param2Field != null) savedParam2 = param2Field.getValue();
        if (param3Field != null) savedParam3 = param3Field.getValue();
        if (tempField != null)   savedTemp   = tempField.getValue();
        if (pdkLibField != null) pdkLibPath  = pdkLibField.getValue();
        if (pdkLibPathsField != null) pdkLibPaths = pdkLibPathsField.getValue();
        if (dcSrc1Field   != null) savedDcSrc1   = dcSrc1Field.getValue();
        if (dcStart1Field != null) savedDcStart1 = dcStart1Field.getValue();
        if (dcStop1Field  != null) savedDcStop1  = dcStop1Field.getValue();
        if (dcStep1Field  != null) savedDcStep1  = dcStep1Field.getValue();
        if (dcSrc2Field   != null) savedDcSrc2   = dcSrc2Field.getValue();
        if (dcStart2Field != null) savedDcStart2 = dcStart2Field.getValue();
        if (dcStop2Field  != null) savedDcStop2  = dcStop2Field.getValue();
        if (dcStep2Field  != null) savedDcStep2  = dcStep2Field.getValue();
    }

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        int px = (width - W) / 2,
            py = (height - H) / 2;
        // Analysis type rows
        if (hit(mx, my, px + 14, py + Y_ANALYSIS_OP,   W - 20, 16)) {
            setAnalysis("OP");
            return true;
        }
        if (hit(mx, my, px + 14, py + Y_ANALYSIS_DC,   W - 20, 16)) {
            setAnalysis("DC");
            return true;
        }
        if (hit(mx, my, px + 14, py + Y_ANALYSIS_AC,   W - 20, 16)) {
            setAnalysis("AC");
            return true;
        }
        if (hit(mx, my, px + 14, py + Y_ANALYSIS_TRAN, W - 20, 16)) {
            setAnalysis("TRAN");
            return true;
        }
        // 2D-sweep checkbox in the DC sub-panel.
        if ("DC".equals(analysis)) {
            int cbX = px + 14, cbY = py + Y_DC_2DTOGGLE + 3;
            if (hit(mx, my, cbX, cbY, 200, 14)) {
                captureWidgetState();
                savedDc2D = !savedDc2D;
                refreshDcFields();
                return true;
            }
        }
        // ngbehavior compat chips (now placed above PDK)
        for (int i = 0; i < NG_MODES.length; i++) {
            if (hit(mx, my, px + 70 + i * 34, py + Y_COMPAT_CHIPS, 30, 14)) {
                setNgBehavior(NG_MODES[i]);
                return true;
            }
        }
        // PDK radio options — only active in non-psa modes (in psa we have a
        // multi-line edit box covering this area instead).
        if (!"psa".equals(ngBehavior)) {
            if (hit(mx, my, px + 14, py + Y_PDK_CHIPS, 84, 16)) {
                setPdk("none");
                return true;
            }
            if (hit(mx, my, px + 98, py + Y_PDK_CHIPS, 75, 16)) {
                setPdk("sky130A");
                return true;
            }
            if (hit(mx, my, px + 173, py + Y_PDK_CHIPS, 110, 16)) {
                setPdk("placeholder");
                return true;
            }
        }
        return super.mouseClicked(mx, my, btn);
    }

    private static boolean hit(
        double mx,
        double my,
        int x,
        int y,
        int w,
        int h
    ) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    private void drawBackground(GuiGraphics g) {
        int px = (width - W) / 2,
            py = (height - H) / 2;
        g.fill(px, py, px + W, py + H, BG);
        border(g, px, py, W, H);
        g.fill(px + 2, py + 22, px + W - 2, py + 23, 0xFF444444);
        g.fill(px + 2, py + Y_DIV_AFTER_ANALYSIS, px + W - 2, py + Y_DIV_AFTER_ANALYSIS + 1, 0xFF333333);
        g.fill(px + 2, py + Y_DIV_AFTER_PARAMS,   px + W - 2, py + Y_DIV_AFTER_PARAMS + 1,   0xFF333333);
        g.fill(px + 2, py + Y_DIV_AFTER_COMPAT,   px + W - 2, py + Y_DIV_AFTER_COMPAT + 1,   0xFF333333);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        renderBackground(g);
        drawBackground(g);
        super.render(g, mx, my, pt);

        int px = (width - W) / 2,
            py = (height - H) / 2;
        var f = Minecraft.getInstance().font;

        g.drawCenteredString(f, "Circuit Analysis", width / 2, py + 7, TITLE);
        g.drawString(f, "Analysis Type:", px + 14, py + Y_ANALYSIS_LABEL, LABEL);

        boolean op   = "OP".equals(analysis);
        boolean dc   = "DC".equals(analysis);
        boolean ac   = "AC".equals(analysis);
        boolean tran = "TRAN".equals(analysis);

        radio(g, px + 16, py + Y_ANALYSIS_OP + 2, op);
        g.drawString(f, ".OP   - DC Operating Point", px + 30, py + Y_ANALYSIS_OP + 1, op ? SEL : DIM);

        radio(g, px + 16, py + Y_ANALYSIS_DC + 2, dc);
        g.drawString(f, ".DC   - DC Sweep", px + 30, py + Y_ANALYSIS_DC + 1, dc ? SEL : DIM);

        radio(g, px + 16, py + Y_ANALYSIS_AC + 2, ac);
        g.drawString(f, ".AC   - Frequency Sweep", px + 30, py + Y_ANALYSIS_AC + 1, ac ? SEL : DIM);

        radio(g, px + 16, py + Y_ANALYSIS_TRAN + 2, tran);
        g.drawString(f, ".TRAN - Transient Analysis", px + 30, py + Y_ANALYSIS_TRAN + 1, tran ? SEL : DIM);

        // DC sub-panel — labels and column headers (rendered only when DC).
        if (dc) {
            // Column headers above the four EditBoxes (row 1).
            g.drawString(f, "Src",   px + 56,  py + Y_DC_ROW1 - 10, LABEL);
            g.drawString(f, "Start", px + 116, py + Y_DC_ROW1 - 10, LABEL);
            g.drawString(f, "Stop",  px + 176, py + Y_DC_ROW1 - 10, LABEL);
            g.drawString(f, "Step",  px + 236, py + Y_DC_ROW1 - 10, LABEL);
            g.drawString(f, "1:",    px + 14,  py + Y_DC_ROW1 + 5,  LABEL);
            // 2D toggle between the two source rows.
            int cbY = py + Y_DC_2DTOGGLE + 3;
            checkbox(g, px + 14, cbY, savedDc2D);
            g.drawString(f, "Enable 2D sweep (outer source)", px + 30, cbY - 1,
                    savedDc2D ? CHK_ON : DIM);
            // Row-2 label (dimmed when 2D is off, hidden when DC isn't active).
            int row2Label = savedDc2D ? LABEL : DIM;
            g.drawString(f, "2:", px + 14, py + Y_DC_ROW2 + 5, row2Label);
        }

        // AC/TRAN param labels — rendered only when the matching analysis is
        // active. OP and DC leave this region empty (DC uses the same Y for
        // its own labels above).
        if (ac) {
            g.drawString(f, "Start Freq (Hz):", px + 16, py + Y_PARAM1 + 3, LABEL);
            g.drawString(f, "Stop Freq  (Hz):", px + 16, py + Y_PARAM2 + 3, LABEL);
            g.drawString(f, "Pts / Decade:",   px + 16, py + Y_PARAM3 + 3, LABEL);
        } else if (tran) {
            g.drawString(f, "Time Step:", px + 16, py + Y_PARAM1 + 3, LABEL);
            g.drawString(f, "Stop Time:", px + 16, py + Y_PARAM2 + 3, LABEL);
            g.drawString(f, "Pts / Dec:", px + 16, py + Y_PARAM3 + 3, DIM);
        }
        // OP: nothing rendered in the analysis-specific region.

        // Single value (e.g. "27") sets the circuit temperature; a sweep spec
        // ("20:40:5" or "20,30,40") triggers one run per temperature.
        g.drawString(f, "Temperature (°C):", px + 16, py + Y_PARAM_TEMP + 3, LABEL);

        // compat section (moved above PDK)
        g.drawString(f, "compat:", px + 16, py + Y_COMPAT_LABEL, LABEL);
        for (int i = 0; i < NG_MODES.length; i++) {
            int cx = px + 70 + i * 34;
            int cy = py + Y_COMPAT_CHIPS;
            boolean sel = NG_MODES[i].equals(ngBehavior);
            g.fill(cx, cy, cx + 30, cy + 14, sel ? 0xFF1A4A6A : 0xFF333333);
            g.fill(cx + 1, cy + 1, cx + 29, cy + 13, sel ? 0xFF2A6A9A : 0xFF444444);
            g.drawCenteredString(f, NG_MODES[i], cx + 15, cy + 3, sel ? SEL : DIM);
        }

        // PDK / library section — varies by compat mode.
        if ("psa".equals(ngBehavior)) {
            g.drawString(f, ".lib paths (.INCLUDE, one per line):", px + 14, py + Y_PDK_LABEL, LABEL);
        } else {
            g.drawString(f, "PDK:", px + 14, py + Y_PDK_LABEL, LABEL);

            boolean isNone        = "none".equals(pdkName);
            boolean isSky130      = "sky130A".equals(pdkName);
            boolean isPlaceholder = "placeholder".equals(pdkName);

            checkbox(g, px + 16, py + Y_PDK_CHIPS + 2, isNone);
            g.drawString(f, "no pdk", px + 30, py + Y_PDK_CHIPS + 1, isNone ? CHK_ON : DIM);

            checkbox(g, px + 100, py + Y_PDK_CHIPS + 2, isSky130);
            g.drawString(f, "sky130A", px + 114, py + Y_PDK_CHIPS + 1, isSky130 ? CHK_ON : DIM);

            checkbox(g, px + 175, py + Y_PDK_CHIPS + 2, isPlaceholder);
            g.drawString(f, "placeholder", px + 189, py + Y_PDK_CHIPS + 1, isPlaceholder ? CHK_ON : DIM);

            boolean hasPdk = !"none".equals(pdkName);
            g.drawString(f, ".lib path:", px + 16, py + Y_LIB_LABEL, hasPdk ? LABEL : DIM);
        }
    }

    private void radio(GuiGraphics g, int x, int y, boolean sel) {
        g.fill(x, y, x + 10, y + 10, 0xFF888888);
        g.fill(x + 1, y + 1, x + 9, y + 9, BG);
        if (sel) g.fill(x + 3, y + 3, x + 7, y + 7, SEL);
    }

    private void checkbox(GuiGraphics g, int x, int y, boolean sel) {
        g.fill(x, y, x + 10, y + 10, 0xFF888888);
        g.fill(x + 1, y + 1, x + 9, y + 9, BG);
        if (sel) g.fill(x + 2, y + 2, x + 8, y + 8, CHK_ON);
    }

    private void border(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + 2, BORDER);
        g.fill(x, y + h - 2, x + w, y + h, BORDER);
        g.fill(x, y, x + 2, y + h, BORDER);
        g.fill(x + w - 2, y, x + w, y + h, BORDER);
    }

    private void sendPacket() {
        double p1 = 0.0,
            p2 = 0.0;
        int p3 = 10;

        if ("AC".equals(analysis)) {
            try {
                p1 = ComponentEditScreen.parseSI(param1Field.getValue().trim());
            } catch (Exception ignored) {
                p1 = 10.0;
            }
            try {
                p2 = ComponentEditScreen.parseSI(param2Field.getValue().trim());
            } catch (Exception ignored) {
                p2 = 1_000_000.0;
            }
            try {
                p3 = Integer.parseInt(param3Field.getValue().trim());
            } catch (Exception ignored) {
                p3 = 10;
            }
            if (p3 < 1) p3 = 1;
            if (p3 > 100) p3 = 100;
        } else if ("TRAN".equals(analysis)) {
            try {
                p1 = ComponentEditScreen.parseSI(param1Field.getValue().trim());
            } catch (Exception ignored) {
                p1 = 1e-6;
            }
            try {
                p2 = ComponentEditScreen.parseSI(param2Field.getValue().trim());
            } catch (Exception ignored) {
                p2 = 1e-2;
            }
            p3 = 0;
        }

        // Snapshot both lib-path variants so switching back to the other mode
        // later restores whatever the user had configured.
        captureWidgetState();
        String tempValue = tempField != null ? tempField.getValue().trim() : "27";
        if (tempValue.isEmpty()) tempValue = "27";
        String raw1 = param1Field != null ? param1Field.getValue().trim() : savedParam1;
        String raw2 = param2Field != null ? param2Field.getValue().trim() : savedParam2;
        String raw3 = param3Field != null ? param3Field.getValue().trim() : savedParam3;
        ModMessages.sendToServer(
            new SimulatePacket(pos, analysis, p1, p2, p3, pdkName, pdkLibPath, pdkLibPaths,
                ngBehavior,
                raw1, raw2, raw3,
                tempValue,
                savedDcSrc1, savedDcStart1, savedDcStop1, savedDcStep1,
                savedDc2D,
                savedDcSrc2, savedDcStart2, savedDcStop2, savedDcStep2)
        );
    }

    @Override
    public boolean keyPressed(int k, int s, int m) {
        if (
            getFocused() instanceof EditBox eb && eb.keyPressed(k, s, m)
        ) return true;
        return super.keyPressed(k, s, m);
    }

    @Override
    public boolean charTyped(char c, int m) {
        if (
            getFocused() instanceof EditBox eb && eb.charTyped(c, m)
        ) return true;
        return super.charTyped(c, m);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }
}
