package com.circuitsim.simulation;

import com.circuitsim.block.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CircuitExtractor {

    /**
     * Matches {@code plot NAME = EXPR} or {@code plot NAME[UNIT] = EXPR} (case-insensitive).
     * Group 1 = name, group 2 = optional unit, group 3 = expression.
     */
    private static final Pattern PLOT_DIRECTIVE =
            Pattern.compile("^plot\\s+([^\\s\\[=]+)\\s*(?:\\[([^\\]]*)\\])?\\s*=\\s*(.+)$",
                    Pattern.CASE_INSENSITIVE);

    private final Map<BlockPos, BlockPos> parent = new HashMap<>();

    private BlockPos find(BlockPos pos) {
        BlockPos p = parent.getOrDefault(pos, pos);
        if (!p.equals(pos)) {
            BlockPos root = find(p);
            parent.put(pos, root);
            return root;
        }
        return pos;
    }

    private void union(BlockPos a, BlockPos b) {
        BlockPos rootA = find(a);
        BlockPos rootB = find(b);
        if (!rootA.equals(rootB)) {
            parent.put(rootA, rootB);
        }
    }

    public static ExtractionResult extract(Level level, BlockPos startPos) {
        return new CircuitExtractor().extractCircuit(level, startPos);
    }

    private ExtractionResult extractCircuit(Level level, BlockPos startPos) {
        Set<BlockPos> visited = new HashSet<>();
        Queue<BlockPos> queue = new LinkedList<>();
        queue.add(startPos);
        visited.add(startPos);

        while (!queue.isEmpty()) {
            BlockPos current = queue.poll();
            for (Direction dir : Direction.values()) {
                BlockPos neighbor = current.relative(dir);
                if (!visited.contains(neighbor) && isCircuitBlock(level, neighbor)) {
                    visited.add(neighbor);
                    queue.add(neighbor);
                }
            }
        }

        if (visited.size() <= 1) {
            return new ExtractionResult(false, "No connected circuit found!",
                    Collections.emptyList(), Collections.emptyList(),
                    Collections.emptyList(), Collections.emptyMap(),
                    Collections.emptyList(), Collections.emptyList(),
                    Collections.emptyList());
        }

        for (BlockPos pos : visited) {
            Block block = level.getBlockState(pos).getBlock();
            if (block instanceof WireBlock || block instanceof GroundBlock) {
                for (Direction dir : Direction.values()) {
                    BlockPos neighbor = pos.relative(dir);
                    if (visited.contains(neighbor)) {
                        Block neighborBlock = level.getBlockState(neighbor).getBlock();
                        if (neighborBlock instanceof WireBlock || neighborBlock instanceof GroundBlock) {
                            union(pos, neighbor);
                        }
                    }
                }
            }
        }

        BlockPos groundAnchor = new BlockPos(0, Integer.MIN_VALUE, 0);
        boolean hasGround = false;
        for (BlockPos pos : visited) {
            if (level.getBlockState(pos).getBlock() instanceof GroundBlock) {
                union(pos, groundAnchor);
                hasGround = true;
            }
        }

        Map<BlockPos, Integer> nodeMap = new HashMap<>();
        int[] nextNode = {1};

        if (hasGround) {
            nodeMap.put(find(groundAnchor), 0);
        }

        for (BlockPos pos : visited) {
            Block block = level.getBlockState(pos).getBlock();
            if (block instanceof WireBlock || block instanceof GroundBlock) {
                BlockPos root = find(pos);
                if (!nodeMap.containsKey(root)) {
                    nodeMap.put(root, nextNode[0]++);
                }
            }
        }

        List<NetlistBuilder.CircuitComponent> components = new ArrayList<>();
        List<NetlistBuilder.ProbeInfo> probes = new ArrayList<>();
        // Parallel to `probes`. Holds the raw user-typed label when set,
        // null when the probe has no explicit label. Used after the visit
        // loop to derive net aliases.
        List<String> probeUserLabels = new ArrayList<>();
        List<NetlistBuilder.CurrentProbeInfo> currentProbes = new ArrayList<>();
        List<ParametricInfo> parametricBlocks = new ArrayList<>();
        List<String> userCommands = new ArrayList<>();
        List<NetlistBuilder.UserPlot> userPlots = new ArrayList<>();
        Set<String> usedPlotNames = new HashSet<>();
        Map<Integer, String> probeLabels = new HashMap<>();

        for (BlockPos pos : visited) {
            Block block = level.getBlockState(pos).getBlock();
            BlockState state = level.getBlockState(pos);

            if (block instanceof ParametricBlock) {
                Direction facing = state.getValue(BaseComponentBlock.FACING);
                BlockPos targetPos = pos.relative(facing);
                String sweepString = "";
                if (level.getBlockEntity(pos) instanceof com.circuitsim.blockentity.ComponentBlockEntity be) {
                    sweepString = be.getLabel();
                }
                // Skip unconfigured parametric blocks. An empty sweep would otherwise
                // hijack every simulation in the circuit with the "No sweep values"
                // error, even when the user just wants a regular OP/AC/TRAN run.
                if (hasSweepValues(sweepString)) {
                    Block targetBlock = level.getBlockState(targetPos).getBlock();
                    parametricBlocks.add(new ParametricInfo(pos, targetPos, targetBlock, sweepString));
                }

            } else if (block instanceof CommandsBlock) {
                if (level.getBlockEntity(pos) instanceof com.circuitsim.blockentity.ComponentBlockEntity be) {
                    String text = be.getCommands();
                    if (text != null && !text.isBlank()) {
                        for (String line : text.split("\\r?\\n")) {
                            String trimmed = line.strip();
                            if (trimmed.isEmpty()) continue;
                            NetlistBuilder.UserPlot plot = parsePlotDirective(trimmed, usedPlotNames);
                            if (plot != null) {
                                userPlots.add(plot);
                            } else {
                                userCommands.add(trimmed);
                            }
                        }
                    }
                }

            } else if (block instanceof ProbeBlock) {
                Direction facing = state.getValue(BaseComponentBlock.FACING);
                BlockPos probeTarget = pos.relative(facing);
                int node = resolveNode(probeTarget, visited, nodeMap, nextNode);

                String userLabel = null;
                if (level.getBlockEntity(pos) instanceof com.circuitsim.blockentity.ComponentBlockEntity be) {
                    String beLabel = be.getProbeLabel();
                    if (beLabel != null && !beLabel.isEmpty()) userLabel = beLabel;
                }
                String displayLabel = userLabel != null ? userLabel : "Probe_" + pos.toShortString();

                probes.add(new NetlistBuilder.ProbeInfo(node, displayLabel));
                probeUserLabels.add(userLabel);
                probeLabels.put(node, displayLabel);

            } else if (block instanceof CurrentProbeBlock) {
                Direction facing = state.getValue(BaseComponentBlock.FACING);
                BlockPos frontPos = pos.relative(facing);
                BlockPos backPos  = pos.relative(facing.getOpposite());

                int nodeA = resolveNode(frontPos, visited, nodeMap, nextNode);
                int nodeB = resolveNode(backPos,  visited, nodeMap, nextNode);

                String label = "IProbe_" + pos.toShortString();
                if (level.getBlockEntity(pos) instanceof com.circuitsim.blockentity.ComponentBlockEntity be) {
                    String beLabel = be.getProbeLabel();
                    if (beLabel != null && !beLabel.isEmpty()) label = beLabel;
                }

                currentProbes.add(new NetlistBuilder.CurrentProbeInfo(nodeA, nodeB, label));

            } else if (block instanceof DiodeBlock) {
                Direction facing = state.getValue(BaseComponentBlock.FACING);
                int nodeA = resolveNode(pos.relative(facing),               visited, nodeMap, nextNode);
                int nodeB = resolveNode(pos.relative(facing.getOpposite()), visited, nodeMap, nextNode);
                int compNum = 0;
                if (level.getBlockEntity(pos) instanceof com.circuitsim.blockentity.ComponentBlockEntity be) {
                    compNum = be.getComponentNumber();
                }
                components.add(new NetlistBuilder.CircuitComponent(
                        block, pos, nodeA, nodeB, -1, -1, 0, "DC", 0,
                        "", 1.0, 1.0, 1.0, 1.0, compNum));

            } else if (block instanceof IcResistorBlock) {
                Direction facing = state.getValue(BaseComponentBlock.FACING);
                int nodeA = resolveNode(pos.relative(facing),               visited, nodeMap, nextNode);
                int nodeB = resolveNode(pos.relative(facing.getOpposite()), visited, nodeMap, nextNode);

                // Bulk pin is on the right side of the block (clockwise of facing).
                // Default to node 0 (ground) if no wire is connected there.
                BlockPos bulkPos = pos.relative(facing.getClockWise());
                int nodeC = visited.contains(bulkPos)
                        ? resolveNode(bulkPos, visited, nodeMap, nextNode)
                        : 0;

                String modelName = "";
                double wParam    = 1.0;
                double lParam    = 1.0;
                double multParam = 1.0;
                int    compNum   = 0;

                if (level.getBlockEntity(pos) instanceof com.circuitsim.blockentity.ComponentBlockEntity be) {
                    modelName = be.getModelName();
                    wParam    = be.getWParam();
                    lParam    = be.getLParam();
                    multParam = be.getMultParam();
                    compNum   = be.getComponentNumber();
                }

                components.add(new NetlistBuilder.CircuitComponent(
                        block, pos, nodeA, nodeB, nodeC, -1, 0, "DC", 0,
                        modelName, wParam, lParam, multParam, 1.0, compNum));

            } else if (block instanceof IcCapacitorBlock) {
                Direction facing = state.getValue(BaseComponentBlock.FACING);
                int nodeA = resolveNode(pos.relative(facing),               visited, nodeMap, nextNode);
                int nodeB = resolveNode(pos.relative(facing.getOpposite()), visited, nodeMap, nextNode);

                String modelName = "";
                double wParam    = 1.0;
                double lParam    = 1.0;
                double multParam = 1.0;
                int    compNum   = 0;

                if (level.getBlockEntity(pos) instanceof com.circuitsim.blockentity.ComponentBlockEntity be) {
                    modelName = be.getModelName();
                    wParam    = be.getWParam();
                    lParam    = be.getLParam();
                    multParam = be.getMultParam();
                    compNum   = be.getComponentNumber();
                }

                components.add(new NetlistBuilder.CircuitComponent(
                        block, pos, nodeA, nodeB, -1, -1, 0, "DC", 0,
                        modelName, wParam, lParam, multParam, 1.0, compNum));

            } else if (block instanceof IcNmos4Block || block instanceof IcPmos4Block) {
                Direction facing = state.getValue(BaseComponentBlock.FACING);
                boolean mirrored = state.hasProperty(BaseComponentBlock.MIRRORED)
                        && state.getValue(BaseComponentBlock.MIRRORED);
                // Front = drain (NMOS) / source (PMOS), back = source (NMOS) / drain (PMOS)
                int nodeA = resolveNode(pos.relative(facing),               visited, nodeMap, nextNode);
                int nodeB = resolveNode(pos.relative(facing.getOpposite()), visited, nodeMap, nextNode);
                // Default: gate on counter-clockwise (left/west when facing north),
                // bulk on clockwise (right/east when facing north).
                // Mirrored: swap so gate is on clockwise side and bulk on counter-clockwise.
                Direction gateDir = mirrored ? facing.getClockWise()        : facing.getCounterClockWise();
                Direction bulkDir = mirrored ? facing.getCounterClockWise() : facing.getClockWise();
                BlockPos bulkPos = pos.relative(bulkDir);
                int nodeC = visited.contains(bulkPos)
                        ? resolveNode(bulkPos, visited, nodeMap, nextNode)
                        : 0;
                BlockPos gatePos = pos.relative(gateDir);
                int nodeD = visited.contains(gatePos)
                        ? resolveNode(gatePos, visited, nodeMap, nextNode)
                        : 0;

                String modelName = "";
                double wParam    = 1.0;
                double lParam    = 1.0;
                double multParam = 1.0;
                double nfParam   = 1.0;
                int    compNum   = 0;

                if (level.getBlockEntity(pos) instanceof com.circuitsim.blockentity.ComponentBlockEntity be) {
                    modelName = be.getModelName();
                    wParam    = be.getWParam();
                    lParam    = be.getLParam();
                    multParam = be.getMultParam();
                    nfParam   = be.getNfParam();
                    compNum   = be.getComponentNumber();
                }

                components.add(new NetlistBuilder.CircuitComponent(
                        block, pos, nodeA, nodeB, nodeC, nodeD,
                        0, "DC", 0,
                        modelName, wParam, lParam, multParam, nfParam, compNum));

            } else if (block instanceof BaseComponentBlock) {
                Direction facing = state.getValue(BaseComponentBlock.FACING);
                int nodeA = resolveNode(pos.relative(facing),               visited, nodeMap, nextNode);
                int nodeB = resolveNode(pos.relative(facing.getOpposite()), visited, nodeMap, nextNode);

                double value = 0;
                String sourceType = "DC";
                double frequency  = 0;
                int    compNum    = 0;

                if (level.getBlockEntity(pos) instanceof com.circuitsim.blockentity.ComponentBlockEntity be) {
                    value      = be.getValue();
                    sourceType = be.getSourceType();
                    frequency  = be.getFrequency();
                    compNum    = be.getComponentNumber();
                }

                components.add(new NetlistBuilder.CircuitComponent(
                        block, pos, nodeA, nodeB, -1, -1, value, sourceType, frequency,
                        "", 1.0, 1.0, 1.0, 1.0, compNum));
            }
        }

        // ── alias resolution ─────────────────────────────────────────────────
        // Voltage-probe labels become ngspice net names. The first probe whose
        // sanitised label is unique wins for that node; collisions (same alias
        // already used for a different node) fall back to the integer node id.
        Map<Integer, String> nodeAliases = new HashMap<>();
        Set<String> usedAliases = new HashSet<>();
        for (int i = 0; i < probes.size(); i++) {
            NetlistBuilder.ProbeInfo p = probes.get(i);
            String userLabel = probeUserLabels.get(i);
            if (p.node == 0 || userLabel == null) continue;
            String alias = NetlistBuilder.sanitizeNodeName(userLabel);
            if (alias.isEmpty()) continue;
            if (usedAliases.contains(alias)) continue;
            if (nodeAliases.containsKey(p.node)) continue;
            nodeAliases.put(p.node, alias);
            usedAliases.add(alias);
        }

        // Re-emit probes with resolved netName so downstream code can lookup
        // ngspice values without recomputing aliases.
        List<NetlistBuilder.ProbeInfo> aliasedProbes = new ArrayList<>(probes.size());
        for (NetlistBuilder.ProbeInfo p : probes) {
            String netName = nodeAliases.getOrDefault(p.node, Integer.toString(p.node));
            aliasedProbes.add(new NetlistBuilder.ProbeInfo(p.node, p.label, netName));
        }

        return new ExtractionResult(true, "", components, aliasedProbes, currentProbes, probeLabels, parametricBlocks, userCommands, userPlots);
    }

    /**
     * True iff {@code raw} contains an actual sweep specification, i.e. is
     * non-blank and (when it has a {@code paramName=} prefix) has a non-blank
     * portion after the {@code =}. Used to filter out unconfigured Parametric
     * blocks so they don't block normal simulations.
     */
    private static boolean hasSweepValues(String raw) {
        if (raw == null) return false;
        String s = raw.trim();
        if (s.isEmpty()) return false;
        int eq = s.indexOf('=');
        if (eq < 0) return true;
        return !s.substring(eq + 1).trim().isEmpty();
    }

    /**
     * Parses a single line from a Commands block. If it matches the
     * {@code plot NAME = EXPR} directive, returns a {@link NetlistBuilder.UserPlot};
     * otherwise returns {@code null} (caller treats line as a raw ngspice command).
     * Names that collide with already-used plot names or sanitise to empty are
     * rejected (caller falls back to treating the line as a raw command).
     */
    private static NetlistBuilder.UserPlot parsePlotDirective(String line, Set<String> usedNames) {
        Matcher m = PLOT_DIRECTIVE.matcher(line);
        if (!m.matches()) return null;
        String displayName = m.group(1).trim();
        String unitOverride = m.group(2);
        String expr         = m.group(3).trim();
        if (expr.isEmpty()) return null;
        String safeName = NetlistBuilder.sanitizeNodeName(displayName);
        if (safeName.isEmpty()) return null;
        if (usedNames.contains(safeName)) return null;
        usedNames.add(safeName);
        String unit = unitOverride != null ? unitOverride.trim() : detectPlotUnit(expr);
        return new NetlistBuilder.UserPlot(safeName, expr, displayName, unit);
    }

    /**
     * Best-effort unit guess for a {@code plot} expression when the user did not
     * provide an explicit {@code [unit]} override. Matches the common ngspice
     * functions ({@code db}, {@code ph}, {@code mag}) and treats top-level
     * division as a unitless ratio. {@code mag/abs/real/imag} are unwrapped
     * since they preserve the unit of the inner expression — so
     * {@code mag(v(out)/v(in))} is correctly detected as unitless.
     */
    private static String detectPlotUnit(String expr) {
        String s = expr.toLowerCase().replaceAll("\\s+", "");
        if (s.startsWith("db(")) return "dB";
        if (s.startsWith("ph(") || s.startsWith("cph(")) return "rad";
        s = stripScalarWrapper(s);
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == '/' && depth == 0) return "";
        }
        boolean hasV = s.contains("v(");
        boolean hasI = s.contains("i(");
        if (hasV && !hasI) return "V";
        if (hasI && !hasV) return "A";
        return "";
    }

    /**
     * If {@code s} is exactly {@code mag(...)}, {@code abs(...)}, {@code real(...)}
     * or {@code imag(...)} (i.e. the wrapper's closing paren is the final char),
     * returns the inner expression. Otherwise returns {@code s} unchanged.
     */
    private static String stripScalarWrapper(String s) {
        String[] wrappers = { "mag(", "abs(", "real(", "imag(" };
        for (String w : wrappers) {
            if (!s.startsWith(w) || !s.endsWith(")")) continue;
            int depth = 0;
            for (int i = w.length() - 1; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '(') depth++;
                else if (c == ')') {
                    depth--;
                    if (depth == 0) {
                        if (i == s.length() - 1) return s.substring(w.length(), s.length() - 1);
                        break;
                    }
                }
            }
        }
        return s;
    }

    private int resolveNode(BlockPos pos, Set<BlockPos> visited,
                             Map<BlockPos, Integer> nodeMap, int[] nextNode) {
        if (visited.contains(pos)) {
            BlockPos root = find(pos);
            if (nodeMap.containsKey(root)) return nodeMap.get(root);
            int node = nextNode[0]++;
            nodeMap.put(root, node);
            return node;
        }
        return nextNode[0]++;
    }

    private boolean isCircuitBlock(Level level, BlockPos pos) {
        Block block = level.getBlockState(pos).getBlock();
        return block instanceof ResistorBlock
                || block instanceof IcResistorBlock
                || block instanceof IcCapacitorBlock
                || block instanceof IcNmos4Block
                || block instanceof IcPmos4Block
                || block instanceof CapacitorBlock
                || block instanceof InductorBlock
                || block instanceof VoltageSourceBlock
                || block instanceof VoltageSourceSinBlock
                || block instanceof CurrentSourceBlock
                || block instanceof DiodeBlock
                || block instanceof WireBlock
                || block instanceof GroundBlock
                || block instanceof ProbeBlock
                || block instanceof CurrentProbeBlock
                || block instanceof SimulateBlock
                || block instanceof ParametricBlock
                || block instanceof CommandsBlock;
    }

    public static class ParametricInfo {
        public final BlockPos pos;
        public final BlockPos targetPos;
        /**
         * Cached at extraction time so downstream sim code (which now runs on a
         * background thread) does not have to call {@code Level.getBlockState}.
         * Off-thread world reads are unsafe.
         */
        public final Block    targetBlock;
        public final String   sweepString;

        public ParametricInfo(BlockPos pos, BlockPos targetPos, Block targetBlock, String sweepString) {
            this.pos         = pos;
            this.targetPos   = targetPos;
            this.targetBlock = targetBlock;
            this.sweepString = sweepString;
        }
    }

    public static class ExtractionResult {
        public final boolean success;
        public final String  errorMessage;
        public final List<NetlistBuilder.CircuitComponent>   components;
        public final List<NetlistBuilder.ProbeInfo>          probes;
        public final List<NetlistBuilder.CurrentProbeInfo>   currentProbes;
        public final Map<Integer, String>                    probeLabels;
        public final List<ParametricInfo>                    parametricBlocks;
        /** Free-form ngspice commands collected from Commands blocks; one per line. */
        public final List<String>                            userCommands;
        /** {@code plot NAME = EXPR} directives extracted from Commands blocks. */
        public final List<NetlistBuilder.UserPlot>           userPlots;

        public ExtractionResult(boolean success, String errorMessage,
                                List<NetlistBuilder.CircuitComponent> components,
                                List<NetlistBuilder.ProbeInfo>        probes,
                                List<NetlistBuilder.CurrentProbeInfo> currentProbes,
                                Map<Integer, String>                  probeLabels,
                                List<ParametricInfo>                  parametricBlocks,
                                List<String>                          userCommands,
                                List<NetlistBuilder.UserPlot>         userPlots) {
            this.success          = success;
            this.errorMessage     = errorMessage;
            this.components       = components;
            this.probes           = probes;
            this.currentProbes    = currentProbes;
            this.probeLabels      = probeLabels;
            this.parametricBlocks = parametricBlocks;
            this.userCommands     = userCommands;
            this.userPlots        = userPlots;
        }
    }
}