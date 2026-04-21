package com.circuitsim.network;

import com.circuitsim.block.*;
import com.circuitsim.blockentity.ComponentBlockEntity;
import com.circuitsim.screen.ComponentEditScreen;
import com.circuitsim.simulation.CircuitExtractor;
import com.circuitsim.simulation.NetlistBuilder;
import com.circuitsim.simulation.NgSpiceRunner;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.network.NetworkEvent;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Client → Server packet that triggers a parametric sweep.
 *
 * <p>The packet carries the position of the {@link ParametricBlock} and
 * the raw sweep string entered by the player (e.g. {@code "100,200,500,1k"}
 * or {@code "100:1k:100"}).  The server resolves the target component from
 * the block's facing direction, runs one ngspice .op per sweep value, and
 * prints results directly to the player's chat.</p>
 */
public class ParametricSimulatePacket {

    private final BlockPos pos;
    private final String   paramString;

    // ---- construction ----------------------------------------------------

    public ParametricSimulatePacket(BlockPos pos, String paramString) {
        this.pos         = pos;
        this.paramString = paramString;
    }

    public ParametricSimulatePacket(FriendlyByteBuf buf) {
        this.pos         = buf.readBlockPos();
        this.paramString = buf.readUtf(256);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeUtf(paramString, 256);
    }

    public static ParametricSimulatePacket decode(FriendlyByteBuf buf) {
        return new ParametricSimulatePacket(buf);
    }

    // ---- server-side handler ---------------------------------------------

    public void handle(NetworkEvent.Context ctx) {
        ServerPlayer player = ctx.getSender();
        if (player == null) return;

        Level level = player.level();
        if (!level.isLoaded(pos)) return;

        // ---- 1. Persist the param string in the block entity ----
        BlockEntity rawBe = level.getBlockEntity(pos);
        if (rawBe instanceof ComponentBlockEntity cbe) {
            cbe.setLabel(paramString);
            cbe.setChanged();
            cbe.syncToClient();
        }

        // ---- 2. Resolve target component from facing direction ----
        BlockState paramState = level.getBlockState(pos);
        if (!(paramState.getBlock() instanceof ParametricBlock)) return;

        Direction facing   = paramState.getValue(BaseComponentBlock.FACING);
        BlockPos  targetPos   = pos.relative(facing);
        Block     targetBlock = level.getBlockState(targetPos).getBlock();

        if (!isParametrizable(targetBlock)) {
            msg(player, "The block in front is not a parametrizable component "
                    + "(need Resistor, Capacitor, Inductor, Voltage Source, or Current Source).",
                    ChatFormatting.RED);
            return;
        }

        // ---- 3. Parse sweep values ----
        List<Double> paramValues;
        try {
            paramValues = parseParamString(paramString);
        } catch (IllegalArgumentException e) {
            msg(player, "Invalid sweep string: " + e.getMessage(), ChatFormatting.RED);
            return;
        }

        if (paramValues.isEmpty()) {
            msg(player, "No values to sweep — enter a list or a range.", ChatFormatting.RED);
            return;
        }

        if (paramValues.size() > 50) {
            msg(player, "Too many sweep values (" + paramValues.size()
                    + "); maximum is 50.", ChatFormatting.RED);
            return;
        }

        // ---- 4. Extract circuit ----
        CircuitExtractor.ExtractionResult extraction = CircuitExtractor.extract(level, targetPos);
        if (!extraction.success) {
            msg(player, "Circuit error: " + extraction.errorMessage, ChatFormatting.RED);
            return;
        }

        // ---- 5. Build effective probe list ----
        //  If the user placed no Voltage Probes, auto-generate probes for every
        //  non-ground node so that results are still shown.
        boolean autoProbe = extraction.probes.isEmpty();
        List<NetlistBuilder.ProbeInfo> effectiveProbes;
        if (autoProbe) {
            Set<Integer> nodes = new LinkedHashSet<>();
            for (NetlistBuilder.CircuitComponent c : extraction.components) {
                if (c.nodeA != 0) nodes.add(c.nodeA);
                if (c.nodeB != 0) nodes.add(c.nodeB);
            }
            effectiveProbes = nodes.stream()
                    .map(n -> new NetlistBuilder.ProbeInfo(n, "Node " + n))
                    .collect(Collectors.toList());
        } else {
            effectiveProbes = extraction.probes;
        }

        // ---- 6. Print header ----
        msg(player, "=== Parametric: " + displayName(targetBlock) + " sweep ("
                + paramValues.size() + " points) ===", ChatFormatting.GOLD);

        // ---- 7. One simulation run per sweep value ----
        for (double val : paramValues) {

            // Replace target component's value in the component list
            final double sweepVal = val;
            List<NetlistBuilder.CircuitComponent> swept = extraction.components.stream()
                    .map(c -> c.pos.equals(targetPos)
                            ? new NetlistBuilder.CircuitComponent(
                                    c.block, c.pos, c.nodeA, c.nodeB,
                                    sweepVal, c.sourceType, c.frequency)
                            : c)
                    .collect(Collectors.toList());

            String netlist = NetlistBuilder.buildNetlist(swept, effectiveProbes, extraction.currentProbes);
            NgSpiceRunner.Result result = NgSpiceRunner.run(netlist);

            // Section header (value with SI suffix + unit)
            msg(player, "--- " + ComponentEditScreen.formatValue(val) + unit(targetBlock) + " ---",
                    ChatFormatting.YELLOW);

            if (result.error != null) {
                // Show only the first meaningful line of the error
                String firstLine = result.error.lines()
                        .filter(l -> !l.isBlank())
                        .findFirst()
                        .orElse("unknown error");
                msg(player, "  Error: " + firstLine, ChatFormatting.RED);
                continue;
            }

            if (result.values.isEmpty()) {
                msg(player, "  (no results — verify the circuit has a Ground block)",
                        ChatFormatting.GRAY);
                continue;
            }

            // Voltage probes
            for (NetlistBuilder.ProbeInfo probe : effectiveProbes) {
                msg(player, "  [" + probe.label + "]: "
                        + result.getNodeVoltage(probe.node), ChatFormatting.AQUA);
            }

            // Current probes
            int vmIdx = 1;
            for (NetlistBuilder.CurrentProbeInfo cp : extraction.currentProbes) {
                msg(player, "  [" + cp.label + "]: "
                        + result.getBranchCurrent("vm" + vmIdx), ChatFormatting.LIGHT_PURPLE);
                vmIdx++;
            }
        }
    }

    // ---- param-string parsing --------------------------------------------

    /**
     * Parses the sweep string into an ordered list of doubles.
     *
     * <p>Supported formats:</p>
     * <ul>
     *   <li><b>List</b>: {@code 100,200,500,1k,2k} — comma-separated values,
     *       each may carry an SI suffix recognised by
     *       {@link ComponentEditScreen#parseSI}.</li>
     *   <li><b>Range</b>: {@code start:stop:step} — inclusive on both ends;
     *       negative step for descending ranges.</li>
     * </ul>
     */
    public static List<Double> parseParamString(String raw) {
        String s = raw.trim();
        if (s.isEmpty()) throw new IllegalArgumentException("empty string");

        if (s.contains(":")) {
            // ---- range ----
            String[] parts = s.split(":");
            if (parts.length != 3)
                throw new IllegalArgumentException("range must be  start:stop:step");

            double start = parseSI(parts[0]);
            double stop  = parseSI(parts[1]);
            double step  = parseSI(parts[2]);

            if (step == 0)
                throw new IllegalArgumentException("step cannot be zero");

            List<Double> vals = new ArrayList<>();
            double epsilon = 1e-10 * Math.abs(step);

            if (step > 0) {
                for (double v = start; v <= stop + epsilon && vals.size() < 50; v += step)
                    vals.add(v);
            } else {
                for (double v = start; v >= stop - epsilon && vals.size() < 50; v += step)
                    vals.add(v);
            }
            return vals;

        } else {
            // ---- list ----
            return Arrays.stream(s.split(","))
                    .map(String::trim)
                    .filter(t -> !t.isEmpty())
                    .map(t -> {
                        try { return parseSI(t); }
                        catch (NumberFormatException e) {
                            throw new IllegalArgumentException("cannot parse '" + t + "'");
                        }
                    })
                    .collect(Collectors.toList());
        }
    }

    // ---- helpers ---------------------------------------------------------

    private static double parseSI(String t) {
        return ComponentEditScreen.parseSI(t.trim());
    }

    private static boolean isParametrizable(Block b) {
        return b instanceof ResistorBlock
                || b instanceof CapacitorBlock
                || b instanceof InductorBlock
                || b instanceof VoltageSourceBlock
                || b instanceof CurrentSourceBlock;
    }

    private static String displayName(Block b) {
        if (b instanceof ResistorBlock)      return "Resistor";
        if (b instanceof CapacitorBlock)     return "Capacitor";
        if (b instanceof InductorBlock)      return "Inductor";
        if (b instanceof VoltageSourceBlock) return "Voltage Source";
        if (b instanceof CurrentSourceBlock) return "Current Source";
        return "Component";
    }

    private static String unit(Block b) {
        if (b instanceof ResistorBlock)      return "Ω";
        if (b instanceof CapacitorBlock)     return "F";
        if (b instanceof InductorBlock)      return "H";
        if (b instanceof VoltageSourceBlock) return "V";
        if (b instanceof CurrentSourceBlock) return "A";
        return "";
    }

    private static void msg(ServerPlayer p, String text, ChatFormatting fmt) {
        p.displayClientMessage(Component.literal(text).withStyle(fmt), false);
    }
}