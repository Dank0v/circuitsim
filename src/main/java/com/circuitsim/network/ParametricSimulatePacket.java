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

public class ParametricSimulatePacket {

    private final BlockPos pos;
    private final String   paramString;

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

    public void handle(NetworkEvent.Context ctx) {
        ServerPlayer player = ctx.getSender();
        if (player == null) return;

        Level level = player.level();
        if (!level.isLoaded(pos)) return;

        BlockEntity rawBe = level.getBlockEntity(pos);
        if (rawBe instanceof ComponentBlockEntity cbe) {
            cbe.setLabel(paramString);
            cbe.setChanged();
            cbe.syncToClient();
        }

        BlockState paramState = level.getBlockState(pos);
        if (!(paramState.getBlock() instanceof ParametricBlock)) return;

        Direction facing    = paramState.getValue(BaseComponentBlock.FACING);
        BlockPos  targetPos = pos.relative(facing);
        Block     targetBlock = level.getBlockState(targetPos).getBlock();

        if (!isParametrizable(targetBlock)) {
            msg(player, "The block in front is not a parametrizable component.",
                    ChatFormatting.RED);
            return;
        }

        // Parse "paramName=sweepStr" — legacy bare strings default to "value"
        String[] spec      = com.circuitsim.screen.ParametricEditScreen.parseParametricSpec(paramString);
        String   paramName = spec[0];
        String   sweepStr  = spec[1];

        List<Double> paramValues;
        try {
            paramValues = parseParamString(sweepStr);
        } catch (IllegalArgumentException e) {
            msg(player, "Invalid sweep string: " + e.getMessage(), ChatFormatting.RED);
            return;
        }

        if (paramValues.isEmpty()) {
            msg(player, "No values to sweep — enter a list or a range.", ChatFormatting.RED);
            return;
        }
        if (paramValues.size() > 50) {
            msg(player, "Too many sweep values (" + paramValues.size() + "); maximum is 50.",
                    ChatFormatting.RED);
            return;
        }

        CircuitExtractor.ExtractionResult extraction = CircuitExtractor.extract(level, targetPos);
        if (!extraction.success) {
            msg(player, "Circuit error: " + extraction.errorMessage, ChatFormatting.RED);
            return;
        }

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

        msg(player, "=== Parametric: " + displayName(targetBlock) + " (" + paramName + ") sweep ("
                + paramValues.size() + " points) ===", ChatFormatting.GOLD);

        for (double val : paramValues) {
            final double sweepVal = val;
            List<NetlistBuilder.CircuitComponent> swept = extraction.components.stream()
                    .map(c -> {
                        if (!c.pos.equals(targetPos)) return c;
                        double value = c.value;
                        double w = c.wParam, l = c.lParam, mult = c.multParam, nf = c.nfParam;
                        switch (paramName) {
                            case "W"    -> w    = sweepVal;
                            case "L"    -> l    = sweepVal;
                            case "mult" -> mult = sweepVal;
                            case "nf"   -> nf   = sweepVal;
                            default     -> value = sweepVal;
                        }
                        return new NetlistBuilder.CircuitComponent(
                                c.block, c.pos,
                                c.nodeA, c.nodeB, c.nodeC, c.nodeD,
                                value, c.sourceType, c.frequency,
                                c.modelName, w, l, mult, nf, c.componentNumber);
                    })
                    .collect(Collectors.toList());

            String netlist = NetlistBuilder.buildNetlist(swept, effectiveProbes, extraction.currentProbes);
            NgSpiceRunner.Result result = NgSpiceRunner.run(netlist);

            msg(player, "--- " + ComponentEditScreen.formatValue(val) + unit(targetBlock, paramName) + " ---",
                    ChatFormatting.YELLOW);

            if (result.error != null) {
                String firstLine = result.error.lines().filter(l -> !l.isBlank())
                        .findFirst().orElse("unknown error");
                msg(player, "  Error: " + firstLine, ChatFormatting.RED);
                continue;
            }
            if (result.values.isEmpty()) {
                msg(player, "  (no results — verify the circuit has a Ground block)",
                        ChatFormatting.GRAY);
                continue;
            }

            for (NetlistBuilder.ProbeInfo probe : effectiveProbes) {
                msg(player, "  [" + probe.label + "]: "
                        + result.getNodeVoltage(probe.netName), ChatFormatting.AQUA);
            }
            int vmIdx = 1;
            for (NetlistBuilder.CurrentProbeInfo cp : extraction.currentProbes) {
                msg(player, "  [" + cp.label + "]: "
                        + result.getBranchCurrent("vm" + vmIdx), ChatFormatting.LIGHT_PURPLE);
                vmIdx++;
            }
        }
    }

    public static List<Double> parseParamString(String raw) {
        String s = raw.trim();
        if (s.isEmpty()) throw new IllegalArgumentException("empty string");

        if (s.contains(":")) {
            String[] parts = s.split(":");
            if (parts.length != 3)
                throw new IllegalArgumentException("range must be start:stop:step");
            double start = parseSI(parts[0]);
            double stop  = parseSI(parts[1]);
            double step  = parseSI(parts[2]);
            if (step == 0) throw new IllegalArgumentException("step cannot be zero");
            List<Double> vals = new ArrayList<>();
            double epsilon = 1e-10 * Math.abs(step);
            if (step > 0) {
                for (double v = start; v <= stop + epsilon && vals.size() < 50; v += step) vals.add(v);
            } else {
                for (double v = start; v >= stop - epsilon && vals.size() < 50; v += step) vals.add(v);
            }
            return vals;
        }

        return Arrays.stream(s.split(","))
                .map(String::trim).filter(t -> !t.isEmpty())
                .map(t -> { try { return parseSI(t); }
                            catch (NumberFormatException e) {
                                throw new IllegalArgumentException("cannot parse '" + t + "'"); }})
                .collect(Collectors.toList());
    }

    private static double parseSI(String t) { return ComponentEditScreen.parseSI(t.trim()); }

    private static boolean isParametrizable(Block b) {
        return b instanceof ResistorBlock
                || b instanceof CapacitorBlock
                || b instanceof InductorBlock
                || b instanceof VoltageSourceBlock
                || b instanceof VoltageSourceSinBlock
                || b instanceof CurrentSourceBlock
                || b instanceof IcResistorBlock
                || b instanceof IcCapacitorBlock
                || b instanceof IcNmos4Block
                || b instanceof IcPmos4Block;
    }

    private static String displayName(Block b) {
        if (b instanceof ResistorBlock)         return "Resistor";
        if (b instanceof CapacitorBlock)        return "Capacitor";
        if (b instanceof InductorBlock)         return "Inductor";
        if (b instanceof VoltageSourceBlock)    return "Voltage Source";
        if (b instanceof VoltageSourceSinBlock) return "SIN Voltage Source";
        if (b instanceof CurrentSourceBlock)    return "Current Source";
        if (b instanceof IcResistorBlock)       return "IC Resistor";
        if (b instanceof IcCapacitorBlock)      return "IC Capacitor";
        if (b instanceof IcNmos4Block)          return "IC NMOS4";
        if (b instanceof IcPmos4Block)          return "IC PMOS4";
        return "Component";
    }

    private static String unit(Block b, String paramName) {
        if ("W".equals(paramName) || "L".equals(paramName)) return "u";
        if ("mult".equals(paramName) || "nf".equals(paramName)) return "";
        if (b instanceof ResistorBlock)         return "\u03A9";
        if (b instanceof CapacitorBlock)        return "F";
        if (b instanceof InductorBlock)         return "H";
        if (b instanceof VoltageSourceBlock)    return "V";
        if (b instanceof VoltageSourceSinBlock) return "V";
        if (b instanceof CurrentSourceBlock)    return "A";
        return "";
    }

    private static void msg(ServerPlayer p, String text, ChatFormatting fmt) {
        p.displayClientMessage(Component.literal(text).withStyle(fmt), false);
    }
}
