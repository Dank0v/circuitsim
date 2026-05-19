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
    private String ngBehavior = "hsa";
    // Single-line lib path used by non-psa modes (sky130A flow).
    private String pdkLibPath  = "";
    // Newline-separated lib paths used by psa mode (one .INCLUDE per line).
    private String pdkLibPaths = "";
    // Cached param values so widgets survive a rebuild on compat-mode switch.
    private String savedParam1 = "10";
    private String savedParam2 = "1Meg";
    private String savedParam3 = "10";
    private String savedTemp   = "27";
    // Set after the first init() so subsequent rebuilds preserve in-flight edits
    // instead of re-reading from the block entity.
    private boolean loadedFromBe = false;

    private EditBox pdkLibField;
    private MultiLineEditBox pdkLibPathsField;

    private static final String[] NG_MODES = {"hsa", "psa", "lt", "ki", "va"};

    private EditBox param1Field;
    private EditBox param2Field;
    private EditBox param3Field;
    private EditBox tempField;

    private static final int W = 290,
        H = 388;

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
    private static final int Y_ANALYSIS_AC    = 63;
    private static final int Y_ANALYSIS_TRAN  = 83;
    private static final int Y_DIV_AFTER_ANALYSIS = 102;
    // Params section
    private static final int Y_PARAM1     = 108;
    private static final int Y_PARAM2     = 131;
    private static final int Y_PARAM3     = 154;
    private static final int Y_PARAM_TEMP = 177;       // temperature override (single value or sweep spec)
    private static final int Y_DIV_AFTER_PARAMS = 197;
    // Compat section
    private static final int Y_COMPAT_LABEL = 204;
    private static final int Y_COMPAT_CHIPS = 218;
    private static final int Y_DIV_AFTER_COMPAT = 241;
    // PDK/lib section (depends on mode)
    private static final int Y_PDK_LABEL  = 248;       // "PDK:" or ".lib paths:"
    private static final int Y_PDK_CHIPS  = 262;       // PDK radio row (non-psa only)
    private static final int Y_LIB_LABEL  = 280;       // ".lib path:" (non-psa only)
    private static final int Y_LIB_FIELD  = 293;       // single-line field (non-psa)
    // psa multi-line field spans the entire mode-dependent slot:
    private static final int Y_PSA_FIELD       = 262;
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
        boolean ac   = "AC".equals(analysis);
        boolean tran = "TRAN".equals(analysis);

        param1Field.setEditable(ac || tran);
        param1Field.setTextColor((ac || tran) ? 0xFFFFFFFF : DIM);

        param2Field.setEditable(ac || tran);
        param2Field.setTextColor((ac || tran) ? 0xFFFFFFFF : DIM);

        param3Field.setEditable(ac);
        param3Field.setTextColor(ac ? 0xFFFFFFFF : DIM);

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
        analysis = newAnalysis;
        switch (newAnalysis) {
            case "AC" -> {
                param1Field.setValue("10");
                param2Field.setValue("1Meg");
                param3Field.setValue("10");
            }
            case "TRAN" -> {
                param1Field.setValue("1u");
                param2Field.setValue("10m");
                param3Field.setValue("");
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
        if (hit(mx, my, px + 14, py + Y_ANALYSIS_AC,   W - 20, 16)) {
            setAnalysis("AC");
            return true;
        }
        if (hit(mx, my, px + 14, py + Y_ANALYSIS_TRAN, W - 20, 16)) {
            setAnalysis("TRAN");
            return true;
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

        boolean op = "OP".equals(analysis);
        boolean ac = "AC".equals(analysis);
        boolean tran = "TRAN".equals(analysis);

        radio(g, px + 16, py + Y_ANALYSIS_OP + 2, op);
        g.drawString(f, ".OP  - DC Operating Point", px + 30, py + Y_ANALYSIS_OP + 1, op ? SEL : DIM);

        radio(g, px + 16, py + Y_ANALYSIS_AC + 2, ac);
        g.drawString(f, ".AC  - Frequency Sweep", px + 30, py + Y_ANALYSIS_AC + 1, ac ? SEL : DIM);

        radio(g, px + 16, py + Y_ANALYSIS_TRAN + 2, tran);
        g.drawString(f, ".TRAN - Transient Analysis", px + 30, py + Y_ANALYSIS_TRAN + 1, tran ? SEL : DIM);

        if (ac) {
            g.drawString(f, "Start Freq (Hz):", px + 16, py + Y_PARAM1 + 3, LABEL);
            g.drawString(f, "Stop Freq  (Hz):", px + 16, py + Y_PARAM2 + 3, LABEL);
            g.drawString(f, "Pts / Decade:",   px + 16, py + Y_PARAM3 + 3, LABEL);
        } else if (tran) {
            g.drawString(f, "Time Step:", px + 16, py + Y_PARAM1 + 3, LABEL);
            g.drawString(f, "Stop Time:", px + 16, py + Y_PARAM2 + 3, LABEL);
            g.drawString(f, "Pts / Dec:", px + 16, py + Y_PARAM3 + 3, DIM);
        } else {
            g.drawString(f, "Start Freq (Hz):", px + 16, py + Y_PARAM1 + 3, DIM);
            g.drawString(f, "Stop Freq  (Hz):", px + 16, py + Y_PARAM2 + 3, DIM);
            g.drawString(f, "Pts / Decade:",   px + 16, py + Y_PARAM3 + 3, DIM);
        }

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
            // The MultiLineEditBox is a widget; super.render drew it.
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
        ModMessages.sendToServer(
            new SimulatePacket(pos, analysis, p1, p2, p3, pdkName, pdkLibPath, pdkLibPaths,
                ngBehavior,
                param1Field.getValue().trim(), param2Field.getValue().trim(), param3Field.getValue().trim(),
                tempValue)
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
