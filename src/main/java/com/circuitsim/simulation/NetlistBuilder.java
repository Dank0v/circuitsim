package com.circuitsim.simulation;

import com.circuitsim.block.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;

import java.util.List;

public class NetlistBuilder {

    // -------------------------------------------------------------------------
    // .OP
    // -------------------------------------------------------------------------

    public static String buildNetlist(List<CircuitComponent> components,
                                       List<ProbeInfo>        probes,
                                       List<CurrentProbeInfo> currentProbes) {
        StringBuilder sb = new StringBuilder();
        sb.append("* CircuitSim Netlist\n");

        int rCount = 1, cCount = 1, lCount = 1, vCount = 1, iCount = 1,
            dCount = 1, vmCount = 1;
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
                    line = String.format("V%d %d %d AC %g", vCount++, comp.nodeA, comp.nodeB, comp.value);
                } else {
                    line = String.format("V%d %d %d DC %g", vCount++, comp.nodeA, comp.nodeB, comp.value);
                }
            } else if (comp.block instanceof VoltageSourceSinBlock) {
                // For .OP the SIN source contributes only its DC offset (0 V)
                line = String.format("V%d %d %d DC 0", vCount++, comp.nodeA, comp.nodeB);
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

        for (CurrentProbeInfo cp : currentProbes) {
            sb.append(String.format("VM%d %d %d DC 0\n", vmCount++, cp.nodeA, cp.nodeB));
        }

        if (hasDiode) sb.append(".MODEL DMOD D\n");

        sb.append(".op\n");
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

    // -------------------------------------------------------------------------
    // .AC
    // -------------------------------------------------------------------------

    public static String buildAcNetlist(List<CircuitComponent> components,
                                         List<ProbeInfo>        probes,
                                         List<CurrentProbeInfo> currentProbes,
                                         double fStart, double fStop, int ptsPerDec) {
        StringBuilder sb = new StringBuilder();
        sb.append("* CircuitSim AC Netlist\n");

        int rCount = 1, cCount = 1, lCount = 1, vCount = 1, iCount = 1,
            dCount = 1, vmCount = 1;
        boolean hasDiode    = false;
        boolean hasAcSource = false;

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
                    line = String.format("V%d %d %d DC 0 AC %g",
                            vCount++, comp.nodeA, comp.nodeB, comp.value);
                    hasAcSource = true;
                } else {
                    line = String.format("V%d %d %d DC %g AC 0",
                            vCount++, comp.nodeA, comp.nodeB, comp.value);
                }
            } else if (comp.block instanceof VoltageSourceSinBlock) {
                // The SIN source drives AC analysis with its stored amplitude
                line = String.format("V%d %d %d DC 0 AC %g",
                        vCount++, comp.nodeA, comp.nodeB, comp.value);
                hasAcSource = true;
            } else if (comp.block instanceof CurrentSourceBlock) {
                if ("AC".equalsIgnoreCase(comp.sourceType)) {
                    line = String.format("I%d %d %d DC 0 AC %g",
                            iCount++, comp.nodeA, comp.nodeB, comp.value);
                    hasAcSource = true;
                } else {
                    line = String.format("I%d %d %d DC %g AC 0",
                            iCount++, comp.nodeA, comp.nodeB, comp.value);
                }
            } else if (comp.block instanceof DiodeBlock) {
                line = String.format("D%d %d %d DMOD", dCount++, comp.nodeA, comp.nodeB);
                hasDiode = true;
            } else {
                continue;
            }
            sb.append(line).append("\n");
        }

        if (!hasAcSource && !components.isEmpty()) {
            for (CircuitComponent comp : components) {
                if (comp.nodeA != 0) {
                    sb.append(String.format("VACDEF %d 0 DC 0 AC 1\n", comp.nodeA));
                    break;
                }
            }
        }

        for (CurrentProbeInfo cp : currentProbes) {
            sb.append(String.format("VM%d %d %d DC 0\n", vmCount++, cp.nodeA, cp.nodeB));
        }

        if (hasDiode) sb.append(".MODEL DMOD D\n");

        sb.append(String.format(".ac dec %d %g %g\n", ptsPerDec, fStart, fStop));

        sb.append(".control\n");
        sb.append("  run\n");

        if (!probes.isEmpty() || !currentProbes.isEmpty()) {
            StringBuilder printLine = new StringBuilder("  print");
            for (ProbeInfo probe : probes) {
                printLine.append(String.format(" v(%d)", probe.node));
            }
            int vmIdx2 = 1;
            for (int k = 0; k < currentProbes.size(); k++) {
                printLine.append(String.format(" i(VM%d)", vmIdx2++));
            }
            sb.append(printLine).append("\n");
        }

        sb.append(".endc\n");
        sb.append(".end\n");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // .TRAN
    // -------------------------------------------------------------------------

    public static String buildTranNetlist(List<CircuitComponent> components,
                                           List<ProbeInfo>        probes,
                                           List<CurrentProbeInfo> currentProbes,
                                           double tstep, double tstop) {
        StringBuilder sb = new StringBuilder();
        sb.append("* CircuitSim TRAN Netlist\n");

        int rCount = 1, cCount = 1, lCount = 1, vCount = 1, iCount = 1,
            dCount = 1, vmCount = 1;
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
                line = String.format("V%d %d %d DC %g", vCount++, comp.nodeA, comp.nodeB, comp.value);
            } else if (comp.block instanceof VoltageSourceSinBlock) {
                double freq = (comp.frequency > 0) ? comp.frequency : 1.0;
                line = String.format("V%d %d %d SIN(0 %g %g)",
                        vCount++, comp.nodeA, comp.nodeB, comp.value, freq);
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

        for (CurrentProbeInfo cp : currentProbes) {
            sb.append(String.format("VM%d %d %d DC 0\n", vmCount++, cp.nodeA, cp.nodeB));
        }

        if (hasDiode) sb.append(".MODEL DMOD D\n");

        sb.append(String.format(".tran %g %g\n", tstep, tstop));

        sb.append(".control\n");
        sb.append("  run\n");

        if (!probes.isEmpty() || !currentProbes.isEmpty()) {
            StringBuilder printLine = new StringBuilder("  print");
            for (ProbeInfo probe : probes) {
                printLine.append(String.format(" v(%d)", probe.node));
            }
            int vmIdx = 1;
            for (int k = 0; k < currentProbes.size(); k++) {
                printLine.append(String.format(" i(VM%d)", vmIdx++));
            }
            sb.append(printLine).append("\n");
        }

        sb.append(".endc\n");
        sb.append(".end\n");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Inner classes
    // -------------------------------------------------------------------------

    public static class CircuitComponent {
        public final Block    block;
        public final BlockPos pos;
        public final int      nodeA;
        public final int      nodeB;
        public final double   value;
        public final String   sourceType;
        public final double   frequency;

        public CircuitComponent(Block block, BlockPos pos, int nodeA, int nodeB,
                                double value, String sourceType, double frequency) {
            this.block      = block;
            this.pos        = pos;
            this.nodeA      = nodeA;
            this.nodeB      = nodeB;
            this.value      = value;
            this.sourceType = sourceType;
            this.frequency  = frequency;
        }
    }

    public static class ProbeInfo {
        public final int    node;
        public final String label;
        public ProbeInfo(int node, String label) { this.node = node; this.label = label; }
    }

    public static class CurrentProbeInfo {
        public final int    nodeA;
        public final int    nodeB;
        public final String label;
        public CurrentProbeInfo(int nodeA, int nodeB, String label) {
            this.nodeA = nodeA; this.nodeB = nodeB; this.label = label;
        }
    }
}