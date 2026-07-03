package com.circuitsim.screen;

import com.circuitsim.network.MeasSignalsPacket;
import com.circuitsim.network.MeasSignalsRequestPacket;
import com.circuitsim.network.MeasTestPacket;
import com.circuitsim.network.ModMessages;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The measurement builder — an LTspice-style {@code .meas} editor adapted to
 * ngspice's control-mode syntax and this mod's Commands block. Opened from
 * {@link CommandsEditScreen}; generated lines are appended to the (unsaved)
 * commands text and carried back on close.
 *
 * <p>Three tabs:
 * <ul>
 *   <li><b>Measure</b> — one {@code meas} statement from a genre form
 *       (Trig→Targ, When, Find, statistics, Deriv — manual ch. 11.4).</li>
 *   <li><b>Functions</b> — the ch. 13.2 expression functions as a clickable
 *       palette, emitting {@code plot NAME = fn(x)} / {@code let} lines.</li>
 *   <li><b>Presets</b> — multi-line recipes (AC report, rise time, …).</li>
 * </ul>
 *
 * Signal fields offer a picker fed by {@link MeasSignalsRequestPacket} (the
 * server resolves probe labels to real net names). <b>Test</b> ships the
 * candidate lines to the server, which reruns the Simulate block's configured
 * analysis with them appended and returns the measured values — LTspice's
 * Test button, but against the live circuit.
 */
public class MeasBuilderScreen extends Screen {

    private static final int MAX_W = 440, MAX_H = 320;

    private static final int C_BG      = 0xF01A1A2E;
    private static final int C_BORDER  = 0xFF4A90D9;
    private static final int C_TITLE   = 0xFFFFD700;
    private static final int C_LABEL   = 0xFFB0B0C8;
    private static final int C_DIM     = 0xFF8888AA;
    private static final int C_PREVIEW = 0xFF9FE49F;
    private static final int C_ERROR   = 0xFFFF6666;
    private static final int C_OK      = 0xFF66FF88;

    private final BlockPos pos;
    /** Working copy of the Commands block text; "Add" appends, "Done" carries it back. */
    private String commandsText;

    private int tab = 0;                       // 0 measure, 1 functions, 2 presets

    // Measure tab
    private int analysisIdx = 0;
    private boolean analysisTouched = false;
    private String measName = "";
    private int genreIdx = 0;
    private final Map<String, String> fieldVals = new HashMap<>();

    // Functions tab
    private int funcIdx = 0;
    private String funcName = "";
    private String funcArg  = "";

    // Presets tab
    private int presetIdx = 0;
    private final Map<String, String> slotVals = new HashMap<>();

    // Signals (from the server)
    private List<String[]> signals = List.of();   // {vector, label}
    private String simAnalysis = "";
    private String signalsError = "";
    private boolean signalsRequested = false;

    // Signal-picker overlay: non-null = the state key the picked vector goes to.
    private String pickerTarget = null;

    // Test round-trip
    private boolean testing = false;
    private List<String> testResults = List.of();

    private String flash = "";

    private int panelX, panelY, panelW, panelH;

    public MeasBuilderScreen(BlockPos pos, String commandsText) {
        super(Component.literal("Measurement Builder"));
        this.pos = pos;
        this.commandsText = commandsText == null ? "" : commandsText;
    }

    // ------------------------------------------------------------------------
    // Packet hooks (called on the client main thread)
    // ------------------------------------------------------------------------

    public static void onSignals(MeasSignalsPacket pkt) {
        if (Minecraft.getInstance().screen instanceof MeasBuilderScreen s) {
            List<String[]> sig = new ArrayList<>();
            for (int i = 0; i < pkt.vectors.size(); i++) {
                sig.add(new String[]{ pkt.vectors.get(i), pkt.labels.get(i) });
            }
            s.signals      = sig;
            s.simAnalysis  = pkt.simAnalysis;
            s.signalsError = pkt.error;
            if (!s.analysisTouched && !pkt.simAnalysis.isEmpty()) {
                String an = pkt.simAnalysis.toLowerCase(Locale.ROOT);
                for (int i = 0; i < MeasCatalog.ANALYSES.length; i++) {
                    if (MeasCatalog.ANALYSES[i].equals(an)) s.analysisIdx = i;
                }
            }
            s.rebuildWidgets();
        }
    }

    public static void onTestResult(List<String> lines) {
        if (Minecraft.getInstance().screen instanceof MeasBuilderScreen s) {
            s.testing = false;
            s.testResults = lines;
            s.rebuildWidgets();
        }
    }

    // ------------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------------

    @Override
    protected void init() {
        super.init();
        panelW = Math.min(MAX_W, width - 8);
        panelH = Math.min(MAX_H, height - 8);
        panelX = (width - panelW) / 2;
        panelY = (height - panelH) / 2;

        if (!signalsRequested) {
            signalsRequested = true;
            ModMessages.sendToServer(new MeasSignalsRequestPacket(pos));
        }

        if (pickerTarget != null) {
            initPickerOverlay();
            return;
        }

        // Tabs (top right)
        String[] tabNames = { "Measure", "Functions", "Presets" };
        int tx = panelX + panelW - 3 * 72 - 6;
        for (int i = 0; i < 3; i++) {
            final int ti = i;
            Button b = Button.builder(Component.literal(tabNames[i]), btn -> {
                        tab = ti;
                        testResults = List.of();
                        flash = "";
                        rebuildWidgets();
                    })
                    .bounds(tx + i * 72, panelY + 4, 70, 16).build();
            b.active = tab != i;
            addRenderableWidget(b);
        }

        switch (tab) {
            case 0 -> initMeasureTab();
            case 1 -> initFunctionsTab();
            case 2 -> initPresetsTab();
        }

        // Action row
        int ay = panelY + panelH - 24;
        int ax = panelX + 8;
        addRenderableWidget(Button.builder(Component.literal("Copy"), b -> {
            Minecraft.getInstance().keyboardHandler.setClipboard(String.join("\n", currentLines()));
            flash = "Copied to clipboard.";
        }).bounds(ax, ay, 56, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Add to Commands"), b -> {
            if (currentError() != null) { flash = ""; return; }
            String joined = String.join("\n", currentLines());
            commandsText = commandsText.isBlank()
                    ? joined
                    : commandsText.stripTrailing() + "\n" + joined;
            flash = "Added (save in the Commands editor).";
        }).bounds(ax + 60, ay, 116, 20).build());

        Button test = Button.builder(Component.literal("Test"), b -> {
            if (currentError() != null) return;
            testing = true;
            testResults = List.of("Testing…");
            ModMessages.sendToServer(new MeasTestPacket(pos, currentLines()));
            rebuildWidgets();
        }).bounds(ax + 180, ay, 52, 20).build();
        test.active = !testing;
        test.setTooltip(Tooltip.create(Component.literal(
                "Runs the Simulate block's analysis with these lines appended "
                + "(uses the saved Commands text) and shows the measured values.")));
        addRenderableWidget(test);

        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                .bounds(panelX + panelW - 60, ay, 52, 20).build());
    }

    // ------------------------------------------------------------------------
    // Measure tab
    // ------------------------------------------------------------------------

    private void initMeasureTab() {
        int cy0 = panelY + 26;

        // Genre list, left column
        List<MeasCatalog.Genre> genres = MeasCatalog.GENRES;
        for (int i = 0; i < genres.size(); i++) {
            final int gi = i;
            Button b = Button.builder(Component.literal(genres.get(i).label()), btn -> {
                        genreIdx = gi;
                        testResults = List.of();
                        rebuildWidgets();
                    })
                    .bounds(panelX + 8, cy0 + i * 16, 96, 14).build();
            b.active = genreIdx != i;
            b.setTooltip(Tooltip.create(Component.literal(genres.get(i).desc())));
            addRenderableWidget(b);
        }

        int rx = panelX + 112;

        // Analysis cycle + result name
        addRenderableWidget(Button.builder(analysisLabel(), btn -> {
                    analysisIdx = (analysisIdx + 1) % MeasCatalog.ANALYSES.length;
                    analysisTouched = true;
                    btn.setMessage(analysisLabel());
                }).bounds(rx, cy0, 72, 14).build());

        EditBox nameBox = new EditBox(font, rx + 108, cy0, panelX + panelW - 8 - (rx + 108), 14,
                Component.literal("name"));
        nameBox.setMaxLength(64);
        nameBox.setValue(measName);
        nameBox.setResponder(v -> measName = v);
        addRenderableWidget(nameBox);

        // Genre fields, two columns, col-major
        MeasCatalog.Genre g = genres.get(genreIdx);
        int rows = (g.fields().size() + 1) / 2;
        int colW = (panelW - 112 - 8) / 2;
        int fy0 = cy0 + 20;
        for (int i = 0; i < g.fields().size(); i++) {
            MeasCatalog.Field f = g.fields().get(i);
            int col = i / rows, row = i % rows;
            int fx = rx + col * colW + 58;
            int fy = fy0 + row * 16;
            fieldVals.computeIfAbsent(f.key(), k -> f.def());
            addFieldWidget(f.key(), f.kind(), f.hint(), fx, fy, colW - 62);
        }
    }

    private Component analysisLabel() {
        return Component.literal("meas " + MeasCatalog.ANALYSES[analysisIdx].toUpperCase(Locale.ROOT));
    }

    /** One editable field: EDGE/STAT get a cycle button, the rest an EditBox (+ signal picker). */
    private void addFieldWidget(String key, MeasCatalog.FieldKind kind, String hint,
                                int x, int y, int w) {
        switch (kind) {
            case EDGE, STAT -> {
                String[] options = kind == MeasCatalog.FieldKind.EDGE
                        ? MeasCatalog.EDGE_OPTIONS : MeasCatalog.STAT_OPTIONS;
                addRenderableWidget(Button.builder(
                        Component.literal(fieldVals.getOrDefault(key, options[0])), btn -> {
                            String cur = fieldVals.getOrDefault(key, options[0]);
                            int idx = 0;
                            for (int i = 0; i < options.length; i++) {
                                if (options[i].equals(cur)) idx = i;
                            }
                            fieldVals.put(key, options[(idx + 1) % options.length]);
                            btn.setMessage(Component.literal(fieldVals.get(key)));
                        }).bounds(x, y, w, 14).build());
            }
            case SIGNAL -> {
                EditBox box = new EditBox(font, x, y, w - 16, 14, Component.literal(key));
                box.setMaxLength(128);
                box.setValue(fieldVals.getOrDefault(key, ""));
                box.setResponder(v -> fieldVals.put(key, v));
                addRenderableWidget(box);
                addRenderableWidget(Button.builder(Component.literal("▾"), btn -> {
                            pickerTarget = key;
                            rebuildWidgets();
                        }).bounds(x + w - 14, y, 14, 14).build());
            }
            case TEXT -> {
                EditBox box = new EditBox(font, x, y, w, 14, Component.literal(key));
                box.setMaxLength(128);
                box.setValue(fieldVals.getOrDefault(key, ""));
                box.setResponder(v -> fieldVals.put(key, v));
                if (!hint.isEmpty()) box.setTooltip(Tooltip.create(Component.literal(hint)));
                addRenderableWidget(box);
            }
        }
    }

    // ------------------------------------------------------------------------
    // Functions tab
    // ------------------------------------------------------------------------

    private void initFunctionsTab() {
        int cy0 = panelY + 26;
        List<MeasCatalog.Func> fns = MeasCatalog.FUNCTIONS;

        int cols = Math.max(3, (panelW - 16 + 2) / 64);
        int gridRows = (fns.size() + cols - 1) / cols;
        for (int i = 0; i < fns.size(); i++) {
            final int fi = i;
            Button b = Button.builder(Component.literal(fns.get(i).name()), btn -> {
                        funcIdx = fi;
                        testResults = List.of();
                        rebuildWidgets();
                    })
                    .bounds(panelX + 8 + (i % cols) * 64, cy0 + (i / cols) * 14, 62, 13).build();
            b.active = funcIdx != i;
            b.setTooltip(Tooltip.create(Component.literal(fns.get(i).doc())));
            addRenderableWidget(b);
        }

        // Constants: append to the argument expression
        int constY = cy0 + gridRows * 14 + 4;
        for (int i = 0; i < MeasCatalog.CONSTANTS.length; i++) {
            String[] c = MeasCatalog.CONSTANTS[i];
            Button b = Button.builder(Component.literal(c[0]), btn -> {
                        funcArg = funcArg + c[0];
                        rebuildWidgets();
                    })
                    .bounds(panelX + 8 + i * 58, constY, 56, 12).build();
            b.setTooltip(Tooltip.create(Component.literal(c[1])));
            addRenderableWidget(b);
        }

        // name = fn( arg ▾ )
        int fy = constY + 16;
        EditBox nameBox = new EditBox(font, panelX + 8 + 34, fy, 90, 14, Component.literal("name"));
        nameBox.setMaxLength(64);
        nameBox.setValue(funcName);
        nameBox.setResponder(v -> funcName = v);
        addRenderableWidget(nameBox);

        int argX = panelX + 8 + 34 + 94 + font.width(" = " + fns.get(funcIdx).name() + "(") + 4;
        int argW = panelX + panelW - 8 - argX - font.width(")") - 4;
        EditBox argBox = new EditBox(font, argX, fy, argW - 16, 14, Component.literal("arg"));
        argBox.setMaxLength(256);
        argBox.setValue(funcArg);
        argBox.setResponder(v -> funcArg = v);
        addRenderableWidget(argBox);
        addRenderableWidget(Button.builder(Component.literal("▾"), btn -> {
                    pickerTarget = "@funcArg";
                    rebuildWidgets();
                }).bounds(argX + argW - 14, fy, 14, 14).build());
    }

    // ------------------------------------------------------------------------
    // Presets tab
    // ------------------------------------------------------------------------

    private void initPresetsTab() {
        int cy0 = panelY + 26;
        List<MeasCatalog.Preset> presets = MeasCatalog.PRESETS;
        for (int i = 0; i < presets.size(); i++) {
            final int pi = i;
            Button b = Button.builder(Component.literal(presets.get(i).label()), btn -> {
                        presetIdx = pi;
                        testResults = List.of();
                        rebuildWidgets();
                    })
                    .bounds(panelX + 8, cy0 + i * 16, 110, 14).build();
            b.active = presetIdx != i;
            b.setTooltip(Tooltip.create(Component.literal(presets.get(i).desc())));
            addRenderableWidget(b);
        }

        MeasCatalog.Preset p = presets.get(presetIdx);
        int rx = panelX + 126;
        int fy0 = cy0 + 26;    // description line rendered above
        for (int i = 0; i < p.slots().size(); i++) {
            MeasCatalog.Slot s = p.slots().get(i);
            String key = slotKey(p, s);
            slotVals.computeIfAbsent(key, k -> s.def());
            int fy = fy0 + i * 16;
            int w = panelX + panelW - 8 - (rx + 92);
            if (s.kind() == MeasCatalog.FieldKind.SIGNAL) {
                EditBox box = new EditBox(font, rx + 92, fy, w - 16, 14, Component.literal(s.key()));
                box.setMaxLength(128);
                box.setValue(slotVals.getOrDefault(key, ""));
                box.setResponder(v -> slotVals.put(key, v));
                addRenderableWidget(box);
                addRenderableWidget(Button.builder(Component.literal("▾"), btn -> {
                            pickerTarget = "#" + key;
                            rebuildWidgets();
                        }).bounds(rx + 92 + w - 14, fy, 14, 14).build());
            } else {
                EditBox box = new EditBox(font, rx + 92, fy, w, 14, Component.literal(s.key()));
                box.setMaxLength(128);
                box.setValue(slotVals.getOrDefault(key, ""));
                box.setResponder(v -> slotVals.put(key, v));
                if (!s.hint().isEmpty()) box.setTooltip(Tooltip.create(Component.literal(s.hint())));
                addRenderableWidget(box);
            }
        }
    }

    private static String slotKey(MeasCatalog.Preset p, MeasCatalog.Slot s) {
        return p.id() + "." + s.key();
    }

    // ------------------------------------------------------------------------
    // Signal-picker overlay
    // ------------------------------------------------------------------------

    private void initPickerOverlay() {
        int cy0 = panelY + 26;
        int cols = Math.max(2, (panelW - 16 + 4) / 134);
        int shown = Math.min(signals.size(), 5 * cols);
        for (int i = 0; i < shown; i++) {
            String[] sig = signals.get(i);
            Button b = Button.builder(Component.literal(sig[0]), btn -> pickSignal(sig[0]))
                    .bounds(panelX + 8 + (i % cols) * 134, cy0 + (i / cols) * 15, 130, 14).build();
            b.setTooltip(Tooltip.create(Component.literal(sig[1])));
            addRenderableWidget(b);
        }
        addRenderableWidget(Button.builder(Component.literal("Cancel"), btn -> {
                    pickerTarget = null;
                    rebuildWidgets();
                }).bounds(panelX + (panelW - 100) / 2, panelY + panelH - 26, 100, 18).build());
    }

    private void pickSignal(String vector) {
        String target = pickerTarget;
        pickerTarget = null;
        if (target == null) return;
        if (target.equals("@funcArg")) {
            funcArg = vector;
        } else if (target.startsWith("#")) {
            slotVals.put(target.substring(1), vector);
        } else {
            fieldVals.put(target, vector);
        }
        rebuildWidgets();
    }

    // ------------------------------------------------------------------------
    // Generation
    // ------------------------------------------------------------------------

    private List<String> currentLines() {
        switch (tab) {
            case 0 -> {
                MeasCatalog.Genre g = MeasCatalog.GENRES.get(genreIdx);
                String name = measName.isBlank() ? "?" : measName.trim();
                return List.of(MeasCatalog.measLine(
                        MeasCatalog.ANALYSES[analysisIdx], name, g, fieldVals));
            }
            case 1 -> {
                MeasCatalog.Func fn = MeasCatalog.FUNCTIONS.get(funcIdx);
                String name = funcName.isBlank() ? "?" : funcName.trim();
                return MeasCatalog.functionLines(fn, name, funcArg.isBlank() ? "?" : funcArg);
            }
            default -> {
                MeasCatalog.Preset p = MeasCatalog.PRESETS.get(presetIdx);
                Map<String, String> vals = new HashMap<>();
                for (MeasCatalog.Slot s : p.slots()) {
                    vals.put(s.key(), slotVals.getOrDefault(slotKey(p, s), ""));
                }
                return MeasCatalog.presetLines(p, vals);
            }
        }
    }

    private String currentError() {
        switch (tab) {
            case 0 -> {
                return MeasCatalog.validate(measName,
                        MeasCatalog.GENRES.get(genreIdx), fieldVals);
            }
            case 1 -> {
                return MeasCatalog.validateLet(funcName, funcArg);
            }
            default -> {
                MeasCatalog.Preset p = MeasCatalog.PRESETS.get(presetIdx);
                Map<String, String> vals = new HashMap<>();
                for (MeasCatalog.Slot s : p.slots()) {
                    vals.put(s.key(), slotVals.getOrDefault(slotKey(p, s), ""));
                }
                return MeasCatalog.validatePreset(p, vals);
            }
        }
    }

    // ------------------------------------------------------------------------
    // Render
    // ------------------------------------------------------------------------

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        renderBackground(g);
        g.fill(panelX, panelY, panelX + panelW, panelY + panelH, C_BG);
        g.fill(panelX, panelY, panelX + panelW, panelY + 1, C_BORDER);
        g.fill(panelX, panelY + panelH - 1, panelX + panelW, panelY + panelH, C_BORDER);
        g.fill(panelX, panelY, panelX + 1, panelY + panelH, C_BORDER);
        g.fill(panelX + panelW - 1, panelY, panelX + panelW, panelY + panelH, C_BORDER);

        g.drawString(font, pickerTarget != null ? "Pick a signal" : "Measurement Builder",
                panelX + 8, panelY + 8, C_TITLE, false);

        if (pickerTarget != null) {
            if (signals.isEmpty()) {
                String msg = signalsError.isEmpty()
                        ? "No signals found — is the circuit connected to this Commands block?"
                        : signalsError;
                for (var line : font.split(Component.literal(msg), panelW - 16)) {
                    g.drawString(font, line, panelX + 8, panelY + 30, C_DIM, false);
                }
            }
            super.render(g, mx, my, pt);
            return;
        }

        switch (tab) {
            case 0 -> renderMeasureLabels(g);
            case 1 -> renderFunctionLabels(g);
            case 2 -> renderPresetLabels(g);
        }

        // Preview + status, above the action row
        int py = panelY + panelH - 62;
        g.fill(panelX + 4, py - 2, panelX + panelW - 4, py - 1, 0xFF444466);
        List<String> lines = currentLines();
        String preview = String.join("  ⏎  ", lines);
        var wrapped = font.split(Component.literal(preview), panelW - 16);
        for (int i = 0; i < Math.min(2, wrapped.size()); i++) {
            g.drawString(font, wrapped.get(i), panelX + 8, py + i * 10, C_PREVIEW, false);
        }

        String err = currentError();
        int sy = py + 22;
        if (err != null) {
            g.drawString(font, err, panelX + 8, sy, C_ERROR, false);
        } else if (!flash.isEmpty()) {
            g.drawString(font, flash, panelX + 8, sy, C_OK, false);
        } else if (!simAnalysis.isEmpty()
                && !simAnalysis.equalsIgnoreCase(MeasCatalog.ANALYSES[analysisIdx])
                && tab == 0) {
            g.drawString(font, "Note: the Simulate block is set to " + simAnalysis + ".",
                    panelX + 8, sy, C_DIM, false);
        }

        super.render(g, mx, my, pt);

        // Test results float above everything; click anywhere to dismiss.
        if (!testResults.isEmpty()) {
            int th = testResults.size() * 10 + 14;
            int ty = panelY + Math.max(26, (panelH - th) / 2);
            g.fill(panelX + 20, ty, panelX + panelW - 20, ty + th, 0xF8101020);
            g.fill(panelX + 20, ty, panelX + panelW - 20, ty + 1, C_BORDER);
            g.fill(panelX + 20, ty + th - 1, panelX + panelW - 20, ty + th, C_BORDER);
            for (int i = 0; i < testResults.size(); i++) {
                g.drawString(font, font.substrByWidth(
                                net.minecraft.network.chat.FormattedText.of(testResults.get(i)),
                                panelW - 56).getString(),
                        panelX + 28, ty + 8 + i * 10, 0xFFE8E8FF, false);
            }
            if (!testing) {
                g.drawString(font, "(click to dismiss)", panelX + 28, ty + th - 9, C_DIM, false);
            }
        }
    }

    private void renderMeasureLabels(GuiGraphics g) {
        int cy0 = panelY + 26;
        int rx = panelX + 112;
        g.drawString(font, "Name:", rx + 76, cy0 + 3, C_LABEL, false);

        MeasCatalog.Genre gen = MeasCatalog.GENRES.get(genreIdx);
        int rows = (gen.fields().size() + 1) / 2;
        int colW = (panelW - 112 - 8) / 2;
        for (int i = 0; i < gen.fields().size(); i++) {
            MeasCatalog.Field f = gen.fields().get(i);
            int col = i / rows, row = i % rows;
            g.drawString(font, f.label() + ":", rx + col * colW,
                    cy0 + 20 + row * 16 + 3, C_LABEL, false);
        }
    }

    private void renderFunctionLabels(GuiGraphics g) {
        List<MeasCatalog.Func> fns = MeasCatalog.FUNCTIONS;
        int cols = Math.max(3, (panelW - 16 + 2) / 64);
        int gridRows = (fns.size() + cols - 1) / cols;
        int fy = panelY + 26 + gridRows * 14 + 4 + 16;
        g.drawString(font, "let", panelX + 8 + 12, fy + 3, C_LABEL, false);
        MeasCatalog.Func fn = fns.get(funcIdx);
        int eqX = panelX + 8 + 34 + 94;
        g.drawString(font, " = " + fn.name() + "(", eqX, fy + 3, C_LABEL, false);
        g.drawString(font, ")", panelX + panelW - 8 - font.width(")") - 2, fy + 3, C_LABEL, false);
        g.drawString(font, fn.name() + " — " + fn.doc(), panelX + 8, fy + 18, C_DIM, false);
    }

    private void renderPresetLabels(GuiGraphics g) {
        MeasCatalog.Preset p = MeasCatalog.PRESETS.get(presetIdx);
        int rx = panelX + 126;
        int cy0 = panelY + 26;
        var desc = font.split(Component.literal(
                p.desc() + "  (" + p.analysis().toUpperCase(Locale.ROOT) + ")"),
                panelW - (rx - panelX) - 8);
        for (int i = 0; i < Math.min(2, desc.size()); i++) {
            g.drawString(font, desc.get(i), rx, cy0 + i * 10, C_DIM, false);
        }
        for (int i = 0; i < p.slots().size(); i++) {
            g.drawString(font, p.slots().get(i).label() + ":",
                    rx, cy0 + 26 + i * 16 + 3, C_LABEL, false);
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (!testResults.isEmpty() && !testing) {
            testResults = List.of();
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(new CommandsEditScreen(pos, commandsText));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
