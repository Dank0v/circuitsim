package com.circuitsim.simulation;

import com.circuitsim.block.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
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
        // returns 0 for none, 1=R, 2=C, 3=L, 4=V, 5=I, 6=D, 7=M, 8=X (subcircuits)
        // 9=E (VCVS), 10=F (CCCS), 11=G (VCCS), 12=H (CCVS), 13=S (switch),
        // 14=B (behavioral V/I sources — both share the B namespace)
        if (c.subcircuitNodes != null) return 8;
        if (c.block instanceof BehavioralVoltageSourceBlock
                || c.block instanceof BehavioralCurrentSourceBlock) return 14;
        if (c.block instanceof ResistorBlock || c.block instanceof IcResistorBlock) return 1;
        if (c.block instanceof CapacitorBlock || c.block instanceof IcCapacitorBlock) return 2;
        if (c.block instanceof InductorBlock) return 3;
        if (c.block instanceof VoltageSourceBlock
                || c.block instanceof VoltageSourceSinBlock
                || c.block instanceof VoltageSourcePulseBlock) return 4;
        if (c.block instanceof CurrentSourceBlock) return 5;
        if (c.block instanceof DiodeBlock) return 6;
        if (c.block instanceof IcNmos4Block || c.block instanceof IcPmos4Block) return 7;
        if (c.block instanceof VcvsBlock) return 9;
        if (c.block instanceof CccsBlock) return 10;
        if (c.block instanceof VccsBlock) return 11;
        if (c.block instanceof CcvsBlock) return 12;
        if (c.block instanceof VSwitchBlock) return 13;
        return 0;
    }

    /** Pre-claims all manually-set component numbers so auto-assignment skips them. */
    private static void claimManual(List<CircuitComponent> components,
                                    IndexAssigner r, IndexAssigner c, IndexAssigner l,
                                    IndexAssigner v, IndexAssigner i, IndexAssigner d,
                                    IndexAssigner m, IndexAssigner x,
                                    IndexAssigner e, IndexAssigner f,
                                    IndexAssigner g, IndexAssigner h,
                                    IndexAssigner s, IndexAssigner b) {
        for (CircuitComponent comp : components) {
            int n = comp.componentNumber;
            if (n <= 0) continue;
            switch (rIndexFamily(comp)) {
                case 1  -> r.claim(n);
                case 2  -> c.claim(n);
                case 3  -> l.claim(n);
                case 4  -> v.claim(n);
                case 5  -> i.claim(n);
                case 6  -> d.claim(n);
                case 7  -> m.claim(n);
                case 8  -> x.claim(n);
                case 9  -> e.claim(n);
                case 10 -> f.claim(n);
                case 11 -> g.claim(n);
                case 12 -> h.claim(n);
                case 13 -> s.claim(n);
                case 14 -> b.claim(n);
                default -> {}
            }
        }
    }

    // -------------------------------------------------------------------------
    // Voltage-controlled switch (S device + .model SW)
    // -------------------------------------------------------------------------

    /**
     * Formats an {@code S<n> n+ n- nc+ nc- MODEL [on|off]} line. Model
     * parameters travel in the sky130 carrier slots (the same trick the pulse
     * source uses): wParam=Vt, lParam=Vh, multParam=Ron, nfParam=Roff;
     * modelName holds the optional initial state.
     *
     * <p>Switches with identical parameters share one {@code .model} — the
     * {@code swModels} map is keyed by the canonical parameter string and
     * grows a fresh {@code SWMOD<k>} name per distinct set. The caller emits
     * the collected models via {@link #appendSwitchModels}.
     */
    private static String formatSwitch(CircuitComponent comp, IndexAssigner sIdx,
                                       java.util.Map<Integer, String> aliases,
                                       java.util.Map<String, String> swModels) {
        double vt   = comp.wParam;
        double vh   = comp.lParam;
        double ron  = comp.multParam > 0 ? comp.multParam : 1.0;
        double roff = comp.nfParam   > 0 ? comp.nfParam   : 1e12;
        String params = String.format(java.util.Locale.ROOT,
                "Vt=%g Vh=%g Ron=%g Roff=%g", vt, vh, ron, roff);
        String model = swModels.computeIfAbsent(params,
                k -> "SWMOD" + (swModels.size() + 1));
        String init = comp.modelName;
        String initSuffix = ("on".equalsIgnoreCase(init) || "off".equalsIgnoreCase(init))
                ? " " + init.toLowerCase(java.util.Locale.ROOT) : "";
        return String.format("S%d %s %s %s %s %s%s",
                sIdx.assign(comp.componentNumber),
                nodeRef(comp.nodeA, aliases), nodeRef(comp.nodeB, aliases),
                nodeRef(comp.nodeC, aliases), nodeRef(comp.nodeD, aliases),
                model, initSuffix);
    }

    /** Emits one {@code .model NAME SW(...)} line per distinct switch parameter set. */
    private static void appendSwitchModels(StringBuilder sb,
                                           java.util.Map<String, String> swModels) {
        for (java.util.Map.Entry<String, String> e : swModels.entrySet()) {
            sb.append(".model ").append(e.getValue())
              .append(" SW(").append(e.getKey()).append(")\n");
        }
    }

    /**
     * Value token for a device line: when the slot is driven by a Param
     * variable that survived constant substitution (i.e. the one being swept
     * by the {@code .control}-loop runner), emit a brace expression
     * {@code {name}} that ngspice re-evaluates from its {@code .param} on
     * every {@code alterparam}/{@code reset}; otherwise the plain number.
     */
    private static String num(double v, String expr) {
        if (expr != null && !expr.isEmpty()) return "{" + expr + "}";
        return String.format(java.util.Locale.ROOT, "%g", v);
    }

    /**
     * Formats a plain resistor line. When the block's "noiseless" toggle is
     * set (carried in the otherwise-unused modelName slot), the line gets the
     * ngspice instance flag {@code noisy=0}, which removes this resistor's
     * thermal noise from .noise analysis (verified against ngspice-46). The
     * flag is harmless in every other analysis, so it is emitted everywhere.
     */
    private static String formatResistor(int idx, CircuitComponent comp,
                                         java.util.Map<Integer, String> aliases) {
        String noisy = "noiseless".equals(comp.modelName) ? " noisy=0" : "";
        return String.format("R%d %s %s %s%s", idx,
                nodeRef(comp.nodeA, aliases), nodeRef(comp.nodeB, aliases),
                num(comp.value, comp.valueExpr), noisy);
    }

    /**
     * Formats a line for one of the four linear dependent sources. Returns
     * {@code null} if {@code comp.block} is not a controlled-source block.
     *
     * <p>Index assignment uses a separate per-family counter (one of
     * {@code e/f/g/h}). The voltage-controlled forms (E, G) use 4 explicit
     * pins; the current-controlled forms (F, H) use 2 pins plus a {@code vnam}
     * string carried in {@code comp.modelName} that names a voltage source
     * elsewhere in the netlist whose current sources the dependency.
     */
    private static String formatControlledSource(CircuitComponent comp,
                                                  IndexAssigner eIdx, IndexAssigner fIdx,
                                                  IndexAssigner gIdx, IndexAssigner hIdx,
                                                  java.util.Map<Integer, String> aliases) {
        if (comp.block instanceof VcvsBlock) {
            return String.format("E%d %s %s %s %s %s",
                    eIdx.assign(comp.componentNumber),
                    nodeRef(comp.nodeA, aliases), nodeRef(comp.nodeB, aliases),
                    nodeRef(comp.nodeC, aliases), nodeRef(comp.nodeD, aliases),
                    num(comp.value, comp.valueExpr));
        }
        if (comp.block instanceof VccsBlock) {
            return String.format("G%d %s %s %s %s %s",
                    gIdx.assign(comp.componentNumber),
                    nodeRef(comp.nodeA, aliases), nodeRef(comp.nodeB, aliases),
                    nodeRef(comp.nodeC, aliases), nodeRef(comp.nodeD, aliases),
                    num(comp.value, comp.valueExpr));
        }
        if (comp.block instanceof CcvsBlock) {
            String vnam = (comp.modelName == null || comp.modelName.isBlank())
                    ? "VUNDEFINED" : comp.modelName.trim();
            return String.format("H%d %s %s %s %s",
                    hIdx.assign(comp.componentNumber),
                    nodeRef(comp.nodeA, aliases), nodeRef(comp.nodeB, aliases),
                    vnam, num(comp.value, comp.valueExpr));
        }
        if (comp.block instanceof CccsBlock) {
            String vnam = (comp.modelName == null || comp.modelName.isBlank())
                    ? "VUNDEFINED" : comp.modelName.trim();
            return String.format("F%d %s %s %s %s",
                    fIdx.assign(comp.componentNumber),
                    nodeRef(comp.nodeA, aliases), nodeRef(comp.nodeB, aliases),
                    vnam, num(comp.value, comp.valueExpr));
        }
        return null;
    }

    /**
     * Formats a behavioral (arbitrary) source line:
     * {@code B<n> n+ n- V=<expr>} for a behavioral voltage source, or
     * {@code B<n> n+ n- I=<expr>} for a behavioral current source. Both forms
     * share the single ngspice {@code B} device namespace, so they draw from
     * one index family.
     *
     * <p>The expression is the raw text the player typed (carried in
     * {@code comp.modelName}); it is emitted verbatim so any ngspice-legal
     * expression — node voltages {@code v(a)}, branch currents {@code i(Vx)},
     * {@code time}, {@code hertz}, math functions, etc. — works. An empty
     * expression falls back to {@code 0} so the netlist stays valid.
     */
    private static String formatBehavioral(int idx, CircuitComponent comp, boolean isVoltage,
                                           java.util.Map<Integer, String> aliases) {
        String expr = (comp.modelName == null || comp.modelName.isBlank())
                ? "0" : comp.modelName.trim();
        return String.format("B%d %s %s %s=%s", idx,
                nodeRef(comp.nodeA, aliases), nodeRef(comp.nodeB, aliases),
                isVoltage ? "V" : "I", expr);
    }

    /**
     * Builds an {@code X<n> pin0 pin1 ... model} line for a subcircuit
     * instance, applying any active node aliases.
     */
    private static String formatSubcircuit(int idx, CircuitComponent comp,
                                            java.util.Map<Integer, String> aliases) {
        StringBuilder sb = new StringBuilder();
        // Discrete BJTs reference a SPICE .model (e.g. from a vendor BIPOLAR.lib)
        // and so are emitted as native Q devices (Q<n> C B E MODEL), not X
        // subcircuit instances. Everything else on this path is a real .SUBCKT.
        boolean isBjt = comp.block instanceof DiscreteNpnBlock
                || comp.block instanceof DiscretePnpBlock;
        sb.append(isBjt ? 'Q' : 'X').append(idx);
        for (int n : comp.subcircuitNodes) sb.append(' ').append(nodeRef(n, aliases));
        String model = comp.modelName == null || comp.modelName.isBlank()
                ? "UNDEFINED_MODEL"
                : comp.modelName;
        sb.append(' ').append(model);
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // .SUBCKT definition (user-defined subcircuits)
    // -------------------------------------------------------------------------

    /**
     * Builds a self-contained {@code .subckt <name> <pins…> … .ends} block from
     * an extracted circuit. The device lines mirror the analysis-independent
     * forms used by {@link #buildTranNetlist} (full SIN/PULSE specs, etc.) so
     * the resulting subcircuit behaves correctly under any analysis once
     * instantiated. Current probes, analysis directives, and the control block
     * are intentionally omitted — a subcircuit is just a device list.
     *
     * <p>{@code pinNames} are the external terminals in declaration order; they
     * must be the sanitized probe-label aliases that also appear in the body
     * (via {@link #aliasesFromProbes}), so node references resolve to the pin
     * names. Node {@code 0} stays global ground inside the subcircuit.
     */
    public static String buildSubcktDefinition(String name, List<String> pinNames,
                                               List<CircuitComponent> components,
                                               List<ProbeInfo> probes) {
        StringBuilder sb = new StringBuilder();
        sb.append(".subckt ").append(name);
        if (pinNames != null) {
            for (String p : pinNames) sb.append(' ').append(p);
        }
        sb.append('\n');

        java.util.Map<Integer, String> aliases = aliasesFromProbes(probes);

        IndexAssigner rIdx = new IndexAssigner(), cIdx = new IndexAssigner(),
                      lIdx = new IndexAssigner(), vIdx = new IndexAssigner(),
                      iIdx = new IndexAssigner(), dIdx = new IndexAssigner(),
                      mIdx = new IndexAssigner(), xIdx = new IndexAssigner(),
                      eIdx = new IndexAssigner(), fIdx = new IndexAssigner(),
                      gIdx = new IndexAssigner(), hIdx = new IndexAssigner(),
                      sIdx = new IndexAssigner(), bIdx = new IndexAssigner();
        claimManual(components, rIdx, cIdx, lIdx, vIdx, iIdx, dIdx, mIdx, xIdx,
                eIdx, fIdx, gIdx, hIdx, sIdx, bIdx);
        java.util.Map<String, String> swModels = new java.util.LinkedHashMap<>();
        boolean hasDiode = false;

        for (CircuitComponent comp : components) {
            String line;
            if (comp.subcircuitNodes != null) {
                line = formatSubcircuit(xIdx.assign(comp.componentNumber), comp, aliases);
            } else if (comp.block instanceof IcResistorBlock) {
                line = formatIcResistor(rIdx.assign(comp.componentNumber), comp, aliases);
            } else if (comp.block instanceof IcCapacitorBlock) {
                line = formatIcCapacitor(cIdx.assign(comp.componentNumber), comp, aliases);
            } else if (comp.block instanceof IcNmos4Block) {
                line = formatIcMosfet(mIdx.assign(comp.componentNumber), comp, false, aliases);
            } else if (comp.block instanceof IcPmos4Block) {
                line = formatIcMosfet(mIdx.assign(comp.componentNumber), comp, true, aliases);
            } else if (comp.block instanceof ResistorBlock) {
                line = formatResistor(rIdx.assign(comp.componentNumber), comp, aliases);
            } else if (comp.block instanceof CapacitorBlock) {
                line = String.format("C%d %s %s %s", cIdx.assign(comp.componentNumber), nodeRef(comp.nodeA, aliases), nodeRef(comp.nodeB, aliases), num(comp.value, comp.valueExpr));
            } else if (comp.block instanceof InductorBlock) {
                line = String.format("L%d %s %s %s", lIdx.assign(comp.componentNumber), nodeRef(comp.nodeA, aliases), nodeRef(comp.nodeB, aliases), num(comp.value, comp.valueExpr));
            } else if (comp.block instanceof VoltageSourceBlock) {
                // Carry both DC bias and (when set) AC magnitude so the subckt
                // is usable in .op AND .ac without re-extraction.
                StringBuilder v = new StringBuilder();
                v.append(String.format("V%d %s %s DC %s",
                        vIdx.assign(comp.componentNumber),
                        nodeRef(comp.nodeA, aliases), nodeRef(comp.nodeB, aliases),
                        num(comp.value, comp.valueExpr)));
                if (comp.acValue != 0 || (comp.acValueExpr != null && !comp.acValueExpr.isBlank())) {
                    v.append(" AC ").append(num(comp.acValue, comp.acValueExpr));
                }
                line = v.toString();
            } else if (comp.block instanceof VoltageSourceSinBlock) {
                double freq = (comp.frequency > 0) ? comp.frequency : 1.0;
                line = String.format("V%d %s %s SIN(0 %s %g)",
                        vIdx.assign(comp.componentNumber), nodeRef(comp.nodeA, aliases), nodeRef(comp.nodeB, aliases), num(comp.value, comp.valueExpr), freq);
            } else if (comp.block instanceof VoltageSourcePulseBlock) {
                double v1  = comp.wParam;
                double tr  = comp.lParam    > 0 ? comp.lParam    : 1e-9;
                double tf  = comp.multParam > 0 ? comp.multParam : 1e-9;
                double pw  = comp.nfParam   > 0 ? comp.nfParam   : 1e-6;
                double per = comp.frequency > 0 ? comp.frequency : 2e-6;
                line = String.format("V%d %s %s PULSE(%g %s 0 %g %g %g %g)",
                        vIdx.assign(comp.componentNumber),
                        nodeRef(comp.nodeA, aliases), nodeRef(comp.nodeB, aliases),
                        v1, num(comp.value, comp.valueExpr), tr, tf, pw, per);
            } else if (comp.block instanceof CurrentSourceBlock) {
                line = String.format("I%d %s %s DC %s", iIdx.assign(comp.componentNumber), nodeRef(comp.nodeA, aliases), nodeRef(comp.nodeB, aliases), num(comp.value, comp.valueExpr));
            } else if (comp.block instanceof DiodeBlock) {
                String dmodel = (comp.modelName == null || comp.modelName.isBlank())
                        ? "DMOD" : comp.modelName.trim();
                line = String.format("D%d %s %s %s",
                        dIdx.assign(comp.componentNumber),
                        nodeRef(comp.nodeA, aliases), nodeRef(comp.nodeB, aliases), dmodel);
                if ("DMOD".equals(dmodel)) hasDiode = true;
            } else if (comp.block instanceof BehavioralVoltageSourceBlock) {
                line = formatBehavioral(bIdx.assign(comp.componentNumber), comp, true, aliases);
            } else if (comp.block instanceof BehavioralCurrentSourceBlock) {
                line = formatBehavioral(bIdx.assign(comp.componentNumber), comp, false, aliases);
            } else if (comp.block instanceof VSwitchBlock) {
                line = formatSwitch(comp, sIdx, aliases, swModels);
            } else {
                String controlled = formatControlledSource(comp, eIdx, fIdx, gIdx, hIdx, aliases);
                if (controlled == null) continue;
                line = controlled;
            }
            sb.append(line).append('\n');
        }

        // Models local to the subcircuit (legal in ngspice — scoped to the subckt).
        if (hasDiode) sb.append(".MODEL DMOD D\n");
        appendSwitchModels(sb, swModels);

        sb.append(".ends ").append(name).append('\n');
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // .OP
    // -------------------------------------------------------------------------

    public static String buildNetlist(List<CircuitComponent> components,
                                       List<ProbeInfo>        probes,
                                       List<CurrentProbeInfo> currentProbes,
                                       String                 pdkName,
                                       String                 pdkLibPath) {
        return buildNetlist(components, probes, currentProbes, pdkName, pdkLibPath, List.of(), List.of());
    }

    public static String buildNetlist(List<CircuitComponent> components,
                                       List<ProbeInfo>        probes,
                                       List<CurrentProbeInfo> currentProbes,
                                       String                 pdkName,
                                       String                 pdkLibPath,
                                       List<String>           userCommands) {
        return buildNetlist(components, probes, currentProbes, pdkName, pdkLibPath, userCommands, List.of());
    }

    public static String buildNetlist(List<CircuitComponent> components,
                                       List<ProbeInfo>        probes,
                                       List<CurrentProbeInfo> currentProbes,
                                       String                 pdkName,
                                       String                 pdkLibPath,
                                       List<String>           userCommands,
                                       List<UserPlot>         userPlots) {
        return buildNetlist(components, probes, currentProbes, pdkName, pdkLibPath,
                "", "hsa", userCommands, userPlots);
    }

    public static String buildNetlist(List<CircuitComponent> components,
                                       List<ProbeInfo>        probes,
                                       List<CurrentProbeInfo> currentProbes,
                                       String                 pdkName,
                                       String                 pdkLibPath,
                                       String                 pdkLibPaths,
                                       String                 ngBehavior,
                                       List<String>           userCommands,
                                       List<UserPlot>         userPlots) {
        StringBuilder sb = new StringBuilder();
        sb.append("* CircuitSim Netlist\n");
        appendPdkLib(sb, pdkName, pdkLibPath, pdkLibPaths, ngBehavior);

        java.util.Map<Integer, String> aliases = aliasesFromProbes(probes);

        IndexAssigner rIdx = new IndexAssigner(), cIdx = new IndexAssigner(),
                      lIdx = new IndexAssigner(), vIdx = new IndexAssigner(),
                      iIdx = new IndexAssigner(), dIdx = new IndexAssigner(),
                      mIdx = new IndexAssigner(), xIdx = new IndexAssigner(),
                      eIdx = new IndexAssigner(), fIdx = new IndexAssigner(),
                      gIdx = new IndexAssigner(), hIdx = new IndexAssigner(),
                      sIdx = new IndexAssigner(), bIdx = new IndexAssigner();
        claimManual(components, rIdx, cIdx, lIdx, vIdx, iIdx, dIdx, mIdx, xIdx,
                eIdx, fIdx, gIdx, hIdx, sIdx, bIdx);
        java.util.Map<String, String> swModels = new java.util.LinkedHashMap<>();
        int vmCount = 1;
        boolean hasDiode = false;

        for (CircuitComponent comp : components) {
            String line;
            if (comp.subcircuitNodes != null) {
                line = formatSubcircuit(xIdx.assign(comp.componentNumber), comp, aliases);
            } else if (comp.block instanceof IcResistorBlock) {
                line = formatIcResistor(rIdx.assign(comp.componentNumber), comp, aliases);
            } else if (comp.block instanceof IcCapacitorBlock) {
                line = formatIcCapacitor(cIdx.assign(comp.componentNumber), comp, aliases);
            } else if (comp.block instanceof IcNmos4Block) {
                line = formatIcMosfet(mIdx.assign(comp.componentNumber), comp, false, aliases);
            } else if (comp.block instanceof IcPmos4Block) {
                line = formatIcMosfet(mIdx.assign(comp.componentNumber), comp, true, aliases);
            } else if (comp.block instanceof ResistorBlock) {
                line = formatResistor(rIdx.assign(comp.componentNumber), comp, aliases);
            } else if (comp.block instanceof CapacitorBlock) {
                line = String.format("C%d %s %s %s", cIdx.assign(comp.componentNumber), nodeRef(comp.nodeA, aliases), nodeRef(comp.nodeB, aliases), num(comp.value, comp.valueExpr));
            } else if (comp.block instanceof InductorBlock) {
                line = String.format("L%d %s %s %s", lIdx.assign(comp.componentNumber), nodeRef(comp.nodeA, aliases), nodeRef(comp.nodeB, aliases), num(comp.value, comp.valueExpr));
            } else if (comp.block instanceof VoltageSourceBlock) {
                // OP analysis cares only about the DC bias; the AC magnitude is
                // a small-signal perturbation that is meaningless here.
                line = String.format("V%d %s %s DC %s",
                        vIdx.assign(comp.componentNumber),
                        nodeRef(comp.nodeA, aliases), nodeRef(comp.nodeB, aliases),
                        num(comp.value, comp.valueExpr));
            } else if (comp.block instanceof VoltageSourceSinBlock) {
                line = String.format("V%d %s %s DC 0", vIdx.assign(comp.componentNumber), nodeRef(comp.nodeA, aliases), nodeRef(comp.nodeB, aliases));
            } else if (comp.block instanceof VoltageSourcePulseBlock) {
                // Pulse source's "initial value" V1 is the steady-state DC
                // ngspice uses during the bias-point calculation. For .op
                // this is the only meaningful number; the rest of the spec
                // is irrelevant until .tran.
                line = String.format("V%d %s %s DC %g",
                        vIdx.assign(comp.componentNumber),
                        nodeRef(comp.nodeA, aliases), nodeRef(comp.nodeB, aliases),
                        comp.wParam);
            } else if (comp.block instanceof CurrentSourceBlock) {
                line = String.format("I%d %s %s DC %s", iIdx.assign(comp.componentNumber), nodeRef(comp.nodeA, aliases), nodeRef(comp.nodeB, aliases), num(comp.value, comp.valueExpr));
            } else if (comp.block instanceof DiodeBlock) {
                // No user model → fall back to the built-in DMOD (.MODEL D)
                // emitted below. A user-typed model name is assumed to be
                // provided by an included library (.lib / .INCLUDE).
                String dmodel = (comp.modelName == null || comp.modelName.isBlank())
                        ? "DMOD" : comp.modelName.trim();
                line = String.format("D%d %s %s %s",
                        dIdx.assign(comp.componentNumber),
                        nodeRef(comp.nodeA, aliases), nodeRef(comp.nodeB, aliases), dmodel);
                if ("DMOD".equals(dmodel)) hasDiode = true;
            } else if (comp.block instanceof BehavioralVoltageSourceBlock) {
                line = formatBehavioral(bIdx.assign(comp.componentNumber), comp, true, aliases);
            } else if (comp.block instanceof BehavioralCurrentSourceBlock) {
                line = formatBehavioral(bIdx.assign(comp.componentNumber), comp, false, aliases);
            } else if (comp.block instanceof VSwitchBlock) {
                line = formatSwitch(comp, sIdx, aliases, swModels);
            } else {
                String controlled = formatControlledSource(comp, eIdx, fIdx, gIdx, hIdx, aliases);
                if (controlled == null) continue;
                line = controlled;
            }
            sb.append(line).append("\n");
        }

        for (CurrentProbeInfo cp : currentProbes) {
            sb.append(String.format("VM%d %s %s DC 0\n", vmCount++, nodeRef(cp.nodeA, aliases), nodeRef(cp.nodeB, aliases)));
        }

        if (hasDiode) sb.append(".MODEL DMOD D\n");
        appendSwitchModels(sb, swModels);

        sb.append(".op\n");
        sb.append(".control\n");

        appendPreRunCommands(sb, userCommands);
        sb.append("  run\n");

        appendUserPlotLets(sb, userPlots);

        appendUserCommands(sb, userCommands);

        for (ProbeInfo probe : probes) {
            if (probe.noPlot) continue;   // name-only probe: alias the net, don't print it
            sb.append(String.format("  print v(%s)\n", probe.netName));
        }
        int vmIdx = 1;
        for (CurrentProbeInfo cp : currentProbes) {
            sb.append(String.format("  print i(VM%d)\n", vmIdx++));
        }
        if (userPlots != null) {
            for (UserPlot p : userPlots) {
                if (p != null && p.name != null) sb.append("  print ").append(p.name).append("\n");
            }
        }

        // Dump every device's full operating point so the client's "annotate
        // operating points" feature (the K menu) can pull from it. One `show
        // <class>` per device family present enumerates all params for all
        // devices of that family — including the ones nested inside subcircuits
        // (sky130 IC mosfets show up as `m.xm1.m1`). Parsed back in
        // NgSpiceRunner.parseShowTables.
        for (char cls : opShowClasses(components)) {
            sb.append("  show ").append(cls).append("\n");
        }

        sb.append(".endc\n");
        sb.append(".end\n");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Device → operating-point mapping (powers the K-menu OP annotation)
    // -------------------------------------------------------------------------

    /**
     * One mappable device: its physical block position, the SPICE instance name
     * the netlist builder assigned it, the {@code show} class letter that lists
     * its operating point, whether it's subcircuit-wrapped (so its {@code show}
     * name is hierarchical), and a UI {@code typeKey}/{@code label} for grouping
     * in the edit menu. Multi-device blocks (amplifier, user subcircuit chips)
     * have no single operating point and are intentionally absent.
     */
    public record DeviceRef(BlockPos pos, String spiceName, char showClass,
                            boolean subckt, String typeKey, String label) {}

    /**
     * Describes every annotatable device in {@code components}, assigning SPICE
     * names with the exact same family counters / manual-claim logic as the
     * netlist builders so the names line up with what ngspice actually used.
     * The returned order matches the component order.
     */
    public static List<DeviceRef> describeDevices(List<CircuitComponent> components) {
        IndexAssigner[] fam = new IndexAssigner[15];
        for (int k = 1; k <= 14; k++) fam[k] = new IndexAssigner();
        for (CircuitComponent comp : components) {
            int n = comp.componentNumber;
            if (n > 0) {
                int f = rIndexFamily(comp);
                if (f >= 1 && f <= 14) fam[f].claim(n);
            }
        }
        List<DeviceRef> out = new ArrayList<>();
        for (CircuitComponent comp : components) {
            int f = rIndexFamily(comp);
            if (f < 1 || f > 14) continue;
            int idx = fam[f].assign(comp.componentNumber);
            DeviceRef ref = describeOne(comp, idx);
            if (ref != null) out.add(ref);
        }
        return out;
    }

    private static DeviceRef describeOne(CircuitComponent comp, int idx) {
        Block b = comp.block;
        if (b instanceof IcResistorBlock)   return new DeviceRef(comp.pos, "XR" + idx, 'r', true,  "ic_resistor3", "IC Resistor");
        if (b instanceof ResistorBlock)      return new DeviceRef(comp.pos, "R"  + idx, 'r', false, "resistor",     "Resistor");
        if (b instanceof IcCapacitorBlock)   return new DeviceRef(comp.pos, "XC" + idx, 'c', true,  "ic_capacitor2","IC Capacitor");
        if (b instanceof CapacitorBlock)     return new DeviceRef(comp.pos, "C"  + idx, 'c', false, "capacitor",    "Capacitor");
        if (b instanceof InductorBlock)      return new DeviceRef(comp.pos, "L"  + idx, 'l', false, "inductor",     "Inductor");
        if (b instanceof VoltageSourceBlock
                || b instanceof VoltageSourceSinBlock
                || b instanceof VoltageSourcePulseBlock)
                                             return new DeviceRef(comp.pos, "V"  + idx, 'v', false, "voltage_source","Voltage Source");
        if (b instanceof CurrentSourceBlock) return new DeviceRef(comp.pos, "I"  + idx, 'i', false, "current_source","Current Source");
        if (b instanceof DiodeBlock)         return new DeviceRef(comp.pos, "D"  + idx, 'd', false, "diode",        "Diode");
        if (b instanceof IcNmos4Block)       return new DeviceRef(comp.pos, "XM" + idx, 'm', true,  "ic_nmos4",     "NMOS (IC)");
        if (b instanceof IcPmos4Block)       return new DeviceRef(comp.pos, "XM" + idx, 'm', true,  "ic_pmos4",     "PMOS (IC)");
        if (b instanceof DiscreteNmosBlock)  return new DeviceRef(comp.pos, "X"  + idx, 'm', true,  "discrete_nmos","NMOS");
        if (b instanceof DiscretePmosBlock)  return new DeviceRef(comp.pos, "X"  + idx, 'm', true,  "discrete_pmos","PMOS");
        if (b instanceof DiscreteNpnBlock)   return new DeviceRef(comp.pos, "Q"  + idx, 'q', false, "discrete_npn", "NPN");
        if (b instanceof DiscretePnpBlock)   return new DeviceRef(comp.pos, "Q"  + idx, 'q', false, "discrete_pnp", "PNP");
        if (b instanceof BehavioralVoltageSourceBlock)
                                             return new DeviceRef(comp.pos, "B"  + idx, 'b', false, "behavioral_voltage_source", "B-Source V");
        if (b instanceof BehavioralCurrentSourceBlock)
                                             return new DeviceRef(comp.pos, "B"  + idx, 'b', false, "behavioral_current_source", "B-Source I");
        if (b instanceof VcvsBlock)          return new DeviceRef(comp.pos, "E"  + idx, 'e', false, "vcvs", "VCVS");
        if (b instanceof VccsBlock)          return new DeviceRef(comp.pos, "G"  + idx, 'g', false, "vccs", "VCCS");
        if (b instanceof CcvsBlock)          return new DeviceRef(comp.pos, "H"  + idx, 'h', false, "ccvs", "CCVS");
        if (b instanceof CccsBlock)          return new DeviceRef(comp.pos, "F"  + idx, 'f', false, "cccs", "CCCS");
        if (b instanceof VSwitchBlock)       return new DeviceRef(comp.pos, "S"  + idx, 's', false, "vswitch", "Switch");
        return null;   // amplifier / user subcircuit chip — no single OP
    }

    /** One user-subcircuit instance: its anchor block and the SPICE X-index assigned to it. */
    public record SubInstanceRef(BlockPos pos, int xIndex) {}

    /**
     * Assigns each user {@link SubcircuitBlock} instance its SPICE X-index using
     * the same family-8 ({@code X}) counter the netlist builders use, so the
     * index lines up with the {@code x<idx>} segment ngspice prints for the
     * subcircuit's internal devices (e.g. {@code m.x1.m1}). Discrete-mosfet / IC
     * subckts also live in the X family, so they're counted here (but not
     * emitted) to keep the numbering identical to the generated netlist.
     */
    public static List<SubInstanceRef> describeSubcircuitInstances(List<CircuitComponent> components) {
        IndexAssigner xIdx = new IndexAssigner();
        for (CircuitComponent comp : components) {
            int n = comp.componentNumber;
            if (n > 0 && rIndexFamily(comp) == 8) xIdx.claim(n);
        }
        List<SubInstanceRef> out = new ArrayList<>();
        for (CircuitComponent comp : components) {
            if (rIndexFamily(comp) != 8) continue;
            int idx = xIdx.assign(comp.componentNumber);
            if (comp.block instanceof SubcircuitBlock) out.add(new SubInstanceRef(comp.pos, idx));
        }
        return out;
    }

    /** The distinct {@code show} class letters needed for {@code components}, in stable order. */
    private static List<Character> opShowClasses(List<CircuitComponent> components) {
        java.util.LinkedHashSet<Character> set = new java.util.LinkedHashSet<>();
        for (DeviceRef ref : describeDevices(components)) set.add(ref.showClass());
        // When a subcircuit instance is present, its internal devices appear in
        // the show tables as hierarchical names (e.g. "r.x1.r1") — but only for
        // classes we actually `show`. We can't see the internal device classes
        // from the top-level components, so widen to the full device-class set so
        // every nested device's operating point is dumped (for the floating OP
        // projection). Classes with no devices print a harmless "no matching
        // instances" line.
        for (CircuitComponent c : components) {
            if (c.subcircuitNodes != null) {
                for (char cls : new char[]{'r','c','l','v','i','d','m','q','b','e','f','g','h','s'}) {
                    set.add(cls);
                }
                break;
            }
        }
        return new ArrayList<>(set);
    }

    /**
     * Finds the {@link DeviceRef} that a {@code show}-table device name refers
     * to. Flat names ({@code "r1"}) match a non-subckt ref by SPICE name;
     * hierarchical names ({@code "m.xm1.m1"}) match a subckt ref by the
     * subcircuit-instance segment ({@code "xm1"}). Returns null for names that
     * don't map to a physical block (e.g. devices inside a user subcircuit).
     */
    public static DeviceRef matchShowDevice(String showName, List<DeviceRef> refs) {
        if (showName == null || showName.isEmpty()) return null;
        String name = showName.toLowerCase();
        int dot = name.indexOf('.');
        if (dot >= 0) {
            // Hierarchical name like "m.x1.m1": the leading letter is the device
            // class, the first sub-instance segment ("x1") is the chip we placed.
            // Vendor models wrap a main device alongside parasitics (a body
            // diode "d.x1.d1", a gate resistor "r.x1.r1", …) — all share the
            // "x1" segment, so we MUST also match the class letter, otherwise a
            // discrete mosfet's operating point gets overwritten by its parasitic
            // diode/resistor (which lack id/vgs/gm and render nothing).
            char cls = name.charAt(0);
            String rest = name.substring(dot + 1);
            int dot2 = rest.indexOf('.');
            String inst = dot2 >= 0 ? rest.substring(0, dot2) : rest;
            for (DeviceRef r : refs) {
                if (r.subckt() && r.showClass() == cls
                        && r.spiceName().toLowerCase().equals(inst)) return r;
            }
            return null;
        }
        for (DeviceRef r : refs) {
            if (!r.subckt() && r.spiceName().toLowerCase().equals(name)) return r;
        }
        return null;
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
        return buildAcNetlist(components, probes, currentProbes, fStart, fStop, ptsPerDec,
                pdkName, pdkLibPath, List.of(), List.of());
    }

    public static String buildAcNetlist(List<CircuitComponent> components,
                                         List<ProbeInfo>        probes,
                                         List<CurrentProbeInfo> currentProbes,
                                         double fStart, double fStop, int ptsPerDec,
                                         String pdkName, String pdkLibPath,
                                         List<String> userCommands) {
        return buildAcNetlist(components, probes, currentProbes, fStart, fStop, ptsPerDec,
                pdkName, pdkLibPath, userCommands, List.of());
    }

    public static String buildAcNetlist(List<CircuitComponent> components,
                                         List<ProbeInfo>        probes,
                                         List<CurrentProbeInfo> currentProbes,
                                         double fStart, double fStop, int ptsPerDec,
                                         String pdkName, String pdkLibPath,
                                         List<String> userCommands,
                                         List<UserPlot> userPlots) {
        return buildAcNetlist(components, probes, currentProbes, fStart, fStop, ptsPerDec,
                pdkName, pdkLibPath, "", "hsa", userCommands, userPlots);
    }

    public static String buildAcNetlist(List<CircuitComponent> components,
                                         List<ProbeInfo>        probes,
                                         List<CurrentProbeInfo> currentProbes,
                                         double fStart, double fStop, int ptsPerDec,
                                         String pdkName, String pdkLibPath,
                                         String pdkLibPaths, String ngBehavior,
                                         List<String> userCommands,
                                         List<UserPlot> userPlots) {
        StringBuilder sb = new StringBuilder();
        sb.append("* CircuitSim AC Netlist\n");
        appendPdkLib(sb, pdkName, pdkLibPath, pdkLibPaths, ngBehavior);

        java.util.Map<Integer, String> aliases = aliasesFromProbes(probes);

        IndexAssigner rIdx = new IndexAssigner(), cIdx = new IndexAssigner(),
                      lIdx = new IndexAssigner(), vIdx = new IndexAssigner(),
                      iIdx = new IndexAssigner(), dIdx = new IndexAssigner(),
                      mIdx = new IndexAssigner(), xIdx = new IndexAssigner(),
                      eIdx = new IndexAssigner(), fIdx = new IndexAssigner(),
                      gIdx = new IndexAssigner(), hIdx = new IndexAssigner(),
                      sIdx = new IndexAssigner(), bIdx = new IndexAssigner();
        claimManual(components, rIdx, cIdx, lIdx, vIdx, iIdx, dIdx, mIdx, xIdx,
                eIdx, fIdx, gIdx, hIdx, sIdx, bIdx);
        java.util.Map<String, String> swModels = new java.util.LinkedHashMap<>();
        int vmCount = 1;
        boolean hasDiode    = false;
        boolean hasAcSource = false;

        for (CircuitComponent comp : components) {
            String line;
            if (comp.subcircuitNodes != null) {
                line = formatSubcircuit(xIdx.assign(comp.componentNumber), comp, aliases);
            } else if (comp.block instanceof IcResistorBlock) {
                line = formatIcResistor(rIdx.assign(comp.componentNumber), comp, aliases);
            } else if (comp.block instanceof IcCapacitorBlock) {
                line = formatIcCapacitor(cIdx.assign(comp.componentNumber), comp, aliases);
            } else if (comp.block instanceof IcNmos4Block) {
                line = formatIcMosfet(mIdx.assign(comp.componentNumber), comp, false, aliases);
            } else if (comp.block instanceof IcPmos4Block) {
                line = formatIcMosfet(mIdx.assign(comp.componentNumber), comp, true, aliases);
            } else if (comp.block instanceof ResistorBlock) {
                line = formatResistor(rIdx.assign(comp.componentNumber), comp, aliases);
            } else if (comp.block instanceof CapacitorBlock) {
                line = String.format("C%d %s %s %s", cIdx.assign(comp.componentNumber), nodeRef(comp.nodeA, aliases), nodeRef(comp.nodeB, aliases), num(comp.value, comp.valueExpr));
            } else if (comp.block instanceof InductorBlock) {
                line = String.format("L%d %s %s %s", lIdx.assign(comp.componentNumber), nodeRef(comp.nodeA, aliases), nodeRef(comp.nodeB, aliases), num(comp.value, comp.valueExpr));
            } else if (comp.block instanceof VoltageSourceBlock) {
                // Both halves are independent: DC sets the bias point that the
                // small-signal solver linearises around, AC is the perturbation.
                line = String.format("V%d %s %s DC %s AC %s",
                        vIdx.assign(comp.componentNumber),
                        nodeRef(comp.nodeA, aliases), nodeRef(comp.nodeB, aliases),
                        num(comp.value, comp.valueExpr), num(comp.acValue, comp.acValueExpr));
                if (comp.acValue != 0.0) hasAcSource = true;
            } else if (comp.block instanceof VoltageSourceSinBlock) {
                line = String.format("V%d %s %s DC 0 AC %s",
                        vIdx.assign(comp.componentNumber), nodeRef(comp.nodeA, aliases), nodeRef(comp.nodeB, aliases), num(comp.value, comp.valueExpr));
                hasAcSource = true;
            } else if (comp.block instanceof VoltageSourcePulseBlock) {
                // A pulse train has no defined small-signal AC component; we
                // hold the source at V1 (the .op operating point) with AC=0
                // so the bias is correct and the source doesn't perturb the
                // small-signal response.
                line = String.format("V%d %s %s DC %g AC 0",
                        vIdx.assign(comp.componentNumber),
                        nodeRef(comp.nodeA, aliases), nodeRef(comp.nodeB, aliases),
                        comp.wParam);
            } else if (comp.block instanceof CurrentSourceBlock) {
                if ("AC".equalsIgnoreCase(comp.sourceType)) {
                    line = String.format("I%d %s %s DC 0 AC %s",
                            iIdx.assign(comp.componentNumber), nodeRef(comp.nodeA, aliases), nodeRef(comp.nodeB, aliases), num(comp.value, comp.valueExpr));
                    hasAcSource = true;
                } else {
                    line = String.format("I%d %s %s DC %s AC 0",
                            iIdx.assign(comp.componentNumber), nodeRef(comp.nodeA, aliases), nodeRef(comp.nodeB, aliases), num(comp.value, comp.valueExpr));
                }
            } else if (comp.block instanceof DiodeBlock) {
                // No user model → fall back to the built-in DMOD (.MODEL D)
                // emitted below. A user-typed model name is assumed to be
                // provided by an included library (.lib / .INCLUDE).
                String dmodel = (comp.modelName == null || comp.modelName.isBlank())
                        ? "DMOD" : comp.modelName.trim();
                line = String.format("D%d %s %s %s",
                        dIdx.assign(comp.componentNumber),
                        nodeRef(comp.nodeA, aliases), nodeRef(comp.nodeB, aliases), dmodel);
                if ("DMOD".equals(dmodel)) hasDiode = true;
            } else if (comp.block instanceof BehavioralVoltageSourceBlock) {
                line = formatBehavioral(bIdx.assign(comp.componentNumber), comp, true, aliases);
            } else if (comp.block instanceof BehavioralCurrentSourceBlock) {
                line = formatBehavioral(bIdx.assign(comp.componentNumber), comp, false, aliases);
            } else if (comp.block instanceof VSwitchBlock) {
                line = formatSwitch(comp, sIdx, aliases, swModels);
            } else {
                String controlled = formatControlledSource(comp, eIdx, fIdx, gIdx, hIdx, aliases);
                if (controlled == null) continue;
                line = controlled;
            }
            sb.append(line).append("\n");
        }

        if (!hasAcSource && !components.isEmpty()) {
            for (CircuitComponent comp : components) {
                if (comp.nodeA != 0) {
                    sb.append(String.format("VACDEF %s 0 DC 0 AC 1\n", nodeRef(comp.nodeA, aliases)));
                    break;
                }
            }
        }

        for (CurrentProbeInfo cp : currentProbes) {
            sb.append(String.format("VM%d %s %s DC 0\n", vmCount++, nodeRef(cp.nodeA, aliases), nodeRef(cp.nodeB, aliases)));
        }

        if (hasDiode) sb.append(".MODEL DMOD D\n");
        appendSwitchModels(sb, swModels);

        sb.append(String.format(".ac dec %d %g %g\n", ptsPerDec, fStart, fStop));

        sb.append(".control\n");

        appendPreRunCommands(sb, userCommands);
        sb.append("  run\n");

        appendUserPlotLets(sb, userPlots);

        appendUserCommands(sb, userCommands);

        boolean hasUserPlots = userPlots != null && !userPlots.isEmpty();
        if (!probes.isEmpty() || !currentProbes.isEmpty() || hasUserPlots) {
            StringBuilder printLine = new StringBuilder("  print");
            for (ProbeInfo probe : probes) {
                if (probe.noPlot) continue;   // name-only probe: alias the net, don't print it
                printLine.append(String.format(" v(%s)", probe.netName));
            }
            int vmIdx2 = 1;
            for (int k = 0; k < currentProbes.size(); k++) {
                printLine.append(String.format(" i(VM%d)", vmIdx2++));
            }
            if (hasUserPlots) {
                for (UserPlot p : userPlots) {
                    if (p != null && p.name != null) printLine.append(' ').append(p.name);
                }
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
    // .NOISE
    // -------------------------------------------------------------------------

    /**
     * Builds a netlist with a {@code .noise v(OUT[,REF]) SRC TYPE PTS FSTART
     * FSTOP [PTS_PER_SUMMARY]} card. Components are emitted exactly like the
     * AC builder (the DC values fix the operating point ngspice linearises
     * around); no AC excitation is required — the named independent source
     * only serves as the reference for input-referred noise.
     *
     * <p>ngspice puts the results into two plots: the spectral densities land
     * in {@code noiseN} ("Noise Spectral Density Curves") and the integrated
     * totals in {@code noiseN+1} ("Integrated Noise"), which is the current
     * plot after {@code run}. The control block therefore prints the totals
     * first, then steps back with {@code setplot previous} for the spectrum
     * table (verified against ngspice-46).
     */
    public static String buildNoiseNetlist(List<CircuitComponent> components,
                                            List<ProbeInfo>        probes,
                                            List<CurrentProbeInfo> currentProbes,
                                            String outNode, String refNode, String srcName,
                                            String sweepType, int pts,
                                            double fStart, double fStop, int ptsPerSummary,
                                            String pdkName, String pdkLibPath,
                                            String pdkLibPaths, String ngBehavior,
                                            List<String> userCommands) {
        StringBuilder sb = new StringBuilder();
        sb.append("* CircuitSim NOISE Netlist\n");
        appendPdkLib(sb, pdkName, pdkLibPath, pdkLibPaths, ngBehavior);

        java.util.Map<Integer, String> aliases = aliasesFromProbes(probes);

        IndexAssigner rIdx = new IndexAssigner(), cIdx = new IndexAssigner(),
                      lIdx = new IndexAssigner(), vIdx = new IndexAssigner(),
                      iIdx = new IndexAssigner(), dIdx = new IndexAssigner(),
                      mIdx = new IndexAssigner(), xIdx = new IndexAssigner(),
                      eIdx = new IndexAssigner(), fIdx = new IndexAssigner(),
                      gIdx = new IndexAssigner(), hIdx = new IndexAssigner(),
                      sIdx = new IndexAssigner(), bIdx = new IndexAssigner();
        claimManual(components, rIdx, cIdx, lIdx, vIdx, iIdx, dIdx, mIdx, xIdx,
                eIdx, fIdx, gIdx, hIdx, sIdx, bIdx);
        java.util.Map<String, String> swModels = new java.util.LinkedHashMap<>();
        int vmCount = 1;
        boolean hasDiode = false;

        for (CircuitComponent comp : components) {
            String line;
            if (comp.subcircuitNodes != null) {
                line = formatSubcircuit(xIdx.assign(comp.componentNumber), comp, aliases);
            } else if (comp.block instanceof IcResistorBlock) {
                line = formatIcResistor(rIdx.assign(comp.componentNumber), comp, aliases);
            } else if (comp.block instanceof IcCapacitorBlock) {
                line = formatIcCapacitor(cIdx.assign(comp.componentNumber), comp, aliases);
            } else if (comp.block instanceof IcNmos4Block) {
                line = formatIcMosfet(mIdx.assign(comp.componentNumber), comp, false, aliases);
            } else if (comp.block instanceof IcPmos4Block) {
                line = formatIcMosfet(mIdx.assign(comp.componentNumber), comp, true, aliases);
            } else if (comp.block instanceof ResistorBlock) {
                line = formatResistor(rIdx.assign(comp.componentNumber), comp, aliases);
            } else if (comp.block instanceof CapacitorBlock) {
                line = String.format("C%d %s %s %s", cIdx.assign(comp.componentNumber), nodeRef(comp.nodeA, aliases), nodeRef(comp.nodeB, aliases), num(comp.value, comp.valueExpr));
            } else if (comp.block instanceof InductorBlock) {
                line = String.format("L%d %s %s %s", lIdx.assign(comp.componentNumber), nodeRef(comp.nodeA, aliases), nodeRef(comp.nodeB, aliases), num(comp.value, comp.valueExpr));
            } else if (comp.block instanceof VoltageSourceBlock) {
                line = String.format("V%d %s %s DC %s AC %s",
                        vIdx.assign(comp.componentNumber),
                        nodeRef(comp.nodeA, aliases), nodeRef(comp.nodeB, aliases),
                        num(comp.value, comp.valueExpr), num(comp.acValue, comp.acValueExpr));
            } else if (comp.block instanceof VoltageSourceSinBlock) {
                line = String.format("V%d %s %s DC 0 AC %s",
                        vIdx.assign(comp.componentNumber), nodeRef(comp.nodeA, aliases), nodeRef(comp.nodeB, aliases), num(comp.value, comp.valueExpr));
            } else if (comp.block instanceof VoltageSourcePulseBlock) {
                line = String.format("V%d %s %s DC %g AC 0",
                        vIdx.assign(comp.componentNumber),
                        nodeRef(comp.nodeA, aliases), nodeRef(comp.nodeB, aliases),
                        comp.wParam);
            } else if (comp.block instanceof CurrentSourceBlock) {
                line = String.format("I%d %s %s DC %s", iIdx.assign(comp.componentNumber), nodeRef(comp.nodeA, aliases), nodeRef(comp.nodeB, aliases), num(comp.value, comp.valueExpr));
            } else if (comp.block instanceof DiodeBlock) {
                String dmodel = (comp.modelName == null || comp.modelName.isBlank())
                        ? "DMOD" : comp.modelName.trim();
                line = String.format("D%d %s %s %s",
                        dIdx.assign(comp.componentNumber),
                        nodeRef(comp.nodeA, aliases), nodeRef(comp.nodeB, aliases), dmodel);
                if ("DMOD".equals(dmodel)) hasDiode = true;
            } else if (comp.block instanceof BehavioralVoltageSourceBlock) {
                line = formatBehavioral(bIdx.assign(comp.componentNumber), comp, true, aliases);
            } else if (comp.block instanceof BehavioralCurrentSourceBlock) {
                line = formatBehavioral(bIdx.assign(comp.componentNumber), comp, false, aliases);
            } else if (comp.block instanceof VSwitchBlock) {
                line = formatSwitch(comp, sIdx, aliases, swModels);
            } else {
                String controlled = formatControlledSource(comp, eIdx, fIdx, gIdx, hIdx, aliases);
                if (controlled == null) continue;
                line = controlled;
            }
            sb.append(line).append("\n");
        }

        for (CurrentProbeInfo cp : currentProbes) {
            sb.append(String.format("VM%d %s %s DC 0\n", vmCount++, nodeRef(cp.nodeA, aliases), nodeRef(cp.nodeB, aliases)));
        }

        if (hasDiode) sb.append(".MODEL DMOD D\n");
        appendSwitchModels(sb, swModels);

        String outSpec = (refNode == null || refNode.isBlank())
                ? "v(" + outNode + ")"
                : "v(" + outNode + "," + refNode + ")";
        sb.append(String.format(".noise %s %s %s %d %g %g",
                outSpec, srcName, sweepType, pts, fStart, fStop));
        if (ptsPerSummary > 0) sb.append(' ').append(ptsPerSummary);
        sb.append('\n');

        sb.append(".control\n");
        appendPreRunCommands(sb, userCommands);
        sb.append("  run\n");
        sb.append("  print onoise_total inoise_total\n");
        sb.append("  setplot previous\n");
        sb.append("  print onoise_spectrum inoise_spectrum\n");
        appendUserCommands(sb, userCommands);
        sb.append(".endc\n");
        sb.append(".end\n");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // .DC
    // -------------------------------------------------------------------------

    /**
     * Builds a netlist with a {@code .dc SRCNAM VSTART VSTOP VINCR
     * [SRC2 VSTART2 VSTOP2 VINCR2]} directive. The swept source's stored
     * DC value is overridden by ngspice with the per-step value.
     */
    public static String buildDcNetlist(List<CircuitComponent> components,
                                         List<ProbeInfo>        probes,
                                         List<CurrentProbeInfo> currentProbes,
                                         String src1, double start1, double stop1, double step1,
                                         boolean enable2D,
                                         String src2, double start2, double stop2, double step2,
                                         String pdkName, String pdkLibPath,
                                         String pdkLibPaths, String ngBehavior,
                                         List<String> userCommands,
                                         List<UserPlot> userPlots) {
        StringBuilder sb = new StringBuilder();
        sb.append("* CircuitSim DC Netlist\n");
        appendPdkLib(sb, pdkName, pdkLibPath, pdkLibPaths, ngBehavior);

        java.util.Map<Integer, String> aliases = aliasesFromProbes(probes);

        IndexAssigner rIdx = new IndexAssigner(), cIdx = new IndexAssigner(),
                      lIdx = new IndexAssigner(), vIdx = new IndexAssigner(),
                      iIdx = new IndexAssigner(), dIdx = new IndexAssigner(),
                      mIdx = new IndexAssigner(), xIdx = new IndexAssigner(),
                      eIdx = new IndexAssigner(), fIdx = new IndexAssigner(),
                      gIdx = new IndexAssigner(), hIdx = new IndexAssigner(),
                      sIdx = new IndexAssigner(), bIdx = new IndexAssigner();
        claimManual(components, rIdx, cIdx, lIdx, vIdx, iIdx, dIdx, mIdx, xIdx,
                eIdx, fIdx, gIdx, hIdx, sIdx, bIdx);
        java.util.Map<String, String> swModels = new java.util.LinkedHashMap<>();
        int vmCount = 1;
        boolean hasDiode = false;

        for (CircuitComponent comp : components) {
            String line;
            if (comp.subcircuitNodes != null) {
                line = formatSubcircuit(xIdx.assign(comp.componentNumber), comp, aliases);
            } else if (comp.block instanceof IcResistorBlock) {
                line = formatIcResistor(rIdx.assign(comp.componentNumber), comp, aliases);
            } else if (comp.block instanceof IcCapacitorBlock) {
                line = formatIcCapacitor(cIdx.assign(comp.componentNumber), comp, aliases);
            } else if (comp.block instanceof IcNmos4Block) {
                line = formatIcMosfet(mIdx.assign(comp.componentNumber), comp, false, aliases);
            } else if (comp.block instanceof IcPmos4Block) {
                line = formatIcMosfet(mIdx.assign(comp.componentNumber), comp, true, aliases);
            } else if (comp.block instanceof ResistorBlock) {
                line = formatResistor(rIdx.assign(comp.componentNumber), comp, aliases);
            } else if (comp.block instanceof CapacitorBlock) {
                line = String.format("C%d %s %s %s", cIdx.assign(comp.componentNumber), nodeRef(comp.nodeA, aliases), nodeRef(comp.nodeB, aliases), num(comp.value, comp.valueExpr));
            } else if (comp.block instanceof InductorBlock) {
                line = String.format("L%d %s %s %s", lIdx.assign(comp.componentNumber), nodeRef(comp.nodeA, aliases), nodeRef(comp.nodeB, aliases), num(comp.value, comp.valueExpr));
            } else if (comp.block instanceof VoltageSourceBlock) {
                line = String.format("V%d %s %s DC %s", vIdx.assign(comp.componentNumber), nodeRef(comp.nodeA, aliases), nodeRef(comp.nodeB, aliases), num(comp.value, comp.valueExpr));
            } else if (comp.block instanceof VoltageSourceSinBlock) {
                // SIN sources reduce to their DC offset for .dc (they're
                // small-signal AC during .ac and time-varying during .tran).
                line = String.format("V%d %s %s DC 0", vIdx.assign(comp.componentNumber), nodeRef(comp.nodeA, aliases), nodeRef(comp.nodeB, aliases));
            } else if (comp.block instanceof VoltageSourcePulseBlock) {
                line = String.format("V%d %s %s DC %g",
                        vIdx.assign(comp.componentNumber),
                        nodeRef(comp.nodeA, aliases), nodeRef(comp.nodeB, aliases),
                        comp.wParam);
            } else if (comp.block instanceof CurrentSourceBlock) {
                line = String.format("I%d %s %s DC %s", iIdx.assign(comp.componentNumber), nodeRef(comp.nodeA, aliases), nodeRef(comp.nodeB, aliases), num(comp.value, comp.valueExpr));
            } else if (comp.block instanceof DiodeBlock) {
                // No user model → fall back to the built-in DMOD (.MODEL D)
                // emitted below. A user-typed model name is assumed to be
                // provided by an included library (.lib / .INCLUDE).
                String dmodel = (comp.modelName == null || comp.modelName.isBlank())
                        ? "DMOD" : comp.modelName.trim();
                line = String.format("D%d %s %s %s",
                        dIdx.assign(comp.componentNumber),
                        nodeRef(comp.nodeA, aliases), nodeRef(comp.nodeB, aliases), dmodel);
                if ("DMOD".equals(dmodel)) hasDiode = true;
            } else if (comp.block instanceof BehavioralVoltageSourceBlock) {
                line = formatBehavioral(bIdx.assign(comp.componentNumber), comp, true, aliases);
            } else if (comp.block instanceof BehavioralCurrentSourceBlock) {
                line = formatBehavioral(bIdx.assign(comp.componentNumber), comp, false, aliases);
            } else if (comp.block instanceof VSwitchBlock) {
                line = formatSwitch(comp, sIdx, aliases, swModels);
            } else {
                String controlled = formatControlledSource(comp, eIdx, fIdx, gIdx, hIdx, aliases);
                if (controlled == null) continue;
                line = controlled;
            }
            sb.append(line).append("\n");
        }

        for (CurrentProbeInfo cp : currentProbes) {
            sb.append(String.format("VM%d %s %s DC 0\n", vmCount++, nodeRef(cp.nodeA, aliases), nodeRef(cp.nodeB, aliases)));
        }

        if (hasDiode) sb.append(".MODEL DMOD D\n");
        appendSwitchModels(sb, swModels);

        if (enable2D && src2 != null && !src2.isEmpty()) {
            sb.append(String.format(".dc %s %g %g %g %s %g %g %g\n",
                    src1, start1, stop1, step1, src2, start2, stop2, step2));
        } else {
            sb.append(String.format(".dc %s %g %g %g\n", src1, start1, stop1, step1));
        }

        sb.append(".control\n");
        appendPreRunCommands(sb, userCommands);
        sb.append("  run\n");

        appendUserPlotLets(sb, userPlots);
        appendUserCommands(sb, userCommands);

        boolean hasUserPlots = userPlots != null && !userPlots.isEmpty();
        if (!probes.isEmpty() || !currentProbes.isEmpty() || hasUserPlots) {
            StringBuilder printLine = new StringBuilder("  print");
            for (ProbeInfo probe : probes) {
                if (probe.noPlot) continue;   // name-only probe: alias the net, don't print it
                printLine.append(String.format(" v(%s)", probe.netName));
            }
            int vmIdx2 = 1;
            for (int k = 0; k < currentProbes.size(); k++) {
                printLine.append(String.format(" i(VM%d)", vmIdx2++));
            }
            if (hasUserPlots) {
                for (UserPlot p : userPlots) {
                    if (p != null && p.name != null) printLine.append(' ').append(p.name);
                }
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
                                           double tstep, double tstop,
                                           String pdkName, String pdkLibPath) {
        return buildTranNetlist(components, probes, currentProbes, tstep, tstop,
                pdkName, pdkLibPath, List.of(), List.of());
    }

    public static String buildTranNetlist(List<CircuitComponent> components,
                                           List<ProbeInfo>        probes,
                                           List<CurrentProbeInfo> currentProbes,
                                           double tstep, double tstop,
                                           String pdkName, String pdkLibPath,
                                           List<String> userCommands) {
        return buildTranNetlist(components, probes, currentProbes, tstep, tstop,
                pdkName, pdkLibPath, userCommands, List.of());
    }

    public static String buildTranNetlist(List<CircuitComponent> components,
                                           List<ProbeInfo>        probes,
                                           List<CurrentProbeInfo> currentProbes,
                                           double tstep, double tstop,
                                           String pdkName, String pdkLibPath,
                                           List<String> userCommands,
                                           List<UserPlot> userPlots) {
        return buildTranNetlist(components, probes, currentProbes, tstep, tstop,
                pdkName, pdkLibPath, "", "hsa", userCommands, userPlots);
    }

    public static String buildTranNetlist(List<CircuitComponent> components,
                                           List<ProbeInfo>        probes,
                                           List<CurrentProbeInfo> currentProbes,
                                           double tstep, double tstop,
                                           String pdkName, String pdkLibPath,
                                           String pdkLibPaths, String ngBehavior,
                                           List<String> userCommands,
                                           List<UserPlot> userPlots) {
        StringBuilder sb = new StringBuilder();
        sb.append("* CircuitSim TRAN Netlist\n");
        appendPdkLib(sb, pdkName, pdkLibPath, pdkLibPaths, ngBehavior);

        java.util.Map<Integer, String> aliases = aliasesFromProbes(probes);

        IndexAssigner rIdx = new IndexAssigner(), cIdx = new IndexAssigner(),
                      lIdx = new IndexAssigner(), vIdx = new IndexAssigner(),
                      iIdx = new IndexAssigner(), dIdx = new IndexAssigner(),
                      mIdx = new IndexAssigner(), xIdx = new IndexAssigner(),
                      eIdx = new IndexAssigner(), fIdx = new IndexAssigner(),
                      gIdx = new IndexAssigner(), hIdx = new IndexAssigner(),
                      sIdx = new IndexAssigner(), bIdx = new IndexAssigner();
        claimManual(components, rIdx, cIdx, lIdx, vIdx, iIdx, dIdx, mIdx, xIdx,
                eIdx, fIdx, gIdx, hIdx, sIdx, bIdx);
        java.util.Map<String, String> swModels = new java.util.LinkedHashMap<>();
        int vmCount = 1;
        boolean hasDiode = false;

        for (CircuitComponent comp : components) {
            String line;
            if (comp.subcircuitNodes != null) {
                line = formatSubcircuit(xIdx.assign(comp.componentNumber), comp, aliases);
            } else if (comp.block instanceof IcResistorBlock) {
                line = formatIcResistor(rIdx.assign(comp.componentNumber), comp, aliases);
            } else if (comp.block instanceof IcCapacitorBlock) {
                line = formatIcCapacitor(cIdx.assign(comp.componentNumber), comp, aliases);
            } else if (comp.block instanceof IcNmos4Block) {
                line = formatIcMosfet(mIdx.assign(comp.componentNumber), comp, false, aliases);
            } else if (comp.block instanceof IcPmos4Block) {
                line = formatIcMosfet(mIdx.assign(comp.componentNumber), comp, true, aliases);
            } else if (comp.block instanceof ResistorBlock) {
                line = formatResistor(rIdx.assign(comp.componentNumber), comp, aliases);
            } else if (comp.block instanceof CapacitorBlock) {
                line = String.format("C%d %s %s %s", cIdx.assign(comp.componentNumber), nodeRef(comp.nodeA, aliases), nodeRef(comp.nodeB, aliases), num(comp.value, comp.valueExpr));
            } else if (comp.block instanceof InductorBlock) {
                line = String.format("L%d %s %s %s", lIdx.assign(comp.componentNumber), nodeRef(comp.nodeA, aliases), nodeRef(comp.nodeB, aliases), num(comp.value, comp.valueExpr));
            } else if (comp.block instanceof VoltageSourceBlock) {
                line = String.format("V%d %s %s DC %s", vIdx.assign(comp.componentNumber), nodeRef(comp.nodeA, aliases), nodeRef(comp.nodeB, aliases), num(comp.value, comp.valueExpr));
            } else if (comp.block instanceof VoltageSourceSinBlock) {
                double freq = (comp.frequency > 0) ? comp.frequency : 1.0;
                line = String.format("V%d %s %s SIN(0 %s %g)",
                        vIdx.assign(comp.componentNumber), nodeRef(comp.nodeA, aliases), nodeRef(comp.nodeB, aliases), num(comp.value, comp.valueExpr), freq);
            } else if (comp.block instanceof VoltageSourcePulseBlock) {
                // PULSE(V1 V2 TD TR TF PW PER) — TD fixed at 0, NP omitted
                // (unlimited repeats). Slot map (set in CircuitExtractor):
                //   comp.value  = V2 (pulsed / high voltage)
                //   comp.frequency = PER (period)
                //   comp.wParam = V1 (initial / low voltage)
                //   comp.lParam = TR (rise time)
                //   comp.multParam = TF (fall time)
                //   comp.nfParam = PW (pulse width / time-high)
                double v1  = comp.wParam;
                double tr  = comp.lParam   > 0 ? comp.lParam   : 1e-9;
                double tf  = comp.multParam > 0 ? comp.multParam : 1e-9;
                double pw  = comp.nfParam   > 0 ? comp.nfParam   : 1e-6;
                double per = comp.frequency > 0 ? comp.frequency : 2e-6;
                line = String.format("V%d %s %s PULSE(%g %s 0 %g %g %g %g)",
                        vIdx.assign(comp.componentNumber),
                        nodeRef(comp.nodeA, aliases), nodeRef(comp.nodeB, aliases),
                        v1, num(comp.value, comp.valueExpr), tr, tf, pw, per);
            } else if (comp.block instanceof CurrentSourceBlock) {
                line = String.format("I%d %s %s DC %s", iIdx.assign(comp.componentNumber), nodeRef(comp.nodeA, aliases), nodeRef(comp.nodeB, aliases), num(comp.value, comp.valueExpr));
            } else if (comp.block instanceof DiodeBlock) {
                // No user model → fall back to the built-in DMOD (.MODEL D)
                // emitted below. A user-typed model name is assumed to be
                // provided by an included library (.lib / .INCLUDE).
                String dmodel = (comp.modelName == null || comp.modelName.isBlank())
                        ? "DMOD" : comp.modelName.trim();
                line = String.format("D%d %s %s %s",
                        dIdx.assign(comp.componentNumber),
                        nodeRef(comp.nodeA, aliases), nodeRef(comp.nodeB, aliases), dmodel);
                if ("DMOD".equals(dmodel)) hasDiode = true;
            } else if (comp.block instanceof BehavioralVoltageSourceBlock) {
                line = formatBehavioral(bIdx.assign(comp.componentNumber), comp, true, aliases);
            } else if (comp.block instanceof BehavioralCurrentSourceBlock) {
                line = formatBehavioral(bIdx.assign(comp.componentNumber), comp, false, aliases);
            } else if (comp.block instanceof VSwitchBlock) {
                line = formatSwitch(comp, sIdx, aliases, swModels);
            } else {
                String controlled = formatControlledSource(comp, eIdx, fIdx, gIdx, hIdx, aliases);
                if (controlled == null) continue;
                line = controlled;
            }
            sb.append(line).append("\n");
        }

        for (CurrentProbeInfo cp : currentProbes) {
            sb.append(String.format("VM%d %s %s DC 0\n", vmCount++, nodeRef(cp.nodeA, aliases), nodeRef(cp.nodeB, aliases)));
        }

        if (hasDiode) sb.append(".MODEL DMOD D\n");
        appendSwitchModels(sb, swModels);

        sb.append(String.format(".tran %g %g\n", tstep, tstop));

        sb.append(".control\n");

        appendPreRunCommands(sb, userCommands);
        sb.append("  run\n");

        appendUserPlotLets(sb, userPlots);

        appendUserCommands(sb, userCommands);

        boolean hasUserPlots = userPlots != null && !userPlots.isEmpty();
        if (!probes.isEmpty() || !currentProbes.isEmpty() || hasUserPlots) {
            // One shared signal list: every probed voltage, every current
            // probe, every user plot — used for the tran print AND the FFT.
            java.util.List<String> vecs = new java.util.ArrayList<>();
            for (ProbeInfo probe : probes) {
                if (probe.noPlot) continue;   // name-only probe: alias the net, don't print it
                vecs.add(String.format("v(%s)", probe.netName));
            }
            int vmIdx = 1;
            for (int k = 0; k < currentProbes.size(); k++) {
                vecs.add(String.format("i(VM%d)", vmIdx++));
            }
            if (hasUserPlots) {
                for (UserPlot p : userPlots) {
                    if (p != null && p.name != null) vecs.add(p.name);
                }
            }
            sb.append("  print");
            for (String v : vecs) sb.append(' ').append(v);
            sb.append('\n');

            // ngspice-side FFT of every probed signal. `linearize` resamples
            // the variable-timestep transient onto an equidistant grid (a
            // DFT needs uniform sampling) and `fft` transforms the copies
            // into a spectrum plot. Each vector is printed in its own table
            // with an explicit `frequency` column: the spec plot's print
            // omits the scale by default, and page-wrapped multi-vector
            // prints drop it from continuation chunks — one print per vector
            // keeps every chunk parseable into Result.fftData. The window is
            // ngspice's default hanning; a `set specwindow=blackman` (etc.)
            // line in a Commands block overrides it, since user commands are
            // emitted above.
            sb.append("  linearize\n");
            sb.append("  fft");
            for (String v : vecs) sb.append(' ').append(v);
            sb.append('\n');
            for (String v : vecs) {
                sb.append("  print frequency ").append(v).append('\n');
            }
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
        appendPdkLib(sb, pdkName, pdkLibPath, "", "hsa");
    }

    /**
     * Emits library include directives.
     *
     * <p>In PSpice compatibility mode ({@code ngBehavior="psa"}) each non-empty
     * line of {@code pdkLibPaths} becomes a separate {@code .INCLUDE "..."}
     * directive — psa collapses {@code .lib} to {@code .include} anyway and
     * has no hierarchical section support, so we emit .INCLUDE directly.
     *
     * <p>In any other mode (the sky130A HSPICE path) each non-empty line of
     * {@code pdkLibPath} becomes its own {@code .lib <path>} directive; this
     * preserves the section lookup required by HSPICE-style PDKs while
     * allowing several libraries to be included.
     */
    private static void appendPdkLib(StringBuilder sb, String pdkName, String pdkLibPath,
                                     String pdkLibPaths, String ngBehavior) {
        if ("psa".equals(ngBehavior)) {
            if (pdkLibPaths == null || pdkLibPaths.isBlank()) return;
            for (String raw : pdkLibPaths.split("\\r?\\n")) {
                String line = raw.strip();
                if (line.isEmpty()) continue;
                // Quote the path so spaces work, but don't double-quote if the
                // user already typed quotes.
                if (line.startsWith("\"") && line.endsWith("\"")) {
                    sb.append(".INCLUDE ").append(line).append("\n");
                } else {
                    sb.append(".INCLUDE \"").append(line).append("\"\n");
                }
            }
            return;
        }
        if (pdkLibPath != null && !pdkLibPath.isBlank()) {
            // hsa accepts multiple libraries — one .lib directive per non-empty
            // line, mirroring the psa .INCLUDE handling above. Emitted
            // regardless of any PDK selection (the model prefix is now chosen
            // per-component, so the sim block no longer gates lib includes).
            for (String raw : pdkLibPath.split("\\r?\\n")) {
                String line = raw.strip();
                if (line.isEmpty()) continue;
                sb.append(".lib ").append(line).append("\n");
            }
        }
    }

    // -------------------------------------------------------------------------
    // Net aliasing — voltage probe labels become ngspice node names
    // -------------------------------------------------------------------------

    /**
     * Sanitises a free-form probe label into something ngspice will accept as
     * a node name: lowercased, only {@code [a-z0-9_]}; a leading underscore is
     * inserted if the result starts with a digit. Returns the empty string
     * when no usable characters remain.
     */
    public static String sanitizeNodeName(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_') {
                out.append(Character.toLowerCase(c));
            } else {
                out.append('_');
            }
        }
        // Strip leading/trailing underscores but keep interior ones
        while (out.length() > 0 && out.charAt(0) == '_') out.deleteCharAt(0);
        while (out.length() > 0 && out.charAt(out.length() - 1) == '_') out.deleteCharAt(out.length() - 1);
        if (out.length() == 0) return "";
        if (Character.isDigit(out.charAt(0))) out.insert(0, '_');
        return out.toString();
    }

    /**
     * Builds a node→alias map from the netName of each {@link ProbeInfo}. Only
     * probes whose {@code netName} differs from the stringified node id (i.e.
     * the user actually set a label that became a non-trivial alias) are kept.
     * Node 0 (ground) is never aliased.
     */
    public static java.util.Map<Integer, String> aliasesFromProbes(List<ProbeInfo> probes) {
        java.util.Map<Integer, String> aliases = new java.util.HashMap<>();
        if (probes == null) return aliases;
        for (ProbeInfo p : probes) {
            if (p.node == 0) continue;
            if (p.netName == null) continue;
            if (p.netName.equals(Integer.toString(p.node))) continue;
            aliases.putIfAbsent(p.node, p.netName);
        }
        return aliases;
    }

    /** Returns the alias for {@code n} if one exists, otherwise the stringified integer. */
    private static String nodeRef(int n, java.util.Map<Integer, String> aliases) {
        if (aliases != null) {
            String s = aliases.get(n);
            if (s != null) return s;
        }
        return Integer.toString(n);
    }

    /**
     * Post-run user commands. Skips lines that {@link #isPreRunCommand}
     * matches — those are emitted earlier (before {@code run}) by
     * {@link #appendPreRunCommands} so they actually take effect.
     */
    private static void appendUserCommands(StringBuilder sb, List<String> userCommands) {
        if (userCommands == null) return;
        for (String cmd : userCommands) {
            if (cmd == null) continue;
            String trimmed = cmd.strip();
            if (trimmed.isEmpty()) continue;
            if (isPreRunCommand(trimmed)) continue;
            sb.append("  ").append(trimmed).append("\n");
        }
    }

    /**
     * Emits commands that must run BEFORE {@code run} to have any effect.
     * Currently just {@code save <vector>}: ngspice's {@code save} command
     * registers which vectors the analysis should track, so it has to be
     * issued before the analysis executes. If a user types it inside the
     * Commands block we'd otherwise emit it after {@code run} (where it's
     * a no-op).
     */
    private static void appendPreRunCommands(StringBuilder sb, List<String> userCommands) {
        if (userCommands == null) return;
        boolean hasAnySave = false;
        for (String cmd : userCommands) {
            if (cmd != null && isPreRunCommand(cmd.strip())) { hasAnySave = true; break; }
        }
        // ngspice flips to selective-save mode the moment ANY `save <vec>` is
        // issued, which drops the default node voltages / branch currents
        // and leaves the DC/AC/TRAN result table empty. Prepending `save all`
        // keeps the defaults around so the user's extra saves are purely
        // additive.
        if (hasAnySave) sb.append("  save all\n");
        for (String cmd : userCommands) {
            if (cmd == null) continue;
            String trimmed = cmd.strip();
            if (trimmed.isEmpty()) continue;
            if (isPreRunCommand(trimmed)) sb.append("  ").append(trimmed).append("\n");
        }
    }

    private static boolean isPreRunCommand(String trimmed) {
        String lower = trimmed.toLowerCase();
        return lower.startsWith("save ") || lower.equals("save");
    }

    /**
     * Emits a {@code let NAME = EXPR} line for each user plot. Called after
     * {@code run} but before raw user commands so plot-defined vectors are
     * available for any subsequent {@code print}/{@code show}/etc.
     */
    private static void appendUserPlotLets(StringBuilder sb, List<UserPlot> plots) {
        if (plots == null) return;
        for (UserPlot p : plots) {
            if (p == null || p.name == null || p.expr == null) continue;
            sb.append("  let ").append(p.name).append(" = ").append(p.expr).append("\n");
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
    private static String formatIcResistor(int idx, CircuitComponent comp,
                                            java.util.Map<Integer, String> aliases) {
        String prefix = pdkModelPrefix(comp.pdkName);
        String name   = comp.modelName.isBlank() ? "res_high_po" : comp.modelName;
        String model  = prefix + name;
        double w    = comp.wParam    > 0 ? comp.wParam    : 1.0;
        double l    = comp.lParam    > 0 ? comp.lParam    : 1.0;
        double mult = comp.multParam > 0 ? comp.multParam : 1.0;
        // 3-pin subcircuit: p+ p- bulk. The multiplier is ngspice's built-in
        // subcircuit `m` (parallel copies → R/m), which matches the displayed
        // R = (...)/mult; the subckt's own `mult` param only scales its
        // mismatch term and leaves the nominal resistance unchanged.
        int bulk = comp.nodeC >= 0 ? comp.nodeC : 0;
        return String.format("XR%d %s %s %s %s W=%g L=%g m=%g",
                idx, nodeRef(comp.nodeA, aliases), nodeRef(comp.nodeB, aliases),
                nodeRef(bulk, aliases), model, w, l, mult);
    }

    /**
     * Formats a 4-pin MOSFET instance line for sky130A nfet/pfet models.
     * Pin order in netlist: drain gate source bulk.
     * For NMOS: nodeA=drain(front), nodeB=source(back), nodeC=bulk(right), nodeD=gate(left).
     * For PMOS: nodeA=source(front), nodeB=drain(back), nodeC=bulk(right), nodeD=gate(left).
     * Derived area/perimeter params computed from W, L, NF per the sky130 HDK formulas.
     */
    /**
     * Per-PDK MOSFET geometry constants. The derived area/perimeter params
     * (AD/AS/PD/PS/NRD/NRS) are computed from these; a {@code null} lookup
     * means the PDK has no geometry model, so only W/L/mult are emitted.
     *
     * <p>This is the per-PDK "dictionary" — add a {@code case} here to give a
     * new PDK its own constants. {@code none}/{@code placeholder} resolve to
     * {@code null} and emit a bare instance.
     */
    private record MosfetGeometry(double hdif) {}

    private static MosfetGeometry mosfetGeometry(String pdkName) {
        return switch (pdkName == null ? "none" : pdkName) {
            case "sky130A" -> new MosfetGeometry(0.29); // sky130 HDK standard
            default        -> null;                     // none / placeholder
        };
    }

    private static String formatIcMosfet(int idx, CircuitComponent comp, boolean isPmos,
                                          java.util.Map<Integer, String> aliases) {
        String prefix = pdkModelPrefix(comp.pdkName);
        String defaultModel = isPmos ? "pfet_01v8" : "nfet_01v8";
        String name  = comp.modelName.isBlank() ? defaultModel : comp.modelName;
        String model = prefix + name;

        double w    = comp.wParam    > 0 ? comp.wParam    : 1.0;
        double l    = comp.lParam    > 0 ? comp.lParam    : 1.0;
        double mult = comp.multParam > 0 ? comp.multParam : 1.0;

        int drain = isPmos ? comp.nodeB : comp.nodeA;
        int src   = isPmos ? comp.nodeA : comp.nodeB;
        int bulk  = comp.nodeC >= 0 ? comp.nodeC : 0;
        int gate  = comp.nodeD >= 0 ? comp.nodeD : 0;

        MosfetGeometry geo = mosfetGeometry(comp.pdkName);
        if (geo == null) {
            // No PDK geometry model (none/placeholder): emit a bare instance
            // with only W/L and the device multiplier — no NF and no derived
            // area/perimeter params. The multiplier is ngspice's built-in
            // subcircuit `m` (parallel copies), which actually scales the
            // device — unlike the subckt's own `mult` param.
            return String.format(
                "XM%d %s %s %s %s %s%n+ L=%g W=%g m=%g",
                idx,
                nodeRef(drain, aliases), nodeRef(gate, aliases),
                nodeRef(src, aliases),   nodeRef(bulk, aliases),
                model,
                l, w, mult
            );
        }

        int    nf   = (int) Math.max(1, Math.round(comp.nfParam));
        // area/perimeter formulas, parameterised by the PDK's hdif
        double w_f  = w / nf;
        int    n_d  = (nf + 1) / 2;
        int    n_s  = (nf + 2) / 2;
        double ad   = w_f * geo.hdif() * n_d;
        double as_  = w_f * geo.hdif() * n_s;
        double pd   = 2.0 * n_d * (w_f + geo.hdif());
        double ps   = 2.0 * n_s * (w_f + geo.hdif());
        double nrd  = geo.hdif() / w;
        double nrs  = geo.hdif() / w;

        // The multiplier is emitted as ngspice's built-in subcircuit `m`
        // (parallel copies), NOT the sky130 subckt's `mult` param: `mult` only
        // scales the (disabled-by-default) Monte-Carlo mismatch term and leaves
        // the operating point unchanged, whereas `m` actually multiplies the
        // device current/area.
        return String.format(
            "XM%d %s %s %s %s %s%n+ L=%g W=%g NF=%d%n+ AD=%g AS=%g%n+ PD=%g PS=%g%n+ NRD=%g NRS=%g%n+ SA=0 SB=0 SD=0 m=%g",
            idx,
            nodeRef(drain, aliases), nodeRef(gate, aliases),
            nodeRef(src, aliases),   nodeRef(bulk, aliases),
            model,
            l, w, nf,
            ad, as_,
            pd, ps,
            nrd, nrs,
            mult
        );
    }

    /** Formats a 2-pin IC capacitor subcircuit line. */
    private static String formatIcCapacitor(int idx, CircuitComponent comp,
                                             java.util.Map<Integer, String> aliases) {
        String prefix = pdkModelPrefix(comp.pdkName);
        String name   = comp.modelName.isBlank() ? "cap_mim_m3_1" : comp.modelName;
        String model  = prefix + name;
        double w  = comp.wParam    > 0 ? comp.wParam    : 1.0;
        double l  = comp.lParam    > 0 ? comp.lParam    : 1.0;
        double mf = comp.multParam > 0 ? comp.multParam : 1.0;
        // `m` (ngspice subcircuit multiplier) scales the nominal capacitance;
        // the subckt's own `mf` only feeds its mismatch term, so we drop it.
        return String.format("XC%d %s %s %s W=%g L=%g m=%g",
                idx, nodeRef(comp.nodeA, aliases), nodeRef(comp.nodeB, aliases),
                model, w, l, mf);
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
        // Per-component process PDK (e.g. "sky130A"). Drives the model-name
        // prefix and the MOSFET geometry-param formula set. "none"/"placeholder"
        // emit a bare W/L/mult instance with no prefix and no derived params.
        // Non-final because it's attached post-construction via withPdkName()
        // to avoid threading it through the telescoping constructor chain; the
        // copy helpers (withValue/substituteVariable) carry it forward.
        public String         pdkName = "none";
        // user-chosen index in the netlist (e.g. R5). 0 = auto-assigned.
        public final int      componentNumber;
        /**
         * Variable-arity pins for subcircuit-instance components (X-prefix in
         * SPICE). When non-null the netlist builder emits
         * {@code X<n> pin0 pin1 ... <modelName>} instead of the device-family
         * line. Currently used by the amplifier block (5 or 7 pins).
         */
        public final int[]    subcircuitNodes;
        /**
         * Variable name from the Parametric block system. When non-empty the
         * component's effective value is looked up from a Parametric block
         * defining this name. Empty means use the numeric {@link #value}.
         */
        public final String   valueExpr;
        // Same idea, one per IC numeric slot. Each is empty when the
        // corresponding numeric slot should be used directly.
        public final String   wExpr;
        public final String   lExpr;
        public final String   multExpr;
        public final String   nfExpr;
        // Voltage source AC magnitude (paired with the DC `value` slot). Other
        // component types leave these at 0 / "".
        public final double   acValue;
        public final String   acValueExpr;

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
            this(block, pos, nodeA, nodeB, nodeC, nodeD, value, sourceType, frequency,
                    modelName, wParam, lParam, multParam, nfParam, componentNumber, null, "");
        }

        public CircuitComponent(Block block, BlockPos pos, int nodeA, int nodeB, int nodeC, int nodeD,
                                double value, String sourceType, double frequency,
                                String modelName, double wParam, double lParam, double multParam,
                                double nfParam, int componentNumber, int[] subcircuitNodes) {
            this(block, pos, nodeA, nodeB, nodeC, nodeD, value, sourceType, frequency,
                    modelName, wParam, lParam, multParam, nfParam, componentNumber, subcircuitNodes, "");
        }

        public CircuitComponent(Block block, BlockPos pos, int nodeA, int nodeB, int nodeC, int nodeD,
                                double value, String sourceType, double frequency,
                                String modelName, double wParam, double lParam, double multParam,
                                double nfParam, int componentNumber, int[] subcircuitNodes,
                                String valueExpr) {
            this(block, pos, nodeA, nodeB, nodeC, nodeD, value, sourceType, frequency,
                    modelName, wParam, lParam, multParam, nfParam, componentNumber,
                    subcircuitNodes, valueExpr, "", "", "", "");
        }

        public CircuitComponent(Block block, BlockPos pos, int nodeA, int nodeB, int nodeC, int nodeD,
                                double value, String sourceType, double frequency,
                                String modelName, double wParam, double lParam, double multParam,
                                double nfParam, int componentNumber, int[] subcircuitNodes,
                                String valueExpr,
                                String wExpr, String lExpr, String multExpr, String nfExpr) {
            this(block, pos, nodeA, nodeB, nodeC, nodeD, value, sourceType, frequency,
                    modelName, wParam, lParam, multParam, nfParam, componentNumber,
                    subcircuitNodes, valueExpr, wExpr, lExpr, multExpr, nfExpr, 0.0, "");
        }

        public CircuitComponent(Block block, BlockPos pos, int nodeA, int nodeB, int nodeC, int nodeD,
                                double value, String sourceType, double frequency,
                                String modelName, double wParam, double lParam, double multParam,
                                double nfParam, int componentNumber, int[] subcircuitNodes,
                                String valueExpr,
                                String wExpr, String lExpr, String multExpr, String nfExpr,
                                double acValue, String acValueExpr) {
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
            this.subcircuitNodes = subcircuitNodes;
            this.valueExpr       = valueExpr == null ? "" : valueExpr;
            this.wExpr           = wExpr     == null ? "" : wExpr;
            this.lExpr           = lExpr     == null ? "" : lExpr;
            this.multExpr        = multExpr  == null ? "" : multExpr;
            this.nfExpr          = nfExpr    == null ? "" : nfExpr;
            this.acValue         = acValue;
            this.acValueExpr     = acValueExpr == null ? "" : acValueExpr;
        }

        /** Convenience constructor for subcircuit-instance components (X-prefix). */
        public static CircuitComponent subcircuit(Block block, BlockPos pos,
                                                   int[] pinNodes, String modelName, int componentNumber) {
            return new CircuitComponent(block, pos, 0, 0, -1, -1, 0, "DC", 0,
                    modelName == null ? "" : modelName,
                    1.0, 1.0, 1.0, 1.0, componentNumber, pinNodes);
        }

        /** Attaches the process PDK and returns {@code this} (fluent). */
        public CircuitComponent withPdkName(String pdk) {
            this.pdkName = (pdk == null || pdk.isBlank()) ? "none" : pdk;
            return this;
        }

        /** Copy of {@code base} with the numeric {@link #value} replaced. Used by parametric sweep substitution. */
        public CircuitComponent withValue(double newValue) {
            return new CircuitComponent(block, pos, nodeA, nodeB, nodeC, nodeD,
                    newValue, sourceType, frequency,
                    modelName, wParam, lParam, multParam, nfParam,
                    componentNumber, subcircuitNodes, "",
                    wExpr, lExpr, multExpr, nfExpr,
                    acValue, acValueExpr).withPdkName(pdkName);
        }

        /** True if any expression slot references {@code varName}. */
        public boolean referencesVariable(String varName) {
            return varName.equals(valueExpr) || varName.equals(wExpr)
                    || varName.equals(lExpr) || varName.equals(multExpr)
                    || varName.equals(nfExpr) || varName.equals(acValueExpr);
        }

        /**
         * Returns a copy with every slot referencing {@code varName} replaced
         * by {@code newVal}. Slots not referencing the variable keep their
         * existing numeric value and expression (so multiple sequential
         * substitutions can compose).
         */
        public CircuitComponent substituteVariable(String varName, double newVal) {
            double v = value, w = wParam, l = lParam, m = multParam, n = nfParam, ac = acValue;
            String ve = valueExpr, we = wExpr, le = lExpr, me = multExpr, ne = nfExpr, ace = acValueExpr;
            if (varName.equals(valueExpr))   { v  = newVal; ve  = ""; }
            if (varName.equals(wExpr))       { w  = newVal; we  = ""; }
            if (varName.equals(lExpr))       { l  = newVal; le  = ""; }
            if (varName.equals(multExpr))    { m  = newVal; me  = ""; }
            if (varName.equals(nfExpr))      { n  = newVal; ne  = ""; }
            if (varName.equals(acValueExpr)) { ac = newVal; ace = ""; }
            return new CircuitComponent(block, pos, nodeA, nodeB, nodeC, nodeD,
                    v, sourceType, frequency,
                    modelName, w, l, m, n,
                    componentNumber, subcircuitNodes, ve,
                    we, le, me, ne,
                    ac, ace).withPdkName(pdkName);
        }

        /** Which slot of this component references {@code varName}, or null. */
        public String slotFor(String varName) {
            if (varName.equals(valueExpr))   return "value";
            if (varName.equals(wExpr))       return "W";
            if (varName.equals(lExpr))       return "L";
            if (varName.equals(multExpr))    return "mult";
            if (varName.equals(nfExpr))      return "nf";
            if (varName.equals(acValueExpr)) return "acValue";
            return null;
        }
    }

    public static class ProbeInfo {
        /** Numeric node id assigned during graph extraction. Always the canonical integer. */
        public final int    node;
        /** Human-readable label shown in chat/book/UI. */
        public final String label;
        /**
         * Name used in the netlist and ngspice output for this node. Either the
         * sanitized probe label (e.g. {@code "vout"}) when the user has set one
         * and aliasing is unique, or the stringified integer node id.
         */
        public final String netName;
        /**
         * "Name only" mode. When true this probe still contributes its label as
         * a net alias (so it names — and, when its label is shared with another
         * probe, merges — the net), but it is excluded from every simulation
         * print/plot: no {@code print v(...)} line and no result/graph series.
         * Used to name or bridge a net without cluttering the output with a
         * value you don't care to read.
         */
        public final boolean noPlot;

        public ProbeInfo(int node, String label) {
            this(node, label, Integer.toString(node), false);
        }

        public ProbeInfo(int node, String label, String netName) {
            this(node, label, netName, false);
        }

        public ProbeInfo(int node, String label, String netName, boolean noPlot) {
            this.node    = node;
            this.label   = label;
            this.netName = netName == null || netName.isEmpty() ? Integer.toString(node) : netName;
            this.noPlot  = noPlot;
        }
    }

    public static class CurrentProbeInfo {
        public final int    nodeA;
        public final int    nodeB;
        public final String label;
        public CurrentProbeInfo(int nodeA, int nodeB, String label) {
            this.nodeA = nodeA; this.nodeB = nodeB; this.label = label;
        }
    }

    /**
     * A user-defined plot expression coming from a {@code plot NAME = EXPR}
     * directive in a Commands block. Each plot results in a {@code let NAME = EXPR}
     * line plus an entry in the {@code print} column list, so the value is
     * available both as a scalar (OP) and as a graphable series (AC/TRAN).
     */
    public static class UserPlot {
        /** Sanitised ngspice vector name (lowercase, [a-z0-9_]). */
        public final String name;
        /** The right-hand-side expression as the user typed it. */
        public final String expr;
        /** Display label shown in chat/book/graph legends. */
        public final String label;
        /** Y-axis unit for the plot ("" = unitless, "V", "A", "dB", "rad", etc.). */
        public final String unit;

        public UserPlot(String name, String expr, String label) {
            this(name, expr, label, "");
        }

        public UserPlot(String name, String expr, String label, String unit) {
            this.name  = name;
            this.expr  = expr;
            this.label = label;
            this.unit  = unit == null ? "" : unit;
        }
    }
}
