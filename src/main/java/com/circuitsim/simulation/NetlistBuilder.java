package com.circuitsim.simulation;

import com.circuitsim.block.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;

import java.util.List;

public class NetlistBuilder {

    public static String buildNetlist(List<CircuitComponent> components,
                                       List<ProbeInfo> probes,
                                       List<CurrentProbeInfo> currentProbes) {
        StringBuilder sb = new StringBuilder();
        sb.append("* CircuitSim Netlist\n");

        int rCount = 1, cCount = 1, lCount = 1, vCount = 1, iCount = 1, dCount = 1, vmCount = 1;
        boolean hasDiode = false;

        for (CircuitComponent comp : components) {
            String line;
            if (comp.block instanceof ResistorBlock) {
                line = String.format("R%d %d %d %g", rCount++, comp.nodeA, comp.nodeB, comp.value);

            } else if (comp.block instanceof CapacitorBlock) {
                line = String.format("C%d %d %d %g", cCount++, comp.nodeA, comp.nodeB, comp.value);

            } else if (comp.block instanceof InductorBlock) {
                line = String.format("L%d %d %d %g", lCount++, comp.nodeA, comp.nodeB, comp.value);

            } else if (comp.block instanceof VoltageSourceBlock) {
                if ("AC".equalsIgnoreCase(comp.sourceType)) {
                    line = String.format("V%d %d %d AC %g",
                            vCount++, comp.nodeA, comp.nodeB, comp.value);
                } else {
                    line = String.format("V%d %d %d DC %g",
                            vCount++, comp.nodeA, comp.nodeB, comp.value);
                }

            } else if (comp.block instanceof CurrentSourceBlock) {
                line = String.format("I%d %d %d DC %g", iCount++, comp.nodeA, comp.nodeB, comp.value);

            } else if (comp.block instanceof DiodeBlock) {
                line = String.format("D%d %d %d DMOD", dCount++, comp.nodeA, comp.nodeB);
                hasDiode = true;

            } else {
                continue;
            }
            sb.append(line).append("\n");
        }

        // Current probes are 0V voltage sources placed in series
        for (CurrentProbeInfo cp : currentProbes) {
            sb.append(String.format("VM%d %d %d DC 0\n", vmCount++, cp.nodeA, cp.nodeB));
        }

        if (hasDiode) {
            sb.append(".MODEL DMOD D\n");
        }

        sb.append(".op\n");

        // Use .control block — the reliable way to get output in ngspice batch mode.
        // "run" executes the .op analysis; each "print" emits "name = value" lines
        // that are trivial to parse unambiguously.
        sb.append(".control\n");
        sb.append("  run\n");

        for (ProbeInfo probe : probes) {
            sb.append(String.format("  print v(%d)\n", probe.node));
        }

        int vmIdx = 1;
        for (CurrentProbeInfo cp : currentProbes) {
            sb.append(String.format("  print i(VM%d)\n", vmIdx++));
        }

        sb.append(".endc\n");
        sb.append(".end\n");

        return sb.toString();
    }

    public static String buildNetlist(List<CircuitComponent> components, List<ProbeInfo> probes) {
        return buildNetlist(components, probes, List.of());
    }

    public static class CircuitComponent {
        public final Block block;
        public final BlockPos pos;
        public final int nodeA;
        public final int nodeB;
        public final double value;
        public final String sourceType;
        public final double frequency;

        public CircuitComponent(Block block, BlockPos pos, int nodeA, int nodeB,
                                double value, String sourceType, double frequency) {
            this.block = block;
            this.pos = pos;
            this.nodeA = nodeA;
            this.nodeB = nodeB;
            this.value = value;
            this.sourceType = sourceType;
            this.frequency = frequency;
        }
    }

    public static class ProbeInfo {
        public final int node;
        public final String label;

        public ProbeInfo(int node, String label) {
            this.node = node;
            this.label = label;
        }
    }

    public static class CurrentProbeInfo {
        public final int nodeA;
        public final int nodeB;
        public final String label;

        public CurrentProbeInfo(int nodeA, int nodeB, String label) {
            this.nodeA = nodeA;
            this.nodeB = nodeB;
            this.label = label;
        }
    }
}