package com.circuitsim.simulation;

import com.circuitsim.block.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class NetlistBuilder {

    /**
     * Assigns netlist indices for one prefix family (R, C, L, V, I, D, M, ...).
     * Components with {@code componentNumber > 0} keep their requested number;
     * the rest are auto-assigned starting at 1, skipping any number that is
     * already manually claimed.
     */
    private static class IndexAssigner {
        private final Set<Integer> manual = new HashSet<>();
        private int nextAuto = 1;
        void claim(int n) { if (n > 0) manual.add(n); }
        int assign(int requested) {
            if (requested > 0) return requested;
            while (manual.contains(nextAuto)) nextAuto++;
            return nextAuto++;
        }
    }

    private static int rIndexFamily(CircuitComponent c) {
        // returns 0 for none, 1=R, 2=C, 3=L, 4=V, 5=I, 6=D, 7=M
        if (c.block instanceof ResistorBlock || c.block instanceof IcResistorBlock) return 1;
        if (c.block instanceof CapacitorBlock || c.block instanceof IcCapacitorBlock) return 2;
        if (c.block instanceof InductorBlock) return 3;
        if (c.block instanceof VoltageSourceBlock || c.block instanceof VoltageSourceSinBlock) return 4;
        if (c.block instanceof CurrentSourceBlock) return 5;
        if (c.block instanceof DiodeBlock) return 6;
        if (c.block instanceof IcNmos4Block || c.block instanceof IcPmos4Block) return 7;
        return 0;
    }

    /** Pre-claims all manually-set component numbers so auto-assignment skips them. */
    private static void claimManual(List<CircuitComponent> components,
                                    IndexAssigner r, IndexAssigner c, IndexAssigner l,
                                    IndexAssigner v, IndexAssigner i, IndexAssigner d,
                                    IndexAssigner m) {
        for (CircuitComponent comp : components) {
            int n = comp.componentNumber;
            if (n <= 0) continue;
            switch (rIndexFamily(comp)) {
                case 1 -> r.claim(n);
                case 2 -> c.claim(n);
                case 3 -> l.claim(n);
                case 4 -> v.claim(n);
                case 5 -> i.claim(n);
                case 6 -> d.claim(n);
                case 7 -> m.claim(n);
                default -> {}
            }
        }
    }

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

        IndexAssigner rIdx = new IndexAssigner(), cIdx = new IndexAssigner(),
                      lIdx = new IndexAssigner(), vIdx = new IndexAssigner(),
                      iIdx = new IndexAssigner(), dIdx = new IndexAssigner(),
                      mIdx = new IndexAssigner();
        claimManual(components, rIdx, cIdx, lIdx, vIdx, iIdx, dIdx, mIdx);
        int vmCount = 1;
        boolean hasDiode = false;

        for (CircuitComponent comp : components) {
            String line;
            if (comp.block instanceof IcResistorBlock) {
                line = formatIcResistor(rIdx.assign(comp.componentNumber), comp, pdkName);
            } else if (comp.block instanceof IcCapacitorBlock) {
                line = formatIcCapacitor(cIdx.assign(comp.componentNumber), comp, pdkName);
            } else if (comp.block instanceof IcNmos4Block) {
                line = formatIcMosfet(mIdx.assign(comp.componentNumber), comp, pdkName, false);
            } else if (comp.block instanceof IcPmos4Block) {
                line = formatIcMosfet(mIdx.assign(comp.componentNumber), comp, pdkName, true);
            } else if (comp.block instanceof ResistorBlock) {
                line = String.format("R%d %d %d %g", rIdx.assign(comp.componentNumber), comp.nodeA, comp.nodeB, comp.value);
            } else if (comp.block instanceof CapacitorBlock) {
                line = String.format("C%d %d %d %g", cIdx.assign(comp.componentNumber), comp.nodeA, comp.nodeB, comp.value);
            } else if (comp.block instanceof InductorBlock) {
                line = String.format("L%d %d %d %g", lIdx.assign(comp.componentNumber), comp.nodeA, comp.nodeB, comp.value);
            } else if (comp.block instanceof VoltageSourceBlock) {
                if ("AC".equalsIgnoreCase(comp.sourceType)) {
                    line = String.format("V%d %d %d AC %g", vIdx.assign(comp.componentNumber), comp.nodeA, comp.nodeB, comp.value);
                } else {
                    line = String.format("V%d %d %d DC %g", vIdx.assign(comp.componentNumber), comp.nodeA, comp.nodeB, comp.value);
                }
            } else if (comp.block instanceof VoltageSourceSinBlock) {
                line = String.format("V%d %d %d DC 0", vIdx.assign(comp.componentNumber), comp.nodeA, comp.nodeB);
            } else if (comp.block instanceof CurrentSourceBlock) {
                line = String.format("I%d %d %d DC %g", iIdx.assign(comp.componentNumber), comp.nodeA, comp.nodeB, comp.value);
            } else if (comp.block instanceof DiodeBlock) {
                line = String.format("D%d %d %d DMOD", dIdx.assign(comp.componentNumber), comp.nodeA, comp.nodeB);
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

        IndexAssigner rIdx = new IndexAssigner(), cIdx = new IndexAssigner(),
                      lIdx = new IndexAssigner(), vIdx = new IndexAssigner(),
                      iIdx = new IndexAssigner(), dIdx = new IndexAssigner(),
                      mIdx = new IndexAssigner();
        claimManual(components, rIdx, cIdx, lIdx, vIdx, iIdx, dIdx, mIdx);
        int vmCount = 1;
        boolean hasDiode    = false;
        boolean hasAcSource = false;

        for (CircuitComponent comp : components) {
            String line;
            if (comp.block instanceof IcResistorBlock) {
                line = formatIcResistor(rIdx.assign(comp.componentNumber), comp, pdkName);
            } else if (comp.block instanceof IcCapacitorBlock) {
                line = formatIcCapacitor(cIdx.assign(comp.componentNumber), comp, pdkName);
            } else if (comp.block instanceof IcNmos4Block) {
                line = formatIcMosfet(mIdx.assign(comp.componentNumber), comp, pdkName, false);
            } else if (comp.block instanceof IcPmos4Block) {
                line = formatIcMosfet(mIdx.assign(comp.componentNumber), comp, pdkName, true);
            } else if (comp.block instanceof ResistorBlock) {
                line = String.format("R%d %d %d %g", rIdx.assign(comp.componentNumber), comp.nodeA, comp.nodeB, comp.value);
            } else if (comp.block instanceof CapacitorBlock) {
                line = String.format("C%d %d %d %g", cIdx.assign(comp.componentNumber), comp.nodeA, comp.nodeB, comp.value);
            } else if (comp.block instanceof InductorBlock) {
                line = String.format("L%d %d %d %g", lIdx.assign(comp.componentNumber), comp.nodeA, comp.nodeB, comp.value);
            } else if (comp.block instanceof VoltageSourceBlock) {
                if ("AC".equalsIgnoreCase(comp.sourceType)) {
                    line = String.format("V%d %d %d DC 0 AC %g",
                            vIdx.assign(comp.componentNumber), comp.nodeA, comp.nodeB, comp.value);
                    hasAcSource = true;
                } else {
                    line = String.format("V%d %d %d DC %g AC 0",
                            vIdx.assign(comp.componentNumber), comp.nodeA, comp.nodeB, comp.value);
                }
            } else if (comp.block instanceof VoltageSourceSinBlock) {
                line = String.format("V%d %d %d DC 0 AC %g",
                        vIdx.assign(comp.componentNumber), comp.nodeA, comp.nodeB, comp.value);
                hasAcSource = true;
            } else if (comp.block instanceof CurrentSourceBlock) {
                if ("AC".equalsIgnoreCase(comp.sourceType)) {
                    line = String.format("I%d %d %d DC 0 AC %g",
                            iIdx.assign(comp.componentNumber), comp.nodeA, comp.nodeB, comp.value);
                    hasAcSource = true;
                } else {
                    line = String.format("I%d %d %d DC %g AC 0",
                            iIdx.assign(comp.componentNumber), comp.nodeA, comp.nodeB, comp.value);
                }
            } else if (comp.block instanceof DiodeBlock) {
                line = String.format("D%d %d %d DMOD", dIdx.assign(comp.componentNumber), comp.nodeA, comp.nodeB);
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

        IndexAssigner rIdx = new IndexAssigner(), cIdx = new IndexAssigner(),
                      lIdx = new IndexAssigner(), vIdx = new IndexAssigner(),
                      iIdx = new IndexAssigner(), dIdx = new IndexAssigner(),
                      mIdx = new IndexAssigner();
        claimManual(components, rIdx, cIdx, lIdx, vIdx, iIdx, dIdx, mIdx);
        int vmCount = 1;
        boolean hasDiode = false;

        for (CircuitComponent comp : components) {
            String line;
            if (comp.block instanceof IcResistorBlock) {
                line = formatIcResistor(rIdx.assign(comp.componentNumber), comp, pdkName);
            } else if (comp.block instanceof IcCapacitorBlock) {
                line = formatIcCapacitor(cIdx.assign(comp.componentNumber), comp, pdkName);
            } else if (comp.block instanceof IcNmos4Block) {
                line = formatIcMosfet(mIdx.assign(comp.componentNumber), comp, pdkName, false);
            } else if (comp.block instanceof IcPmos4Block) {
                line = formatIcMosfet(mIdx.assign(comp.componentNumber), comp, pdkName, true);
            } else if (comp.block instanceof ResistorBlock) {
                line = String.format("R%d %d %d %g", rIdx.assign(comp.componentNumber), comp.nodeA, comp.nodeB, comp.value);
            } else if (comp.block instanceof CapacitorBlock) {
                line = String.format("C%d %d %d %g", cIdx.assign(comp.componentNumber), comp.nodeA, comp.nodeB, comp.value);
            } else if (comp.block instanceof InductorBlock) {
                line = String.format("L%d %d %d %g", lIdx.assign(comp.componentNumber), comp.nodeA, comp.nodeB, comp.value);
            } else if (comp.block instanceof VoltageSourceBlock) {
                line = String.format("V%d %d %d DC %g", vIdx.assign(comp.componentNumber), comp.nodeA, comp.nodeB, comp.value);
            } else if (comp.block instanceof VoltageSourceSinBlock) {
                double freq = (comp.frequency > 0) ? comp.frequency : 1.0;
                line = String.format("V%d %d %d SIN(0 %g %g)",
                        vIdx.assign(comp.componentNumber), comp.nodeA, comp.nodeB, comp.value, freq);
            } else if (comp.block instanceof CurrentSourceBlock) {
                line = String.format("I%d %d %d DC %g", iIdx.assign(comp.componentNumber), comp.nodeA, comp.nodeB, comp.value);
            } else if (comp.block instanceof DiodeBlock) {
                line = String.format("D%d %d %d DMOD", dIdx.assign(comp.componentNumber), comp.nodeA, comp.nodeB);
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

    /**
     * Formats a 4-pin MOSFET instance line for sky130A nfet/pfet models.
     * Pin order in netlist: drain gate source bulk.
     * For NMOS: nodeA=drain(front), nodeB=source(back), nodeC=bulk(right), nodeD=gate(left).
     * For PMOS: nodeA=source(front), nodeB=drain(back), nodeC=bulk(right), nodeD=gate(left).
     * Derived area/perimeter params computed from W, L, NF per the sky130 HDK formulas.
     */
    private static String formatIcMosfet(int idx, CircuitComponent comp, String pdkName, boolean isPmos) {
        String prefix = pdkModelPrefix(pdkName);
        String defaultModel = isPmos ? "pfet_01v8" : "nfet_01v8";
        String name  = comp.modelName.isBlank() ? defaultModel : comp.modelName;
        String model = prefix + name;

        double w    = comp.wParam    > 0 ? comp.wParam    : 1.0;
        double l    = comp.lParam    > 0 ? comp.lParam    : 1.0;
        double mult = comp.multParam > 0 ? comp.multParam : 1.0;
        int    nf   = (int) Math.max(1, Math.round(comp.nfParam));

        int drain = isPmos ? comp.nodeB : comp.nodeA;
        int src   = isPmos ? comp.nodeA : comp.nodeB;
        int bulk  = comp.nodeC >= 0 ? comp.nodeC : 0;
        int gate  = comp.nodeD >= 0 ? comp.nodeD : 0;

        // sky130 HDK standard area/perimeter formulas
        double hdif = 0.29;
        double w_f  = w / nf;
        int    n_d  = (nf + 1) / 2;
        int    n_s  = (nf + 2) / 2;
        double ad   = w_f * hdif * n_d;
        double as_  = w_f * hdif * n_s;
        double pd   = 2.0 * n_d * (w_f + hdif);
        double ps   = 2.0 * n_s * (w_f + hdif);
        double nrd  = hdif / w;
        double nrs  = hdif / w;

        return String.format(
            "XM%d %d %d %d %d %s%n+ L=%g W=%g NF=%d%n+ AD=%g AS=%g%n+ PD=%g PS=%g%n+ NRD=%g NRS=%g%n+ SA=0 SB=0 SD=0 MULT=%g",
            idx, drain, gate, src, bulk, model,
            l, w, nf,
            ad, as_,
            pd, ps,
            nrd, nrs,
            mult
        );
    }

    /** Formats a 2-pin IC capacitor subcircuit line. */
    private static String formatIcCapacitor(int idx, CircuitComponent comp, String pdkName) {
        String prefix = pdkModelPrefix(pdkName);
        String name   = comp.modelName.isBlank() ? "cap_mim_m3_1" : comp.modelName;
        String model  = prefix + name;
        double w  = comp.wParam    > 0 ? comp.wParam    : 1.0;
        double l  = comp.lParam    > 0 ? comp.lParam    : 1.0;
        double mf = comp.multParam > 0 ? comp.multParam : 1.0;
        return String.format("XC%d %d %d %s W=%g L=%g MF=%g m=%g",
                idx, comp.nodeA, comp.nodeB, model, w, l, mf, mf);
    }

    /**
     * Computes display capacitance in Farads given the active PDK and model name.
     * W and L are in µm. Returns null if no formula is known for the combination.
     */
    public static Double computeCapacitance(String pdkName, String modelName, double w, double l, double mf) {
        double wEff  = w  > 0 ? w  : 1.0;
        double lEff  = l  > 0 ? l  : 1.0;
        double mfEff = mf > 0 ? mf : 1.0;
        if ("sky130A".equals(pdkName)) {
            String name = (modelName == null || modelName.isBlank()) ? "cap_mim_m3_1" : modelName;
            return switch (name) {
                case "cap_mim_m3_1" -> mfEff * (wEff * lEff * 2e-15 + (wEff + lEff) * 0.38e-15);
                default             -> null;
            };
        }
        return null;
    }

    /**
     * Computes display resistance in Ohms given the active PDK and model name.
     * W and L are in µm. Returns null if no formula is known for the combination.
     */
    public static Double computeResistance(String pdkName, String modelName, double w, double l, double mult) {
        double wEff    = w    > 0 ? w    : 1.0;
        double lEff    = l    > 0 ? l    : 1.0;
        double multEff = mult > 0 ? mult : 1.0;
        if ("sky130A".equals(pdkName)) {
            String name = (modelName == null || modelName.isBlank()) ? "res_high_po" : modelName;
            return switch (name) {
                case "res_high_po"  -> (378.3 + 317.17 * lEff) / wEff / multEff;
                case "res_xhigh_po" -> 2000.0 * lEff / wEff / multEff;
                default             -> null;
            };
        }
        return null;
    }

    /** @deprecated Use {@link #computeResistance} with pdkName and modelName. */
    @Deprecated
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
        public final int      nodeC;   // third pin (bulk for resistor/mosfet), -1 means unused
        public final int      nodeD;   // fourth pin (gate for mosfet), -1 means unused
        public final double   value;
        public final String   sourceType;
        public final double   frequency;
        // sky130 params
        public final String   modelName;
        public final double   wParam;
        public final double   lParam;
        public final double   multParam;
        public final double   nfParam;
        // user-chosen index in the netlist (e.g. R5). 0 = auto-assigned.
        public final int      componentNumber;

        public CircuitComponent(Block block, BlockPos pos, int nodeA, int nodeB,
                                double value, String sourceType, double frequency) {
            this(block, pos, nodeA, nodeB, -1, -1, value, sourceType, frequency, "", 1.0, 1.0, 1.0, 1.0, 0);
        }

        public CircuitComponent(Block block, BlockPos pos, int nodeA, int nodeB,
                                double value, String sourceType, double frequency,
                                String modelName, double wParam, double lParam, double multParam) {
            this(block, pos, nodeA, nodeB, -1, -1, value, sourceType, frequency,
                    modelName, wParam, lParam, multParam, 1.0, 0);
        }

        public CircuitComponent(Block block, BlockPos pos, int nodeA, int nodeB, int nodeC,
                                double value, String sourceType, double frequency,
                                String modelName, double wParam, double lParam, double multParam) {
            this(block, pos, nodeA, nodeB, nodeC, -1, value, sourceType, frequency,
                    modelName, wParam, lParam, multParam, 1.0, 0);
        }

        public CircuitComponent(Block block, BlockPos pos, int nodeA, int nodeB, int nodeC, int nodeD,
                                double value, String sourceType, double frequency,
                                String modelName, double wParam, double lParam, double multParam,
                                double nfParam) {
            this(block, pos, nodeA, nodeB, nodeC, nodeD, value, sourceType, frequency,
                    modelName, wParam, lParam, multParam, nfParam, 0);
        }

        public CircuitComponent(Block block, BlockPos pos, int nodeA, int nodeB, int nodeC, int nodeD,
                                double value, String sourceType, double frequency,
                                String modelName, double wParam, double lParam, double multParam,
                                double nfParam, int componentNumber) {
            this.block           = block;
            this.pos             = pos;
            this.nodeA           = nodeA;
            this.nodeB           = nodeB;
            this.nodeC           = nodeC;
            this.nodeD           = nodeD;
            this.value           = value;
            this.sourceType      = sourceType;
            this.frequency       = frequency;
            this.modelName       = modelName;
            this.wParam          = wParam;
            this.lParam          = lParam;
            this.multParam       = multParam;
            this.nfParam         = nfParam;
            this.componentNumber = Math.max(0, componentNumber);
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
