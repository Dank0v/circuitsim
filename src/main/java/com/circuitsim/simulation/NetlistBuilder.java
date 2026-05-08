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
                                       List<CurrentProbeInfo> currentProbes,
                                       String                 pdkName,
                                       String                 pdkLibPath) {
        StringBuilder sb = new StringBuilder();
        sb.append("* CircuitSim Netlist\n");
        appendPdkLib(sb, pdkName, pdkLibPath);

        int rCount = 1, cCount = 1, lCount = 1, vCount = 1, iCount = 1,
            dCount = 1, vmCount = 1;
        boolean hasDiode = false;

        for (CircuitComponent comp : components) {
            String line;
            if (comp.block instanceof IcResistorBlock) {
                line = formatIcResistor(rCount++, comp, pdkName);
            } else if (comp.block instanceof ResistorBlock) {
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
        return buildNetlist(components, probes, List.of(), "none", "");
    }

    public static String buildNetlist(List<CircuitComponent> components,
                                       List<ProbeInfo> probes,
                                       List<CurrentProbeInfo> currentProbes) {
        return buildNetlist(components, probes, currentProbes, "none", "");
    }

    // -------------------------------------------------------------------------
    // .AC
    // -------------------------------------------------------------------------

    public static String buildAcNetlist(List<CircuitComponent> components,
                                         List<ProbeInfo>        probes,
                                         List<CurrentProbeInfo> currentProbes,
                                         double fStart, double fStop, int ptsPerDec,
                                         String pdkName, String pdkLibPath) {
        StringBuilder sb = new StringBuilder();
        sb.append("* CircuitSim AC Netlist\n");
        appendPdkLib(sb, pdkName, pdkLibPath);

        int rCount = 1, cCount = 1, lCount = 1, vCount = 1, iCount = 1,
            dCount = 1, vmCount = 1;
        boolean hasDiode    = false;
        boolean hasAcSource = false;

        for (CircuitComponent comp : components) {
            String line;
            if (comp.block instanceof IcResistorBlock) {
                line = formatIcResistor(rCount++, comp, pdkName);
            } else if (comp.block instanceof ResistorBlock) {
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

    public static String buildAcNetlist(List<CircuitComponent> components,
                                         List<ProbeInfo>        probes,
                                         List<CurrentProbeInfo> currentProbes,
                                         double fStart, double fStop, int ptsPerDec) {
        return buildAcNetlist(components, probes, currentProbes, fStart, fStop, ptsPerDec, "none", "");
    }

    // -------------------------------------------------------------------------
    // .TRAN
    // -------------------------------------------------------------------------

    public static String buildTranNetlist(List<CircuitComponent> components,
                                           List<ProbeInfo>        probes,
                                           List<CurrentProbeInfo> currentProbes,
                                           double tstep, double tstop,
                                           String pdkName, String pdkLibPath) {
        StringBuilder sb = new StringBuilder();
        sb.append("* CircuitSim TRAN Netlist\n");
        appendPdkLib(sb, pdkName, pdkLibPath);

        int rCount = 1, cCount = 1, lCount = 1, vCount = 1, iCount = 1,
            dCount = 1, vmCount = 1;
        boolean hasDiode = false;

        for (CircuitComponent comp : components) {
            String line;
            if (comp.block instanceof IcResistorBlock) {
                line = formatIcResistor(rCount++, comp, pdkName);
            } else if (comp.block instanceof ResistorBlock) {
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

    public static String buildTranNetlist(List<CircuitComponent> components,
                                           List<ProbeInfo>        probes,
                                           List<CurrentProbeInfo> currentProbes,
                                           double tstep, double tstop) {
        return buildTranNetlist(components, probes, currentProbes, tstep, tstop, "none", "");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static void appendPdkLib(StringBuilder sb, String pdkName, String pdkLibPath) {
        if (!"none".equals(pdkName) && pdkLibPath != null && !pdkLibPath.isBlank()) {
            sb.append(".lib ").append(pdkLibPath).append("\n");
        }
    }


    /**
     * Formats a sky130A resistor instance line.
     * Resistance formula (W and L in µm): R = (378.3 + 317.17 * L) / W / mult
     */
    private static String pdkModelPrefix(String pdkName) {
        return switch (pdkName == null ? "none" : pdkName) {
            case "sky130A" -> "sky130_fd_pr__";
            default        -> "";
        };
    }

    /**
     * Formats a 3-pin IC resistor subcircuit line.
     * The model prefix is determined by the active PDK (e.g. sky130_fd_pr__ for sky130A).
     * Resistance formula for display (W, L in µm): R = (378.3 + 317.17*L) / W / mult
     */
    private static String formatIcResistor(int idx, CircuitComponent comp, String pdkName) {
        String prefix = pdkModelPrefix(pdkName);
        String name   = comp.modelName.isBlank() ? "res_high_po" : comp.modelName;
        String model  = prefix + name;
        double w    = comp.wParam    > 0 ? comp.wParam    : 1.0;
        double l    = comp.lParam    > 0 ? comp.lParam    : 1.0;
        double mult = comp.multParam > 0 ? comp.multParam : 1.0;
        // 3-pin subcircuit: p+ p- bulk
        int bulk = comp.nodeC >= 0 ? comp.nodeC : 0;
        return String.format("XR%d %d %d %d %s W=%g L=%g mult=%g",
                idx, comp.nodeA, comp.nodeB, bulk, model, w, l, mult);
    }

    /** Computes the expected resistance in Ohms using the sky130 formula (W, L in µm). */
    public static double computeSky130Resistance(double w, double l, double mult) {
        double wEff    = w    > 0 ? w    : 1.0;
        double lEff    = l    > 0 ? l    : 1.0;
        double multEff = mult > 0 ? mult : 1.0;
        return (378.3 + 317.17 * lEff) / wEff / multEff;
    }

    // -------------------------------------------------------------------------
    // Inner classes
    // -------------------------------------------------------------------------

    public static class CircuitComponent {
        public final Block    block;
        public final BlockPos pos;
        public final int      nodeA;
        public final int      nodeB;
        public final int      nodeC;   // third pin (sky130 bulk), -1 means unused
        public final double   value;
        public final String   sourceType;
        public final double   frequency;
        // sky130 resistor params
        public final String   modelName;
        public final double   wParam;
        public final double   lParam;
        public final double   multParam;

        public CircuitComponent(Block block, BlockPos pos, int nodeA, int nodeB,
                                double value, String sourceType, double frequency) {
            this(block, pos, nodeA, nodeB, -1, value, sourceType, frequency, "", 1.0, 1.0, 1.0);
        }

        public CircuitComponent(Block block, BlockPos pos, int nodeA, int nodeB,
                                double value, String sourceType, double frequency,
                                String modelName, double wParam, double lParam, double multParam) {
            this(block, pos, nodeA, nodeB, -1, value, sourceType, frequency,
                    modelName, wParam, lParam, multParam);
        }

        public CircuitComponent(Block block, BlockPos pos, int nodeA, int nodeB, int nodeC,
                                double value, String sourceType, double frequency,
                                String modelName, double wParam, double lParam, double multParam) {
            this.block      = block;
            this.pos        = pos;
            this.nodeA      = nodeA;
            this.nodeB      = nodeB;
            this.nodeC      = nodeC;
            this.value      = value;
            this.sourceType = sourceType;
            this.frequency  = frequency;
            this.modelName  = modelName;
            this.wParam     = wParam;
            this.lParam     = lParam;
            this.multParam  = multParam;
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
