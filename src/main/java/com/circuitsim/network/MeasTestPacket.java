package com.circuitsim.network;

import com.circuitsim.blockentity.ComponentBlockEntity;
import com.circuitsim.screen.ComponentEditScreen;
import com.circuitsim.screen.MeasCatalog;
import com.circuitsim.simulation.CircuitExtractor;
import com.circuitsim.simulation.NetlistBuilder;
import com.circuitsim.simulation.NgSpiceRunner;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Server-bound: "run the circuit's configured analysis with these candidate
 * measurement lines appended, and tell me what they evaluate to" — the
 * measurement builder's Test button. The deck is the one the Simulate block
 * would run (its saved analysis + parameters, PDK, temperature, saved Commands
 * text), plus the candidate lines and a {@code print} per result name so every
 * value shows up in stdout. The reply ({@link MeasTestResultPacket}) carries
 * the matched {@code name = value} lines.
 *
 * <p>Candidate {@code plot NAME = EXPR} directives are rewritten to
 * {@code let} here: raw {@code plot} inside a control block is ngspice's
 * graphics command, while the directive form is normally translated by the
 * extractor — which only sees the block's <i>saved</i> text, not these
 * not-yet-saved lines.
 */
public class MeasTestPacket {

    private static final int MAX_LINES = 64;

    private final BlockPos pos;
    private final List<String> candidateLines;

    public MeasTestPacket(BlockPos pos, List<String> candidateLines) {
        this.pos            = pos;
        this.candidateLines = candidateLines;
    }

    public MeasTestPacket(FriendlyByteBuf buf) {
        this.pos = buf.readBlockPos();
        int n = Math.min(buf.readVarInt(), MAX_LINES);
        candidateLines = new ArrayList<>(n);
        for (int i = 0; i < n; i++) candidateLines.add(buf.readUtf(512));
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        int n = Math.min(candidateLines.size(), MAX_LINES);
        buf.writeVarInt(n);
        for (int i = 0; i < n; i++) buf.writeUtf(candidateLines.get(i), 512);
    }

    public static MeasTestPacket decode(FriendlyByteBuf buf) {
        return new MeasTestPacket(buf);
    }

    public void handle(NetworkEvent.Context ctx) {
        ServerPlayer player = ctx.getSender();
        if (player == null) return;
        Level level = player.level();
        if (!level.isLoaded(pos)) return;

        CircuitExtractor.ExtractionResult ex = CircuitExtractor.extract(level, pos);
        if (!ex.success) {
            reply(player, List.of("Test failed: " + ex.errorMessage));
            return;
        }
        if (ex.simulatePos == null) {
            reply(player, List.of("Test failed: no Simulate block in this circuit."));
            return;
        }
        BlockEntity be = level.getBlockEntity(ex.simulatePos);
        if (!(be instanceof ComponentBlockEntity sim)) {
            reply(player, List.of("Test failed: Simulate block has no data."));
            return;
        }

        // Snapshot every BE-backed setting on the main thread; the build+run
        // happens off-thread like a normal simulation.
        final String analysis    = sim.getSimAnalysis();
        final String pdkName     = sim.getPdkName();
        final String pdkLibPath  = sim.getPdkLibPath();
        final String pdkLibPaths = sim.getPdkLibPaths();
        final String ngBehavior  = sim.getNgBehavior();
        final String simTemp     = sim.getSimTemp();
        final String acP1 = sim.getSimAcParam1(),   acP2 = sim.getSimAcParam2(),
                     acP3 = sim.getSimAcParam3();
        final String trP1 = sim.getSimTranParam1(), trP2 = sim.getSimTranParam2();
        final String dcSrc = sim.getDcSource1(), dcStart = sim.getDcStart1(),
                     dcStop = sim.getDcStop1(), dcStep = sim.getDcStep1();

        Thread worker = new Thread(
            () -> runTest(player, ex, analysis, pdkName, pdkLibPath, pdkLibPaths,
                    ngBehavior, simTemp, acP1, acP2, acP3, trP1, trP2,
                    dcSrc, dcStart, dcStop, dcStep),
            "CircuitSim-MeasTest");
        worker.setDaemon(true);
        worker.start();
    }

    private void runTest(ServerPlayer player, CircuitExtractor.ExtractionResult ex,
                         String analysis, String pdkName, String pdkLibPath,
                         String pdkLibPaths, String ngBehavior, String simTemp,
                         String acP1, String acP2, String acP3,
                         String trP1, String trP2,
                         String dcSrc, String dcStart, String dcStop, String dcStep) {

        List<String> testLines = rewritePlotDirectives(candidateLines);
        List<String> names = MeasCatalog.resultNames(testLines);

        List<String> cmds = new ArrayList<>(ex.userCommands);
        cmds.addAll(testLines);
        for (String n : names) cmds.add("print " + n);

        String netlist;
        try {
            switch (analysis) {
                case "AC" -> netlist = NetlistBuilder.buildAcNetlist(
                        ex.components, ex.probes, ex.currentProbes,
                        ComponentEditScreen.parseSI(acP1), ComponentEditScreen.parseSI(acP2),
                        (int) Math.round(ComponentEditScreen.parseSI(acP3)),
                        pdkName, pdkLibPath, pdkLibPaths, ngBehavior, cmds, ex.userPlots);
                case "TRAN" -> netlist = NetlistBuilder.buildTranNetlist(
                        ex.components, ex.probes, ex.currentProbes,
                        ComponentEditScreen.parseSI(trP1), ComponentEditScreen.parseSI(trP2),
                        pdkName, pdkLibPath, pdkLibPaths, ngBehavior, cmds, ex.userPlots);
                case "DC" -> netlist = NetlistBuilder.buildDcNetlist(
                        ex.components, ex.probes, ex.currentProbes,
                        dcSrc.trim(),
                        ComponentEditScreen.parseSI(dcStart),
                        ComponentEditScreen.parseSI(dcStop),
                        ComponentEditScreen.parseSI(dcStep),
                        false, "", 0, 0, 1,
                        pdkName, pdkLibPath, pdkLibPaths, ngBehavior, cmds, ex.userPlots);
                case "OP" -> netlist = NetlistBuilder.buildNetlist(
                        ex.components, ex.probes, ex.currentProbes,
                        pdkName, pdkLibPath, pdkLibPaths, ngBehavior, cmds, ex.userPlots);
                default -> {
                    reply(player, List.of("Test failed: the Simulate block is set to "
                            + analysis + "; Test supports OP / AC / DC / TRAN."));
                    return;
                }
            }
        } catch (NumberFormatException nfe) {
            reply(player, List.of("Test failed: the Simulate block's " + analysis
                    + " parameters don't parse as numbers."));
            return;
        }

        // Same deck decorations a real run gets: .subckt defs, Param-block
        // .param lines (sweeps tested at their first value), .temp override.
        List<String> defs = new ArrayList<>();
        for (CircuitExtractor.ParametricInfo p : ex.parametricBlocks) {
            try {
                List<Double> vals = SimulatePacket.parseSweepString(p.valuesString);
                if (!vals.isEmpty()) {
                    defs.add(String.format(Locale.ROOT, "%s=%g", p.varName, vals.get(0)));
                }
            } catch (IllegalArgumentException ignored) {
                // Malformed Param block — a real run would refuse; for Test just
                // omit the def and let ngspice report the undefined parameter.
            }
        }
        netlist = SimulatePacket.injectTemp(
                SimulatePacket.injectParams(
                        SimulatePacket.injectSubcktDefs(netlist, ex.subcktDefs), defs),
                firstTemp(simTemp));

        NgSpiceRunner.Result result = NgSpiceRunner.run(netlist, ngBehavior);
        if (result.error != null) {
            reply(player, List.of("Test failed: " + firstLine(result.error)));
            return;
        }

        reply(player, harvest(result.rawStdout, analysis, names, testLines));
    }

    // ------------------------------------------------------------------------
    // Output harvesting
    // ------------------------------------------------------------------------

    /**
     * Pulls the lines worth showing out of the raw stdout: one
     * {@code name = value} line per result (the last occurrence wins — meas
     * prints its own line and the appended {@code print} echoes it again),
     * any measurement failure chatter, and the Fourier/THD table when a
     * {@code fourier} command was part of the test.
     */
    private static List<String> harvest(String stdout, String analysis,
                                        List<String> names, List<String> testLines) {
        String[] raw = stdout.split("\n");
        List<String> out = new ArrayList<>();
        out.add("Tested with " + analysis + " analysis:");

        for (String name : names) {
            Pattern pat = Pattern.compile("(?i)^\\s*" + Pattern.quote(name) + "\\s*=\\s*\\S.*");
            String found = null;
            for (String ln : raw) {
                if (pat.matcher(ln).matches()) found = ln.trim();
            }
            out.add(found != null ? found
                    : name + " = (not found — measurement may have failed)");
        }

        boolean wantFourier = testLines.stream()
                .anyMatch(l -> l.toLowerCase(Locale.ROOT).startsWith("fourier"));
        if (wantFourier) {
            for (String ln : raw) {
                if (ln.toLowerCase(Locale.ROOT).contains("thd")) out.add(ln.trim());
                if (out.size() >= 18) break;
            }
        }

        Pattern err = Pattern.compile("(?i).*meas.*(fail|error|interval).*");
        for (String ln : raw) {
            if (err.matcher(ln).matches() && out.size() < 20) out.add(ln.trim());
        }
        if (out.size() == 1) out.add("(no measurement output found)");
        return out;
    }

    /** Rewrites the mod's {@code plot NAME[unit] = EXPR} directive to {@code let}. */
    private static List<String> rewritePlotDirectives(List<String> lines) {
        Pattern plot = Pattern.compile(
                "(?i)^plot\\s+([a-z_][a-z0-9_]*)\\s*(?:\\[[^\\]]*\\])?\\s*=\\s*(.+)$");
        List<String> out = new ArrayList<>(lines.size());
        for (String line : lines) {
            if (line == null || line.isBlank()) continue;
            Matcher m = plot.matcher(line.strip());
            out.add(m.matches() ? "let " + m.group(1) + " = " + m.group(2) : line.strip());
        }
        return out;
    }

    private static double firstTemp(String spec) {
        if (spec == null) return 27.0;
        String head = spec.split("[:,]")[0].trim();
        try {
            return Double.parseDouble(head);
        } catch (NumberFormatException e) {
            return 27.0;
        }
    }

    private static String firstLine(String s) {
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }

    private static void reply(ServerPlayer player, List<String> lines) {
        ModMessages.sendToPlayer(player, new MeasTestResultPacket(lines));
    }
}
