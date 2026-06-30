package com.circuitsim.network;

import com.circuitsim.block.*;
import com.circuitsim.blockentity.ComponentBlockEntity;
import com.circuitsim.screen.ComponentEditScreen;
import com.circuitsim.simulation.CircuitExtractor;
import com.circuitsim.simulation.CircuitLinter;
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
    // Per-analysis raw param sets so each analysis tab keeps its own values.
    // rawParam1/2/3 above is the active analysis's set (legacy round-trip);
    // these carry AC and TRAN independently of which tab was active on submit.
    private final String acParam1, acParam2, acParam3;
    private final String tranParam1, tranParam2, tranParam3;
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
    /**
     * Scalar Param-variable definitions ({@code name=value}) for the current
     * run — injected as {@code .param} lines into every generated netlist.
     * Set on the sim worker thread (one per packet) before any netlist is
     * built; stays empty when the circuit has no Param blocks.
     */
    private List<String> activeParamDefs = Collections.emptyList();
    // User-defined subcircuit definitions ( .subckt … .ends ) for every distinct
    // SubcircuitBlock chip in the circuit. Captured once from the top-level
    // extraction and spliced into every netlist this packet builds.
    private List<String> activeSubcktDefs = Collections.emptyList();

    // .NOISE analysis config — raw UI strings (parsed at sim time).
    private final String noiseOut;     // output node (probe label or node id)
    private final String noiseRef;     // optional reference node
    private final String noiseSrc;     // input source name (e.g. V1)
    private final String noiseSweep;   // dec / lin / oct
    private final String noisePts;     // points (per dec/oct, total for lin)
    private final String noiseFstart;
    private final String noiseFstop;
    private final String noisePtsSum;  // optional pts-per-summary

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
        this(pos, analysis, fStart, fStop, ptsPerDec, pdkName, pdkLibPath, pdkLibPaths,
                ngBehavior, rawParam1, rawParam2, rawParam3, simTemp,
                dcSource1, dcStart1, dcStop1, dcStep1, dc2D,
                dcSource2, dcStart2, dcStop2, dcStep2,
                "", "", "", "dec", "20", "1", "1Meg", "",
                "10", "1Meg", "10", "1u", "10m", "");
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
        String dcStep2,
        String noiseOut,
        String noiseRef,
        String noiseSrc,
        String noiseSweep,
        String noisePts,
        String noiseFstart,
        String noiseFstop,
        String noisePtsSum,
        String acParam1,
        String acParam2,
        String acParam3,
        String tranParam1,
        String tranParam2,
        String tranParam3
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
        this.noiseOut    = noiseOut    == null ? ""    : noiseOut;
        this.noiseRef    = noiseRef    == null ? ""    : noiseRef;
        this.noiseSrc    = noiseSrc    == null ? ""    : noiseSrc;
        this.noiseSweep  = noiseSweep  == null ? "dec" : noiseSweep;
        this.noisePts    = noisePts    == null ? "20"  : noisePts;
        this.noiseFstart = noiseFstart == null ? "1"   : noiseFstart;
        this.noiseFstop  = noiseFstop  == null ? "1Meg" : noiseFstop;
        this.noisePtsSum = noisePtsSum == null ? ""    : noisePtsSum;
        this.acParam1   = acParam1   == null ? "10"   : acParam1;
        this.acParam2   = acParam2   == null ? "1Meg" : acParam2;
        this.acParam3   = acParam3   == null ? "10"   : acParam3;
        this.tranParam1 = tranParam1 == null ? "1u"   : tranParam1;
        this.tranParam2 = tranParam2 == null ? "10m"  : tranParam2;
        this.tranParam3 = tranParam3 == null ? ""     : tranParam3;
    }

    public SimulatePacket(FriendlyByteBuf buf) {
        this.pos = buf.readBlockPos();
        this.analysis = buf.readUtf(8);
        this.fStart = buf.readDouble();
        this.fStop = buf.readDouble();
        this.ptsPerDec = buf.readInt();
        this.pdkName = buf.readUtf(32);
        this.pdkLibPath = buf.readUtf(4096);
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
        this.noiseOut    = buf.readUtf(64);
        this.noiseRef    = buf.readUtf(64);
        this.noiseSrc    = buf.readUtf(32);
        this.noiseSweep  = buf.readUtf(8);
        this.noisePts    = buf.readUtf(16);
        this.noiseFstart = buf.readUtf(32);
        this.noiseFstop  = buf.readUtf(32);
        this.noisePtsSum = buf.readUtf(16);
        this.acParam1   = buf.readUtf(32);
        this.acParam2   = buf.readUtf(32);
        this.acParam3   = buf.readUtf(32);
        this.tranParam1 = buf.readUtf(32);
        this.tranParam2 = buf.readUtf(32);
        this.tranParam3 = buf.readUtf(32);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeUtf(analysis, 8);
        buf.writeDouble(fStart);
        buf.writeDouble(fStop);
        buf.writeInt(ptsPerDec);
        buf.writeUtf(pdkName, 32);
        buf.writeUtf(pdkLibPath, 4096);
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
        buf.writeUtf(noiseOut,    64);
        buf.writeUtf(noiseRef,    64);
        buf.writeUtf(noiseSrc,    32);
        buf.writeUtf(noiseSweep,  8);
        buf.writeUtf(noisePts,    16);
        buf.writeUtf(noiseFstart, 32);
        buf.writeUtf(noiseFstop,  32);
        buf.writeUtf(noisePtsSum, 16);
        buf.writeUtf(acParam1,   32);
        buf.writeUtf(acParam2,   32);
        buf.writeUtf(acParam3,   32);
        buf.writeUtf(tranParam1, 32);
        buf.writeUtf(tranParam2, 32);
        buf.writeUtf(tranParam3, 32);
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
            simCbe.setNoiseOut(noiseOut);
            simCbe.setNoiseRef(noiseRef);
            simCbe.setNoiseSrc(noiseSrc);
            simCbe.setNoiseSweep(noiseSweep);
            simCbe.setNoisePts(noisePts);
            simCbe.setNoiseFstart(noiseFstart);
            simCbe.setNoiseFstop(noiseFstop);
            simCbe.setNoisePtsSum(noisePtsSum);
            simCbe.setSimAcParam1(acParam1);
            simCbe.setSimAcParam2(acParam2);
            simCbe.setSimAcParam3(acParam3);
            simCbe.setSimTranParam1(tranParam1);
            simCbe.setSimTranParam2(tranParam2);
            simCbe.setSimTranParam3(tranParam3);
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
        // Subckt defs don't change under parametric substitution (substitution
        // only rewrites component values), so capture them from the top-level
        // extraction before the worker thread starts.
        activeSubcktDefs = extraction.subcktDefs;

        // Pre-sim lint: surface floating nodes / no-ground / no-DC-path issues
        // as friendly warnings before ngspice produces a cryptic error. Advisory
        // only — the simulation still runs.
        for (String warning : CircuitLinter.lint(extraction)) {
            msg(player, "Lint: " + warning, ChatFormatting.YELLOW);
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
                activeParamDefs = applied.paramDefs;
            } else if (!checkAllVariablesDefined(player, extraction,
                    Collections.emptySet())) {
                return;       // a component references a variable with no Parametric block
            }

            if (sweepParam != null) {
                // Preferred sweep engine: ONE ngspice process whose .control
                // block loops alterparam/reset/run over the swept .param and
                // we split the output per iteration. Falls back to the legacy
                // one-process-per-value runner for the combinations the loop
                // can't express: multi-temperature overlays, 2D DC, NOISE,
                // and sweeps that drive an IC geometry slot (W/L/mult/nf),
                // whose derived area/perimeter params must be recomputed in
                // Java per value.
                boolean controlLoopOk = tempValues.size() <= 1
                        && !"NOISE".equals(analysis)
                        && !("DC".equals(analysis) && dc2D)
                        && sweptSlotsAreSimple(extraction.components, sweepParam.varName);
                if (controlLoopOk) {
                    double tempC = tempValues.isEmpty() ? 27.0 : tempValues.get(0);
                    runParamControlSweep(
                        player,
                        extraction,
                        sweepParam,
                        sweepValues,
                        tempC,
                        bookLines,
                        graphPageComponents
                    );
                } else {
                    runParametricSweep(
                        player,
                        extraction,
                        sweepParam,
                        sweepValues,
                        tempValues,
                        bookLines,
                        graphPageComponents
                    );
                }
            } else if ("NOISE".equals(analysis)) {
                double tempC = tempValues.isEmpty() ? 27.0 : tempValues.get(0);
                if (tempValues.size() > 1) {
                    msg(player,
                        "Noise analysis runs at a single temperature; using "
                            + ComponentEditScreen.formatValue(tempC) + "C.",
                        ChatFormatting.YELLOW);
                }
                runNoiseSimulation(player, extraction, bookLines, graphPageComponents, tempC);
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
        netlist = prepareNetlist(netlist, tempC);
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

        // Map every device's operating point (the `show` tables) back to its
        // block and ship it to the client for the "annotate operating points"
        // feature (press K after an .OP run). Always sent — an empty list
        // clears any stale annotation data on the client.
        sendOperatingPoints(player, extraction, result);

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
        netlist = prepareNetlist(netlist, tempC);
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
    // .NOISE
    // -------------------------------------------------------------------------

    /**
     * Maps the user-typed output/ref node onto a netlist node name: bare
     * integers pass through (raw node ids), anything else is sanitised the
     * same way probe labels are, so typing the probe's label finds the
     * aliased net.
     */
    private static String noiseNodeRef(String typed) {
        String s = typed.trim();
        if (s.matches("\\d+")) return s;
        return NetlistBuilder.sanitizeNodeName(s);
    }

    private void runNoiseSimulation(
        ServerPlayer player,
        CircuitExtractor.ExtractionResult extraction,
        List<String> bookLines,
        List<Component> graphPageComponents,
        double tempC
    ) {
        // ── validate the dialog fields ───────────────────────────────────────
        String out = noiseNodeRef(noiseOut);
        if (out.isEmpty()) {
            msg(player, "Noise analysis needs an output node (probe label or node id).",
                ChatFormatting.RED);
            return;
        }
        String ref = noiseRef.trim().isEmpty() ? "" : noiseNodeRef(noiseRef);
        String src = noiseSrc.trim();
        if (src.isEmpty()) {
            msg(player, "Noise analysis needs an input source name (e.g. V1).",
                ChatFormatting.RED);
            return;
        }
        char srcPrefix = Character.toUpperCase(src.charAt(0));
        if (srcPrefix != 'V' && srcPrefix != 'I') {
            msg(player, "Noise input source must be an independent V or I source (got '"
                    + src + "').", ChatFormatting.RED);
            return;
        }
        String sweep = switch (noiseSweep.toLowerCase(java.util.Locale.ROOT)) {
            case "lin" -> "lin";
            case "oct" -> "oct";
            default    -> "dec";
        };
        int pts;
        try { pts = Integer.parseInt(noisePts.trim()); }
        catch (NumberFormatException e) { pts = 20; }
        if (pts < 1) pts = 1;
        double fstart, fstop;
        try {
            fstart = ComponentEditScreen.parseSI(noiseFstart.trim());
            fstop  = ComponentEditScreen.parseSI(noiseFstop.trim());
        } catch (NumberFormatException e) {
            msg(player, "Noise Fstart/Fstop must parse as frequencies.", ChatFormatting.RED);
            return;
        }
        if (fstart <= 0 || fstop <= fstart) {
            msg(player, "Noise sweep needs 0 < Fstart < Fstop.", ChatFormatting.RED);
            return;
        }
        int ptsSum = 0;
        if (!noisePtsSum.trim().isEmpty()) {
            try { ptsSum = Integer.parseInt(noisePtsSum.trim()); }
            catch (NumberFormatException e) {
                msg(player, "Pts-per-summary must be a whole number (or empty).",
                    ChatFormatting.RED);
                return;
            }
            if (ptsSum < 0) ptsSum = 0;
        }

        String netlist = NetlistBuilder.buildNoiseNetlist(
            extraction.components,
            extraction.probes,
            extraction.currentProbes,
            out, ref, src, sweep, pts, fstart, fstop, ptsSum,
            pdkName, pdkLibPath, pdkLibPaths, ngBehavior,
            extraction.userCommands
        );

        // The chosen source must actually exist in the generated netlist —
        // catch the typo here instead of letting ngspice abort cryptically.
        boolean srcFound = netlist.lines().anyMatch(l -> {
            String t = l.trim();
            int sp = t.indexOf(' ');
            return sp > 0 && t.substring(0, sp).equalsIgnoreCase(src);
        });
        if (!srcFound) {
            msg(player, "Input source '" + src + "' is not in the circuit. "
                    + "Set the source's Netlist index so its name matches (e.g. V1).",
                ChatFormatting.RED);
            return;
        }

        netlist = prepareNetlist(netlist, tempC);
        printNetlist(player, netlist, bookLines);

        NgSpiceRunner.Result result = NgSpiceRunner.run(netlist, ngBehavior);
        if (result.error != null) {
            msg(player, "Simulation Error: " + result.error, ChatFormatting.RED);
            bookLines.add("Error: " + result.error);
            return;
        }
        if (result.noiseData.isEmpty()) {
            msg(player, "No noise results returned. Raw output:", ChatFormatting.RED);
            for (String l : result.output) msg(player, l, ChatFormatting.GRAY);
            return;
        }

        List<Double> freqAxis = new ArrayList<>(result.noiseData.keySet());
        Collections.sort(freqAxis);

        msg(player,
            "--- Noise Results (" + freqAxis.size() + " freq points) ---",
            ChatFormatting.GREEN);
        bookLines.add("=== Noise Results ===");

        // Integrated totals — the headline numbers.
        Double onTotal = result.extrasByName.get("onoise_total");
        Double inTotal = result.extrasByName.get("inoise_total");
        String inUnit  = srcPrefix == 'I' ? "A" : "V";
        if (onTotal != null) {
            String line = "  onoise_total = "
                    + ComponentEditScreen.formatValue(onTotal) + "V rms (" + fstartStopLabel(fstart, fstop) + ")";
            msg(player, line, ChatFormatting.AQUA);
            bookLines.add(line);
        }
        if (inTotal != null) {
            String line = "  inoise_total = "
                    + ComponentEditScreen.formatValue(inTotal) + inUnit + " rms (referred to " + src + ")";
            msg(player, line, ChatFormatting.AQUA);
            bookLines.add(line);
        }

        // Raw stdout into the result book so the device-noise summaries
        // (pts-per-summary mode) are reachable without re-running.
        if (result.rawStdout != null && !result.rawStdout.isEmpty()) {
            bookLines.add("=== ngspice raw output ===");
            for (String l : result.rawStdout.split("\n")) {
                String s = l.replace("\r", "");
                if (s.isEmpty()) continue;
                bookLines.add("  " + s);
            }
        }

        Map<String, List<Double>> voltData   = new LinkedHashMap<>();
        Map<String, List<Double>> currData   = new LinkedHashMap<>();
        Map<String, String>       probeUnits = new LinkedHashMap<>();

        for (String vec : new String[]{"onoise_spectrum", "inoise_spectrum"}) {
            List<Double> vals = new ArrayList<>();
            boolean any = false;
            for (double f : freqAxis) {
                Map<String, Double> row = result.noiseData.get(f);
                Double v = row != null ? row.get(vec) : null;
                if (v != null) any = true;
                vals.add(v != null ? v : 0.0);
            }
            if (!any) continue;
            voltData.put(vec, vals);
            probeUnits.put(vec, (vec.startsWith("inoise") ? inUnit : "V") + "/√Hz");
        }

        int sessionId = ParametricResultCache.store(
            new ParametricResultCache.ResultSet(
                "Frequency", "Hz", freqAxis,
                voltData, currData, probeUnits,
                true,   // log X
                true)); // log Y by default — noise spectra read log-log
        emitGraphLinks(player, sessionId, voltData, currData, probeUnits, graphPageComponents);
        emitOutputViewerLink(player, sessionId, bookLines, "CircuitSim Output (NOISE)");
    }

    private static String fstartStopLabel(double fstart, double fstop) {
        return ComponentEditScreen.formatValue(fstart) + "Hz - "
                + ComponentEditScreen.formatValue(fstop) + "Hz";
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
        netlist = prepareNetlist(netlist, tempC);
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
        netlist = prepareNetlist(netlist, tempC);
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

        // Companion FFT session — ngspice's linearize+fft of the same probed
        // signals, parsed from the spectrum print of this run.
        int fftSessionId = storeFftSession(result.fftData, "",
                effectiveProbes, extraction.currentProbes, extraction.userPlots);

        ParametricResultCache.ResultSet tranRs = new ParametricResultCache.ResultSet(
                "Time", "s", timeAxis, voltageData, currentData, probeUnits, false);
        tranRs.fftSessionId = fftSessionId;
        int sessionId = ParametricResultCache.store(tranRs);
        emitGraphLinks(
            player,
            sessionId,
            voltageData,
            currentData,
            probeUnits,
            graphPageComponents
        );
        if (fftSessionId >= 0) emitFftLink(player, fftSessionId, graphPageComponents);
        emitOutputViewerLink(player, sessionId, bookLines,
                "CircuitSim Output (TRAN)");
    }

    /**
     * Stores the FFT spectra of one transient run (or one sweep step, when
     * {@code suffix} is non-empty) as their own result session: frequency
     * axis, log-log by default, one series per probed signal. The DC bin is
     * skipped — it has no home on the log-frequency axis and the time-domain
     * plot already shows the average level. Returns the session id, or -1
     * when no FFT table was parsed.
     */
    private static int storeFftSession(
        Map<Double, Map<String, Double>> fftData,
        String suffix,
        List<NetlistBuilder.ProbeInfo> effectiveProbes,
        List<NetlistBuilder.CurrentProbeInfo> cpList,
        List<NetlistBuilder.UserPlot> userPlots
    ) {
        Map<String, List<Double>> voltData   = new LinkedHashMap<>();
        Map<String, List<Double>> currData   = new LinkedHashMap<>();
        Map<String, String>       probeUnits = new LinkedHashMap<>();
        List<Double> freqAxis = fftAxisOf(fftData);
        if (freqAxis == null) return -1;
        collectSweepSeries(fftData, freqAxis, suffix, true,
                effectiveProbes, cpList, userPlots, voltData, currData, probeUnits);
        return ParametricResultCache.store(
            new ParametricResultCache.ResultSet(
                "Frequency", "Hz", freqAxis, voltData, currData, probeUnits,
                true,    // log X
                true));  // log Y — spectra read log-log; Log toggles back to linear
    }

    /** Sorted positive frequencies of an FFT table, or null when unusable. */
    private static List<Double> fftAxisOf(Map<Double, Map<String, Double>> fftData) {
        if (fftData == null || fftData.isEmpty()) return null;
        List<Double> axis = new ArrayList<>(fftData.keySet());
        Collections.sort(axis);
        axis.removeIf(f -> f <= 0);   // drop the DC bin
        return axis.isEmpty() ? null : axis;
    }

    /** Clickable chat + book link opening the companion FFT spectrum session. */
    private void emitFftLink(ServerPlayer player, int fftSessionId,
                             List<Component> graphPageComponents) {
        String cmd = "/circuitsim graph " + fftSessionId + " 0";
        Style style = Style.EMPTY.withColor(ChatFormatting.DARK_AQUA)
                .withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, cmd));
        sendChatComponent(player,
            Component.literal("[Open FFT spectrum]").withStyle(style));
        graphPageComponents.add(
            Component.literal("Open FFT spectrum\n").withStyle(style));
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
        /**
         * {@code name=value} definitions for every scalar Param variable —
         * emitted as {@code .param} lines so the declarations are visible in
         * the netlist and usable from Commands-block expressions. The swept
         * variable's definition (seeded with its first value) is appended by
         * the control-loop runner.
         */
        final List<String>                     paramDefs;
        ApplyResult(CircuitExtractor.ExtractionResult e,
                    CircuitExtractor.ParametricInfo s, List<Double> v,
                    List<String> paramDefs) {
            this.extraction = e; this.sweepParam = s; this.sweepValues = v;
            this.paramDefs = paramDefs;
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

        // Scalar declarations become .param lines (Feature: "Param block").
        List<String> paramDefs = new ArrayList<>();
        for (Map.Entry<String, Double> e : constMap.entrySet()) {
            paramDefs.add(String.format(java.util.Locale.ROOT,
                    "%s=%g", e.getKey(), e.getValue()));
        }

        if (constMap.isEmpty()) {
            return new ApplyResult(extraction, sweep, sweepVals, paramDefs);
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
        return new ApplyResult(substituted, sweep, sweepVals, paramDefs);
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
            case "NOISE" -> msg(player,
                "Param sweeps are not supported with NOISE analysis. "
                    + "Use a single value for every Param variable.",
                ChatFormatting.RED);
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

    // -------------------------------------------------------------------------
    // Param-block sweep via a .control loop — ONE ngspice process that
    // foreach/alterparam/reset/runs over the swept .param. ngspice has no
    // PSpice-style .step card, so the loop is the native way to re-evaluate
    // a parameter per run; we echo a marker before each iteration and split
    // stdout on it to recover the per-step tables (NgSpiceRunner.runSweep).
    // -------------------------------------------------------------------------

    /**
     * True when every component slot referencing {@code var} is a plain
     * value/acValue slot. IC geometry slots (W/L/mult/nf) derive area and
     * perimeter parameters in Java, so they can't be expressed as a brace
     * {@code {param}} token — those sweeps use the legacy per-value runner.
     */
    private static boolean sweptSlotsAreSimple(
            List<NetlistBuilder.CircuitComponent> comps, String var) {
        for (NetlistBuilder.CircuitComponent c : comps) {
            if (var.equals(c.wExpr) || var.equals(c.lExpr)
                    || var.equals(c.multExpr) || var.equals(c.nfExpr)) return false;
        }
        return true;
    }

    /**
     * Wraps the netlist's {@code .control} body in a sweep loop:
     * <pre>
     *   foreach pv v1 v2 ...
     *     alterparam name = $pv
     *     reset
     *     echo PARAMSWEEP $pv
     *     &lt;original body: saves, run, lets, prints&gt;
     *   end
     * </pre>
     * {@code alterparam} updates the {@code .param}; {@code reset} re-expands
     * the circuit so the new value takes effect before {@code run} (verified
     * against ngspice-46). The original body stays inside the loop so its
     * pre-run {@code save} commands re-register after every reset.
     */
    private static String wrapControlWithSweep(String netlist, String varName,
                                               List<Double> values) {
        int ctrl = netlist.indexOf(".control\n");
        int endc = netlist.lastIndexOf(".endc");
        if (ctrl < 0 || endc <= ctrl) return netlist;
        int bodyStart = ctrl + ".control\n".length();
        String body = netlist.substring(bodyStart, endc);

        StringBuilder sb = new StringBuilder(body.length() + 256);
        sb.append("  foreach pv");
        for (double v : values) {
            sb.append(' ').append(String.format(java.util.Locale.ROOT, "%g", v));
        }
        sb.append('\n');
        sb.append("    alterparam ").append(varName).append(" = $pv\n");
        sb.append("    reset\n");
        sb.append("    echo ").append(NgSpiceRunner.SWEEP_MARKER).append(" $pv\n");
        for (String line : body.split("\n")) {
            if (line.isEmpty()) continue;
            sb.append("  ").append(line).append('\n');
        }
        sb.append("  end\n");
        return netlist.substring(0, bodyStart) + sb + netlist.substring(endc);
    }

    private void runParamControlSweep(
        ServerPlayer player,
        CircuitExtractor.ExtractionResult extraction,
        CircuitExtractor.ParametricInfo param,
        List<Double> sweepValues,
        double tempC,
        List<String> bookLines,
        List<Component> graphPageComponents
    ) {
        String varName = param.varName;
        String varUnit = unitForVariable(extraction.components, varName);
        List<NetlistBuilder.ProbeInfo> effectiveProbes = effectiveProbes(extraction);
        List<NetlistBuilder.CurrentProbeInfo> cpList = extraction.currentProbes;

        String header = "=== Param sweep: " + varName + " ("
                + sweepValues.size() + " values, " + analysis + ", one ngspice run) ===";
        msg(player, header, ChatFormatting.GOLD);
        bookLines.add(header);

        // Base netlist for the analysis. Components whose slot references the
        // swept variable emit {varName}, re-evaluated by every alterparam.
        String netlist;
        double dcStartV = 0, dcStopV = 0, dcStepV = 0;
        String dcSrc = "";
        switch (analysis) {
            case "AC" -> netlist = NetlistBuilder.buildAcNetlist(
                    extraction.components, effectiveProbes, cpList,
                    fStart, fStop, ptsPerDec,
                    pdkName, pdkLibPath, pdkLibPaths, ngBehavior,
                    extraction.userCommands, extraction.userPlots);
            case "TRAN" -> netlist = NetlistBuilder.buildTranNetlist(
                    extraction.components, effectiveProbes, cpList,
                    fStart, fStop,
                    pdkName, pdkLibPath, pdkLibPaths, ngBehavior,
                    extraction.userCommands, extraction.userPlots);
            case "DC" -> {
                try {
                    dcStartV = ComponentEditScreen.parseSI(dcStart1);
                    dcStopV  = ComponentEditScreen.parseSI(dcStop1);
                    dcStepV  = ComponentEditScreen.parseSI(dcStep1);
                } catch (NumberFormatException nfe) {
                    msg(player, "DC range fields must parse as numbers.", ChatFormatting.RED);
                    return;
                }
                if (dcStepV == 0) {
                    msg(player, "DC step cannot be zero.", ChatFormatting.RED);
                    return;
                }
                dcSrc = dcSource1 == null ? "" : dcSource1.trim();
                if (dcSrc.isEmpty()) {
                    msg(player, "DC sweep needs a source name (e.g. V1).", ChatFormatting.RED);
                    return;
                }
                netlist = NetlistBuilder.buildDcNetlist(
                        extraction.components, effectiveProbes, cpList,
                        dcSrc, dcStartV, dcStopV, dcStepV,
                        false, "", 0, 0, 1,
                        pdkName, pdkLibPath, pdkLibPaths, ngBehavior,
                        extraction.userCommands, extraction.userPlots);
            }
            default -> netlist = NetlistBuilder.buildNetlist(
                    extraction.components, effectiveProbes, cpList,
                    pdkName, pdkLibPath, pdkLibPaths, ngBehavior,
                    extraction.userCommands, extraction.userPlots);
        }

        // .param lines: every scalar definition plus the swept variable
        // seeded with its first value (alterparam re-drives it per step).
        List<String> defs = new ArrayList<>(activeParamDefs);
        defs.add(String.format(java.util.Locale.ROOT, "%s=%g", varName, sweepValues.get(0)));
        netlist = injectTemp(injectParams(injectSubcktDefs(netlist, activeSubcktDefs), defs), tempC);
        netlist = wrapControlWithSweep(netlist, varName, sweepValues);
        printNetlist(player, netlist, bookLines);

        NgSpiceRunner.SweepResult sw = NgSpiceRunner.runSweep(netlist, ngBehavior);
        if (sw.error != null) {
            String first = sw.error.lines().filter(l -> !l.isBlank())
                    .findFirst().orElse("unknown error");
            msg(player, "Simulation Error: " + first, ChatFormatting.RED);
            bookLines.add("Error: " + first);
            return;
        }
        int n = Math.min(sw.steps.size(), sweepValues.size());
        if (n == 0) {
            msg(player, "Sweep returned no per-step results.", ChatFormatting.RED);
            return;
        }
        if (sw.steps.size() != sweepValues.size()) {
            msg(player, "Sweep produced " + sw.steps.size() + " of "
                    + sweepValues.size() + " steps.", ChatFormatting.YELLOW);
        }

        Map<String, List<Double>> voltData   = new LinkedHashMap<>();
        Map<String, List<Double>> currData   = new LinkedHashMap<>();
        Map<String, String>       probeUnits = new LinkedHashMap<>();

        switch (analysis) {
            case "AC" -> {
                List<Double> freqAxis = null;
                for (int i = 0; i < n; i++) {
                    NgSpiceRunner.Result step = sw.steps.get(i);
                    if (step.acData.isEmpty()) continue;
                    List<Double> sorted = new ArrayList<>(step.acData.keySet());
                    Collections.sort(sorted);
                    if (freqAxis == null) freqAxis = sorted;
                    String suffix = "@" + ComponentEditScreen.formatValue(sweepValues.get(i)) + varUnit;
                    collectSweepSeries(step.acData, sorted, suffix, true,
                            effectiveProbes, cpList, extraction.userPlots,
                            voltData, currData, probeUnits);
                    echoExtras(player, step, bookLines);
                }
                if (freqAxis == null) return;
                storeAndLink(player, "Frequency", "Hz", freqAxis, true, -1,
                        voltData, currData, probeUnits, bookLines, graphPageComponents);
            }
            case "TRAN" -> {
                List<Double> timeAxis = null;
                // FFT companion accumulators — one spectrum per swept value,
                // overlaid in their own session like the time-domain curves.
                List<Double> fftAxis  = null;
                Map<String, List<Double>> fftVolt  = new LinkedHashMap<>();
                Map<String, List<Double>> fftCurr  = new LinkedHashMap<>();
                Map<String, String>       fftUnits = new LinkedHashMap<>();
                for (int i = 0; i < n; i++) {
                    NgSpiceRunner.Result step = sw.steps.get(i);
                    if (step.tranData.isEmpty()) continue;
                    List<Double> sorted = new ArrayList<>(step.tranData.keySet());
                    Collections.sort(sorted);
                    if (timeAxis == null) timeAxis = sorted;
                    String suffix = "@" + ComponentEditScreen.formatValue(sweepValues.get(i)) + varUnit;
                    collectSweepSeries(step.tranData, sorted, suffix, false,
                            effectiveProbes, cpList, extraction.userPlots,
                            voltData, currData, probeUnits);
                    List<Double> fa = fftAxisOf(step.fftData);
                    if (fa != null) {
                        if (fftAxis == null) fftAxis = fa;
                        collectSweepSeries(step.fftData, fa, suffix, true,
                                effectiveProbes, cpList, extraction.userPlots,
                                fftVolt, fftCurr, fftUnits);
                    }
                    echoExtras(player, step, bookLines);
                }
                if (timeAxis == null) return;
                int fftSession = -1;
                if (fftAxis != null) {
                    fftSession = ParametricResultCache.store(
                        new ParametricResultCache.ResultSet(
                            "Frequency", "Hz", fftAxis, fftVolt, fftCurr, fftUnits,
                            true, true));
                }
                storeAndLink(player, "Time", "s", timeAxis, false, fftSession,
                        voltData, currData, probeUnits, bookLines, graphPageComponents);
                if (fftSession >= 0) emitFftLink(player, fftSession, graphPageComponents);
            }
            case "DC" -> {
                List<Double> dcAxis = null;
                for (int i = 0; i < n; i++) {
                    NgSpiceRunner.Result step = sw.steps.get(i);
                    if (step.dcData.isEmpty()) continue;
                    List<Double> sorted = new ArrayList<>(step.dcData.keySet());
                    Collections.sort(sorted);
                    if (dcAxis == null) dcAxis = sorted;
                    String suffix = "@" + ComponentEditScreen.formatValue(sweepValues.get(i)) + varUnit;
                    collectSweepSeries(step.dcData, sorted, suffix, false,
                            effectiveProbes, cpList, extraction.userPlots,
                            voltData, currData, probeUnits);
                    echoExtras(player, step, bookLines);
                }
                if (dcAxis == null) return;
                String xUnit = Character.toUpperCase(dcSrc.charAt(0)) == 'I' ? "A" : "V";
                storeAndLink(player, dcSrc, xUnit, dcAxis, false, -1,
                        voltData, currData, probeUnits, bookLines, graphPageComponents);
            }
            default -> {   // OP: one scalar set per step → probe-vs-param plot
                List<Double> validSweep = new ArrayList<>();
                // One OP-annotation frame per swept value, so the player can
                // step through them in the K menu (see sendOpFrames below).
                List<NetlistBuilder.DeviceRef> opRefs =
                        NetlistBuilder.describeDevices(extraction.components);
                List<OperatingPointPacket.Frame> opFrames = new ArrayList<>();
                for (NetlistBuilder.ProbeInfo p : plotted(effectiveProbes))
                    voltData.put(p.label, new ArrayList<>());
                for (int k = 0; k < cpList.size(); k++)
                    currData.put(cpList.get(k).label, new ArrayList<>());
                for (NetlistBuilder.UserPlot plot : extraction.userPlots) {
                    voltData.put(plot.label, new ArrayList<>());
                    probeUnits.put(plot.label, plot.unit);
                }
                for (int i = 0; i < n; i++) {
                    NgSpiceRunner.Result step = sw.steps.get(i);
                    if (step.values.isEmpty() && step.extrasByName.isEmpty()) continue;
                    validSweep.add(sweepValues.get(i));
                    if (!step.deviceOps.isEmpty()) {
                        opFrames.add(opFrame(opRefs,
                                varName + "=" + ComponentEditScreen.formatValue(sweepValues.get(i)) + varUnit,
                                step.deviceOps));
                    }
                    String secHdr = "--- " + varName + "="
                            + ComponentEditScreen.formatValue(sweepValues.get(i)) + varUnit + " ---";
                    msg(player, secHdr, ChatFormatting.YELLOW);
                    bookLines.add(secHdr);
                    for (NetlistBuilder.ProbeInfo probe : plotted(effectiveProbes)) {
                        Double v = step.values.get("v(" + probe.netName + ")");
                        voltData.get(probe.label).add(v != null ? v : 0.0);
                        String line = "  [" + probe.label + "]: " + step.getNodeVoltage(probe.netName);
                        msg(player, line, ChatFormatting.AQUA);
                        bookLines.add(line);
                    }
                    for (int k = 0; k < cpList.size(); k++) {
                        Double v = step.values.get("i(vm" + (k + 1) + ")");
                        currData.get(cpList.get(k).label).add(v != null ? v : 0.0);
                        String line = "  [" + cpList.get(k).label + "]: " + step.getBranchCurrent("vm" + (k + 1));
                        msg(player, line, ChatFormatting.LIGHT_PURPLE);
                        bookLines.add(line);
                    }
                    for (NetlistBuilder.UserPlot plot : extraction.userPlots) {
                        Double v = step.extrasByName.get(plot.name);
                        voltData.get(plot.label).add(v != null ? v : 0.0);
                    }
                    echoExtras(player, step, bookLines);
                }
                sendOpFrames(player, opFrames);
                if (validSweep.isEmpty()) return;
                storeAndLink(player, varName, varUnit, validSweep, false, -1,
                        voltData, currData, probeUnits, bookLines, graphPageComponents);
            }
        }
    }

    /**
     * Extracts one sweep step's probe/current/user-plot series from a parsed
     * table ({@code acData}/{@code tranData}/{@code dcData}) into the shared
     * accumulators, naming each series {@code label + suffix}. {@code mag}
     * selects the AC "_mag"-suffixed keys.
     */
    private static void collectSweepSeries(
        Map<Double, Map<String, Double>> table,
        List<Double> axis,
        String suffix,
        boolean mag,
        List<NetlistBuilder.ProbeInfo> effectiveProbes,
        List<NetlistBuilder.CurrentProbeInfo> cpList,
        List<NetlistBuilder.UserPlot> userPlots,
        Map<String, List<Double>> voltData,
        Map<String, List<Double>> currData,
        Map<String, String> probeUnits
    ) {
        String tail = mag ? "_mag" : "";
        for (NetlistBuilder.ProbeInfo probe : plotted(effectiveProbes)) {
            String key = "v(" + probe.netName + ")" + tail;
            List<Double> vals = new ArrayList<>(axis.size());
            for (double x : axis) {
                Map<String, Double> row = table.get(x);
                Double v = row != null ? row.get(key) : null;
                vals.add(v != null ? v : 0.0);
            }
            voltData.put(probe.label + suffix, vals);
        }
        for (int k = 0; k < cpList.size(); k++) {
            String key = "i(vm" + (k + 1) + ")" + tail;
            List<Double> vals = new ArrayList<>(axis.size());
            for (double x : axis) {
                Map<String, Double> row = table.get(x);
                Double v = row != null ? row.get(key) : null;
                vals.add(v != null ? v : 0.0);
            }
            currData.put(cpList.get(k).label + suffix, vals);
        }
        for (NetlistBuilder.UserPlot plot : userPlots) {
            String key = plot.name + tail;
            List<Double> vals = new ArrayList<>(axis.size());
            for (double x : axis) {
                Map<String, Double> row = table.get(x);
                Double v = row != null ? row.get(key) : null;
                vals.add(v != null ? v : 0.0);
            }
            voltData.put(plot.label + suffix, vals);
            probeUnits.put(plot.label + suffix, plot.unit);
        }
    }

    /** Per-step scalar print/meas echo shared by the sweep collectors. */
    private void echoExtras(ServerPlayer player, NgSpiceRunner.Result step,
                            List<String> bookLines) {
        for (String extra : step.extras) {
            String line = "  " + extra;
            msg(player, line, ChatFormatting.LIGHT_PURPLE);
            bookLines.add(line);
        }
    }

    /** Stores the accumulated sweep session and emits the chat links. */
    private void storeAndLink(
        ServerPlayer player,
        String xName, String xUnit, List<Double> axis, boolean logX,
        int fftSessionId,
        Map<String, List<Double>> voltData,
        Map<String, List<Double>> currData,
        Map<String, String> probeUnits,
        List<String> bookLines,
        List<Component> graphPageComponents
    ) {
        msg(player, "Param sweep complete. " + (voltData.size() + currData.size())
                + " series.", ChatFormatting.GREEN);
        ParametricResultCache.ResultSet rs = new ParametricResultCache.ResultSet(
                xName, xUnit, axis, voltData, currData, probeUnits, logX);
        rs.fftSessionId = fftSessionId;
        int sessionId = ParametricResultCache.store(rs);
        emitGraphLinks(player, sessionId, voltData, currData, probeUnits, graphPageComponents);
        emitOutputViewerLink(player, sessionId, bookLines,
                "CircuitSim Output (param sweep " + analysis + ")");
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
        // One OP-annotation frame per (value, temp) combination for the K menu.
        List<NetlistBuilder.DeviceRef> opRefs =
                NetlistBuilder.describeDevices(extraction.components);
        List<OperatingPointPacket.Frame> opFrames = new ArrayList<>();
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
                netlist = prepareNetlist(netlist, T);
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
                if (result != null && !result.deviceOps.isEmpty()) {
                    String fl = ComponentEditScreen.formatValue(val) + varUnit
                            + (multiTemp ? " @" + ComponentEditScreen.formatValue(T) + "C" : "");
                    opFrames.add(opFrame(opRefs, fl, result.deviceOps));
                }
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

        sendOpFrames(player, opFrames);
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
                netlist = prepareNetlist(netlist, T);
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
        // FFT companion accumulators — one ngspice-computed spectrum per
        // iteration, overlaid in their own session.
        List<Double> fftAxis  = null;
        Map<String, List<Double>> fftVolt  = new LinkedHashMap<>();
        Map<String, List<Double>> fftCurr  = new LinkedHashMap<>();
        Map<String, String>       fftUnits = new LinkedHashMap<>();

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
                netlist = prepareNetlist(netlist, T);
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
                List<Double> fa = fftAxisOf(result.fftData);
                if (fa != null) {
                    if (fftAxis == null) fftAxis = fa;
                    collectSweepSeries(result.fftData, fa, stepSuffix, true,
                            effectiveProbes, cpList, extraction.userPlots,
                            fftVolt, fftCurr, fftUnits);
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
        int fftSession = -1;
        if (fftAxis != null) {
            fftSession = ParametricResultCache.store(
                new ParametricResultCache.ResultSet(
                    "Frequency", "Hz", fftAxis, fftVolt, fftCurr, fftUnits,
                    true, true));
        }
        ParametricResultCache.ResultSet tranRs = new ParametricResultCache.ResultSet(
                "Time", "s", timeAxis, voltData, currData, probeUnits, false);
        tranRs.fftSessionId = fftSession;
        int sessionId = ParametricResultCache.store(tranRs);
        emitGraphLinks(
            player,
            sessionId,
            voltData,
            currData,
            probeUnits,
            graphPageComponents
        );
        if (fftSession >= 0) emitFftLink(player, fftSession, graphPageComponents);
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
        // One OP-annotation frame per temperature for the K menu.
        List<NetlistBuilder.DeviceRef> opRefs =
                NetlistBuilder.describeDevices(extraction.components);
        List<OperatingPointPacket.Frame> opFrames = new ArrayList<>();

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
            netlist = prepareNetlist(netlist, T);
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
            if (!result.deviceOps.isEmpty()) {
                opFrames.add(opFrame(opRefs,
                        ComponentEditScreen.formatValue(T) + "C", result.deviceOps));
            }
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

        sendOpFrames(player, opFrames);
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
            netlist = prepareNetlist(netlist, T);
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
        // FFT companion accumulators — one spectrum per temperature.
        List<Double> fftAxis  = null;
        Map<String, List<Double>> fftVolt  = new LinkedHashMap<>();
        Map<String, List<Double>> fftCurr  = new LinkedHashMap<>();
        Map<String, String>       fftUnits = new LinkedHashMap<>();

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
            netlist = prepareNetlist(netlist, T);
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
            List<Double> fa = fftAxisOf(result.fftData);
            if (fa != null) {
                if (fftAxis == null) fftAxis = fa;
                collectSweepSeries(result.fftData, fa, suffix, true,
                        effectiveProbes, cpList, extraction.userPlots,
                        fftVolt, fftCurr, fftUnits);
            }
        }

        if (timeAxis == null || timeAxis.isEmpty()) return;
        int fftSession = -1;
        if (fftAxis != null) {
            fftSession = ParametricResultCache.store(
                new ParametricResultCache.ResultSet(
                    "Frequency", "Hz", fftAxis, fftVolt, fftCurr, fftUnits,
                    true, true));
        }
        ParametricResultCache.ResultSet tranRs = new ParametricResultCache.ResultSet(
                "Time", "s", timeAxis, voltData, currData, probeUnits, false);
        tranRs.fftSessionId = fftSession;
        int sessionId = ParametricResultCache.store(tranRs);
        emitGraphLinks(player, sessionId, voltData, currData, probeUnits, graphPageComponents);
        if (fftSession >= 0) emitFftLink(player, fftSession, graphPageComponents);
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
     * Builds the {@link OperatingPointPacket} from the run's {@code show}-table
     * device operating points and sends it to {@code player}. Each ngspice
     * device name is mapped back to its source block via
     * {@link NetlistBuilder#describeDevices}; names that don't resolve to a
     * physical block (devices buried inside a user subcircuit) are dropped.
     * Runs on the sim worker thread, so the actual send hops to the main
     * thread like {@link #sendChatComponent}.
     */
    private static void sendOperatingPoints(
        ServerPlayer player,
        CircuitExtractor.ExtractionResult extraction,
        NgSpiceRunner.Result result
    ) {
        List<NetlistBuilder.DeviceRef> refs =
            NetlistBuilder.describeDevices(extraction.components);
        List<OperatingPointPacket.Frame> frames = new ArrayList<>();
        if (!result.deviceOps.isEmpty()) {
            frames.add(opFrame(refs, "operating point", result.deviceOps));
        }
        sendOpFrames(player, frames);
    }

    /**
     * Builds one annotation frame by mapping each {@code show}-table device name
     * back to its source block. Devices that don't resolve to a physical block
     * (those buried inside a user subcircuit) are dropped.
     */
    private static OperatingPointPacket.Frame opFrame(
        List<NetlistBuilder.DeviceRef> refs,
        String label,
        Map<String, LinkedHashMap<String, Double>> deviceOps
    ) {
        List<OperatingPointPacket.Entry> entries = new ArrayList<>();
        java.util.Set<BlockPos> seen = new java.util.HashSet<>();
        for (Map.Entry<String, LinkedHashMap<String, Double>> e : deviceOps.entrySet()) {
            NetlistBuilder.DeviceRef ref = NetlistBuilder.matchShowDevice(e.getKey(), refs);
            if (ref == null) continue;
            // A subcircuit can hold several devices of the same class (a main +
            // a sense FET); keep the first that maps to each block so the stored
            // operating point is deterministic.
            if (!seen.add(ref.pos())) continue;
            entries.add(new OperatingPointPacket.Entry(
                ref.pos(), ref.typeKey(), ref.label(), e.getValue()));
        }
        return new OperatingPointPacket.Frame(label, entries);
    }

    /** Sends the assembled frames to {@code player}, hopping to the main thread. */
    private static void sendOpFrames(
        ServerPlayer player, List<OperatingPointPacket.Frame> frames
    ) {
        OperatingPointPacket pkt = new OperatingPointPacket(frames);
        MinecraftServer s = player.getServer();
        if (s != null && !s.isSameThread()) {
            s.execute(() -> ModMessages.sendToPlayer(player, pkt));
        } else {
            ModMessages.sendToPlayer(player, pkt);
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
     * Standard netlist post-processing applied by every analysis path:
     * scalar {@code .param} declarations from Param blocks, then the
     * {@code .temp} override.
     */
    private String prepareNetlist(String netlist, double tempC) {
        return injectTemp(injectParams(injectSubcktDefs(netlist, activeSubcktDefs), activeParamDefs), tempC);
    }

    /**
     * Splices each user-defined {@code .subckt … .ends} block into the deck
     * right after the title line (top level, ahead of the components that
     * instantiate them via {@code X…}). ngspice gathers subcircuit definitions
     * during parsing regardless of position; top placement keeps the deck
     * readable. No-op when there are no subcircuits.
     */
    private static String injectSubcktDefs(String netlist, List<String> defs) {
        if (defs == null || defs.isEmpty()) return netlist;
        int nl = netlist.indexOf('\n');
        if (nl < 0) return netlist;
        StringBuilder sb = new StringBuilder(netlist.length() + 256);
        sb.append(netlist, 0, nl + 1);
        for (String d : defs) {
            sb.append(d);
            if (!d.endsWith("\n")) sb.append('\n');
        }
        sb.append(netlist, nl + 1, netlist.length());
        return sb.toString();
    }

    /**
     * Splices one {@code .param name=value} line per definition right after
     * the netlist's title line. ngspice gathers {@code .param} during parsing
     * regardless of position, but placing them at the top keeps the netlist
     * readable and ahead of any brace expression that references them.
     */
    private static String injectParams(String netlist, List<String> defs) {
        if (defs == null || defs.isEmpty()) return netlist;
        int nl = netlist.indexOf('\n');
        if (nl < 0) return netlist;
        StringBuilder sb = new StringBuilder(netlist.length() + defs.size() * 24);
        sb.append(netlist, 0, nl + 1);
        for (String d : defs) sb.append(".param ").append(d).append('\n');
        sb.append(netlist, nl + 1, netlist.length());
        return sb.toString();
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
                        || trimmed.startsWith(".dc ")
                        || trimmed.startsWith(".noise ")) {
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
