package com.circuitsim.simulation;

import com.circuitsim.block.*;
import com.circuitsim.simulation.NetlistBuilder.CircuitComponent;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Pre-simulation sanity checks on an extracted circuit. Produces human-readable
 * warnings for the mistakes that otherwise surface as cryptic ngspice failures
 * ("singular matrix", "no DC path to ground") or no output at all:
 *
 * <ul>
 *   <li>no ground reference,</li>
 *   <li>a node wired to a single terminal (a floating stub), and</li>
 *   <li>a node with no DC path to ground (reachable only through capacitors /
 *       current sources).</li>
 * </ul>
 *
 * The checks are advisory only — the simulation still runs — and deliberately
 * lean toward <em>under</em>-reporting (treating ambiguous devices as
 * conducting) so a valid circuit isn't nagged with false alarms.
 */
public final class CircuitLinter {

    private CircuitLinter() {}

    private static final int MAX_WARNINGS = 10;

    public static List<String> lint(CircuitExtractor.ExtractionResult ex) {
        List<String> warnings = new ArrayList<>();
        if (ex == null || ex.components == null || ex.components.isEmpty()) return warnings;

        List<CircuitComponent> comps = ex.components;
        Map<Integer, String> names = ex.probeLabels == null ? Map.of() : ex.probeLabels;

        // Pin usage per node (component terminals only — probes don't count as
        // an electrical connection).
        Map<Integer, Integer> usage = new HashMap<>();
        TreeSet<Integer> nodes = new TreeSet<>();
        boolean hasGround = false;
        for (CircuitComponent c : comps) {
            for (int n : pinsOf(c)) {
                if (n < 0) continue;
                nodes.add(n);
                usage.merge(n, 1, Integer::sum);
                if (n == 0) hasGround = true;
            }
        }

        if (!hasGround) {
            warnings.add("No ground in the circuit — place a Ground block (node 0). "
                    + "ngspice needs a 0V reference or every analysis fails.");
        }

        // Single-terminal (floating stub) nodes.
        for (int n : nodes) {
            if (n == 0) continue;
            if (usage.getOrDefault(n, 0) == 1) {
                warnings.add("Node " + name(names, n) + " connects to only one terminal — "
                        + "it floats (a wire to nowhere, or a missing connection).");
            }
        }

        // No DC path to ground.
        if (hasGround) {
            UnionFind uf = new UnionFind();
            for (int n : nodes) uf.add(n);
            for (CircuitComponent c : comps) {
                for (int[] pair : dcGroups(c)) {
                    if (pair[0] >= 0 && pair[1] >= 0) uf.union(pair[0], pair[1]);
                }
            }
            int groundRoot = uf.find(0);
            for (int n : nodes) {
                if (n == 0) continue;
                if (usage.getOrDefault(n, 0) <= 1) continue;   // already flagged / trivially floating
                if (uf.find(n) != groundRoot) {
                    warnings.add("Node " + name(names, n) + " has no DC path to ground — "
                            + "it only reaches ground through capacitors or current sources "
                            + "(add a resistor/bias path, or expect a convergence error).");
                }
            }
        }

        if (warnings.size() > MAX_WARNINGS) {
            List<String> capped = new ArrayList<>(warnings.subList(0, MAX_WARNINGS));
            capped.add("… and " + (warnings.size() - MAX_WARNINGS) + " more potential issue(s).");
            return capped;
        }
        return warnings;
    }

    // ------------------------------------------------------------------------

    /** All node ids a component touches (subcircuit pins, or up to 4 fixed pins). */
    private static int[] pinsOf(CircuitComponent c) {
        if (c.subcircuitNodes != null) return c.subcircuitNodes;
        List<Integer> ns = new ArrayList<>(4);
        if (c.nodeA >= 0) ns.add(c.nodeA);
        if (c.nodeB >= 0) ns.add(c.nodeB);
        if (c.nodeC >= 0) ns.add(c.nodeC);
        if (c.nodeD >= 0) ns.add(c.nodeD);
        int[] out = new int[ns.size()];
        for (int i = 0; i < out.length; i++) out[i] = ns.get(i);
        return out;
    }

    /**
     * Node pairs that conduct at DC for a component. Capacitors, current
     * sources, transconductors (VCCS/CCCS), and the high-impedance sense pins of
     * controlled sources / mosfet gates contribute no pair (they don't carry the
     * DC operating point through). Devices with unknown internals (amplifier,
     * user subcircuit) are treated as fully connected to avoid false alarms.
     */
    private static List<int[]> dcGroups(CircuitComponent c) {
        Block b = c.block;

        // Transformer: both windings are inductors (DC shorts), but the two
        // sides stay galvanically isolated from each other.
        if (b instanceof TransformerBlock) {
            return List.of(new int[]{c.nodeA, c.nodeB}, new int[]{c.nodeC, c.nodeD});
        }

        // Transmission line: two conductors, each a DC path end-to-end
        // (port1+ ↔ port2+ and port1− ↔ port2−); the conductors stay
        // isolated from each other at DC.
        if (b instanceof TransmissionLineBlock) {
            return List.of(new int[]{c.nodeA, c.nodeC}, new int[]{c.nodeB, c.nodeD});
        }

        // Two-terminal DC conductors.
        if (b instanceof ResistorBlock || b instanceof IcResistorBlock
                || b instanceof InductorBlock
                || b instanceof VoltageSourceBlock || b instanceof VoltageSourceSinBlock
                || b instanceof VoltageSourcePulseBlock
                || b instanceof BehavioralVoltageSourceBlock
                || b instanceof DiodeBlock || b instanceof VSwitchBlock
                || b instanceof VcvsBlock || b instanceof CcvsBlock) {
            return List.of(new int[]{c.nodeA, c.nodeB});
        }

        // Open at DC — no terminal pairing.
        if (b instanceof CapacitorBlock || b instanceof IcCapacitorBlock
                || b instanceof CurrentSourceBlock || b instanceof BehavioralCurrentSourceBlock
                || b instanceof VccsBlock || b instanceof CccsBlock) {
            return List.of();
        }

        // IC MOSFET: drain/source/bulk conduct; gate is isolated.
        if (b instanceof IcNmos4Block || b instanceof IcPmos4Block) {
            List<int[]> g = new ArrayList<>();
            g.add(new int[]{c.nodeA, c.nodeB});
            if (c.nodeC >= 0) g.add(new int[]{c.nodeA, c.nodeC});
            return g;
        }

        if (c.subcircuitNodes != null) {
            int[] sn = c.subcircuitNodes;
            if ((b instanceof DiscreteNmosBlock || b instanceof DiscretePmosBlock) && sn.length >= 3) {
                // [drain, gate, source] — drain↔source conduct, gate isolated.
                return List.of(new int[]{sn[0], sn[2]});
            }
            // BJTs (junctions conduct) and amplifier / user subcircuits (unknown
            // internals) — link every pin to the first so the whole device is
            // one connected island.
            return starPairs(sn);
        }

        if (c.nodeA >= 0 && c.nodeB >= 0) return List.of(new int[]{c.nodeA, c.nodeB});
        return List.of();
    }

    /** Pairs (sn[0], sn[i]) for i>0 — unions every pin into one island. */
    private static List<int[]> starPairs(int[] sn) {
        List<int[]> out = new ArrayList<>();
        for (int i = 1; i < sn.length; i++) out.add(new int[]{sn[0], sn[i]});
        return out;
    }

    private static String name(Map<Integer, String> names, int node) {
        if (node == 0) return "GND";
        String lbl = names.get(node);
        return (lbl != null && !lbl.isEmpty()) ? "\"" + lbl + "\"" : "#" + node;
    }

    /** Minimal union-find over integer node ids. */
    private static final class UnionFind {
        private final Map<Integer, Integer> parent = new HashMap<>();

        void add(int x) { parent.putIfAbsent(x, x); }

        int find(int x) {
            parent.putIfAbsent(x, x);
            int root = x;
            while (parent.get(root) != root) root = parent.get(root);
            while (parent.get(x) != root) { int nx = parent.get(x); parent.put(x, root); x = nx; }
            return root;
        }

        void union(int a, int b) {
            int ra = find(a), rb = find(b);
            if (ra != rb) parent.put(ra, rb);
        }
    }
}
