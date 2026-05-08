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
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.network.NetworkEvent;

public class SimulatePacket {

    private final BlockPos pos;
    private final String analysis; // "OP", "AC", or "TRAN"
    private final double fStart; // AC: fStart  |  TRAN: tstep
    private final double fStop; // AC: fStop   |  TRAN: tstop
    private final int ptsPerDec; // AC: pts/dec |  TRAN: unused (0)
    private final String pdkName; // "none", "sky130A", "placeholder"
    private final String pdkLibPath; // path to .lib file
    private final String ngBehavior; // ngspice compat mode: hsa, ps, hs, lt, ki, va
    private final String rawParam1; // raw UI strings preserved for round-trip display
    private final String rawParam2;
    private final String rawParam3;

    public SimulatePacket(
        BlockPos pos,
        String analysis,
        double fStart,
        double fStop,
        int ptsPerDec,
        String pdkName,
        String pdkLibPath,
        String ngBehavior,
        String rawParam1,
        String rawParam2,
        String rawParam3
    ) {
        this.pos = pos;
        this.analysis = analysis;
        this.fStart = fStart;
        this.fStop = fStop;
        this.ptsPerDec = ptsPerDec;
        this.pdkName = pdkName;
        this.pdkLibPath = pdkLibPath;
        this.ngBehavior = ngBehavior;
        this.rawParam1 = rawParam1;
        this.rawParam2 = rawParam2;
        this.rawParam3 = rawParam3;
    }

    public SimulatePacket(FriendlyByteBuf buf) {
        this.pos = buf.readBlockPos();
        this.analysis = buf.readUtf(8);
        this.fStart = buf.readDouble();
        this.fStop = buf.readDouble();
        this.ptsPerDec = buf.readInt();
        this.pdkName = buf.readUtf(32);
        this.pdkLibPath = buf.readUtf(512);
        this.ngBehavior = buf.readUtf(8);
        this.rawParam1 = buf.readUtf(32);
        this.rawParam2 = buf.readUtf(32);
        this.rawParam3 = buf.readUtf(32);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeUtf(analysis, 8);
        buf.writeDouble(fStart);
        buf.writeDouble(fStop);
        buf.writeInt(ptsPerDec);
        buf.writeUtf(pdkName, 32);
        buf.writeUtf(pdkLibPath, 512);
        buf.writeUtf(ngBehavior, 8);
        buf.writeUtf(rawParam1, 32);
        buf.writeUtf(rawParam2, 32);
        buf.writeUtf(rawParam3, 32);
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
            simCbe.setNgBehavior(ngBehavior);
            simCbe.setSimAnalysis(analysis);
            simCbe.setSimParam1(rawParam1);
            simCbe.setSimParam2(rawParam2);
            simCbe.setSimParam3(rawParam3);
            simCbe.setChanged();
            simCbe.syncToClient();
        }

        msg(
            player,
            "=== Circuit Simulation (" + analysis + ") ===",
            ChatFormatting.GOLD
        );

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

        List<String> bookLines = new ArrayList<>();
        List<Component> graphPageComponents = new ArrayList<>();

        String timestamp = LocalDateTime.now().format(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        );
        bookLines.add("CircuitSim Results (" + analysis + ")");
        bookLines.add(timestamp);
        bookLines.add("---");

        if (!extraction.parametricBlocks.isEmpty()) {
            for (CircuitExtractor.ParametricInfo param : extraction.parametricBlocks) {
                runParametricSweep(
                    player,
                    level,
                    extraction,
                    param,
                    bookLines,
                    graphPageComponents
                );
            }
        } else {
            switch (analysis) {
                case "AC" -> runAcSimulation(
                    player,
                    extraction,
                    bookLines,
                    graphPageComponents
                );
                case "TRAN" -> runTranSimulation(
                    player,
                    extraction,
                    bookLines,
                    graphPageComponents
                );
                default -> runOpSimulation(player, extraction, bookLines);
            }
        }

        giveResultBook(
            player,
            analysis + " " + timestamp,
            bookLines,
            graphPageComponents
        );
    }

    // -------------------------------------------------------------------------
    // .OP
    // -------------------------------------------------------------------------

    private void runOpSimulation(
        ServerPlayer player,
        CircuitExtractor.ExtractionResult extraction,
        List<String> bookLines
    ) {
        String netlist = NetlistBuilder.buildNetlist(
            extraction.components,
            extraction.probes,
            extraction.currentProbes,
            pdkName,
            pdkLibPath
        );
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
        for (NetlistBuilder.ProbeInfo probe : extraction.probes) {
            String line =
                "Probe [" +
                probe.label +
                "] Node " +
                probe.node +
                ": " +
                result.getNodeVoltage(probe.node);
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
    }

    // -------------------------------------------------------------------------
    // .AC
    // -------------------------------------------------------------------------

    private void runAcSimulation(
        ServerPlayer player,
        CircuitExtractor.ExtractionResult extraction,
        List<String> bookLines,
        List<Component> graphPageComponents
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
            pdkLibPath
        );
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
            for (NetlistBuilder.ProbeInfo probe : effectiveProbes) {
                String key = "v(" + probe.node + ")_mag";
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

        Map<String, List<Double>> voltageData = new LinkedHashMap<>();
        Map<String, List<Double>> currentData = new LinkedHashMap<>();

        for (NetlistBuilder.ProbeInfo probe : effectiveProbes) {
            List<Double> mags = new ArrayList<>();
            for (double f : freqAxis) {
                Map<String, Double> vals = result.acData.get(f);
                Double v =
                    vals != null ? vals.get("v(" + probe.node + ")_mag") : null;
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

        int sessionId = ParametricResultCache.store(
            new ParametricResultCache.ResultSet(
                "Frequency",
                "Hz",
                freqAxis,
                voltageData,
                currentData,
                true
            )
        );
        emitGraphLinks(
            player,
            sessionId,
            voltageData,
            currentData,
            graphPageComponents
        );
    }

    // -------------------------------------------------------------------------
    // .TRAN
    // -------------------------------------------------------------------------

    private void runTranSimulation(
        ServerPlayer player,
        CircuitExtractor.ExtractionResult extraction,
        List<String> bookLines,
        List<Component> graphPageComponents
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
            pdkLibPath
        );
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
            for (NetlistBuilder.ProbeInfo probe : effectiveProbes) {
                String key = "v(" + probe.node + ")";
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

        for (NetlistBuilder.ProbeInfo probe : effectiveProbes) {
            List<Double> vals = new ArrayList<>();
            for (double t : timeAxis) {
                Map<String, Double> row = result.tranData.get(t);
                Double v =
                    row != null ? row.get("v(" + probe.node + ")") : null;
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

        int sessionId = ParametricResultCache.store(
            new ParametricResultCache.ResultSet(
                "Time",
                "s",
                timeAxis,
                voltageData,
                currentData,
                false
            )
        );
        emitGraphLinks(
            player,
            sessionId,
            voltageData,
            currentData,
            graphPageComponents
        );
    }

    // -------------------------------------------------------------------------
    // Parametric dispatcher
    // -------------------------------------------------------------------------

    private void runParametricSweep(
        ServerPlayer player,
        Level level,
        CircuitExtractor.ExtractionResult extraction,
        CircuitExtractor.ParametricInfo param,
        List<String> bookLines,
        List<Component> graphPageComponents
    ) {
        BlockPos targetPos = param.targetPos;
        Block targetBlock = level.getBlockState(targetPos).getBlock();

        if (!isParametrizable(targetBlock)) {
            msg(
                player,
                "Parametric block not facing a supported component.",
                ChatFormatting.RED
            );
            return;
        }

        List<Double> sweepValues;
        try {
            sweepValues = parseSweepString(param.sweepString);
        } catch (IllegalArgumentException e) {
            msg(
                player,
                "Invalid sweep string: " + e.getMessage(),
                ChatFormatting.RED
            );
            return;
        }
        if (sweepValues.isEmpty()) {
            msg(
                player,
                "No sweep values — right-click the Parametric block to set them.",
                ChatFormatting.RED
            );
            return;
        }
        if (sweepValues.size() > 50) {
            msg(
                player,
                "Too many sweep values (" + sweepValues.size() + "); max 50.",
                ChatFormatting.RED
            );
            return;
        }

        List<NetlistBuilder.ProbeInfo> effectiveProbes = effectiveProbes(
            extraction
        );
        List<NetlistBuilder.CurrentProbeInfo> cpList = extraction.currentProbes;

        String header =
            "=== Parametric: " +
            displayName(targetBlock) +
            " sweep (" +
            sweepValues.size() +
            " pts, " +
            analysis +
            ") ===";
        msg(player, header, ChatFormatting.GOLD);
        bookLines.add(header);

        switch (analysis) {
            case "AC" -> runParametricAcSweep(
                player,
                extraction,
                targetPos,
                targetBlock,
                sweepValues,
                effectiveProbes,
                cpList,
                bookLines,
                graphPageComponents
            );
            case "TRAN" -> runParametricTranSweep(
                player,
                extraction,
                targetPos,
                targetBlock,
                sweepValues,
                effectiveProbes,
                cpList,
                bookLines,
                graphPageComponents
            );
            default -> runParametricOpSweep(
                player,
                extraction,
                targetPos,
                targetBlock,
                sweepValues,
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
        BlockPos targetPos,
        Block targetBlock,
        List<Double> sweepValues,
        List<NetlistBuilder.ProbeInfo> effectiveProbes,
        List<NetlistBuilder.CurrentProbeInfo> cpList,
        List<String> bookLines,
        List<Component> graphPageComponents
    ) {
        List<Double> validSweep = new ArrayList<>();
        Map<String, List<Double>> voltData = new LinkedHashMap<>();
        Map<String, List<Double>> currData = new LinkedHashMap<>();
        for (NetlistBuilder.ProbeInfo p : effectiveProbes)
            voltData.put(p.label, new ArrayList<>());
        for (NetlistBuilder.CurrentProbeInfo c : cpList)
            currData.put(c.label, new ArrayList<>());

        for (double val : sweepValues) {
            String secHdr =
                "--- " +
                ComponentEditScreen.formatValue(val) +
                unit(targetBlock) +
                " ---";
            msg(player, secHdr, ChatFormatting.YELLOW);
            bookLines.add(secHdr);

            NgSpiceRunner.Result result = NgSpiceRunner.run(
                NetlistBuilder.buildNetlist(
                    swapValue(extraction.components, targetPos, val),
                    effectiveProbes,
                    cpList,
                    pdkName,
                    pdkLibPath
                ),
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
            if (result.values.isEmpty()) {
                msg(
                    player,
                    "  (no results — verify Ground block)",
                    ChatFormatting.GRAY
                );
                bookLines.add("  (no results)");
                continue;
            }

            validSweep.add(val);
            for (NetlistBuilder.ProbeInfo probe : effectiveProbes) {
                String line =
                    "  [" +
                    probe.label +
                    "]: " +
                    result.getNodeVoltage(probe.node);
                msg(player, line, ChatFormatting.AQUA);
                bookLines.add(line);
                Double v = result.values.get("v(" + probe.node + ")");
                voltData.get(probe.label).add(v != null ? v : 0.0);
            }
            for (int k = 0; k < cpList.size(); k++) {
                String line =
                    "  [" +
                    cpList.get(k).label +
                    "]: " +
                    result.getBranchCurrent("vm" + (k + 1));
                msg(player, line, ChatFormatting.LIGHT_PURPLE);
                bookLines.add(line);
                Double i = result.values.get("i(vm" + (k + 1) + ")");
                currData.get(cpList.get(k).label).add(i != null ? i : 0.0);
            }
        }

        if (validSweep.isEmpty()) return;
        int sessionId = ParametricResultCache.store(
            new ParametricResultCache.ResultSet(
                displayName(targetBlock),
                unit(targetBlock),
                validSweep,
                voltData,
                currData,
                false
            )
        );
        emitGraphLinks(
            player,
            sessionId,
            voltData,
            currData,
            graphPageComponents
        );
    }

    // --- Parametric .AC ---

    private void runParametricAcSweep(
        ServerPlayer player,
        CircuitExtractor.ExtractionResult extraction,
        BlockPos targetPos,
        Block targetBlock,
        List<Double> sweepValues,
        List<NetlistBuilder.ProbeInfo> effectiveProbes,
        List<NetlistBuilder.CurrentProbeInfo> cpList,
        List<String> bookLines,
        List<Component> graphPageComponents
    ) {
        List<Double> freqAxis = null;
        Map<String, List<Double>> voltData = new LinkedHashMap<>();
        Map<String, List<Double>> currData = new LinkedHashMap<>();

        for (double val : sweepValues) {
            String secHdr =
                "--- " +
                ComponentEditScreen.formatValue(val) +
                unit(targetBlock) +
                " (AC) ---";
            msg(player, secHdr, ChatFormatting.YELLOW);
            bookLines.add(secHdr);

            String netlist = NetlistBuilder.buildAcNetlist(
                swapValue(extraction.components, targetPos, val),
                effectiveProbes,
                cpList,
                fStart,
                fStop,
                ptsPerDec,
                pdkName,
                pdkLibPath
            );

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
                "@" + ComponentEditScreen.formatValue(val) + unit(targetBlock);
            for (NetlistBuilder.ProbeInfo probe : effectiveProbes) {
                String seriesName = probe.label + stepSuffix;
                String vKey = "v(" + probe.node + ")_mag";
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
                true
            )
        );
        emitGraphLinks(
            player,
            sessionId,
            voltData,
            currData,
            graphPageComponents
        );
    }

    // --- Parametric .TRAN ---

    private void runParametricTranSweep(
        ServerPlayer player,
        CircuitExtractor.ExtractionResult extraction,
        BlockPos targetPos,
        Block targetBlock,
        List<Double> sweepValues,
        List<NetlistBuilder.ProbeInfo> effectiveProbes,
        List<NetlistBuilder.CurrentProbeInfo> cpList,
        List<String> bookLines,
        List<Component> graphPageComponents
    ) {
        double tstep = fStart;
        double tstop = fStop;

        List<Double> timeAxis = null;
        Map<String, List<Double>> voltData = new LinkedHashMap<>();
        Map<String, List<Double>> currData = new LinkedHashMap<>();

        for (double val : sweepValues) {
            String secHdr =
                "--- " +
                ComponentEditScreen.formatValue(val) +
                unit(targetBlock) +
                " (TRAN) ---";
            msg(player, secHdr, ChatFormatting.YELLOW);
            bookLines.add(secHdr);

            String netlist = NetlistBuilder.buildTranNetlist(
                swapValue(extraction.components, targetPos, val),
                effectiveProbes,
                cpList,
                tstep,
                tstop,
                pdkName,
                pdkLibPath
            );

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
                "@" + ComponentEditScreen.formatValue(val) + unit(targetBlock);
            for (NetlistBuilder.ProbeInfo probe : effectiveProbes) {
                String seriesName = probe.label + stepSuffix;
                String vKey = "v(" + probe.node + ")";
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
                false
            )
        );
        emitGraphLinks(
            player,
            sessionId,
            voltData,
            currData,
            graphPageComponents
        );
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

    private List<NetlistBuilder.ProbeInfo> effectiveProbes(
        CircuitExtractor.ExtractionResult ex
    ) {
        if (!ex.probes.isEmpty()) return ex.probes;
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
            String cmd = "/circuitsim graph " + sessionId + " " + idx;
            String label = "  Plot " + name + (isVolt ? " (V)" : " (A)");

            player.displayClientMessage(
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
                ),
                false
            );

            graphPageComponents.add(
                Component.literal(
                    "Plot " + name + (isVolt ? " (V)" : " (A)") + "\n"
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

    private static List<NetlistBuilder.CircuitComponent> swapValue(
        List<NetlistBuilder.CircuitComponent> components,
        BlockPos targetPos,
        double newVal
    ) {
        return components
            .stream()
            .map(c ->
                c.pos.equals(targetPos)
                    ? new NetlistBuilder.CircuitComponent(
                          c.block,
                          c.pos,
                          c.nodeA,
                          c.nodeB,
                          newVal,
                          c.sourceType,
                          c.frequency
                      )
                    : c
            )
            .collect(Collectors.toList());
    }

    private static void msg(ServerPlayer p, String text, ChatFormatting fmt) {
        p.displayClientMessage(Component.literal(text).withStyle(fmt), false);
    }

    private static boolean isParametrizable(Block b) {
        return (
            b instanceof ResistorBlock ||
            b instanceof CapacitorBlock ||
            b instanceof InductorBlock ||
            b instanceof VoltageSourceBlock ||
            b instanceof VoltageSourceSinBlock ||
            b instanceof CurrentSourceBlock
        );
    }

    private static String displayName(Block b) {
        if (b instanceof ResistorBlock) return "Resistor";
        if (b instanceof CapacitorBlock) return "Capacitor";
        if (b instanceof InductorBlock) return "Inductor";
        if (b instanceof VoltageSourceBlock) return "Voltage Source";
        if (b instanceof VoltageSourceSinBlock) return "SIN Voltage Source";
        if (b instanceof CurrentSourceBlock) return "Current Source";
        return "Component";
    }

    private static String unit(Block b) {
        if (b instanceof ResistorBlock) return "\u03A9";
        if (b instanceof CapacitorBlock) return "F";
        if (b instanceof InductorBlock) return "H";
        if (b instanceof VoltageSourceBlock) return "V";
        if (b instanceof VoltageSourceSinBlock) return "V";
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
}
