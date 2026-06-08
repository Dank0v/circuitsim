package com.circuitsim.network;

import com.circuitsim.block.*;
import com.circuitsim.blockentity.ComponentBlockEntity;
import com.circuitsim.screen.ComponentEditScreen;
import com.circuitsim.simulation.CircuitExtractor;
import com.circuitsim.simulation.NetlistBuilder;
import com.circuitsim.simulation.NgSpiceRunner;
import com.circuitsim.simulation.ParametricResultCache;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.network.NetworkEvent;

public class SimulatePacket {

    private final BlockPos pos;
    private final String analysis; // "OP", "DC", "AC", or "TRAN"
    private final double fStart; // AC: fStart  |  TRAN: tstep
    private final double fStop; // AC: fStop   |  TRAN: tstop
    private final int ptsPerDec; // AC: pts/dec |  TRAN: unused (0)
    private final String pdkName; // "none", "sky130A", "placeholder"
    private final String pdkLibPath; // path to .lib file (hsa-style)
    private final String pdkLibPaths; // newline-separated .INCLUDE paths (psa-style)
    private final String ngBehavior; // ngspice compat mode: hsa, psa, lt, ki, va
    private final String rawParam1; // raw UI strings preserved for round-trip display
    private final String rawParam2;
    private final String rawParam3;
    /**
     * Either a single value ("27") which sets the circuit temperature for
     * one run, or a sweep spec ("20:40:5" / "20,30,40") which triggers a
     * multi-run pass. For OP the result is a 1D probe-vs-temperature plot;
     * for AC/TRAN each temperature contributes one extra curve per probe
     * (series name suffixed "@<T>C") overlaid on the analysis's natural
     * axis (frequency / time).
     */
    private final String simTemp;
    // .DC analysis config. dcSource1 is the SPICE source name to sweep
    // (e.g. "V1"); start/stop/step are parsed via parseSI at sim time.
    // dc2D enables an outer sweep on dcSource2.
    private final String  dcSource1;
    private final String  dcStart1;
    private final String  dcStop1;
    private final String  dcStep1;
    private final boolean dc2D;
    private final String  dcSource2;
    private final String  dcStart2;
    private final String  dcStop2;
    private final String  dcStep2;

    public SimulatePacket(
        BlockPos pos,
        String analysis,
        double fStart,
        double fStop,
        int ptsPerDec,
        String pdkName,
        String pdkLibPath,
        String pdkLibPaths,
        String ngBehavior,
        String rawParam1,
        String rawParam2,
        String rawParam3,
        String simTemp
    ) {
        this(pos, analysis, fStart, fStop, ptsPerDec, pdkName, pdkLibPath, pdkLibPaths,
                ngBehavior, rawParam1, rawParam2, rawParam3, simTemp,
                "V1", "0", "5", "0.1", false, "", "0", "1", "0.25");
    }

    public SimulatePacket(
        BlockPos pos,
        String analysis,
        double fStart,
        double fStop,
        int ptsPerDec,
        String pdkName,
        String pdkLibPath,
        String pdkLibPaths,
        String ngBehavior,
        String rawParam1,
        String rawParam2,
        String rawParam3,
        String simTemp,
        String dcSource1,
        String dcStart1,
        String dcStop1,
        String dcStep1,
        boolean dc2D,
        String dcSource2,
        String dcStart2,
        String dcStop2,
        String dcStep2
    ) {
        this.pos = pos;
        this.analysis = analysis;
        this.fStart = fStart;
        this.fStop = fStop;
        this.ptsPerDec = ptsPerDec;
        this.pdkName = pdkName;
        this.pdkLibPath = pdkLibPath;
        this.pdkLibPaths = pdkLibPaths == null ? "" : pdkLibPaths;
        this.ngBehavior = ngBehavior;
        this.rawParam1 = rawParam1;
        this.rawParam2 = rawParam2;
        this.rawParam3 = rawParam3;
        this.simTemp = simTemp == null ? "27" : simTemp;
        this.dcSource1 = dcSource1 == null ? ""  : dcSource1;
        this.dcStart1  = dcStart1  == null ? "0" : dcStart1;
        this.dcStop1   = dcStop1   == null ? "0" : dcStop1;
        this.dcStep1   = dcStep1   == null ? "0" : dcStep1;
        this.dc2D      = dc2D;
        this.dcSource2 = dcSource2 == null ? ""  : dcSource2;
        this.dcStart2  = dcStart2  == null ? "0" : dcStart2;
        this.dcStop2   = dcStop2   == null ? "0" : dcStop2;
        this.dcStep2   = dcStep2   == null ? "0" : dcStep2;
    }

    public SimulatePacket(FriendlyByteBuf buf) {
        this.pos = buf.readBlockPos();
        this.analysis = buf.readUtf(8);
        this.fStart = buf.readDouble();
        this.fStop = buf.readDouble();
        this.ptsPerDec = buf.readInt();
        this.pdkName = buf.readUtf(32);
        this.pdkLibPath = buf.readUtf(512);
        this.pdkLibPaths = buf.readUtf(8192);
        this.ngBehavior = buf.readUtf(8);
        this.rawParam1 = buf.readUtf(32);
        this.rawParam2 = buf.readUtf(32);
        this.rawParam3 = buf.readUtf(32);
        this.simTemp = buf.readUtf(64);
        this.dcSource1 = buf.readUtf(32);
        this.dcStart1  = buf.readUtf(32);
        this.dcStop1   = buf.readUtf(32);
        this.dcStep1   = buf.readUtf(32);
        this.dc2D      = buf.readBoolean();
        this.dcSource2 = buf.readUtf(32);
        this.dcStart2  = buf.readUtf(32);
        this.dcStop2   = buf.readUtf(32);
        this.dcStep2   = buf.readUtf(32);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeUtf(analysis, 8);
        buf.writeDouble(fStart);
        buf.writeDouble(fStop);
        buf.writeInt(ptsPerDec);
        buf.writeUtf(pdkName, 32);
        buf.writeUtf(pdkLibPath, 512);
        buf.writeUtf(pdkLibPaths, 8192);
        buf.writeUtf(ngBehavior, 8);
        buf.writeUtf(rawParam1, 32);
        buf.writeUtf(rawParam2, 32);
        buf.writeUtf(rawParam3, 32);
        buf.writeUtf(simTemp, 64);
        buf.writeUtf(dcSource1, 32);
        buf.writeUtf(dcStart1,  32);
        buf.writeUtf(dcStop1,   32);
        buf.writeUtf(dcStep1,   32);
        buf.writeBoolean(dc2D);
        buf.writeUtf(dcSource2, 32);
        buf.writeUtf(dcStart2,  32);
        buf.writeUtf(dcStop2,   32);
        buf.writeUtf(dcStep2,   32);
    }

    public static SimulatePacket decode(FriendlyByteBuf buf) {
        return new SimulatePacket(buf);
    }

    public void handle(NetworkEvent.Context ctx) {
        ServerPlayer player = ctx.getSender();
        if (player == null) return;
        Level level = player.level();
        if (!level.isLoaded(pos)) return;

        // Persist PDK settings in the simulate block entity
        net.minecraft.world.level.block.entity.BlockEntity simBe =
            level.getBlockEntity(pos);
        if (simBe instanceof ComponentBlockEntity simCbe) {
            simCbe.setPdkName(pdkName);
            simCbe.setPdkLibPath(pdkLibPath);
            simCbe.setPdkLibPaths(pdkLibPaths);
            simCbe.setNgBehavior(ngBehavior);
            simCbe.setSimAnalysis(analysis);
            simCbe.setSimParam1(rawParam1);
            simCbe.setSimParam2(rawParam2);
            simCbe.setSimParam3(rawParam3);
            simCbe.setSimTemp(simTemp);
            simCbe.setDcSource1(dcSource1);
            simCbe.setDcStart1(dcStart1);
            simCbe.setDcStop1(dcStop1);
            simCbe.setDcStep1(dcStep1);
            simCbe.setDc2D(dc2D);
            simCbe.setDcSource2(dcSource2);
            simCbe.setDcStart2(dcStart2);
            simCbe.setDcStop2(dcStop2);
            simCbe.setDcStep2(dcStep2);
            simCbe.setChanged();
            simCbe.syncToClient();
        }

        msg(
            player,
            "=== Circuit Simulation (" + analysis + ") ===",
            ChatFormatting.GOLD
        );

        // Extraction must happen on the main thread (Level reads aren't safe
        // off-thread). Everything after this — netlist building, the ngspice
        // subprocess, and result parsing — runs on a background worker so the
        // server thread stays responsive (otherwise sky130 sweeps blow past the
        // 60s watchdog and trigger "Can't keep up" ticks).
        CircuitExtractor.ExtractionResult extraction = CircuitExtractor.extract(
            level,
            pos
        );
        if (!extraction.success) {
            msg(
                player,
                "Error: " + extraction.errorMessage,
                ChatFormatting.RED
            );
            return;
        }

        final MinecraftServer server = player.getServer();
        final String timestamp = LocalDateTime.now().format(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        );

        Thread worker = new Thread(
            () -> runSimulationOffThread(player, server, extraction, timestamp),
            "CircuitSim-Sim"
        );
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Body of the simulation. Runs on a dedicated worker thread. Anything that
     * touches player state (chat, inventory, network) hops back to the main
     * thread via {@code msg(...)} (already thread-safe) or
     * {@code server.execute(...)} for the final book / output-screen handoff.
     */
    private void runSimulationOffThread(
        ServerPlayer player,
        MinecraftServer server,
        CircuitExtractor.ExtractionResult extraction,
        String timestamp
    ) {
        try {
            List<String> bookLines = new ArrayList<>();
            List<Component> graphPageComponents = new ArrayList<>();

            bookLines.add("CircuitSim Results (" + analysis + ")");
            bookLines.add(timestamp);
            bookLines.add("---");

            // Parse the temperature override. A single value sets the circuit
            // temperature for one run; a sweep spec ("20:40:5" / "20,30,40")
            // triggers a multi-run pass — see the dispatch below.
            List<Double> tempValues = parseTempValues(simTemp);

            // ── Parametric block resolution ─────────────────────────────────
            // Each Parametric block declares a variable name + a values spec.
            // - 1 value  → "constant define" (substitute everywhere matching)
            // - >1 values → "sweep" (only one sweep allowed per simulation)
            // Every declared variable must be referenced by at least one
            // component, otherwise it's an authoring mistake.
            CircuitExtractor.ParametricInfo sweepParam = null;
            List<Double> sweepValues = Collections.emptyList();
            if (!extraction.parametricBlocks.isEmpty()) {
                ApplyResult applied = applyParametricConstants(
                    player, extraction);
                if (applied == null) return;        // error already reported
                extraction = applied.extraction;
                sweepParam = applied.sweepParam;
                sweepValues = applied.sweepValues;
            } else if (!checkAllVariablesDefined(player, extraction,
                    Collections.emptySet())) {
                return;       // a component references a variable with no Parametric block
            }

            if (sweepParam != null) {
                runParametricSweep(
                    player,
                    extraction,
                    sweepParam,
                    sweepValues,
                    tempValues,
                    bookLines,
                    graphPageComponents
                );
            } else if ("DC".equals(analysis)) {
                double tempC = tempValues.isEmpty() ? 27.0 : tempValues.get(0);
                runDcSimulation(player, extraction, bookLines, graphPageComponents, tempC);
            } else if (tempValues.size() > 1 && "OP".equals(analysis)) {
                // OP + multi-temp produces a 1D probe-vs-temperature plot.
                runTempSweep(player, extraction, tempValues, bookLines, graphPageComponents);
            } else if (tempValues.size() > 1) {
                // AC / TRAN with multi-temp: overlay one curve per temperature.
                switch (analysis) {
                    case "AC"   -> runMultiTempAcSweep(player, extraction, tempValues, bookLines, graphPageComponents);
                    case "TRAN" -> runMultiTempTranSweep(player, extraction, tempValues, bookLines, graphPageComponents);
                    default     -> runTempSweep(player, extraction, tempValues, bookLines, graphPageComponents);
                }
            } else {
                double tempC = tempValues.isEmpty() ? 27.0 : tempValues.get(0);
                switch (analysis) {
                    case "AC" -> runAcSimulation(
                        player,
                        extraction,
                        bookLines,
                        graphPageComponents,
                        tempC
                    );
                    case "TRAN" -> runTranSimulation(
                        player,
                        extraction,
                        bookLines,
                        graphPageComponents,
                        tempC
                    );
                    default -> runOpSimulation(player, extraction, bookLines, tempC);
                }
            }

            // Inventory mutation must occur on the main server thread.
            // Auto-opening the output viewer is no longer done here — every
            // sim path emits its own clickable "[Open output viewer]" chat
            // link via emitOutputViewerLink so the player decides when to
            // open the screen.
            if (server != null) {
                server.execute(() -> {
                    try {
                        giveResultBook(
                            player,
                            analysis + " " + timestamp,
                            bookLines,
                            graphPageComponents
                        );
                    } catch (Throwable t) {
                        com.circuitsim.CircuitSimMod.LOGGER.error(
                                "giveResultBook failed", t);
                        msg(player, "Result book failed: " + t.getClass().getSimpleName()
                                + " — " + (t.getMessage() == null ? "" : t.getMessage()),
                                ChatFormatting.RED);
                    }
                });
            }
        } catch (Throwable t) {
            String detail = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
            msg(player, "Simulation crashed: " + detail, ChatFormatting.RED);
        }
    }

    // -------------------------------------------------------------------------
    // .OP
    // -------------------------------------------------------------------------

    private void runOpSimulation(
        ServerPlayer player,
        CircuitExtractor.ExtractionResult extraction,
        List<String> bookLines,
        double tempC
    ) {
        String netlist = NetlistBuilder.buildNetlist(
            extraction.components,
            extraction.probes,
            extraction.currentProbes,
            pdkName,
            pdkLibPath,
            pdkLibPaths,
            ngBehavior,
            extraction.userCommands,
            extraction.userPlots
        );
        netlist = injectTemp(netlist, tempC);
        printNetlist(player, netlist, bookLines);

        NgSpiceRunner.Result result = NgSpiceRunner.run(netlist, ngBehavior);
        if (result.error != null) {
            msg(
                player,
                "Simulation Error: " + result.error,
                ChatFormatting.RED
            );
            bookLines.add("Error: " + result.error);
            return;
        }

        msg(player, "--- Results ---", ChatFormatting.GREEN);
        bookLines.add("=== Results ===");
        for (String line : result.output) {
            msg(player, line, ChatFormatting.GREEN);
            bookLines.add(line.trim());
        }
        for (String extra : result.extras) {
            String line = "  " + extra;
            msg(player, line, ChatFormatting.AQUA);
            bookLines.add(line);
        }
        for (NetlistBuilder.ProbeInfo probe : plotted(extraction.probes)) {
            String line =
                "Probe [" +
                probe.label +
                "] Node " +
                probe.netName +
                ": " +
                result.getNodeVoltage(probe.netName);
            msg(player, line, ChatFormatting.AQUA);
            bookLines.add(line);
        }
        int vmIdx = 1;
        for (NetlistBuilder.CurrentProbeInfo cp : extraction.currentProbes) {
            String line =
                "IProbe [" +
                cp.label +
                "]: " +
                result.getBranchCurrent("vm" + vmIdx++);
            msg(player, line, ChatFormatting.LIGHT_PURPLE);
            bookLines.add(line);
        }

        // OP runs have no sweep, but we still cache an output-only session so
        // the player can click "[Open output viewer]" in chat to browse the
        // netlist + values in the searchable screen.
        int sessionId = ParametricResultCache.store(
            new ParametricResultCache.ResultSet(
                "OP", "", java.util.Collections.emptyList(),
                new LinkedHashMap<>(), new LinkedHashMap<>(), false));
        emitOutputViewerLink(player, sessionId, bookLines,
                "CircuitSim Output (OP)");
    }

    // -------------------------------------------------------------------------
    // .AC
    // -------------------------------------------------------------------------

    private void runAcSimulation(
        ServerPlayer player,
        CircuitExtractor.ExtractionResult extraction,
        List<String> bookLines,
        List<Component> graphPageComponents,
        double tempC
    ) {
        List<NetlistBuilder.ProbeInfo> effectiveProbes = effectiveProbes(
            extraction
        );

        String netlist = NetlistBuilder.buildAcNetlist(
            extraction.components,
            effectiveProbes,
            extraction.currentProbes,
            fStart,
            fStop,
            ptsPerDec,
            pdkName,
            pdkLibPath,
            pdkLibPaths,
            ngBehavior,
            extraction.userCommands,
            extraction.userPlots
        );
        netlist = injectTemp(netlist, tempC);
        printNetlist(player, netlist, bookLines);

        NgSpiceRunner.Result result = NgSpiceRunner.run(netlist, ngBehavior);
        if (result.error != null) {
            msg(
                player,
                "Simulation Error: " + result.error,
                ChatFormatting.RED
            );
            bookLines.add("Error: " + result.error);
            return;
        }

        if (result.acData.isEmpty()) {
            msg(
                player,
                "No AC results returned. Raw output:",
                ChatFormatting.RED
            );
            for (String l : result.output) msg(player, l, ChatFormatting.GRAY);
            return;
        }

        List<Double> freqAxis = new ArrayList<>(result.acData.keySet());
        Collections.sort(freqAxis);

        msg(
            player,
            "--- AC Results (" + freqAxis.size() + " freq points) ---",
            ChatFormatting.GREEN
        );
        bookLines.add("=== AC Results ===");

        int stride = Math.max(1, freqAxis.size() / 5);
        for (int i = 0; i < freqAxis.size(); i += stride) {
            double f = freqAxis.get(i);
            String fLbl = ComponentEditScreen.formatValue(f) + "Hz";
            msg(player, "  f=" + fLbl, ChatFormatting.YELLOW);
            bookLines.add("  f=" + fLbl);
            Map<String, Double> vals = result.acData.get(f);
            for (NetlistBuilder.ProbeInfo probe : plotted(effectiveProbes)) {
                String key = "v(" + probe.netName + ")_mag";
                Double mag = vals.get(key);
                String line =
                    "    [" +
                    probe.label +
                    "]: " +
                    (mag != null
                        ? ComponentEditScreen.formatValue(mag) + " V"
                        : "N/A");
                msg(player, line, ChatFormatting.AQUA);
                bookLines.add(line);
            }
        }

        // Scalar measurements (e.g. .meas dc_gain / gbw / pm). These come from
        // the user's Commands block via raw `meas` / `print` ngspice lines.
        if (!result.extras.isEmpty()) {
            msg(player, "--- Measurements ---", ChatFormatting.LIGHT_PURPLE);
            bookLines.add("=== Measurements ===");
            for (String extra : result.extras) {
                String line = "  " + extra;
                msg(player, line, ChatFormatting.LIGHT_PURPLE);
                bookLines.add(line);
            }
        }

        // Diagnostic dump of raw ngspice stdout, so users running `meas` /
        // `print` can see exactly what ngspice produced even if our parser
        // didn't pick the values up. Goes only into the result book / output
        // viewer to avoid flooding chat.
        if (result.rawStdout != null && !result.rawStdout.isEmpty()) {
            bookLines.add("=== ngspice raw output ===");
            for (String l : result.rawStdout.split("\n")) {
                String s = l.replace("\r", "");
                if (s.isEmpty()) continue;
                bookLines.add("  " + s);
            }
        }

        Map<String, List<Double>> voltageData = new LinkedHashMap<>();
        Map<String, List<Double>> currentData = new LinkedHashMap<>();
        Map<String, String>       probeUnits  = new LinkedHashMap<>();

        for (NetlistBuilder.ProbeInfo probe : plotted(effectiveProbes)) {
            List<Double> mags = new ArrayList<>();
            for (double f : freqAxis) {
                Map<String, Double> vals = result.acData.get(f);
                Double v =
                    vals != null ? vals.get("v(" + probe.netName + ")_mag") : null;
                mags.add(v != null ? v : 0.0);
            }
            voltageData.put(probe.label, mags);
        }
        for (int k = 0; k < extraction.currentProbes.size(); k++) {
            NetlistBuilder.CurrentProbeInfo cp = extraction.currentProbes.get(
                k
            );
            List<Double> mags = new ArrayList<>();
            String currentKey = "i(vm" + (k + 1) + ")_mag";
            for (double f : freqAxis) {
                Map<String, Double> vals = result.acData.get(f);
                Double v = vals != null ? vals.get(currentKey) : null;
                mags.add(v != null ? v : 0.0);
            }
            currentData.put(cp.label, mags);
        }
        // Each `plot NAME = EXPR` directive contributes one extra series.
        for (NetlistBuilder.UserPlot plot : extraction.userPlots) {
            List<Double> mags = new ArrayList<>();
            String key = plot.name + "_mag";
            for (double f : freqAxis) {
                Map<String, Double> vals = result.acData.get(f);
                Double v = vals != null ? vals.get(key) : null;
                mags.add(v != null ? v : 0.0);
            }
            voltageData.put(plot.label, mags);
            probeUnits.put(plot.label, plot.unit);
        }

        int sessionId = ParametricResultCache.store(
            new ParametricResultCache.ResultSet(
                "Frequency",
                "Hz",
                freqAxis,
                voltageData,
                currentData,
                probeUnits,
                true
            )
        );
        emitGraphLinks(
            player,
            sessionId,
            voltageData,
            currentData,
            probeUnits,
            graphPageComponents
        );
        emitOutputViewerLink(player, sessionId, bookLines,
                "CircuitSim Output (AC)");
    }

    // -------------------------------------------------------------------------
    // .DC
    // -------------------------------------------------------------------------

    private void runDcSimulation(
        ServerPlayer player,
        CircuitExtractor.ExtractionResult extraction,
        List<String> bookLines,
        List<Component> graphPageComponents,
        double tempC
    ) {
        // Parse range fields. ParametricEditScreen parser would also work but
        // start/stop/step are three independent SI strings on this path.
        double start1, stop1, step1;
        try {
            start1 = ComponentEditScreen.parseSI(dcStart1);
            stop1  = ComponentEditScreen.parseSI(dcStop1);
            step1  = ComponentEditScreen.parseSI(dcStep1);
        } catch (NumberFormatException nfe) {
            msg(player, "DC range fields must parse as numbers.", ChatFormatting.RED);
            return;
        }
        if (step1 == 0) {
            msg(player, "DC step cannot be zero.", ChatFormatting.RED);
            return;
        }
        if (dcSource1 == null || dcSource1.trim().isEmpty()) {
            msg(player, "DC sweep needs a source name (e.g. V1).", ChatFormatting.RED);
            return;
        }

        double start2 = 0, stop2 = 0, step2 = 1;
        if (dc2D) {
            try {
                start2 = ComponentEditScreen.parseSI(dcStart2);
                stop2  = ComponentEditScreen.parseSI(dcStop2);
                step2  = ComponentEditScreen.parseSI(dcStep2);
            } catch (NumberFormatException nfe) {
                msg(player, "DC outer range fields must parse as numbers.", ChatFormatting.RED);
                return;
            }
            if (step2 == 0) {
                msg(player, "DC outer step cannot be zero.", ChatFormatting.RED);
                return;
            }
            if (dcSource2 == null || dcSource2.trim().isEmpty()) {
                msg(player, "2D DC sweep needs an outer source name.", ChatFormatting.RED);
                return;
            }
        }

        List<NetlistBuilder.ProbeInfo> effectiveProbes = effectiveProbes(extraction);

        String header = "=== DC Sweep: " + dcSource1
                + " " + dcStart1 + ":" + dcStop1 + ":" + dcStep1
                + (dc2D ? "  outer " + dcSource2 + " " + dcStart2 + ":" + dcStop2 + ":" + dcStep2 : "")
                + " ===";
        msg(player, header, ChatFormatting.GOLD);
        bookLines.add(header);

        if (!dc2D) {
            DcAccum acc = new DcAccum();
            runDcInner(player, extraction, effectiveProbes,
                    dcSource1.trim(), start1, stop1, step1,
                    "", tempC, bookLines, acc);
            finishDcSession(player, acc, dcSource1.trim(),
                    bookLines, graphPageComponents);
            return;
        }

        // 2D: outer loop runs in Java. For each outer step, override the outer
        // source's DC value in the extraction and call the inner 1D runner.
        // All series are accumulated into a single session so the graph viewer
        // can overlay every outer-step curve onto one plot.
        List<Double> outerSteps = stepRange(start2, stop2, step2);
        if (outerSteps.size() > 50) {
            msg(player, "Too many outer steps (" + outerSteps.size() + "); max 50.",
                    ChatFormatting.RED);
            return;
        }
        DcAccum acc = new DcAccum();
        for (double v2 : outerSteps) {
            String subHdr = "--- " + dcSource2.trim() + "=" + ComponentEditScreen.formatValue(v2) + " ---";
            msg(player, subHdr, ChatFormatting.YELLOW);
            bookLines.add(subHdr);
            CircuitExtractor.ExtractionResult overridden =
                    overrideSourceDc(extraction, dcSource2.trim(), v2);
            if (overridden == null) {
                msg(player, "Outer source '" + dcSource2.trim()
                        + "' not found in the circuit (set its Netlist index).",
                        ChatFormatting.RED);
                return;
            }
            String suffix = "@" + dcSource2.trim() + "=" + ComponentEditScreen.formatValue(v2);
            runDcInner(player, overridden, effectiveProbes,
                    dcSource1.trim(), start1, stop1, step1,
                    suffix, tempC, bookLines, acc);
        }
        finishDcSession(player, acc, dcSource1.trim(),
                bookLines, graphPageComponents);
    }

    /** Accumulator for a multi-iteration DC sweep so all curves share one session. */
    private static final class DcAccum {
        List<Double> sweepAxis = null;     // first inner sweep wins; subsequent must match length
        final Map<String, List<Double>> voltData   = new LinkedHashMap<>();
        final Map<String, List<Double>> currData   = new LinkedHashMap<>();
        final Map<String, String>       probeUnits = new LinkedHashMap<>();
    }

    /** Stores the accumulated DC result and emits the graph links. */
    private void finishDcSession(
        ServerPlayer player,
        DcAccum acc,
        String src,
        List<String> bookLines,
        List<Component> graphPageComponents
    ) {
        if (acc.sweepAxis == null || acc.sweepAxis.isEmpty()) return;
        String xUnit = src.length() > 0
                ? (Character.toUpperCase(src.charAt(0)) == 'I' ? "A" : "V")
                : "";
        int sessionId = ParametricResultCache.store(
                new ParametricResultCache.ResultSet(
                        src, xUnit,
                        acc.sweepAxis, acc.voltData, acc.currData,
                        acc.probeUnits, false));
        emitGraphLinks(player, sessionId, acc.voltData, acc.currData,
                acc.probeUnits, graphPageComponents);
        emitOutputViewerLink(player, sessionId, bookLines, "CircuitSim Output (DC)");
    }

    /**
     * Runs one .dc sweep (inner sweep when 2D, full sweep when 1D) and appends
     * its series to {@code acc}. The caller stores the merged session and
     * emits the graph links once all iterations have completed.
     */
    private void runDcInner(
        ServerPlayer player,
        CircuitExtractor.ExtractionResult extraction,
        List<NetlistBuilder.ProbeInfo> effectiveProbes,
        String src, double start, double stop, double step,
        String seriesSuffix,
        double tempC,
        List<String> bookLines,
        DcAccum acc
    ) {
        String netlist = NetlistBuilder.buildDcNetlist(
                extraction.components,
                effectiveProbes,
                extraction.currentProbes,
                src, start, stop, step,
                false, "", 0, 0, 1,
                pdkName, pdkLibPath, pdkLibPaths, ngBehavior,
                extraction.userCommands, extraction.userPlots
        );
        netlist = injectTemp(netlist, tempC);
        printNetlist(player, netlist, bookLines);

        NgSpiceRunner.Result result = NgSpiceRunner.run(netlist, ngBehavior);
        if (result.error != null) {
            String first = result.error.lines().filter(l -> !l.isBlank())
                    .findFirst().orElse("unknown error");
            msg(player, "Simulation Error: " + first, ChatFormatting.RED);
            bookLines.add("Error: " + first);
            return;
        }
        if (result.dcData.isEmpty()) {
            msg(player, "No DC results returned.", ChatFormatting.RED);
            bookLines.add("(no DC results)");
            return;
        }

        List<Double> sweepAxis = new ArrayList<>(result.dcData.keySet());
        Collections.sort(sweepAxis);

        // First iteration sets the shared X axis; later iterations are
        // assumed to use the same start/stop/step so their axes match.
        if (acc.sweepAxis == null) acc.sweepAxis = sweepAxis;

        msg(player, "DC: " + sweepAxis.size() + " sweep points", ChatFormatting.GREEN);
        bookLines.add("Sweep points: " + sweepAxis.size());

        for (NetlistBuilder.ProbeInfo probe : plotted(effectiveProbes)) {
            List<Double> ys = new ArrayList<>();
            String key = "v(" + probe.netName.toLowerCase() + ")";
            for (double x : sweepAxis) {
                Map<String, Double> row = result.dcData.get(x);
                Double y = row != null ? row.get(key) : null;
                ys.add(y != null ? y : 0.0);
            }
            acc.voltData.put(probe.label + seriesSuffix, ys);
        }
        for (int k = 0; k < extraction.currentProbes.size(); k++) {
            NetlistBuilder.CurrentProbeInfo cp = extraction.currentProbes.get(k);
            List<Double> ys = new ArrayList<>();
            String key = "i(vm" + (k + 1) + ")";
            for (double x : sweepAxis) {
                Map<String, Double> row = result.dcData.get(x);
                Double y = row != null ? row.get(key) : null;
                ys.add(y != null ? y : 0.0);
            }
            acc.currData.put(cp.label + seriesSuffix, ys);
        }
        for (NetlistBuilder.UserPlot plot : extraction.userPlots) {
            List<Double> ys = new ArrayList<>();
            for (double x : sweepAxis) {
                Map<String, Double> row = result.dcData.get(x);
                Double y = row != null ? row.get(plot.name.toLowerCase()) : null;
                ys.add(y != null ? y : 0.0);
            }
            acc.voltData.put(plot.label + seriesSuffix, ys);
            acc.probeUnits.put(plot.label + seriesSuffix, plot.unit);
        }

        // Scalar print/meas extras (e.g. dc_gain) — echo per sweep block.
        if (!result.extras.isEmpty()) {
            for (String extra : result.extras) {
                String line = "  " + extra;
                msg(player, line, ChatFormatting.LIGHT_PURPLE);
                bookLines.add(line);
            }
        }
    }

    /** Builds a start..stop range (inclusive of stop within epsilon) using step. */
    private static List<Double> stepRange(double start, double stop, double step) {
        List<Double> vals = new ArrayList<>();
        double epsilon = 1e-10 * Math.abs(step);
        if (step > 0) {
            for (double v = start; v <= stop + epsilon && vals.size() < 200; v += step) vals.add(v);
        } else {
            for (double v = start; v >= stop - epsilon && vals.size() < 200; v += step) vals.add(v);
        }
        return vals;
    }

    /**
     * Returns a copy of {@code extraction} with the named source's stored DC
     * value replaced by {@code newValue}. The source is matched by SPICE name
     * (prefix V/I/etc. + integer index that matches the component's manual
     * Netlist index). Returns null if no matching source is found.
     */
    private static CircuitExtractor.ExtractionResult overrideSourceDc(
        CircuitExtractor.ExtractionResult ex, String sourceName, double newValue
    ) {
        if (sourceName == null || sourceName.length() < 2) return null;
        char prefix = Character.toUpperCase(sourceName.charAt(0));
        int idx;
        try { idx = Integer.parseInt(sourceName.substring(1)); }
        catch (NumberFormatException e) { return null; }
        boolean matched = false;
        List<NetlistBuilder.CircuitComponent> rewritten = new ArrayList<>(ex.components.size());
        for (NetlistBuilder.CircuitComponent c : ex.components) {
            boolean isV = prefix == 'V' && (c.block instanceof VoltageSourceBlock
                    || c.block instanceof VoltageSourceSinBlock
                    || c.block instanceof VoltageSourcePulseBlock);
            boolean isI = prefix == 'I' && c.block instanceof CurrentSourceBlock;
            if ((isV || isI) && c.componentNumber == idx) {
                rewritten.add(c.withValue(newValue));
                matched = true;
            } else {
                rewritten.add(c);
            }
        }
        if (!matched) return null;
        return new CircuitExtractor.ExtractionResult(
                ex.success, ex.errorMessage, rewritten,
                ex.probes, ex.currentProbes, ex.probeLabels,
                ex.parametricBlocks, ex.userCommands, ex.userPlots);
    }

    // -------------------------------------------------------------------------
    // .TRAN
    // -------------------------------------------------------------------------

    private void runTranSimulation(
        ServerPlayer player,
        CircuitExtractor.ExtractionResult extraction,
        List<String> bookLines,
        List<Component> graphPageComponents,
        double tempC
    ) {
        List<NetlistBuilder.ProbeInfo> effectiveProbes = effectiveProbes(
            extraction
        );

        double tstep = fStart;
        double tstop = fStop;

        String netlist = NetlistBuilder.buildTranNetlist(
            extraction.components,
            effectiveProbes,
            extraction.currentProbes,
            tstep,
            tstop,
            pdkName,
            pdkLibPath,
            pdkLibPaths,
            ngBehavior,
            extraction.userCommands,
            extraction.userPlots
        );
        netlist = injectTemp(netlist, tempC);
        printNetlist(player, netlist, bookLines);

        NgSpiceRunner.Result result = NgSpiceRunner.run(netlist, ngBehavior);
        if (result.error != null) {
            msg(
                player,
                "Simulation Error: " + result.error,
                ChatFormatting.RED
            );
            bookLines.add("Error: " + result.error);
            return;
        }

        if (result.tranData.isEmpty()) {
            msg(
                player,
                "No TRAN results returned. Raw output:",
                ChatFormatting.RED
            );
            for (String l : result.output) msg(player, l, ChatFormatting.GRAY);
            return;
        }

        List<Double> timeAxis = new ArrayList<>(result.tranData.keySet());
        Collections.sort(timeAxis);

        msg(
            player,
            "--- TRAN Results (" + timeAxis.size() + " time points) ---",
            ChatFormatting.GREEN
        );
        bookLines.add("=== TRAN Results ===");

        int stride = Math.max(1, timeAxis.size() / 5);
        for (int i = 0; i < timeAxis.size(); i += stride) {
            double t = timeAxis.get(i);
            String tLbl = ComponentEditScreen.formatValue(t) + "s";
            msg(player, "  t=" + tLbl, ChatFormatting.YELLOW);
            bookLines.add("  t=" + tLbl);
            Map<String, Double> vals = result.tranData.get(t);
            for (NetlistBuilder.ProbeInfo probe : plotted(effectiveProbes)) {
                String key = "v(" + probe.netName + ")";
                Double v = vals != null ? vals.get(key) : null;
                String line =
                    "    [" +
                    probe.label +
                    "]: " +
                    (v != null
                        ? ComponentEditScreen.formatValue(v) + " V"
                        : "N/A");
                msg(player, line, ChatFormatting.AQUA);
                bookLines.add(line);
            }
        }

        Map<String, List<Double>> voltageData = new LinkedHashMap<>();
        Map<String, List<Double>> currentData = new LinkedHashMap<>();
        Map<String, String>       probeUnits  = new LinkedHashMap<>();

        for (NetlistBuilder.ProbeInfo probe : plotted(effectiveProbes)) {
            List<Double> vals = new ArrayList<>();
            for (double t : timeAxis) {
                Map<String, Double> row = result.tranData.get(t);
                Double v =
                    row != null ? row.get("v(" + probe.netName + ")") : null;
                vals.add(v != null ? v : 0.0);
            }
            voltageData.put(probe.label, vals);
        }
        for (int k = 0; k < extraction.currentProbes.size(); k++) {
            NetlistBuilder.CurrentProbeInfo cp = extraction.currentProbes.get(
                k
            );
            String iKey = "i(vm" + (k + 1) + ")";
            List<Double> vals = new ArrayList<>();
            for (double t : timeAxis) {
                Map<String, Double> row = result.tranData.get(t);
                Double v = row != null ? row.get(iKey) : null;
                vals.add(v != null ? v : 0.0);
            }
            currentData.put(cp.label, vals);
        }
        // Each `plot NAME = EXPR` directive contributes one extra series.
        for (NetlistBuilder.UserPlot plot : extraction.userPlots) {
            List<Double> vals = new ArrayList<>();
            for (double t : timeAxis) {
                Map<String, Double> row = result.tranData.get(t);
                Double v = row != null ? row.get(plot.name) : null;
                vals.add(v != null ? v : 0.0);
            }
            voltageData.put(plot.label, vals);
            probeUnits.put(plot.label, plot.unit);
        }

        int sessionId = ParametricResultCache.store(
            new ParametricResultCache.ResultSet(
                "Time",
                "s",
                timeAxis,
                voltageData,
                currentData,
                probeUnits,
                false
            )
        );
        emitGraphLinks(
            player,
            sessionId,
            voltageData,
            currentData,
            probeUnits,
            graphPageComponents
        );
        emitOutputViewerLink(player, sessionId, bookLines,
                "CircuitSim Output (TRAN)");
    }

    // -------------------------------------------------------------------------
    // Parametric dispatcher
    // -------------------------------------------------------------------------

    /** Outcome of {@link #applyParametricConstants}. */
    private static final class ApplyResult {
        final CircuitExtractor.ExtractionResult extraction;
        /** The one sweep variable to drive, or null when all parametric blocks are constants. */
        final CircuitExtractor.ParametricInfo  sweepParam;
        final List<Double>                     sweepValues;
        ApplyResult(CircuitExtractor.ExtractionResult e,
                    CircuitExtractor.ParametricInfo s, List<Double> v) {
            this.extraction = e; this.sweepParam = s; this.sweepValues = v;
        }
    }

    /**
     * Validates every Parametric block in {@code extraction}, substitutes the
     * single-value ones directly into the component list, and returns the
     * remaining (at most one) multi-value sweep. Reports user errors via chat
     * and returns null on validation failure.
     */
    /**
     * Verifies every variable referenced by a component's value/W/L/mult/nf
     * slot is in {@code defined}. Reports the first violation to chat and
     * returns false; returns true when the circuit is clean.
     */
    private boolean checkAllVariablesDefined(
        ServerPlayer player,
        CircuitExtractor.ExtractionResult extraction,
        Set<String> defined
    ) {
        for (NetlistBuilder.CircuitComponent c : extraction.components) {
            for (String ref : new String[]{c.valueExpr, c.wExpr, c.lExpr, c.multExpr, c.nfExpr}) {
                if (ref.isEmpty()) continue;
                if (defined.contains(ref)) continue;
                msg(player,
                        "Component at " + c.pos.toShortString() + " references undefined variable '"
                                + ref + "' (no Parametric block defines it).",
                        ChatFormatting.RED);
                return false;
            }
        }
        return true;
    }

    private ApplyResult applyParametricConstants(
        ServerPlayer player,
        CircuitExtractor.ExtractionResult extraction
    ) {
        // Parse + validate each Parametric block
        List<CircuitExtractor.ParametricInfo> constants = new ArrayList<>();
        Map<String, Double>                   constMap  = new LinkedHashMap<>();
        CircuitExtractor.ParametricInfo       sweep     = null;
        List<Double>                          sweepVals = Collections.emptyList();

        for (CircuitExtractor.ParametricInfo p : extraction.parametricBlocks) {
            boolean used = extraction.components.stream()
                    .anyMatch(c -> c.referencesVariable(p.varName));
            if (!used) {
                msg(player,
                        "Parametric variable '" + p.varName + "' is not used by any component.",
                        ChatFormatting.RED);
                return null;
            }

            List<Double> vals;
            try {
                vals = parseSweepString(p.valuesString);
            } catch (IllegalArgumentException e) {
                msg(player, "Invalid values for '" + p.varName + "': " + e.getMessage(),
                        ChatFormatting.RED);
                return null;
            }
            if (vals.isEmpty()) {
                msg(player, "No values for parametric variable '" + p.varName + "'.",
                        ChatFormatting.RED);
                return null;
            }
            if (vals.size() > 50) {
                msg(player, "Too many values for '" + p.varName + "' ("
                        + vals.size() + "); max 50.", ChatFormatting.RED);
                return null;
            }

            if (vals.size() == 1) {
                constants.add(p);
                constMap.put(p.varName, vals.get(0));
            } else {
                if (sweep != null) {
                    msg(player,
                            "Only one Parametric block can sweep at a time ('"
                                    + sweep.varName + "' and '" + p.varName + "').",
                            ChatFormatting.RED);
                    return null;
                }
                sweep = p;
                sweepVals = vals;
            }
        }

        // Components referencing an undefined variable: also an error.
        Set<String> defined = new LinkedHashSet<>(constMap.keySet());
        if (sweep != null) defined.add(sweep.varName);
        if (!checkAllVariablesDefined(player, extraction, defined)) return null;

        if (constMap.isEmpty()) {
            return new ApplyResult(extraction, sweep, sweepVals);
        }

        // Apply constants: rebuild the components list with single-value
        // variables substituted. Each constant variable substitutes into any
        // slot that references it; components carrying the sweep variable
        // keep that expression so swapVariable() can find them later.
        List<NetlistBuilder.CircuitComponent> rewritten = extraction.components.stream()
                .map(c -> {
                    NetlistBuilder.CircuitComponent cur = c;
                    for (Map.Entry<String, Double> e : constMap.entrySet()) {
                        if (cur.referencesVariable(e.getKey())) {
                            cur = cur.substituteVariable(e.getKey(), e.getValue());
                        }
                    }
                    return cur;
                })
                .collect(Collectors.toList());

        CircuitExtractor.ExtractionResult substituted = new CircuitExtractor.ExtractionResult(
                extraction.success, extraction.errorMessage,
                rewritten, extraction.probes, extraction.currentProbes,
                extraction.probeLabels, extraction.parametricBlocks,
                extraction.userCommands, extraction.userPlots);
        return new ApplyResult(substituted, sweep, sweepVals);
    }

    private void runParametricSweep(
        ServerPlayer player,
        CircuitExtractor.ExtractionResult extraction,
        CircuitExtractor.ParametricInfo param,
        List<Double> sweepValues,
        List<Double> tempValues,
        List<String> bookLines,
        List<Component> graphPageComponents
    ) {
        String varName = param.varName;

        List<NetlistBuilder.ProbeInfo> effectiveProbes = effectiveProbes(
            extraction
        );
        List<NetlistBuilder.CurrentProbeInfo> cpList = extraction.currentProbes;

        // Unit comes from the first component that references the variable;
        // mixed-type sharing (e.g. resistor + capacitor with same name) falls
        // back to a blank unit since no single unit makes sense.
        String varUnit = unitForVariable(extraction.components, varName);

        boolean multiTemp = tempValues.size() > 1;
        String tempPart = multiTemp ? (" x " + tempValues.size() + " temps") : "";
        String header =
            "=== Parametric: " + varName + " sweep (" +
            sweepValues.size() +
            " pts" + tempPart + ", " +
            analysis +
            ") ===";
        msg(player, header, ChatFormatting.GOLD);
        bookLines.add(header);

        switch (analysis) {
            case "AC" -> runParametricAcSweep(
                player,
                extraction,
                varName,
                varUnit,
                sweepValues,
                tempValues,
                effectiveProbes,
                cpList,
                bookLines,
                graphPageComponents
            );
            case "TRAN" -> runParametricTranSweep(
                player,
                extraction,
                varName,
                varUnit,
                sweepValues,
                tempValues,
                effectiveProbes,
                cpList,
                bookLines,
                graphPageComponents
            );
            case "DC" -> runParametricDcSweep(
                player,
                extraction,
                varName,
                varUnit,
                sweepValues,
                tempValues,
                effectiveProbes,
                bookLines,
                graphPageComponents
            );
            default -> runParametricOpSweep(
                player,
                extraction,
                varName,
                varUnit,
                sweepValues,
                tempValues,
                effectiveProbes,
                cpList,
                bookLines,
                graphPageComponents
            );
        }
    }

    // --- Parametric .OP ---

    private void runParametricOpSweep(
        ServerPlayer player,
        CircuitExtractor.ExtractionResult extraction,
        String varName,
        String varUnit,
        List<Double> sweepValues,
        List<Double> tempValues,
        List<NetlistBuilder.ProbeInfo> effectiveProbes,
        List<NetlistBuilder.CurrentProbeInfo> cpList,
        List<String> bookLines,
        List<Component> graphPageComponents
    ) {
        boolean multiTemp = tempValues.size() > 1;
        List<Double> validSweep = new ArrayList<>();
        Map<String, List<Double>> voltData   = new LinkedHashMap<>();
        Map<String, List<Double>> currData   = new LinkedHashMap<>();
        Map<String, String>       probeUnits = new LinkedHashMap<>();
        // Series grown lazily from raw `print` scalars (e.g. @m.xm1.[gm]).
        // Names collide with user `plot` directives, so suppress those keys.
        java.util.Set<String> userPlotNames = new java.util.HashSet<>();
        for (NetlistBuilder.UserPlot plot : extraction.userPlots)
            if (plot.name != null) userPlotNames.add(plot.name.toLowerCase(java.util.Locale.ROOT));
        Map<String, List<Double>> extraSeries = new LinkedHashMap<>();
        // Pre-create one series per (probe, temp) combination. Even when only
        // one temperature is set the suffix is "" so the keys match the old
        // single-temp behaviour.
        for (double T : tempValues) {
            String suffix = tempSuffix(T, multiTemp);
            for (NetlistBuilder.ProbeInfo p : plotted(effectiveProbes))
                voltData.put(p.label + suffix, new ArrayList<>());
            for (NetlistBuilder.CurrentProbeInfo c : cpList)
                currData.put(c.label + suffix, new ArrayList<>());
            for (NetlistBuilder.UserPlot plot : extraction.userPlots) {
                voltData.put(plot.label + suffix, new ArrayList<>());
                probeUnits.put(plot.label + suffix, plot.unit);
            }
        }

        boolean firstIteration = true;
        for (double val : sweepValues) {
            String secHdr =
                "--- " +
                ComponentEditScreen.formatValue(val) +
                varUnit +
                " ---";
            msg(player, secHdr, ChatFormatting.YELLOW);
            bookLines.add(secHdr);

            // Run every temperature for this param value first, so the per-
            // value lockstep append below keeps all series the same length.
            Map<Double, NgSpiceRunner.Result> resultsPerTemp = new LinkedHashMap<>();
            boolean anyOk = false;
            for (double T : tempValues) {
                String netlist = NetlistBuilder.buildNetlist(
                    swapVariable(extraction.components, varName, val),
                    effectiveProbes,
                    cpList,
                    pdkName,
                    pdkLibPath,
                    extraction.userCommands,
                    extraction.userPlots
                );
                netlist = injectTemp(netlist, T);
                if (firstIteration) {
                    appendNetlistToBook(bookLines, netlist,
                            "Netlist (iter 1 of " + sweepValues.size() * tempValues.size() + ", "
                                    + varName + "=" + ComponentEditScreen.formatValue(val)
                                    + varUnit
                                    + (multiTemp ? (", T=" + ComponentEditScreen.formatValue(T) + "C") : "")
                                    + ")");
                    firstIteration = false;
                }
                NgSpiceRunner.Result result = NgSpiceRunner.run(netlist, ngBehavior);
                if (result.error != null) {
                    String first = result.error
                        .lines()
                        .filter(l -> !l.isBlank())
                        .findFirst()
                        .orElse("unknown error");
                    String label = multiTemp ? ("  Error @T=" + ComponentEditScreen.formatValue(T) + "C: ") : "  Error: ";
                    msg(player, label + first, ChatFormatting.RED);
                    bookLines.add(label + first);
                    resultsPerTemp.put(T, null);
                    continue;
                }
                if (result.values.isEmpty()) {
                    resultsPerTemp.put(T, null);
                    continue;
                }
                resultsPerTemp.put(T, result);
                anyOk = true;
            }
            if (!anyOk) {
                bookLines.add("  (no results)");
                continue;
            }

            validSweep.add(val);
            for (double T : tempValues) {
                NgSpiceRunner.Result result = resultsPerTemp.get(T);
                String suffix = tempSuffix(T, multiTemp);
                for (NetlistBuilder.ProbeInfo probe : plotted(effectiveProbes)) {
                    Double v = result != null ? result.values.get("v(" + probe.netName + ")") : null;
                    voltData.get(probe.label + suffix).add(v != null ? v : 0.0);
                    if (result != null) {
                        String tag = multiTemp ? ("@" + ComponentEditScreen.formatValue(T) + "C") : "";
                        String line = "  [" + probe.label + tag + "]: " + result.getNodeVoltage(probe.netName);
                        msg(player, line, ChatFormatting.AQUA);
                        bookLines.add(line);
                    }
                }
                for (int k = 0; k < cpList.size(); k++) {
                    Double i = result != null ? result.values.get("i(vm" + (k + 1) + ")") : null;
                    currData.get(cpList.get(k).label + suffix).add(i != null ? i : 0.0);
                    if (result != null) {
                        String tag = multiTemp ? ("@" + ComponentEditScreen.formatValue(T) + "C") : "";
                        String line = "  [" + cpList.get(k).label + tag + "]: " + result.getBranchCurrent("vm" + (k + 1));
                        msg(player, line, ChatFormatting.LIGHT_PURPLE);
                        bookLines.add(line);
                    }
                }
                for (NetlistBuilder.UserPlot plot : extraction.userPlots) {
                    Double v = result != null ? result.extrasByName.get(plot.name) : null;
                    voltData.get(plot.label + suffix).add(v != null ? v : 0.0);
                }
                // Echo scalar print/meas output (e.g. `print @m.xm1.[gm]`) so
                // user commands aren't silently dropped during a sweep.
                if (result != null && !result.extras.isEmpty()) {
                    for (String extra : result.extras) {
                        String line = "  " + extra;
                        msg(player, line, ChatFormatting.AQUA);
                        bookLines.add(line);
                    }
                }
                // ... and auto-grow a series per print key so each scalar can
                // be plotted against the sweep axis like a regular probe.
                if (result != null) {
                    String tSuffix = tempSuffix(T, multiTemp);
                    for (Map.Entry<String, Double> e : result.extrasByName.entrySet()) {
                        String key = e.getKey();
                        if (key == null) continue;
                        if (userPlotNames.contains(key.toLowerCase(java.util.Locale.ROOT))) continue;
                        String seriesName = key + tSuffix;
                        List<Double> series = extraSeries.get(seriesName);
                        if (series == null) {
                            series = new ArrayList<>();
                            // Pad past validSweep entries that didn't emit this key.
                            int prior = validSweep.size() - 1;
                            for (int i = 0; i < prior; i++) series.add(0.0);
                            extraSeries.put(seriesName, series);
                        }
                        series.add(e.getValue());
                    }
                }
            }
            // Pad any series that existed but didn't appear this iteration
            // so every series stays exactly validSweep.size() long.
            for (List<Double> series : extraSeries.values()) {
                while (series.size() < validSweep.size()) series.add(0.0);
            }
        }

        if (validSweep.isEmpty()) return;
        // Merge auto-discovered print series into voltData with an empty unit
        // (we don't know whether gm is in A/V, fT is in Hz, etc.).
        for (Map.Entry<String, List<Double>> e : extraSeries.entrySet()) {
            voltData.put(e.getKey(), e.getValue());
            probeUnits.put(e.getKey(), "");
        }
        int sessionId = ParametricResultCache.store(
            new ParametricResultCache.ResultSet(
                varName,
                varUnit,
                validSweep,
                voltData,
                currData,
                probeUnits,
                false
            )
        );
        emitGraphLinks(
            player,
            sessionId,
            voltData,
            currData,
            probeUnits,
            graphPageComponents
        );
        emitOutputViewerLink(player, sessionId, bookLines,
                "CircuitSim Output (parametric " + analysis + ")");
    }

    // --- Parametric .AC ---

    private void runParametricAcSweep(
        ServerPlayer player,
        CircuitExtractor.ExtractionResult extraction,
        String varName,
        String varUnit,
        List<Double> sweepValues,
        List<Double> tempValues,
        List<NetlistBuilder.ProbeInfo> effectiveProbes,
        List<NetlistBuilder.CurrentProbeInfo> cpList,
        List<String> bookLines,
        List<Component> graphPageComponents
    ) {
        boolean multiTemp = tempValues.size() > 1;
        List<Double> freqAxis = null;
        Map<String, List<Double>> voltData   = new LinkedHashMap<>();
        Map<String, List<Double>> currData   = new LinkedHashMap<>();
        Map<String, String>       probeUnits = new LinkedHashMap<>();

        boolean firstIteration = true;
        for (double val : sweepValues) {
            for (double T : tempValues) {
                String tempLbl = multiTemp ? (", T=" + ComponentEditScreen.formatValue(T) + "C") : "";
                String secHdr =
                    "--- " +
                    ComponentEditScreen.formatValue(val) +
                    varUnit + tempLbl +
                    " (AC) ---";
                msg(player, secHdr, ChatFormatting.YELLOW);
                bookLines.add(secHdr);

                String netlist = NetlistBuilder.buildAcNetlist(
                    swapVariable(extraction.components, varName, val),
                    effectiveProbes,
                    cpList,
                    fStart,
                    fStop,
                    ptsPerDec,
                    pdkName,
                    pdkLibPath,
                    extraction.userCommands,
                    extraction.userPlots
                );
                netlist = injectTemp(netlist, T);
                if (firstIteration) {
                    appendNetlistToBook(bookLines, netlist,
                            "Netlist (iter 1 of " + sweepValues.size() * tempValues.size() + ", "
                                    + varName + "=" + ComponentEditScreen.formatValue(val)
                                    + varUnit + tempLbl + ")");
                    firstIteration = false;
                }

                NgSpiceRunner.Result result = NgSpiceRunner.run(
                    netlist,
                    ngBehavior
                );
                if (result.error != null) {
                    String first = result.error
                        .lines()
                        .filter(l -> !l.isBlank())
                        .findFirst()
                        .orElse("unknown error");
                    msg(player, "  Error: " + first, ChatFormatting.RED);
                    bookLines.add("  Error: " + first);
                    continue;
                }
                if (result.acData.isEmpty()) {
                    msg(player, "  (no AC results)", ChatFormatting.GRAY);
                    bookLines.add("  (no AC results)");
                    continue;
                }

                List<Double> sortedFreqs = new ArrayList<>(result.acData.keySet());
                Collections.sort(sortedFreqs);
                if (freqAxis == null) freqAxis = sortedFreqs;

                String stepSuffix =
                    "@" + ComponentEditScreen.formatValue(val) + varUnit
                    + tempSuffix(T, multiTemp);
                for (NetlistBuilder.ProbeInfo probe : plotted(effectiveProbes)) {
                    String seriesName = probe.label + stepSuffix;
                    String vKey = "v(" + probe.netName + ")_mag";
                    List<Double> mags = new ArrayList<>();
                    for (double f : sortedFreqs) {
                        Map<String, Double> vals = result.acData.get(f);
                        Double m = vals != null ? vals.get(vKey) : null;
                        mags.add(m != null ? m : 0.0);
                    }
                    voltData.put(seriesName, mags);
                }
                for (int k = 0; k < cpList.size(); k++) {
                    String seriesName = cpList.get(k).label + stepSuffix;
                    String iKey = "i(vm" + (k + 1) + ")_mag";
                    List<Double> mags = new ArrayList<>();
                    for (double f : sortedFreqs) {
                        Map<String, Double> vals = result.acData.get(f);
                        Double m = vals != null ? vals.get(iKey) : null;
                        mags.add(m != null ? m : 0.0);
                    }
                    currData.put(seriesName, mags);
                }
                for (NetlistBuilder.UserPlot plot : extraction.userPlots) {
                    String seriesName = plot.label + stepSuffix;
                    String key = plot.name + "_mag";
                    List<Double> mags = new ArrayList<>();
                    for (double f : sortedFreqs) {
                        Map<String, Double> vals = result.acData.get(f);
                        Double m = vals != null ? vals.get(key) : null;
                        mags.add(m != null ? m : 0.0);
                    }
                    voltData.put(seriesName, mags);
                    probeUnits.put(seriesName, plot.unit);
                }
                // Per-iteration scalar measurements (.meas dc_gain / gbw / pm).
                if (!result.extras.isEmpty()) {
                    bookLines.add("  Measurements:");
                    for (String extra : result.extras) {
                        String line = "    " + extra;
                        msg(player, line, ChatFormatting.LIGHT_PURPLE);
                        bookLines.add(line);
                    }
                }
            }
        }

        if (freqAxis == null || freqAxis.isEmpty()) return;
        msg(
            player,
            "AC sweep complete. " + voltData.size() + " voltage series.",
            ChatFormatting.GREEN
        );
        int sessionId = ParametricResultCache.store(
            new ParametricResultCache.ResultSet(
                "Frequency",
                "Hz",
                freqAxis,
                voltData,
                currData,
                probeUnits,
                true
            )
        );
        emitGraphLinks(
            player,
            sessionId,
            voltData,
            currData,
            probeUnits,
            graphPageComponents
        );
        emitOutputViewerLink(player, sessionId, bookLines,
                "CircuitSim Output (parametric " + analysis + ")");
    }

    // --- Parametric .TRAN ---

    private void runParametricTranSweep(
        ServerPlayer player,
        CircuitExtractor.ExtractionResult extraction,
        String varName,
        String varUnit,
        List<Double> sweepValues,
        List<Double> tempValues,
        List<NetlistBuilder.ProbeInfo> effectiveProbes,
        List<NetlistBuilder.CurrentProbeInfo> cpList,
        List<String> bookLines,
        List<Component> graphPageComponents
    ) {
        boolean multiTemp = tempValues.size() > 1;
        double tstep = fStart;
        double tstop = fStop;

        List<Double> timeAxis = null;
        Map<String, List<Double>> voltData   = new LinkedHashMap<>();
        Map<String, List<Double>> currData   = new LinkedHashMap<>();
        Map<String, String>       probeUnits = new LinkedHashMap<>();

        boolean firstIteration = true;
        for (double val : sweepValues) {
            for (double T : tempValues) {
                String tempLbl = multiTemp ? (", T=" + ComponentEditScreen.formatValue(T) + "C") : "";
                String secHdr =
                    "--- " +
                    ComponentEditScreen.formatValue(val) +
                    varUnit + tempLbl +
                    " (TRAN) ---";
                msg(player, secHdr, ChatFormatting.YELLOW);
                bookLines.add(secHdr);

                String netlist = NetlistBuilder.buildTranNetlist(
                    swapVariable(extraction.components, varName, val),
                    effectiveProbes,
                    cpList,
                    tstep,
                    tstop,
                    pdkName,
                    pdkLibPath,
                    extraction.userCommands,
                    extraction.userPlots
                );
                netlist = injectTemp(netlist, T);
                if (firstIteration) {
                    appendNetlistToBook(bookLines, netlist,
                            "Netlist (iter 1 of " + sweepValues.size() * tempValues.size() + ", "
                                    + varName + "=" + ComponentEditScreen.formatValue(val)
                                    + varUnit + tempLbl + ")");
                    firstIteration = false;
                }

                NgSpiceRunner.Result result = NgSpiceRunner.run(
                    netlist,
                    ngBehavior
                );
                if (result.error != null) {
                    String first = result.error
                        .lines()
                        .filter(l -> !l.isBlank())
                        .findFirst()
                        .orElse("unknown error");
                    msg(player, "  Error: " + first, ChatFormatting.RED);
                    bookLines.add("  Error: " + first);
                    continue;
                }
                if (result.tranData.isEmpty()) {
                    msg(player, "  (no TRAN results)", ChatFormatting.GRAY);
                    bookLines.add("  (no TRAN results)");
                    continue;
                }

                List<Double> sortedTimes = new ArrayList<>(
                    result.tranData.keySet()
                );
                Collections.sort(sortedTimes);
                if (timeAxis == null) timeAxis = sortedTimes;

                String stepSuffix =
                    "@" + ComponentEditScreen.formatValue(val) + varUnit
                    + tempSuffix(T, multiTemp);
                for (NetlistBuilder.ProbeInfo probe : plotted(effectiveProbes)) {
                    String seriesName = probe.label + stepSuffix;
                    String vKey = "v(" + probe.netName + ")";
                    List<Double> vals = new ArrayList<>();
                    for (double t : sortedTimes) {
                        Map<String, Double> row = result.tranData.get(t);
                        Double v = row != null ? row.get(vKey) : null;
                        vals.add(v != null ? v : 0.0);
                    }
                    voltData.put(seriesName, vals);
                }
                for (int k = 0; k < cpList.size(); k++) {
                    String seriesName = cpList.get(k).label + stepSuffix;
                    String iKey = "i(vm" + (k + 1) + ")";
                    List<Double> vals = new ArrayList<>();
                    for (double t : sortedTimes) {
                        Map<String, Double> row = result.tranData.get(t);
                        Double v = row != null ? row.get(iKey) : null;
                        vals.add(v != null ? v : 0.0);
                    }
                    currData.put(seriesName, vals);
                }
                for (NetlistBuilder.UserPlot plot : extraction.userPlots) {
                    String seriesName = plot.label + stepSuffix;
                    List<Double> vals = new ArrayList<>();
                    for (double t : sortedTimes) {
                        Map<String, Double> row = result.tranData.get(t);
                        Double v = row != null ? row.get(plot.name) : null;
                        vals.add(v != null ? v : 0.0);
                    }
                    voltData.put(seriesName, vals);
                    probeUnits.put(seriesName, plot.unit);
                }
                // Echo scalar print/meas output (e.g. `print @m.xm1.[gm]`) so
                // user commands aren't silently dropped during a sweep.
                if (!result.extras.isEmpty()) {
                    for (String extra : result.extras) {
                        String line = "  " + extra;
                        msg(player, line, ChatFormatting.AQUA);
                        bookLines.add(line);
                    }
                }
            }
        }

        if (timeAxis == null || timeAxis.isEmpty()) return;
        msg(
            player,
            "TRAN sweep complete. " + voltData.size() + " voltage series.",
            ChatFormatting.GREEN
        );
        int sessionId = ParametricResultCache.store(
            new ParametricResultCache.ResultSet(
                "Time",
                "s",
                timeAxis,
                voltData,
                currData,
                probeUnits,
                false
            )
        );
        emitGraphLinks(
            player,
            sessionId,
            voltData,
            currData,
            probeUnits,
            graphPageComponents
        );
        emitOutputViewerLink(player, sessionId, bookLines,
                "CircuitSim Output (parametric " + analysis + ")");
    }

    // -------------------------------------------------------------------------
    // .DC + parametric — outer loop over the parametric variable, run a 1D
    // ngspice .dc sweep per step, group series by @<var>=<val> suffix.
    // 2D DC + parametric is disallowed (4 nested loops) — error out cleanly.
    // -------------------------------------------------------------------------

    private void runParametricDcSweep(
        ServerPlayer player,
        CircuitExtractor.ExtractionResult extraction,
        String varName,
        String varUnit,
        List<Double> sweepValues,
        List<Double> tempValues,
        List<NetlistBuilder.ProbeInfo> effectiveProbes,
        List<String> bookLines,
        List<Component> graphPageComponents
    ) {
        if (dc2D) {
            msg(player,
                    "2D DC + parametric is not supported. Disable the DC 2D toggle or use a single value in the Parametric block.",
                    ChatFormatting.RED);
            return;
        }
        double start1, stop1, step1;
        try {
            start1 = ComponentEditScreen.parseSI(dcStart1);
            stop1  = ComponentEditScreen.parseSI(dcStop1);
            step1  = ComponentEditScreen.parseSI(dcStep1);
        } catch (NumberFormatException nfe) {
            msg(player, "DC range fields must parse as numbers.", ChatFormatting.RED);
            return;
        }
        if (step1 == 0) {
            msg(player, "DC step cannot be zero.", ChatFormatting.RED);
            return;
        }
        if (dcSource1 == null || dcSource1.trim().isEmpty()) {
            msg(player, "DC sweep needs a source name (e.g. V1).", ChatFormatting.RED);
            return;
        }
        double tempC = tempValues.isEmpty() ? 27.0 : tempValues.get(0);

        DcAccum acc = new DcAccum();
        for (double val : sweepValues) {
            String subHdr = "--- " + varName + "=" + ComponentEditScreen.formatValue(val) + varUnit + " ---";
            msg(player, subHdr, ChatFormatting.YELLOW);
            bookLines.add(subHdr);

            List<NetlistBuilder.CircuitComponent> swept = swapVariable(extraction.components, varName, val);
            CircuitExtractor.ExtractionResult inner = new CircuitExtractor.ExtractionResult(
                    extraction.success, extraction.errorMessage,
                    swept, extraction.probes, extraction.currentProbes,
                    extraction.probeLabels, extraction.parametricBlocks,
                    extraction.userCommands, extraction.userPlots);

            String suffix = "@" + varName + "=" + ComponentEditScreen.formatValue(val) + varUnit;
            runDcInner(player, inner, effectiveProbes,
                    dcSource1.trim(), start1, stop1, step1,
                    suffix, tempC, bookLines, acc);
        }
        finishDcSession(player, acc, dcSource1.trim(), bookLines, graphPageComponents);
    }

    // -------------------------------------------------------------------------
    // .DC TEMP sweep — 1D, X-axis = temperature, one .op run per step.
    // Reused for OP + multi-temp (same shape, just different source of the
    // temperature list).
    // -------------------------------------------------------------------------

    private void runTempSweep(
        ServerPlayer player,
        CircuitExtractor.ExtractionResult extraction,
        List<Double> sweepTemps,
        List<String> bookLines,
        List<Component> graphPageComponents
    ) {
        if (sweepTemps.isEmpty()) {
            msg(player, "No temperatures to sweep.", ChatFormatting.RED);
            return;
        }
        if (sweepTemps.size() > 50) {
            msg(player, "Too many temperature points (" + sweepTemps.size() + "); max 50.",
                ChatFormatting.RED);
            return;
        }

        // Use ONLY the user-placed probes. effectiveProbes() auto-fabricates
        // a "Node N" probe for every node in the circuit when there are none,
        // which drowns the output in "Node 2: N/A" entries for disconnected
        // pins and pollutes the .control block with spurious `print v(N)`
        // lines that fight the user's own `print` commands.
        List<NetlistBuilder.ProbeInfo> probes = extraction.probes;
        List<NetlistBuilder.CurrentProbeInfo> cpList = extraction.currentProbes;

        String header = "=== Temperature Sweep (" + sweepTemps.size() + " pts) ===";
        msg(player, header, ChatFormatting.GOLD);
        bookLines.add(header);

        List<Double> validSweep = new ArrayList<>();
        Map<String, List<Double>> voltData   = new LinkedHashMap<>();
        Map<String, List<Double>> currData   = new LinkedHashMap<>();
        Map<String, String>       probeUnits = new LinkedHashMap<>();
        for (NetlistBuilder.ProbeInfo p : plotted(probes))
            voltData.put(p.label, new ArrayList<>());
        for (NetlistBuilder.CurrentProbeInfo c : cpList)
            currData.put(c.label, new ArrayList<>());
        for (NetlistBuilder.UserPlot plot : extraction.userPlots) {
            voltData.put(plot.label, new ArrayList<>());
            probeUnits.put(plot.label, plot.unit);
        }
        // Names already declared via `plot NAME = ...` directives; the raw
        // `print` output for these would land in extrasByName under the same
        // key, so skip them when auto-graphing to avoid double-creating a
        // series.
        java.util.Set<String> userPlotNames = new java.util.HashSet<>();
        for (NetlistBuilder.UserPlot plot : extraction.userPlots) {
            if (plot.name != null) userPlotNames.add(plot.name.toLowerCase(java.util.Locale.ROOT));
        }
        // Series grown lazily from raw `print` scalars in the Commands block.
        // Each unique key encountered in result.extrasByName gets one series;
        // entries are padded with zeros for iterations where ngspice didn't
        // emit the key so all series stay aligned with validSweep.
        Map<String, List<Double>> extraSeries = new LinkedHashMap<>();

        boolean firstIteration = true;
        for (double T : sweepTemps) {
            String secHdr = "--- " + ComponentEditScreen.formatValue(T) + "C ---";
            msg(player, secHdr, ChatFormatting.YELLOW);
            bookLines.add(secHdr);

            String netlist = NetlistBuilder.buildNetlist(
                extraction.components,
                probes,
                cpList,
                pdkName,
                pdkLibPath,
                pdkLibPaths,
                ngBehavior,
                extraction.userCommands,
                extraction.userPlots
            );
            netlist = injectTemp(netlist, T);
            if (firstIteration) {
                appendNetlistToBook(bookLines, netlist,
                        "Netlist (iter 1 of " + sweepTemps.size() + ", T="
                                + ComponentEditScreen.formatValue(T) + "C)");
                firstIteration = false;
            }
            NgSpiceRunner.Result result = NgSpiceRunner.run(netlist, ngBehavior);
            if (result.error != null) {
                String first = result.error
                    .lines()
                    .filter(l -> !l.isBlank())
                    .findFirst()
                    .orElse("unknown error");
                msg(player, "  Error: " + first, ChatFormatting.RED);
                bookLines.add("  Error: " + first);
                continue;
            }
            // result.values is empty for circuits driven purely through user
            // `print` commands (no probes, no v/i bucket). Don't abort on
            // that — extrasByName may still have content.
            if (result.values.isEmpty() && result.extrasByName.isEmpty()) {
                msg(player, "  (no results)", ChatFormatting.GRAY);
                bookLines.add("  (no results)");
                continue;
            }

            validSweep.add(T);
            for (NetlistBuilder.ProbeInfo probe : plotted(probes)) {
                Double v = result.values.get("v(" + probe.netName + ")");
                voltData.get(probe.label).add(v != null ? v : 0.0);
                String line = "  [" + probe.label + "]: " + result.getNodeVoltage(probe.netName);
                msg(player, line, ChatFormatting.AQUA);
                bookLines.add(line);
            }
            for (int k = 0; k < cpList.size(); k++) {
                Double i = result.values.get("i(vm" + (k + 1) + ")");
                currData.get(cpList.get(k).label).add(i != null ? i : 0.0);
                String line = "  [" + cpList.get(k).label + "]: " + result.getBranchCurrent("vm" + (k + 1));
                msg(player, line, ChatFormatting.LIGHT_PURPLE);
                bookLines.add(line);
            }
            for (NetlistBuilder.UserPlot plot : extraction.userPlots) {
                Double v = result.extrasByName.get(plot.name);
                voltData.get(plot.label).add(v != null ? v : 0.0);
            }
            // Display raw `print` scalars (e.g. @m.xm1...[gm]) per iteration —
            // the single-shot runOpSimulation already does this; the temp
            // sweep needs to too, otherwise the user's scalar output is
            // silently dropped.
            for (String extra : result.extras) {
                String line = "  " + extra;
                msg(player, line, ChatFormatting.AQUA);
                bookLines.add(line);
            }
            // ... and auto-grow a series per print key so each scalar can be
            // plotted against temperature.
            for (Map.Entry<String, Double> e : result.extrasByName.entrySet()) {
                String key = e.getKey();
                if (key == null) continue;
                if (userPlotNames.contains(key.toLowerCase(java.util.Locale.ROOT))) continue;
                List<Double> series = extraSeries.get(key);
                if (series == null) {
                    series = new ArrayList<>();
                    // Pad past validSweep entries that didn't emit this key.
                    int prior = validSweep.size() - 1;
                    for (int i = 0; i < prior; i++) series.add(0.0);
                    extraSeries.put(key, series);
                }
                series.add(e.getValue());
            }
            // Pad any series that existed but didn't appear this iteration
            // so every series stays exactly validSweep.size() long.
            for (List<Double> series : extraSeries.values()) {
                while (series.size() < validSweep.size()) series.add(0.0);
            }
        }

        if (validSweep.isEmpty()) return;
        // Merge auto-discovered print series into voltData with an empty
        // unit (we don't know whether gm is in A/V, fT is in Hz, etc.).
        for (Map.Entry<String, List<Double>> e : extraSeries.entrySet()) {
            voltData.put(e.getKey(), e.getValue());
            probeUnits.put(e.getKey(), "");
        }
        int sessionId = ParametricResultCache.store(
            new ParametricResultCache.ResultSet(
                "Temperature",
                "C",
                validSweep,
                voltData,
                currData,
                probeUnits,
                false
            )
        );
        emitGraphLinks(player, sessionId, voltData, currData, probeUnits, graphPageComponents);
        emitOutputViewerLink(player, sessionId, bookLines,
                "CircuitSim Output (Temp Sweep)");
    }

    // -------------------------------------------------------------------------
    // Multi-temperature .AC — same X axis (frequency) but one curve per
    // temperature, stacked into a single ResultSet with @<T>C suffix.
    // -------------------------------------------------------------------------

    private void runMultiTempAcSweep(
        ServerPlayer player,
        CircuitExtractor.ExtractionResult extraction,
        List<Double> tempValues,
        List<String> bookLines,
        List<Component> graphPageComponents
    ) {
        List<NetlistBuilder.ProbeInfo> effectiveProbes = effectiveProbes(extraction);
        List<NetlistBuilder.CurrentProbeInfo> cpList = extraction.currentProbes;

        String header = "=== AC + Temp Sweep (" + tempValues.size() + " temps) ===";
        msg(player, header, ChatFormatting.GOLD);
        bookLines.add(header);

        List<Double> freqAxis = null;
        Map<String, List<Double>> voltData   = new LinkedHashMap<>();
        Map<String, List<Double>> currData   = new LinkedHashMap<>();
        Map<String, String>       probeUnits = new LinkedHashMap<>();

        boolean firstIteration = true;
        for (double T : tempValues) {
            String secHdr = "--- " + ComponentEditScreen.formatValue(T) + "C (AC) ---";
            msg(player, secHdr, ChatFormatting.YELLOW);
            bookLines.add(secHdr);

            String netlist = NetlistBuilder.buildAcNetlist(
                extraction.components,
                effectiveProbes,
                cpList,
                fStart, fStop, ptsPerDec,
                pdkName, pdkLibPath, pdkLibPaths, ngBehavior,
                extraction.userCommands, extraction.userPlots
            );
            netlist = injectTemp(netlist, T);
            if (firstIteration) {
                appendNetlistToBook(bookLines, netlist,
                        "Netlist (iter 1 of " + tempValues.size() + ", T="
                                + ComponentEditScreen.formatValue(T) + "C)");
                firstIteration = false;
            }
            NgSpiceRunner.Result result = NgSpiceRunner.run(netlist, ngBehavior);
            if (result.error != null) {
                String first = result.error
                    .lines()
                    .filter(l -> !l.isBlank())
                    .findFirst()
                    .orElse("unknown error");
                msg(player, "  Error: " + first, ChatFormatting.RED);
                bookLines.add("  Error: " + first);
                continue;
            }
            if (result.acData.isEmpty()) {
                msg(player, "  (no AC results)", ChatFormatting.GRAY);
                bookLines.add("  (no AC results)");
                continue;
            }

            List<Double> sortedFreqs = new ArrayList<>(result.acData.keySet());
            Collections.sort(sortedFreqs);
            if (freqAxis == null) freqAxis = sortedFreqs;

            String suffix = tempSuffix(T, true);
            for (NetlistBuilder.ProbeInfo probe : plotted(effectiveProbes)) {
                String seriesName = probe.label + suffix;
                String vKey = "v(" + probe.netName + ")_mag";
                List<Double> mags = new ArrayList<>();
                for (double f : sortedFreqs) {
                    Map<String, Double> vals = result.acData.get(f);
                    Double m = vals != null ? vals.get(vKey) : null;
                    mags.add(m != null ? m : 0.0);
                }
                voltData.put(seriesName, mags);
            }
            for (int k = 0; k < cpList.size(); k++) {
                String seriesName = cpList.get(k).label + suffix;
                String iKey = "i(vm" + (k + 1) + ")_mag";
                List<Double> mags = new ArrayList<>();
                for (double f : sortedFreqs) {
                    Map<String, Double> vals = result.acData.get(f);
                    Double m = vals != null ? vals.get(iKey) : null;
                    mags.add(m != null ? m : 0.0);
                }
                currData.put(seriesName, mags);
            }
            for (NetlistBuilder.UserPlot plot : extraction.userPlots) {
                String seriesName = plot.label + suffix;
                String key = plot.name + "_mag";
                List<Double> mags = new ArrayList<>();
                for (double f : sortedFreqs) {
                    Map<String, Double> vals = result.acData.get(f);
                    Double m = vals != null ? vals.get(key) : null;
                    mags.add(m != null ? m : 0.0);
                }
                voltData.put(seriesName, mags);
                probeUnits.put(seriesName, plot.unit);
            }
        }

        if (freqAxis == null || freqAxis.isEmpty()) return;
        int sessionId = ParametricResultCache.store(
            new ParametricResultCache.ResultSet(
                "Frequency", "Hz", freqAxis,
                voltData, currData, probeUnits, true
            )
        );
        emitGraphLinks(player, sessionId, voltData, currData, probeUnits, graphPageComponents);
        emitOutputViewerLink(player, sessionId, bookLines,
                "CircuitSim Output (AC + Temp Sweep)");
    }

    // -------------------------------------------------------------------------
    // Multi-temperature .TRAN — like AC variant but the X axis is time.
    // -------------------------------------------------------------------------

    private void runMultiTempTranSweep(
        ServerPlayer player,
        CircuitExtractor.ExtractionResult extraction,
        List<Double> tempValues,
        List<String> bookLines,
        List<Component> graphPageComponents
    ) {
        List<NetlistBuilder.ProbeInfo> effectiveProbes = effectiveProbes(extraction);
        List<NetlistBuilder.CurrentProbeInfo> cpList = extraction.currentProbes;

        double tstep = fStart;
        double tstop = fStop;

        String header = "=== TRAN + Temp Sweep (" + tempValues.size() + " temps) ===";
        msg(player, header, ChatFormatting.GOLD);
        bookLines.add(header);

        List<Double> timeAxis = null;
        Map<String, List<Double>> voltData   = new LinkedHashMap<>();
        Map<String, List<Double>> currData   = new LinkedHashMap<>();
        Map<String, String>       probeUnits = new LinkedHashMap<>();

        boolean firstIteration = true;
        for (double T : tempValues) {
            String secHdr = "--- " + ComponentEditScreen.formatValue(T) + "C (TRAN) ---";
            msg(player, secHdr, ChatFormatting.YELLOW);
            bookLines.add(secHdr);

            String netlist = NetlistBuilder.buildTranNetlist(
                extraction.components,
                effectiveProbes,
                cpList,
                tstep, tstop,
                pdkName, pdkLibPath, pdkLibPaths, ngBehavior,
                extraction.userCommands, extraction.userPlots
            );
            netlist = injectTemp(netlist, T);
            if (firstIteration) {
                appendNetlistToBook(bookLines, netlist,
                        "Netlist (iter 1 of " + tempValues.size() + ", T="
                                + ComponentEditScreen.formatValue(T) + "C)");
                firstIteration = false;
            }
            NgSpiceRunner.Result result = NgSpiceRunner.run(netlist, ngBehavior);
            if (result.error != null) {
                String first = result.error
                    .lines()
                    .filter(l -> !l.isBlank())
                    .findFirst()
                    .orElse("unknown error");
                msg(player, "  Error: " + first, ChatFormatting.RED);
                bookLines.add("  Error: " + first);
                continue;
            }
            if (result.tranData.isEmpty()) {
                msg(player, "  (no TRAN results)", ChatFormatting.GRAY);
                bookLines.add("  (no TRAN results)");
                continue;
            }

            List<Double> sortedTimes = new ArrayList<>(result.tranData.keySet());
            Collections.sort(sortedTimes);
            if (timeAxis == null) timeAxis = sortedTimes;

            String suffix = tempSuffix(T, true);
            for (NetlistBuilder.ProbeInfo probe : plotted(effectiveProbes)) {
                String seriesName = probe.label + suffix;
                String vKey = "v(" + probe.netName + ")";
                List<Double> vals = new ArrayList<>();
                for (double t : sortedTimes) {
                    Map<String, Double> row = result.tranData.get(t);
                    Double v = row != null ? row.get(vKey) : null;
                    vals.add(v != null ? v : 0.0);
                }
                voltData.put(seriesName, vals);
            }
            for (int k = 0; k < cpList.size(); k++) {
                String seriesName = cpList.get(k).label + suffix;
                String iKey = "i(vm" + (k + 1) + ")";
                List<Double> vals = new ArrayList<>();
                for (double t : sortedTimes) {
                    Map<String, Double> row = result.tranData.get(t);
                    Double v = row != null ? row.get(iKey) : null;
                    vals.add(v != null ? v : 0.0);
                }
                currData.put(seriesName, vals);
            }
            for (NetlistBuilder.UserPlot plot : extraction.userPlots) {
                String seriesName = plot.label + suffix;
                List<Double> vals = new ArrayList<>();
                for (double t : sortedTimes) {
                    Map<String, Double> row = result.tranData.get(t);
                    Double v = row != null ? row.get(plot.name) : null;
                    vals.add(v != null ? v : 0.0);
                }
                voltData.put(seriesName, vals);
                probeUnits.put(seriesName, plot.unit);
            }
        }

        if (timeAxis == null || timeAxis.isEmpty()) return;
        int sessionId = ParametricResultCache.store(
            new ParametricResultCache.ResultSet(
                "Time", "s", timeAxis,
                voltData, currData, probeUnits, false
            )
        );
        emitGraphLinks(player, sessionId, voltData, currData, probeUnits, graphPageComponents);
        emitOutputViewerLink(player, sessionId, bookLines,
                "CircuitSim Output (TRAN + Temp Sweep)");
    }

    // -------------------------------------------------------------------------
    // Shared helpers
    // -------------------------------------------------------------------------

    private void printNetlist(
        ServerPlayer player,
        String netlist,
        List<String> bookLines
    ) {
        msg(player, "Netlist:", ChatFormatting.YELLOW);
        bookLines.add("=== Netlist ===");
        for (String line : netlist.split("\n")) {
            msg(player, "  " + line, ChatFormatting.WHITE);
            bookLines.add("  " + line);
        }
    }

    /**
     * Appends a netlist to the result book / output viewer text only — no chat
     * spam. Used by parametric sweeps to show a representative netlist (the
     * first iteration's) without flooding chat with N copies of nearly the
     * same text.
     */
    private static void appendNetlistToBook(
        List<String> bookLines,
        String netlist,
        String header
    ) {
        bookLines.add("=== " + header + " ===");
        for (String line : netlist.split("\n")) {
            bookLines.add("  " + line);
        }
    }

    /**
     * Filters out "name only" probes for display / graph-series purposes. Such
     * probes still flow into the netlist builders (which need them to apply net
     * aliases / merges) but must not appear in printed results or plots. The
     * netlist builders skip them internally, so this is only used at the
     * SimulatePacket display layer.
     */
    private static List<NetlistBuilder.ProbeInfo> plotted(
        List<NetlistBuilder.ProbeInfo> probes
    ) {
        List<NetlistBuilder.ProbeInfo> out = new ArrayList<>(probes.size());
        for (NetlistBuilder.ProbeInfo p : probes) {
            if (!p.noPlot) out.add(p);
        }
        return out;
    }

    private List<NetlistBuilder.ProbeInfo> effectiveProbes(
        CircuitExtractor.ExtractionResult ex
    ) {
        if (!ex.probes.isEmpty()) return ex.probes;
        // If the user placed *any* measurement device (current probe, or a
        // `plot ... = ...` directive in a Commands block), respect their
        // explicit intent and don't fabricate voltage probes for every node.
        // The autoprobe-everything fallback is only for circuits with no
        // probes at all, where the alternative is a sim that returns nothing.
        if (!ex.currentProbes.isEmpty() || !ex.userPlots.isEmpty()) {
            return Collections.emptyList();
        }
        Set<Integer> nodes = new LinkedHashSet<>();
        for (NetlistBuilder.CircuitComponent c : ex.components) {
            if (c.nodeA != 0) nodes.add(c.nodeA);
            if (c.nodeB != 0) nodes.add(c.nodeB);
        }
        return nodes
            .stream()
            .map(n -> new NetlistBuilder.ProbeInfo(n, "Node " + n))
            .collect(Collectors.toList());
    }

    private void emitGraphLinks(
        ServerPlayer player,
        int sessionId,
        Map<String, List<Double>> voltData,
        Map<String, List<Double>> currData,
        Map<String, String> probeUnits,
        List<Component> graphPageComponents
    ) {
        List<String> allNames = new ArrayList<>();
        allNames.addAll(voltData.keySet());
        allNames.addAll(currData.keySet());

        msg(player, "--- Graphs - click to open ---", ChatFormatting.DARK_AQUA);
        graphPageComponents.add(
            Component.literal("-- Graphs --\n").withStyle(ChatFormatting.GOLD)
        );

        for (int idx = 0; idx < allNames.size(); idx++) {
            String name = allNames.get(idx);
            boolean isVolt = voltData.containsKey(name);
            String unit;
            if (probeUnits != null && probeUnits.containsKey(name)) {
                unit = probeUnits.get(name);
            } else {
                unit = isVolt ? "V" : "A";
            }
            String unitTag = unit == null || unit.isEmpty() ? "" : " (" + unit + ")";
            String cmd = "/circuitsim graph " + sessionId + " " + idx;
            String label = "  Plot " + name + unitTag;

            sendChatComponent(player,
                Component.literal(label).withStyle(
                    Style.EMPTY.withColor(
                        isVolt
                            ? ChatFormatting.AQUA
                            : ChatFormatting.LIGHT_PURPLE
                    )
                        .withUnderlined(true)
                        .withClickEvent(
                            new ClickEvent(ClickEvent.Action.RUN_COMMAND, cmd)
                        )
                )
            );

            graphPageComponents.add(
                Component.literal(
                    "Plot " + name + unitTag + "\n"
                ).withStyle(
                    Style.EMPTY.withColor(
                        isVolt
                            ? ChatFormatting.AQUA
                            : ChatFormatting.LIGHT_PURPLE
                    )
                        .withUnderlined(true)
                        .withClickEvent(
                            new ClickEvent(ClickEvent.Action.RUN_COMMAND, cmd)
                        )
                )
            );
        }
    }

    private static final int LINES_PER_PAGE = 13;
    private static final int MAX_PAGES = 100;

    private static void giveResultBook(
        ServerPlayer player,
        String title,
        List<String> lines,
        List<Component> graphPageComponents
    ) {
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        CompoundTag tag = book.getOrCreateTag();
        tag.putString("title", title);
        tag.putString("author", "CircuitSim");
        tag.putByte("resolved", (byte) 1);

        ListTag pages = new ListTag();
        List<String> pageBuf = new ArrayList<>();

        for (String line : lines) {
            pageBuf.add(line);
            if (pageBuf.size() >= LINES_PER_PAGE) {
                pages.add(plainPage(pageBuf));
                pageBuf.clear();
                if (pages.size() >= MAX_PAGES) break;
            }
        }
        if (!pageBuf.isEmpty() && pages.size() < MAX_PAGES) pages.add(
            plainPage(pageBuf)
        );

        if (!graphPageComponents.isEmpty() && pages.size() < MAX_PAGES) {
            MutableComponent root = Component.empty();
            for (Component c : graphPageComponents) root.append(c);
            pages.add(StringTag.valueOf(Component.Serializer.toJson(root)));
        }

        if (pages.isEmpty()) pages.add(plainPage(List.of("No results.")));
        tag.put("pages", pages);

        if (!player.getInventory().add(book)) player.drop(book, false);
        msg(
            player,
            "Results saved to a book in your inventory.",
            ChatFormatting.GRAY
        );
    }

    private static StringTag plainPage(List<String> lines) {
        return StringTag.valueOf(
            Component.Serializer.toJson(
                Component.literal(String.join("\n", lines))
            )
        );
    }

    /**
     * Returns a new component list with every component referencing
     * {@code varName} in any of its parameter slots (value / W / L / mult / nf)
     * substituted with {@code newVal}.
     */
    private static List<NetlistBuilder.CircuitComponent> swapVariable(
        List<NetlistBuilder.CircuitComponent> components,
        String varName,
        double newVal
    ) {
        return components.stream()
                .map(c -> c.referencesVariable(varName)
                        ? c.substituteVariable(varName, newVal)
                        : c)
                .collect(Collectors.toList());
    }

    /**
     * Thread-safe chat helper. Auto-dispatches to the main server thread when
     * called from the simulation worker, so existing call sites in the
     * sweep / analysis code don't need to know whether they're on-thread.
     */
    private static void msg(ServerPlayer p, String text, ChatFormatting fmt) {
        sendChatComponent(p, Component.literal(text).withStyle(fmt));
    }

    /**
     * Attaches the simulation's output lines to the cached {@link ResultSet}
     * (so {@code /circuitsim output <id>} can re-open the output viewer) and
     * emits a clickable chat link the player can click to manually open it.
     *
     * Used for parametric runs, where auto-opening the output viewer
     * deterministically crashes the client with a native-crash-no-hs_err
     * pattern. Keeping the open as a manual action sidesteps the crash while
     * still giving the player access to the searchable output text.
     */
    private void emitOutputViewerLink(
        ServerPlayer player,
        int sessionId,
        List<String> bookLines,
        String label
    ) {
        ParametricResultCache.ResultSet rs = ParametricResultCache.get(sessionId);
        if (rs != null) {
            rs.outputLines = new ArrayList<>(bookLines);
            rs.outputTitle = label;
        }
        String cmd = "/circuitsim output " + sessionId;
        sendChatComponent(player,
            Component.literal("[Open output viewer]").withStyle(
                Style.EMPTY.withColor(ChatFormatting.GOLD)
                    .withUnderlined(true)
                    .withClickEvent(
                        new ClickEvent(ClickEvent.Action.RUN_COMMAND, cmd))));
    }

    /**
     * Thread-safe variant for pre-built styled components (e.g. graph links
     * with click events). Sending packets from the background simulation
     * worker can produce silent native crashes — this hops to the main thread
     * if necessary.
     */
    private static void sendChatComponent(ServerPlayer p, Component msg) {
        MinecraftServer s = p.getServer();
        if (s != null && !s.isSameThread()) {
            s.execute(() -> p.displayClientMessage(msg, false));
        } else {
            p.displayClientMessage(msg, false);
        }
    }

    /**
     * Best-effort unit string for sweep values of {@code varName}. The slot
     * the variable occupies decides: W/L = "u" (microns), mult/nf = unitless,
     * value = the block's natural unit. Returns "" when multiple components
     * reference the variable with conflicting units.
     */
    private static String unitForVariable(
            List<NetlistBuilder.CircuitComponent> components, String varName) {
        String chosen = null;
        for (NetlistBuilder.CircuitComponent c : components) {
            String slot = c.slotFor(varName);
            if (slot == null) continue;
            String u = switch (slot) {
                case "W", "L"      -> "u";
                case "mult", "nf"  -> "";
                default            -> unitOf(c.block);
            };
            if (chosen == null) chosen = u;
            else if (!chosen.equals(u)) return "";
        }
        return chosen == null ? "" : chosen;
    }

    private static String unitOf(Block b) {
        if (b instanceof ResistorBlock) return "\u03A9";
        if (b instanceof CapacitorBlock) return "F";
        if (b instanceof InductorBlock) return "H";
        if (b instanceof VoltageSourceBlock) return "V";
        if (b instanceof VoltageSourceSinBlock) return "V";
        if (b instanceof VoltageSourcePulseBlock) return "V";
        if (b instanceof CurrentSourceBlock) return "A";
        return "";
    }

    public static List<Double> parseSweepString(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) throw new IllegalArgumentException(
            "empty sweep string"
        );
        if (s.contains(":")) {
            String[] parts = s.split(":");
            if (parts.length != 3) throw new IllegalArgumentException(
                "range must be start:stop:step"
            );
            double start = ComponentEditScreen.parseSI(parts[0].trim());
            double stop = ComponentEditScreen.parseSI(parts[1].trim());
            double step = ComponentEditScreen.parseSI(parts[2].trim());
            if (step == 0) throw new IllegalArgumentException(
                "step cannot be zero"
            );
            List<Double> vals = new ArrayList<>();
            double eps = 1e-10 * Math.abs(step);
            if (step > 0) {
                for (
                    double v = start;
                    v <= stop + eps && vals.size() < 50;
                    v += step
                ) vals.add(v);
            } else {
                for (
                    double v = start;
                    v >= stop - eps && vals.size() < 50;
                    v += step
                ) vals.add(v);
            }
            return vals;
        }
        return Arrays.stream(s.split(","))
            .map(String::trim)
            .filter(t -> !t.isEmpty())
            .map(t -> {
                try {
                    return ComponentEditScreen.parseSI(t);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(
                        "cannot parse '" + t + "'"
                    );
                }
            })
            .collect(Collectors.toList());
    }

    // -------------------------------------------------------------------------
    // Temperature helpers
    // -------------------------------------------------------------------------

    /**
     * Parses the {@code simTemp} string from the UI. Plain numbers only — no
     * SI suffixes, so "20m" is rejected rather than silently meaning 0.02°C.
     *
     * <ul>
     *   <li>empty / unparseable → {@code [27.0]} (the ngspice default)</li>
     *   <li>{@code "27.5"} → {@code [27.5]} (single run)</li>
     *   <li>{@code "20:40:5"} → expanded inclusive range, max 50 values</li>
     *   <li>{@code "20,30,40"} → comma list</li>
     * </ul>
     */
    private static List<Double> parseTempValues(String spec) {
        if (spec == null) return java.util.List.of(27.0);
        String s = spec.trim();
        if (s.isEmpty()) return java.util.List.of(27.0);
        try {
            if (s.contains(":")) {
                String[] parts = s.split(":");
                if (parts.length != 3) return java.util.List.of(27.0);
                double start = Double.parseDouble(parts[0].trim());
                double stop  = Double.parseDouble(parts[1].trim());
                double step  = Double.parseDouble(parts[2].trim());
                if (step == 0) return java.util.List.of(27.0);
                List<Double> vals = new ArrayList<>();
                double eps = 1e-10 * Math.abs(step);
                if (step > 0) {
                    for (double v = start; v <= stop + eps && vals.size() < 50; v += step) vals.add(v);
                } else {
                    for (double v = start; v >= stop - eps && vals.size() < 50; v += step) vals.add(v);
                }
                return vals.isEmpty() ? java.util.List.of(27.0) : vals;
            }
            if (s.contains(",")) {
                List<Double> vals = new ArrayList<>();
                for (String t : s.split(",")) {
                    String tt = t.trim();
                    if (tt.isEmpty()) continue;
                    vals.add(Double.parseDouble(tt));
                    if (vals.size() >= 50) break;
                }
                return vals.isEmpty() ? java.util.List.of(27.0) : vals;
            }
            return java.util.List.of(Double.parseDouble(s));
        } catch (NumberFormatException e) {
            return java.util.List.of(27.0);
        }
    }

    /**
     * Suffix appended to series names when more than one temperature is being
     * swept. Single-temp runs return {@code ""} so series names stay identical
     * to the pre-temperature-sweep code paths.
     */
    private static String tempSuffix(double tempC, boolean multiTemp) {
        if (!multiTemp) return "";
        String t;
        if (tempC == (long) tempC) t = Long.toString((long) tempC);
        else                       t = String.format(java.util.Locale.ROOT, "%g", tempC);
        return "@" + t + "C";
    }

    /**
     * Splices a {@code .temp <tempC>} directive into a netlist immediately
     * before the first {@code .op}/{@code .ac}/{@code .tran}/{@code .dc}
     * analysis line. ngspice requires {@code .temp} at the netlist (not
     * control) level, so this can't go inside the {@code .control} block we
     * already build. If no analysis directive is found the netlist is
     * returned unchanged.
     */
    private static String injectTemp(String netlist, double tempC) {
        String[] lines = netlist.split("\\r?\\n", -1);
        StringBuilder out = new StringBuilder(netlist.length() + 32);
        boolean injected = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (!injected) {
                String trimmed = line.trim().toLowerCase(java.util.Locale.ROOT);
                if (trimmed.startsWith(".op")
                        || trimmed.startsWith(".ac ")
                        || trimmed.startsWith(".tran ")
                        || trimmed.startsWith(".dc ")) {
                    out.append(String.format(java.util.Locale.ROOT, ".temp %g%n", tempC));
                    injected = true;
                }
            }
            out.append(line);
            if (i < lines.length - 1) out.append('\n');
        }
        return out.toString();
    }
}
