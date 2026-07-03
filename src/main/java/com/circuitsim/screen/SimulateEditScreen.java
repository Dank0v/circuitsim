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
    // Newline-separated lib paths used by the hsa (sky130A) flow; one .lib
    // directive is emitted per line.
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
    // NOISE analysis state — raw UI strings, cached across widget rebuilds.
    private String savedNoiseOut    = "";
    private String savedNoiseRef    = "";
    private String savedNoiseSrc    = "";
    private String savedNoiseSweep  = "dec";
    private String savedNoisePts    = "20";
    private String savedNoiseFstart = "1";
    private String savedNoiseFstop  = "1Meg";
    private String savedNoiseSum    = "";
    // Per-analysis backing store for the shared param1/2/3 boxes. AC and TRAN
    // are the only analyses that use these three fields, so each keeps its own
    // copy; switching tabs stashes the outgoing analysis's values and restores
    // the incoming one's (see loadActiveParams) instead of resetting to defaults.
    private String savedAc1   = "10", savedAc2   = "1Meg", savedAc3   = "10";
    private String savedTran1 = "1u", savedTran2 = "10m",  savedTran3 = "";
    // Set after the first init() so subsequent rebuilds preserve in-flight edits
    // instead of re-reading from the block entity.
    private boolean loadedFromBe = false;

    private MultiLineEditBox pdkLibField;
    private MultiLineEditBox pdkLibPathsField;

    // "none" = strict ngspice, no compatibility tweaks (default for new
    // blocks). The other modes match ngspice's `set ngbehavior=<x>` accepted
    // values for HSPICE / PSPICE / Verilog-A flavours.
    private static final String[] NG_MODES = {"none", "hsa", "psa", "va"};

    private EditBox param1Field;
    private EditBox param2Field;
    private EditBox param3Field;
    private EditBox tempField;
    // DC widgets — populated when DC fields are visible in the layout.
    private EditBox dcSrc1Field, dcStart1Field, dcStop1Field, dcStep1Field;
    private EditBox dcSrc2Field, dcStart2Field, dcStop2Field, dcStep2Field;
    // NOISE widgets — visible only when the NOISE analysis tab is active.
    private EditBox noiseOutField, noiseRefField, noiseSrcField;
    private EditBox noisePtsField, noiseFstartField, noiseFstopField, noiseSumField;

    private static final String[] NOISE_SWEEPS = {"dec", "lin", "oct"};

    private static final int W = 290,
        H = 358;

    // Analysis types live in an array so the selector scales: add an entry
    // here (plus a description and a refreshFields()/setAnalysis() branch) and
    // it shows up as another tab — no layout/height changes required.
    private static final String[] ANALYSES = {"OP", "DC", "AC", "TRAN", "NOISE", "STB"};
    private static final String[] ANALYSIS_DESC = {
        ".OP — DC operating point",
        ".DC — DC sweep",
        ".AC — frequency sweep",
        ".TRAN — transient analysis",
        ".NOISE — small-signal noise analysis",
        ".STB — loop-gain stability (needs a Loop Probe)",
    };
    // Tab-strip geometry (constant height regardless of analysis count).
    private static final int TAB_W = 42, TAB_GAP = 6, TAB_H = 16;

    private static final int BG = 0xFF1E1E1E;
    private static final int BORDER = 0xFF4A90D9;
    private static final int TITLE = 0xFFFFD700;
    private static final int LABEL = 0xFFFFFFFF;
    private static final int DIM = 0xFF666666;
    private static final int SEL = 0xFF4FC3F7;
    private static final int CHK_ON = 0xFF4FC3F7;

    // --- Y-axis layout constants (offsets from py) ---
    // Title (7) ─ divider (22)
    // Analysis section: a label, a single-row tab strip, and a one-line
    // description of the active analysis. Because the tabs are a horizontal
    // strip rather than a stack of radios, adding an analysis costs zero
    // vertical space — the section height is fixed no matter how many exist.
    private static final int Y_ANALYSIS_LABEL = 29;
    private static final int Y_TABS           = 42;   // tab strip (TAB_H tall)
    private static final int Y_ANALYSIS_DESC  = 61;   // description of selected
    private static final int Y_DIV_AFTER_ANALYSIS = 72;
    // Analysis-specific params occupy a single shared region between the
    // analysis tabs and the temperature row. The DC sub-panel and the
    // AC/TRAN param rows share these Y offsets — `refreshFields()` toggles
    // widget visibility so only one set shows at a time, keeping the dialog
    // at a fixed height regardless of which analysis is selected.
    private static final int Y_PARAM1     = 78;
    private static final int Y_PARAM2     = 101;
    private static final int Y_PARAM3     = 124;
    // DC sub-panel rows. Row 1 is offset down from Y_PARAM1 to leave room
    // for the "Src / Start / Stop / Step" column-header strip above it
    // (without clipping into the analysis-tab area above the divider).
    private static final int Y_DC_ROW1     = 88;
    private static final int Y_DC_2DTOGGLE = 108;
    private static final int Y_DC_ROW2     = 125;
    // NOISE sub-panel shares the DC rows' Y offsets: row 1 = output / ref /
    // source, row 2 = sweep-type chips plus pts / fstart / fstop / summary.
    private static final int Y_NOISE_ROW1  = 88;
    private static final int Y_NOISE_ROW2  = 125;
    private static final int Y_PARAM_TEMP = 147;       // temperature override (single value or sweep spec)
    private static final int Y_DIV_AFTER_PARAMS = 167;
    // Compat section
    private static final int Y_COMPAT_LABEL = 174;
    private static final int Y_COMPAT_CHIPS = 188;
    private static final int Y_DIV_AFTER_COMPAT = 211;
    // Library section (hsa/psa only). A single label + multi-line box, one
    // include path per line. No PDK selector here anymore — the model prefix
    // is chosen per IC component block.
    private static final int Y_LIB_LABEL2  = 218;
    private static final int Y_LIB_FIELD2  = 232;
    // MultiLineEditBox draws its "chars/limit" counter at (bottom + 4), so the
    // box must end well above the Simulate/Cancel row (at H-28 = 330) or the
    // counter overlaps the buttons. 232 + 78 = 310 leaves the counter clear.
    private static final int H_LIB_FIELD   = 78;

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
                savedNoiseOut    = cbe.getNoiseOut();
                savedNoiseRef    = cbe.getNoiseRef();
                savedNoiseSrc    = cbe.getNoiseSrc();
                savedNoiseSweep  = cbe.getNoiseSweep();
                savedNoisePts    = cbe.getNoisePts();
                savedNoiseFstart = cbe.getNoiseFstart();
                savedNoiseFstop  = cbe.getNoiseFstop();
                savedNoiseSum    = cbe.getNoisePtsSum();
                savedAc1   = cbe.getSimAcParam1();
                savedAc2   = cbe.getSimAcParam2();
                savedAc3   = cbe.getSimAcParam3();
                savedTran1 = cbe.getSimTranParam1();
                savedTran2 = cbe.getSimTranParam2();
                savedTran3 = cbe.getSimTranParam3();
            }
            // Migrate any saved world that still has the now-removed
            // dedicated TEMP analysis to plain OP — the temperature field
            // covers the same use case via a sweep spec like "0:90:30".
            if ("TEMP".equals(analysis)) analysis = "OP";
            // Point the shared param widgets at the active analysis's saved set
            // so AC/TRAN open showing their own values, not the legacy set.
            loadActiveParams();
            loadedFromBe = true;
        }

        boolean psa = "psa".equals(ngBehavior);
        boolean hsa = "hsa".equals(ngBehavior);

        // Library widgets are only relevant for the two modes that emit
        // includes (hsa → .lib, psa → .INCLUDE). Both present a single
        // multi-line box (one path per line); the choice of model prefix
        // (e.g. sky130_fd_pr__) now lives on the IC component blocks, so the
        // sim block no longer has a PDK selector. Other compat modes (none,
        // va) leave this section empty.
        if (psa) {
            String hint = "C:\\path\\to\\OPAMP.LIB\nC:\\path\\to\\models.lib";
            pdkLibPathsField = new MultiLineEditBox(
                Minecraft.getInstance().font,
                px + 16, py + Y_LIB_FIELD2, W - 32, H_LIB_FIELD,
                Component.literal(hint),
                Component.literal(".include paths"));
            pdkLibPathsField.setCharacterLimit(8000);
            pdkLibPathsField.setValue(pdkLibPaths);
            addRenderableWidget(pdkLibPathsField);
        } else if (hsa) {
            String libHint = "C:\\path\\to\\sky130.lib.spice tt\nC:\\path\\to\\models.lib typical";
            pdkLibField = new MultiLineEditBox(
                Minecraft.getInstance().font,
                px + 16, py + Y_LIB_FIELD2, W - 32, H_LIB_FIELD,
                Component.literal(libHint),
                Component.literal(".lib paths"));
            pdkLibField.setCharacterLimit(4000);
            pdkLibField.setValue(pdkLibPath);
            addRenderableWidget(pdkLibField);
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

        // NOISE widgets share the DC rows' vertical space; refreshFields()
        // ensures only one analysis's widget set is visible at a time.
        noiseOutField    = small(px + 16,  py + Y_NOISE_ROW1, 78, savedNoiseOut);
        noiseRefField    = small(px + 102, py + Y_NOISE_ROW1, 78, savedNoiseRef);
        noiseSrcField    = small(px + 188, py + Y_NOISE_ROW1, 78, savedNoiseSrc);
        noisePtsField    = small(px + 112, py + Y_NOISE_ROW2, 28, savedNoisePts);
        noiseFstartField = small(px + 144, py + Y_NOISE_ROW2, 50, savedNoiseFstart);
        noiseFstopField  = small(px + 198, py + Y_NOISE_ROW2, 50, savedNoiseFstop);
        noiseSumField    = small(px + 252, py + Y_NOISE_ROW2, 24, savedNoiseSum);
        noiseOutField.setSuggestion(savedNoiseOut.isEmpty() ? "vout" : "");
        noiseOutField.setResponder(t -> noiseOutField.setSuggestion(t.isEmpty() ? "vout" : ""));
        noiseSrcField.setSuggestion(savedNoiseSrc.isEmpty() ? "V1" : "");
        noiseSrcField.setResponder(t -> noiseSrcField.setSuggestion(t.isEmpty() ? "V1" : ""));

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
        boolean noise = "NOISE".equals(analysis);
        boolean stb   = "STB".equals(analysis);
        // STB reuses the AC frequency-sweep fields (start / stop / pts-per-dec).
        boolean acStb = ac || stb;
        boolean freqRow = ac || tran || stb;

        // AC/TRAN/STB param row widgets — visible (and editable) only for those
        // analyses; otherwise hidden so the DC fields can occupy the same Y.
        if (param1Field != null) {
            param1Field.visible = freqRow;
            param1Field.setEditable(freqRow);
            param1Field.setTextColor(freqRow ? 0xFFFFFFFF : DIM);
        }
        if (param2Field != null) {
            param2Field.visible = freqRow;
            param2Field.setEditable(freqRow);
            param2Field.setTextColor(freqRow ? 0xFFFFFFFF : DIM);
        }
        if (param3Field != null) {
            param3Field.visible = freqRow;
            param3Field.setEditable(acStb);
            param3Field.setTextColor(acStb ? 0xFFFFFFFF : DIM);
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

        // NOISE widgets — visible only when NOISE is selected.
        for (EditBox f : new EditBox[]{noiseOutField, noiseRefField, noiseSrcField,
                noisePtsField, noiseFstartField, noiseFstopField, noiseSumField}) {
            if (f != null) {
                f.visible = noise;
                f.setEditable(noise);
                f.setTextColor(noise ? 0xFFFFFFFF : DIM);
            }
        }

        if (tempField != null) {
            tempField.setEditable(true);
            tempField.setTextColor(0xFFFFFFFF);
        }
    }

    private void setAnalysis(String newAnalysis) {
        if (newAnalysis.equals(analysis)) return;
        captureWidgetState();   // stashes the outgoing analysis's params into its bucket
        analysis = newAnalysis;
        loadActiveParams();     // restore the incoming analysis's saved params
        refreshFields();
    }

    /**
     * Copies the shared param1/2/3 widget values into the bucket of whichever
     * analysis is currently active. AC and TRAN are the only analyses that use
     * these three fields; for the others this is a no-op.
     */
    private void stashActiveParams() {
        if ("AC".equals(analysis) || "STB".equals(analysis)) {
            // STB reuses the AC frequency-sweep fields, so it shares AC's bucket.
            savedAc1 = savedParam1; savedAc2 = savedParam2; savedAc3 = savedParam3;
        } else if ("TRAN".equals(analysis)) {
            savedTran1 = savedParam1; savedTran2 = savedParam2; savedTran3 = savedParam3;
        }
    }

    /**
     * Loads the active analysis's saved param set into the shared param1/2/3
     * widgets (and savedParam1/2/3), so AC and TRAN each show their own
     * remembered values rather than a shared/reset set. No-op for OP/DC/NOISE,
     * whose param widgets stay hidden. Safe to call before the widgets exist.
     */
    private void loadActiveParams() {
        if ("AC".equals(analysis) || "STB".equals(analysis)) {
            savedParam1 = savedAc1; savedParam2 = savedAc2; savedParam3 = savedAc3;
        } else if ("TRAN".equals(analysis)) {
            savedParam1 = savedTran1; savedParam2 = savedTran2; savedParam3 = savedTran3;
        } else {
            return;
        }
        if (param1Field != null) param1Field.setValue(savedParam1);
        if (param2Field != null) param2Field.setValue(savedParam2);
        if (param3Field != null) param3Field.setValue(savedParam3);
    }

    /**
     * Switches the active compatibility mode. If the change crosses the
     * psa boundary the dialog must be rebuilt because the lib-path widget
     * type changes (single-line EditBox <-> MultiLineEditBox).
     */
    private void setNgBehavior(String mode) {
        if (mode.equals(ngBehavior)) return;
        // The PDK / lib section now has THREE shapes — psa (multi-line
        // .INCLUDE), hsa (single-line .lib + PDK chips), and "no widget" for
        // every other compat mode. Crossing between any two of those shapes
        // changes which widgets exist, so rebuild whenever the shape kind
        // changes.
        String oldKind = pdkSectionKind(ngBehavior);
        String newKind = pdkSectionKind(mode);
        captureWidgetState();
        ngBehavior = mode;
        if (!oldKind.equals(newKind)) {
            rebuildWidgets();
        }
    }

    /** Which PDK-section widget layout this compat mode uses. */
    private static String pdkSectionKind(String mode) {
        if ("psa".equals(mode)) return "psa";
        if ("hsa".equals(mode)) return "hsa";
        return "none";
    }

    /** Copies current widget contents back into instance fields. */
    private void captureWidgetState() {
        if (param1Field != null) savedParam1 = param1Field.getValue();
        if (param2Field != null) savedParam2 = param2Field.getValue();
        if (param3Field != null) savedParam3 = param3Field.getValue();
        stashActiveParams();   // mirror them into the active analysis's bucket
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
        if (noiseOutField    != null) savedNoiseOut    = noiseOutField.getValue();
        if (noiseRefField    != null) savedNoiseRef    = noiseRefField.getValue();
        if (noiseSrcField    != null) savedNoiseSrc    = noiseSrcField.getValue();
        if (noisePtsField    != null) savedNoisePts    = noisePtsField.getValue();
        if (noiseFstartField != null) savedNoiseFstart = noiseFstartField.getValue();
        if (noiseFstopField  != null) savedNoiseFstop  = noiseFstopField.getValue();
        if (noiseSumField    != null) savedNoiseSum    = noiseSumField.getValue();
    }

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        int px = (width - W) / 2,
            py = (height - H) / 2;
        // Analysis type tab strip
        for (int i = 0; i < ANALYSES.length; i++) {
            if (hit(mx, my, tabX(px, i), py + Y_TABS, TAB_W, TAB_H)) {
                setAnalysis(ANALYSES[i]);
                return true;
            }
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
        // NOISE sweep-type chips (dec / lin / oct).
        if ("NOISE".equals(analysis)) {
            for (int i = 0; i < NOISE_SWEEPS.length; i++) {
                if (hit(mx, my, px + 16 + i * 31, py + Y_NOISE_ROW2 + 2, 28, 14)) {
                    savedNoiseSweep = NOISE_SWEEPS[i];
                    return true;
                }
            }
        }
        // ngbehavior compat chips
        for (int i = 0; i < NG_MODES.length; i++) {
            if (hit(mx, my, px + 70 + i * 34, py + Y_COMPAT_CHIPS, 30, 14)) {
                setNgBehavior(NG_MODES[i]);
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

    /** Left edge of analysis tab {@code i}; the strip is centred in the panel. */
    private static int tabX(int px, int i) {
        int n = ANALYSES.length;
        int totalW = n * TAB_W + (n - 1) * TAB_GAP;
        int startX = px + (W - totalW) / 2;
        return startX + i * (TAB_W + TAB_GAP);
    }

    /** Index of the active analysis in {@link #ANALYSES}, or -1 if unknown. */
    private int analysisIndex() {
        for (int i = 0; i < ANALYSES.length; i++) {
            if (ANALYSES[i].equals(analysis)) return i;
        }
        return -1;
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

        boolean dc    = "DC".equals(analysis);
        boolean ac    = "AC".equals(analysis);
        boolean tran  = "TRAN".equals(analysis);
        boolean noise = "NOISE".equals(analysis);
        boolean stb   = "STB".equals(analysis);

        // Analysis-type tab strip — one row, constant height however many
        // analyses exist (see ANALYSES). Selected tab is highlighted like the
        // compat chips below; a one-line description of the active analysis
        // keeps the discoverability the old long radio labels provided.
        for (int i = 0; i < ANALYSES.length; i++) {
            int tx = tabX(px, i);
            int ty = py + Y_TABS;
            boolean sel = ANALYSES[i].equals(analysis);
            g.fill(tx, ty, tx + TAB_W, ty + TAB_H, sel ? 0xFF1A4A6A : 0xFF333333);
            g.fill(tx + 1, ty + 1, tx + TAB_W - 1, ty + TAB_H - 1, sel ? 0xFF2A6A9A : 0xFF444444);
            g.drawCenteredString(f, ANALYSES[i], tx + TAB_W / 2, ty + 4, sel ? SEL : DIM);
        }
        int ai = analysisIndex();
        if (ai >= 0) {
            g.drawCenteredString(f, ANALYSIS_DESC[ai], width / 2, py + Y_ANALYSIS_DESC, DIM);
        }

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

        // NOISE sub-panel — column headers plus the sweep-type chips.
        if (noise) {
            g.drawString(f, "Output",    px + 16,  py + Y_NOISE_ROW1 - 10, LABEL);
            g.drawString(f, "Ref (opt)", px + 102, py + Y_NOISE_ROW1 - 10, LABEL);
            g.drawString(f, "Source",    px + 188, py + Y_NOISE_ROW1 - 10, LABEL);
            g.drawString(f, "Pts",    px + 112, py + Y_NOISE_ROW2 - 10, LABEL);
            g.drawString(f, "Fstart", px + 144, py + Y_NOISE_ROW2 - 10, LABEL);
            g.drawString(f, "Fstop",  px + 198, py + Y_NOISE_ROW2 - 10, LABEL);
            g.drawString(f, "Sum",    px + 252, py + Y_NOISE_ROW2 - 10, LABEL);
            for (int i = 0; i < NOISE_SWEEPS.length; i++) {
                int cx = px + 16 + i * 31;
                int cy = py + Y_NOISE_ROW2 + 2;
                boolean sel = NOISE_SWEEPS[i].equals(savedNoiseSweep);
                g.fill(cx, cy, cx + 28, cy + 14, sel ? 0xFF1A4A6A : 0xFF333333);
                g.fill(cx + 1, cy + 1, cx + 27, cy + 13, sel ? 0xFF2A6A9A : 0xFF444444);
                g.drawCenteredString(f, NOISE_SWEEPS[i], cx + 14, cy + 3, sel ? SEL : DIM);
            }
        }

        // AC/TRAN param labels — rendered only when the matching analysis is
        // active. OP and DC leave this region empty (DC uses the same Y for
        // its own labels above).
        if (ac || stb) {
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

        // Library section — only rendered for hsa (.lib) or psa (.INCLUDE).
        // A single label above the multi-line box; one path per line. Other
        // compat modes (none, va) leave the area blank.
        if ("psa".equals(ngBehavior)) {
            g.drawString(f, ".include paths (one per line):", px + 14, py + Y_LIB_LABEL2, LABEL);
        } else if ("hsa".equals(ngBehavior)) {
            g.drawString(f, ".lib paths (one per line):", px + 14, py + Y_LIB_LABEL2, LABEL);
        }
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

        if ("AC".equals(analysis) || "STB".equals(analysis)) {
            // STB uses the same start/stop/pts-per-decade frequency sweep as AC.
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
                savedDcSrc2, savedDcStart2, savedDcStop2, savedDcStep2,
                savedNoiseOut, savedNoiseRef, savedNoiseSrc, savedNoiseSweep,
                savedNoisePts, savedNoiseFstart, savedNoiseFstop, savedNoiseSum,
                savedAc1, savedAc2, savedAc3, savedTran1, savedTran2, savedTran3)
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
