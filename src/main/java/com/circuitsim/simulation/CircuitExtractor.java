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
     * Matches {@code plot NAME} / {@code plot NAME = EXPR} /
     * {@code plot NAME[UNIT] = EXPR} (case-insensitive). The {@code = EXPR}
     * tail is optional: without it the user is expected to have defined
     * {@code NAME} with their own {@code let} on a prior line, and we only
     * register the series for graphing (no auto-emitted {@code let}).
     * Group 1 = name, group 2 = optional unit, group 3 = optional expression.
     */
    private static final Pattern PLOT_DIRECTIVE =
            Pattern.compile("^plot\\s+([^\\s\\[=]+)\\s*(?:\\[([^\\]]*)\\])?\\s*(?:=\\s*(.+))?$",
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
        CircuitExtractor ex = new CircuitExtractor();
        ExtractionResult result = ex.extractCircuit(level, startPos);
        result.simulatePos = ex.simulatePos;
        return result;
    }

    /** First Simulate block met during the BFS, if any (see ExtractionResult). */
    private BlockPos simulatePos = null;

    /**
     * BFS the connected circuit reachable from {@code startPos}, returning every
     * circuit-block position found. {@code startPos} itself is included only if
     * it is a circuit block. Used by the Subcircuit Converter to snapshot the
     * exact schematic it should turn into a chip.
     */
    public static Set<BlockPos> connectedCircuitBlocks(Level level, BlockPos startPos) {
        CircuitExtractor ex = new CircuitExtractor();
        Set<BlockPos> visited = new HashSet<>();
        Queue<BlockPos> queue = new LinkedList<>();
        queue.add(startPos);
        visited.add(startPos);
        while (!queue.isEmpty()) {
            BlockPos current = queue.poll();
            for (Direction dir : Direction.values()) {
                BlockPos neighbor = current.relative(dir);
                if (!visited.contains(neighbor) && ex.isCircuitBlock(level, neighbor)) {
                    visited.add(neighbor);
                    queue.add(neighbor);
                }
            }
        }
        visited.removeIf(p -> !ex.isCircuitBlock(level, p));
        return visited;
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

        // Amplifier pin cells share a node with their adjacent outward wire.
        // (Body cells deliberately don't union — wires next to body cells must
        // remain separate nodes.) Doing this in the union phase means later
        // resolveNode(pinPos) returns the same node as resolveNode(wirePos),
        // so probes can target either side.
        for (BlockPos pos : visited) {
            BlockState bs = level.getBlockState(pos);
            if (!(bs.getBlock() instanceof AmplifierBlock)) continue;
            AmplifierBlock.CellKind kind = bs.getValue(AmplifierBlock.CELL_KIND);
            if (!kind.isPin()) continue;
            // Outward is purely positional — see AmplifierBlock.outwardOf —
            // because under MIRRORED the CellKind alone no longer determines
            // which edge of the structure the cell sits on (VCC can be at the
            // south edge when mirrored).
            int col = bs.getValue(AmplifierBlock.LOCAL_X);
            int row = bs.getValue(AmplifierBlock.LOCAL_Z);
            Direction outward = AmplifierBlock.rotateDir(
                    AmplifierBlock.outwardOf(col, row),
                    bs.getValue(AmplifierBlock.FACING));
            BlockPos wirePos = pos.relative(outward);
            if (!visited.contains(wirePos)) continue;
            Block wb = level.getBlockState(wirePos).getBlock();
            if (wb instanceof WireBlock || wb instanceof GroundBlock) {
                union(pos, wirePos);
            }
        }

        // Same idea for VCVS / VCCS 2×3 multi-block pin cells.
        for (BlockPos pos : visited) {
            BlockState bs = level.getBlockState(pos);
            if (!(bs.getBlock() instanceof Controlled2x3Block)) continue;
            Controlled2x3Block.CellKind kind = bs.getValue(Controlled2x3Block.CELL_KIND);
            if (!kind.isPin()) continue;
            Direction outward = Controlled2x3Block.rotateDir(kind.defaultOutward(),
                    bs.getValue(Controlled2x3Block.FACING));
            BlockPos wirePos = pos.relative(outward);
            if (!visited.contains(wirePos)) continue;
            Block wb = level.getBlockState(wirePos).getBlock();
            if (wb instanceof WireBlock || wb instanceof GroundBlock) {
                union(pos, wirePos);
            }
        }

        BlockPos groundAnchor = new BlockPos(0, Integer.MIN_VALUE, 0);
        boolean hasGround = false;
        for (BlockPos pos : visited) {
            Block visitedBlock = level.getBlockState(pos).getBlock();
            if (visitedBlock instanceof GroundBlock) {
                union(pos, groundAnchor);
                hasGround = true;
            } else if (visitedBlock instanceof SimulateBlock && simulatePos == null) {
                simulatePos = pos.immutable();
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
        // Param-block parse errors — a malformed block must fail extraction
        // (no netlist gets generated) with the per-line messages.
        List<String> paramErrors = new ArrayList<>();
        List<String> userCommands = new ArrayList<>();
        List<NetlistBuilder.UserPlot> userPlots = new ArrayList<>();
        Set<String> usedPlotNames = new HashSet<>();
        Map<Integer, String> probeLabels = new HashMap<>();
        // Distinct user-defined subcircuit definitions ( .subckt … .ends ),
        // keyed by subckt name so multiple instances of the same subcircuit
        // embed the definition only once. Populated by the SubcircuitBlock branch.
        java.util.LinkedHashMap<String, String> subcktDefs = new java.util.LinkedHashMap<>();
        // Per user-subcircuit anchor → its chip's internal device map, for the
        // floating OP projection (empty for chips made before that was captured).
        Map<BlockPos, List<com.circuitsim.subcircuit.SubcircuitChip.DeviceMapEntry>>
                subcircuitDevices = new HashMap<>();

        for (BlockPos pos : visited) {
            Block block = level.getBlockState(pos).getBlock();
            BlockState state = level.getBlockState(pos);

            if (block instanceof ParametricBlock) {
                // Param block: one "name = value" declaration per line in the
                // commands slot. Old saves carried a single "name=values"
                // spec in the label slot — fall back to it so existing worlds
                // keep working without re-editing the block.
                String text = "";
                if (level.getBlockEntity(pos) instanceof com.circuitsim.blockentity.ComponentBlockEntity be) {
                    text = be.getCommands();
                    if (text == null || text.isBlank()) {
                        String[] legacy = com.circuitsim.screen.ParametricEditScreen.parseSpec(be.getLabel());
                        text = legacy[0].isEmpty() ? "" : legacy[0] + " = " + legacy[1];
                    }
                }
                if (!text.isBlank()) {
                    ParamSpec.ParseResult parsed = ParamSpec.parse(text);
                    for (String err : parsed.errors) {
                        paramErrors.add("Param block at " + pos.toShortString() + ": " + err);
                    }
                    for (ParamSpec.Entry entry : parsed.entries) {
                        parametricBlocks.add(new ParametricInfo(pos, entry.name, entry.rawValue));
                    }
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
                boolean noPlot = false;
                if (level.getBlockEntity(pos) instanceof com.circuitsim.blockentity.ComponentBlockEntity be) {
                    String beLabel = be.getProbeLabel();
                    if (beLabel != null && !beLabel.isEmpty()) userLabel = beLabel;
                    noPlot = be.isProbeNoPlot();
                }
                String displayLabel = userLabel != null ? userLabel : "Probe_" + pos.toShortString();

                probes.add(new NetlistBuilder.ProbeInfo(node, displayLabel, Integer.toString(node), noPlot));
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
                String diodeModel = "";
                if (level.getBlockEntity(pos) instanceof com.circuitsim.blockentity.ComponentBlockEntity be) {
                    compNum    = be.getComponentNumber();
                    diodeModel = be.getModelName();
                }
                components.add(new NetlistBuilder.CircuitComponent(
                        block, pos, nodeA, nodeB, -1, -1, 0, "DC", 0,
                        diodeModel, 1.0, 1.0, 1.0, 1.0, compNum));

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
                String wExpr = "", lExpr = "", multExpr = "";
                String pdkName = "none";

                if (level.getBlockEntity(pos) instanceof com.circuitsim.blockentity.ComponentBlockEntity be) {
                    modelName = be.getModelName();
                    wParam    = be.getWParam();
                    lParam    = be.getLParam();
                    multParam = be.getMultParam();
                    compNum   = be.getComponentNumber();
                    wExpr     = be.getWExpr();
                    lExpr     = be.getLExpr();
                    multExpr  = be.getMultExpr();
                    pdkName   = be.getPdkName();
                }

                components.add(new NetlistBuilder.CircuitComponent(
                        block, pos, nodeA, nodeB, nodeC, -1, 0, "DC", 0,
                        modelName, wParam, lParam, multParam, 1.0, compNum,
                        null, "", wExpr, lExpr, multExpr, "").withPdkName(pdkName));

            } else if (block instanceof IcCapacitorBlock) {
                Direction facing = state.getValue(BaseComponentBlock.FACING);
                int nodeA = resolveNode(pos.relative(facing),               visited, nodeMap, nextNode);
                int nodeB = resolveNode(pos.relative(facing.getOpposite()), visited, nodeMap, nextNode);

                String modelName = "";
                double wParam    = 1.0;
                double lParam    = 1.0;
                double multParam = 1.0;
                int    compNum   = 0;
                String wExpr = "", lExpr = "", multExpr = "";
                String pdkName = "none";

                if (level.getBlockEntity(pos) instanceof com.circuitsim.blockentity.ComponentBlockEntity be) {
                    modelName = be.getModelName();
                    wParam    = be.getWParam();
                    lParam    = be.getLParam();
                    multParam = be.getMultParam();
                    compNum   = be.getComponentNumber();
                    wExpr     = be.getWExpr();
                    lExpr     = be.getLExpr();
                    multExpr  = be.getMultExpr();
                    pdkName   = be.getPdkName();
                }

                components.add(new NetlistBuilder.CircuitComponent(
                        block, pos, nodeA, nodeB, -1, -1, 0, "DC", 0,
                        modelName, wParam, lParam, multParam, 1.0, compNum,
                        null, "", wExpr, lExpr, multExpr, "").withPdkName(pdkName));

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
                String wExpr = "", lExpr = "", multExpr = "", nfExpr = "";
                String pdkName = "none";

                if (level.getBlockEntity(pos) instanceof com.circuitsim.blockentity.ComponentBlockEntity be) {
                    modelName = be.getModelName();
                    wParam    = be.getWParam();
                    lParam    = be.getLParam();
                    multParam = be.getMultParam();
                    nfParam   = be.getNfParam();
                    compNum   = be.getComponentNumber();
                    wExpr     = be.getWExpr();
                    lExpr     = be.getLExpr();
                    multExpr  = be.getMultExpr();
                    nfExpr    = be.getNfExpr();
                    pdkName   = be.getPdkName();
                }

                components.add(new NetlistBuilder.CircuitComponent(
                        block, pos, nodeA, nodeB, nodeC, nodeD,
                        0, "DC", 0,
                        modelName, wParam, lParam, multParam, nfParam, compNum,
                        null, "", wExpr, lExpr, multExpr, nfExpr).withPdkName(pdkName));

            } else if (block instanceof VSwitchBlock) {
                Direction facing = state.getValue(VSwitchBlock.FACING);
                // Switched path: facing = n+, opposite = n-. Control sense:
                // counter-clockwise = nc+, clockwise = nc- (gate-on-the-left,
                // matching the IC MOSFET convention).
                int nodeA = resolveNode(pos.relative(facing),               visited, nodeMap, nextNode);
                int nodeB = resolveNode(pos.relative(facing.getOpposite()), visited, nodeMap, nextNode);
                BlockPos ctlPPos = pos.relative(facing.getCounterClockWise());
                BlockPos ctlNPos = pos.relative(facing.getClockWise());
                int nodeC = visited.contains(ctlPPos)
                        ? resolveNode(ctlPPos, visited, nodeMap, nextNode)
                        : 0;
                int nodeD = visited.contains(ctlNPos)
                        ? resolveNode(ctlNPos, visited, nodeMap, nextNode)
                        : 0;

                double vt = 2.5, vh = 0.0, ron = 1.0, roff = 1e12;
                String initState = "";
                int    compNum   = 0;
                if (level.getBlockEntity(pos) instanceof com.circuitsim.blockentity.ComponentBlockEntity be) {
                    vt        = be.getSwVt();
                    vh        = be.getSwVh();
                    ron       = be.getSwRon();
                    roff      = be.getSwRoff();
                    initState = be.getSwInit();
                    compNum   = be.getComponentNumber();
                }

                // SW model params ride the sky130 carrier slots (same trick as
                // the pulse source): wParam=Vt, lParam=Vh, multParam=Ron,
                // nfParam=Roff; modelName carries the initial on/off state.
                components.add(new NetlistBuilder.CircuitComponent(
                        block, pos, nodeA, nodeB, nodeC, nodeD, 0, "DC", 0,
                        initState, vt, vh, ron, roff, compNum));

            } else if (block instanceof CcvsBlock || block instanceof CccsBlock) {
                Direction facing = state.getValue(BaseComponentBlock.FACING);
                int nodeA = resolveNode(pos.relative(facing),               visited, nodeMap, nextNode);
                int nodeB = resolveNode(pos.relative(facing.getOpposite()), visited, nodeMap, nextNode);

                double value     = 0;
                int    compNum   = 0;
                String controlV  = "";
                String valueExpr = "";
                if (level.getBlockEntity(pos) instanceof com.circuitsim.blockentity.ComponentBlockEntity be) {
                    value     = be.getValue();
                    compNum   = be.getComponentNumber();
                    controlV  = be.getModelName(); // reused: stores the controlling vnam (e.g. "V1")
                    valueExpr = be.getValueExpr();
                }

                components.add(new NetlistBuilder.CircuitComponent(
                        block, pos, nodeA, nodeB, -1, -1, value, "DC", 0,
                        controlV == null ? "" : controlV,
                        1.0, 1.0, 1.0, 1.0, compNum, null, valueExpr));

            } else if (block instanceof BehavioralVoltageSourceBlock
                    || block instanceof BehavioralCurrentSourceBlock) {
                // Behavioral B-source: front = n+, back = n-. The arbitrary
                // expression is free text carried in the modelName slot (same
                // carrier CCVS/CCCS use); the netlist builder emits it after
                // the V= / I= keyword. No numeric value / valueExpr is used.
                Direction facing = state.getValue(BaseComponentBlock.FACING);
                int nodeA = resolveNode(pos.relative(facing),               visited, nodeMap, nextNode);
                int nodeB = resolveNode(pos.relative(facing.getOpposite()), visited, nodeMap, nextNode);

                String expr    = "";
                int    compNum = 0;
                if (level.getBlockEntity(pos) instanceof com.circuitsim.blockentity.ComponentBlockEntity be) {
                    expr    = be.getModelName();
                    compNum = be.getComponentNumber();
                }

                components.add(new NetlistBuilder.CircuitComponent(
                        block, pos, nodeA, nodeB, -1, -1, 0, "DC", 0,
                        expr == null ? "" : expr,
                        1.0, 1.0, 1.0, 1.0, compNum));

            } else if (block instanceof Controlled2x3Block) {
                // Only emit one component per 2×3 instance — at the anchor cell.
                Controlled2x3Block.CellKind kind = state.getValue(Controlled2x3Block.CELL_KIND);
                if (kind != Controlled2x3Block.CellKind.ANCHOR) continue;

                Direction facing = state.getValue(Controlled2x3Block.FACING);
                double value   = 0;
                int    compNum = 0;
                String valueExpr = "";
                if (level.getBlockEntity(pos) instanceof com.circuitsim.blockentity.ComponentBlockEntity be) {
                    value     = be.getValue();
                    compNum   = be.getComponentNumber();
                    valueExpr = be.getValueExpr();
                }

                // Pin order in the netlist line: N+ N- NC+ NC-
                int outP = pinNodeOf(level, pos, facing,
                        Controlled2x3Block.CellKind.OUT_P, visited, nodeMap, nextNode);
                int outN = pinNodeOf(level, pos, facing,
                        Controlled2x3Block.CellKind.OUT_N, visited, nodeMap, nextNode);
                int ctlP = pinNodeOf(level, pos, facing,
                        Controlled2x3Block.CellKind.CTRL_P, visited, nodeMap, nextNode);
                int ctlN = pinNodeOf(level, pos, facing,
                        Controlled2x3Block.CellKind.CTRL_N, visited, nodeMap, nextNode);

                components.add(new NetlistBuilder.CircuitComponent(
                        block, pos, outP, outN, ctlP, ctlN,
                        value, "DC", 0,
                        "", 1.0, 1.0, 1.0, 1.0, compNum, null, valueExpr));

            } else if (block instanceof DiscreteNmosBlock) {
                // 3-pin discrete NMOS: facing=drain, opposite=source,
                // counter-clockwise=gate. Clockwise face is insulated.
                // Emitted as X<n> drain gate source MODEL (pin order matches
                // typical PSpice .SUBCKT D G S).
                Direction facing = state.getValue(DiscreteNmosBlock.FACING);
                int nodeD = resolveNode(pos.relative(facing),                       visited, nodeMap, nextNode);
                int nodeS = resolveNode(pos.relative(facing.getOpposite()),         visited, nodeMap, nextNode);
                int nodeG = resolveNode(pos.relative(facing.getCounterClockWise()), visited, nodeMap, nextNode);

                String modelName = "";
                int    compNum   = 0;
                if (level.getBlockEntity(pos) instanceof com.circuitsim.blockentity.ComponentBlockEntity be) {
                    modelName = be.getModelName();
                    compNum   = be.getComponentNumber();
                }

                components.add(NetlistBuilder.CircuitComponent.subcircuit(
                        block, pos, new int[]{nodeD, nodeG, nodeS}, modelName, compNum));

            } else if (block instanceof DiscretePmosBlock) {
                // 3-pin discrete PMOS — pin layout matches the IC PMOS block:
                // facing=source, opposite=drain, counter-clockwise=gate. The
                // clockwise face is insulated. Emitted as X<n> drain gate
                // source MODEL so a single .SUBCKT pin convention (D G S)
                // works for both discrete NMOS and PMOS.
                Direction facing = state.getValue(DiscretePmosBlock.FACING);
                int nodeS = resolveNode(pos.relative(facing),                       visited, nodeMap, nextNode);
                int nodeD = resolveNode(pos.relative(facing.getOpposite()),         visited, nodeMap, nextNode);
                int nodeG = resolveNode(pos.relative(facing.getCounterClockWise()), visited, nodeMap, nextNode);

                String modelName = "";
                int    compNum   = 0;
                if (level.getBlockEntity(pos) instanceof com.circuitsim.blockentity.ComponentBlockEntity be) {
                    modelName = be.getModelName();
                    compNum   = be.getComponentNumber();
                }

                components.add(NetlistBuilder.CircuitComponent.subcircuit(
                        block, pos, new int[]{nodeD, nodeG, nodeS}, modelName, compNum));

            } else if (block instanceof DiscreteNpnBlock) {
                // 3-pin discrete NPN: facing=collector, opposite=emitter,
                // counter-clockwise=base. Clockwise face is insulated. Emitted
                // as a native BJT device Q<n> collector base emitter MODEL (the
                // standard SPICE BJT pin order C B E).
                Direction facing = state.getValue(DiscreteNpnBlock.FACING);
                int nodeC = resolveNode(pos.relative(facing),                       visited, nodeMap, nextNode);
                int nodeE = resolveNode(pos.relative(facing.getOpposite()),         visited, nodeMap, nextNode);
                int nodeB = resolveNode(pos.relative(facing.getCounterClockWise()), visited, nodeMap, nextNode);

                String modelName = "";
                int    compNum   = 0;
                if (level.getBlockEntity(pos) instanceof com.circuitsim.blockentity.ComponentBlockEntity be) {
                    modelName = be.getModelName();
                    compNum   = be.getComponentNumber();
                }

                components.add(NetlistBuilder.CircuitComponent.subcircuit(
                        block, pos, new int[]{nodeC, nodeB, nodeE}, modelName, compNum));

            } else if (block instanceof DiscretePnpBlock) {
                // 3-pin discrete PNP — collector/emitter swapped vs NPN:
                // facing=emitter, opposite=collector, counter-clockwise=base.
                // The clockwise face is insulated. Emitted as a native BJT
                // device Q<n> collector base emitter MODEL so a single C B E pin
                // convention works for both discrete NPN and PNP.
                Direction facing = state.getValue(DiscretePnpBlock.FACING);
                int nodeE = resolveNode(pos.relative(facing),                       visited, nodeMap, nextNode);
                int nodeC = resolveNode(pos.relative(facing.getOpposite()),         visited, nodeMap, nextNode);
                int nodeB = resolveNode(pos.relative(facing.getCounterClockWise()), visited, nodeMap, nextNode);

                String modelName = "";
                int    compNum   = 0;
                if (level.getBlockEntity(pos) instanceof com.circuitsim.blockentity.ComponentBlockEntity be) {
                    modelName = be.getModelName();
                    compNum   = be.getComponentNumber();
                }

                components.add(NetlistBuilder.CircuitComponent.subcircuit(
                        block, pos, new int[]{nodeC, nodeB, nodeE}, modelName, compNum));

            } else if (block instanceof AmplifierBlock) {
                // Only emit one component per amp — at the anchor cell. Every
                // other cell is part of the same structure.
                AmplifierBlock.CellKind kind = state.getValue(AmplifierBlock.CELL_KIND);
                if (kind != AmplifierBlock.CellKind.ANCHOR) continue;

                Direction facing = state.getValue(AmplifierBlock.FACING);
                boolean   mirrored = state.getValue(AmplifierBlock.MIRRORED);
                String modelName = "";
                int    compNum   = 0;
                boolean offsetEnabled = false;
                if (level.getBlockEntity(pos) instanceof com.circuitsim.blockentity.ComponentBlockEntity be) {
                    modelName     = be.getLabel();
                    compNum       = be.getComponentNumber();
                    offsetEnabled = be.isOffsetEnabled();
                }

                int[] pinNodes = resolveAmpPinNodes(level, pos, facing, offsetEnabled, mirrored,
                        visited, nodeMap, nextNode);
                components.add(NetlistBuilder.CircuitComponent.subcircuit(
                        block, pos, pinNodes, modelName, compNum));

            } else if (block instanceof SubcircuitBlock) {
                // User-defined subcircuit instance. Only the anchor cell emits
                // the X line; it also contributes the .subckt definition once.
                if (state.getValue(SubcircuitBlock.CELL_KIND) != SubcircuitBlock.CellKind.ANCHOR) continue;
                if (!(level.getBlockEntity(pos)
                        instanceof com.circuitsim.blockentity.SubcircuitBlockEntity sbe)) continue;
                int pinCount = sbe.getActivePinCount();
                if (!sbe.hasChip() || pinCount == 0) continue;

                Direction facing = state.getValue(SubcircuitBlock.FACING);
                int[] pinNodes = resolveSubcircuitPinNodes(pos, facing, pinCount,
                        visited, nodeMap, nextNode);
                components.add(NetlistBuilder.CircuitComponent.subcircuit(
                        block, pos, pinNodes, sbe.getSubcktName(), 0));
                subcktDefs.putIfAbsent(sbe.getSubcktName(), sbe.getSubcktDef());
                List<com.circuitsim.subcircuit.SubcircuitChip.DeviceMapEntry> dm =
                        com.circuitsim.subcircuit.SubcircuitChip.getDeviceMap(sbe.getChip());
                if (!dm.isEmpty()) subcircuitDevices.put(pos, dm);

            } else if (block instanceof BaseComponentBlock) {
                // GroundBlock is also a BaseComponentBlock but already
                // handled in the union-find pass above as a node anchor.
                // Falling through here used to emit a phantom CircuitComponent
                // with two synthesised nodes that auto-probe picked up,
                // producing ghost v(N) probes in the print line.
                if (block instanceof GroundBlock) continue;
                Direction facing = state.getValue(BaseComponentBlock.FACING);
                int nodeA = resolveNode(pos.relative(facing),               visited, nodeMap, nextNode);
                int nodeB = resolveNode(pos.relative(facing.getOpposite()), visited, nodeMap, nextNode);

                double value = 0;
                String sourceType = "DC";
                double frequency  = 0;
                int    compNum    = 0;
                String valueExpr  = "";
                // Pulse-source-only — see NetlistBuilder pulse branch for the
                // exact slot meanings. For every other component these stay 1.0
                // and are ignored downstream.
                double wSlot = 1.0, lSlot = 1.0, multSlot = 1.0, nfSlot = 1.0;
                double acValueSlot   = 0.0;
                String acValueExprSlot = "";
                // Resistor-only: the modelName carrier slot flags the
                // noiseless toggle (plain resistors have no model otherwise).
                String modelSlot = "";

                if (level.getBlockEntity(pos) instanceof com.circuitsim.blockentity.ComponentBlockEntity be) {
                    value      = be.getValue();
                    sourceType = be.getSourceType();
                    frequency  = be.getFrequency();
                    compNum    = be.getComponentNumber();
                    valueExpr  = be.getValueExpr();
                    if (block instanceof ResistorBlock && be.isRNoiseless()) {
                        modelSlot = "noiseless";
                    }
                    if (block instanceof VoltageSourcePulseBlock) {
                        // Repurpose the otherwise-idle sky130 carrier slots so
                        // we don't need to widen CircuitComponent for pulse:
                        //   wParam    -> V1 (low / initial voltage)
                        //   lParam    -> TR (rise time)
                        //   multParam -> TF (fall time)
                        //   nfParam   -> PW (pulse width / time-high)
                        // value already carries V2; frequency carries PER.
                        wSlot    = be.getPulseVLow();
                        lSlot    = be.getPulseTr();
                        multSlot = be.getPulseTf();
                        nfSlot   = be.getPulsePw();
                    } else if (block instanceof VoltageSourceBlock) {
                        acValueSlot     = be.getAcValue();
                        acValueExprSlot = be.getAcValueExpr();
                    }
                }

                components.add(new NetlistBuilder.CircuitComponent(
                        block, pos, nodeA, nodeB, -1, -1, value, sourceType, frequency,
                        modelSlot, wSlot, lSlot, multSlot, nfSlot, compNum, null, valueExpr,
                        "", "", "", "",
                        acValueSlot, acValueExprSlot));
            }
        }

        // Malformed Param-block lines abort extraction — no netlist may be
        // generated from an invalid declaration set.
        if (!paramErrors.isEmpty()) {
            return new ExtractionResult(false, String.join("; ", paramErrors),
                    Collections.emptyList(), Collections.emptyList(),
                    Collections.emptyList(), Collections.emptyMap(),
                    Collections.emptyList(), Collections.emptyList(),
                    Collections.emptyList());
        }

        // Cross-block sweep check: ParamSpec.parse enforces one sweep per
        // block, but two blocks could each declare one. Catch it here, naming
        // both variables, before any netlist is built.
        String firstSweep = null;
        for (ParametricInfo p : parametricBlocks) {
            ParamSpec.ParseResult pr = ParamSpec.parse(p.varName + " = " + p.valuesString);
            boolean isSweep = pr.ok() && !pr.entries.isEmpty()
                    && pr.entries.get(0).isSweep && pr.entries.get(0).values.size() > 1;
            if (!isSweep) continue;
            if (firstSweep != null) {
                return new ExtractionResult(false,
                        "Only ONE variable may be swept at a time: '" + firstSweep
                                + "' and '" + p.varName + "' both sweep.",
                        Collections.emptyList(), Collections.emptyList(),
                        Collections.emptyList(), Collections.emptyMap(),
                        Collections.emptyList(), Collections.emptyList(),
                        Collections.emptyList());
            }
            firstSweep = p.varName;
        }

        // ── alias resolution ─────────────────────────────────────────────────
        // Voltage-probe labels become ngspice net names. The first label set on
        // a given node wins for that node (putIfAbsent below).
        //
        // Crucially, the SAME alias may be assigned to SEVERAL distinct nodes:
        // because two ngspice references that share a node name ARE the same
        // node, emitting the same alias for two different nodes electrically
        // shorts them together in the netlist. That is how giving two
        // physically-separate nets (bridged only by a SimLink, which carries
        // BFS but does not union nodes) the same probe name makes the
        // simulation treat them as one net — "connected but not physically".
        // Previously a duplicate label collided and the second node fell back
        // to its bare integer id (e.g. node 3), leaving the nets unmerged.
        Map<Integer, String> nodeAliases = new HashMap<>();
        for (int i = 0; i < probes.size(); i++) {
            NetlistBuilder.ProbeInfo p = probes.get(i);
            String userLabel = probeUserLabels.get(i);
            if (p.node == 0 || userLabel == null) continue;
            String alias = NetlistBuilder.sanitizeNodeName(userLabel);
            if (alias.isEmpty()) continue;
            nodeAliases.putIfAbsent(p.node, alias);
        }

        // Re-emit probes with resolved netName so downstream code can lookup
        // ngspice values without recomputing aliases.
        List<NetlistBuilder.ProbeInfo> aliasedProbes = new ArrayList<>(probes.size());
        for (NetlistBuilder.ProbeInfo p : probes) {
            String netName = nodeAliases.getOrDefault(p.node, Integer.toString(p.node));
            aliasedProbes.add(new NetlistBuilder.ProbeInfo(p.node, p.label, netName, p.noPlot));
        }

        return new ExtractionResult(true, "", components, aliasedProbes, currentProbes, probeLabels, parametricBlocks, userCommands, userPlots,
                new ArrayList<>(subcktDefs.values()))
                .withSubcircuitDevices(subcircuitDevices);
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
        String displayName  = m.group(1).trim();
        String unitOverride = m.group(2);
        String rawExpr      = m.group(3);
        // When the user omits "= EXPR" they're expected to have defined the
        // variable themselves with a `let`. The UserPlot then carries a null
        // expression — appendUserPlotLets() skips it during netlist build,
        // but the rest of the pipeline (print line, series extraction) still
        // treats it as a column to read from ngspice output.
        String expr = rawExpr == null ? null : rawExpr.trim();
        if (expr != null && expr.isEmpty()) expr = null;
        String safeName = NetlistBuilder.sanitizeNodeName(displayName);
        if (safeName.isEmpty()) return null;
        if (usedNames.contains(safeName)) return null;
        usedNames.add(safeName);
        String unit = unitOverride != null ? unitOverride.trim()
                : (expr != null ? detectPlotUnit(expr) : "");
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

    /**
     * Resolves the five (or seven) pin nodes of an amplifier instance in the
     * canonical SPICE subcircuit pin order:
     * {@code vinp vinn vcc vee vout [off1 off2]}. Each pin's node comes from
     * the pin cell itself — because pin cells are unioned with their adjacent
     * outward wire during the union phase, this yields the wire's node.
     */
    private int[] resolveAmpPinNodes(Level level, BlockPos anchor, Direction facing,
                                      boolean offsetEnabled, boolean mirrored,
                                      Set<BlockPos> visited,
                                      Map<BlockPos, Integer> nodeMap, int[] nextNode) {
        // The subcircuit definition's pin order is fixed (VINP, VINN, VCC,
        // VEE, VOUT [, OFF1, OFF2]). We just need to find which world cell
        // *currently* holds each kind — when mirrored that's the row-flipped
        // position, which localPosOf(kind, true) already accounts for.
        AmplifierBlock.CellKind[] order = offsetEnabled
                ? new AmplifierBlock.CellKind[]{
                        AmplifierBlock.CellKind.VINP, AmplifierBlock.CellKind.VINN,
                        AmplifierBlock.CellKind.VCC,  AmplifierBlock.CellKind.VEE,
                        AmplifierBlock.CellKind.VOUT,
                        AmplifierBlock.CellKind.OFF1, AmplifierBlock.CellKind.OFF2}
                : new AmplifierBlock.CellKind[]{
                        AmplifierBlock.CellKind.VINP, AmplifierBlock.CellKind.VINN,
                        AmplifierBlock.CellKind.VCC,  AmplifierBlock.CellKind.VEE,
                        AmplifierBlock.CellKind.VOUT};
        int[] nodes = new int[order.length];
        for (int i = 0; i < order.length; i++) {
            int[] local = AmplifierBlock.localPosOf(order[i], mirrored);
            BlockPos pinPos = AmplifierBlock.cellAt(anchor, local[0], local[1], facing);
            nodes[i] = visited.contains(pinPos)
                    ? resolveNode(pinPos, visited, nodeMap, nextNode)
                    : nextNode[0]++;
        }
        return nodes;
    }

    /**
     * Resolves the live pin nodes of a {@link SubcircuitBlock} instance, in
     * physical pin order (pin 1 → {@code nodes[0]}, …). Unlike the amplifier,
     * each pin's node is read from the wire <i>adjacent to that pin face</i> —
     * NOT from the cell — so the two pins sharing a corner cell stay
     * electrically independent. A pin with no adjacent wire gets a fresh
     * (floating) node.
     */
    private int[] resolveSubcircuitPinNodes(BlockPos anchor, Direction facing, int pinCount,
                                            Set<BlockPos> visited,
                                            Map<BlockPos, Integer> nodeMap, int[] nextNode) {
        int[] nodes = new int[pinCount];
        for (int i = 0; i < pinCount; i++) {
            SubcircuitBlock.Pin pin = SubcircuitBlock.PINS[i];
            BlockPos cell = SubcircuitBlock.cellAt(anchor, pin.col(), pin.row(), facing);
            Direction outward = SubcircuitBlock.rotateDir(pin.outward(), facing);
            BlockPos wirePos = cell.relative(outward);
            nodes[i] = visited.contains(wirePos)
                    ? resolveNode(wirePos, visited, nodeMap, nextNode)
                    : nextNode[0]++;
        }
        return nodes;
    }

    /**
     * Returns the node id of one specific pin cell of a Controlled2x3Block
     * instance whose anchor is {@code anchor}. If the pin cell is not in the
     * visited set (shouldn't normally happen — the instance is atomic), a
     * fresh node id is allocated.
     */
    private int pinNodeOf(Level level, BlockPos anchor, Direction facing,
                          Controlled2x3Block.CellKind kind,
                          Set<BlockPos> visited, Map<BlockPos, Integer> nodeMap,
                          int[] nextNode) {
        int[] local = Controlled2x3Block.localPosOf(kind);
        if (local == null) return nextNode[0]++;
        BlockPos pinPos = Controlled2x3Block.cellAt(anchor, local[0], local[1], facing);
        return visited.contains(pinPos)
                ? resolveNode(pinPos, visited, nodeMap, nextNode)
                : nextNode[0]++;
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
                || block instanceof VoltageSourcePulseBlock
                || block instanceof CurrentSourceBlock
                || block instanceof BehavioralVoltageSourceBlock
                || block instanceof BehavioralCurrentSourceBlock
                || block instanceof DiodeBlock
                || block instanceof WireBlock
                || block instanceof GroundBlock
                || block instanceof ProbeBlock
                || block instanceof CurrentProbeBlock
                || block instanceof LoopProbeBlock
                || block instanceof SimulateBlock
                || block instanceof ParametricBlock
                || block instanceof CommandsBlock
                || block instanceof AmplifierBlock
                || block instanceof SubcircuitBlock
                || block instanceof DiscreteNmosBlock
                || block instanceof DiscretePmosBlock
                || block instanceof DiscreteNpnBlock
                || block instanceof DiscretePnpBlock
                || block instanceof Controlled2x3Block
                || block instanceof CcvsBlock
                || block instanceof CccsBlock
                || block instanceof VSwitchBlock
                || block instanceof SimLinkBlock;
    }

    public static class ParametricInfo {
        public final BlockPos pos;
        /** Variable name the block declares (e.g. {@code "Rs"}). */
        public final String   varName;
        /** Raw values string (single value, comma list, or {@code start:stop:step} range). */
        public final String   valuesString;

        public ParametricInfo(BlockPos pos, String varName, String valuesString) {
            this.pos          = pos;
            this.varName      = varName;
            this.valuesString = valuesString;
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
        /**
         * Full {@code .subckt … .ends} definition text for every distinct
         * user-defined subcircuit instantiated in this circuit (one per unique
         * subckt name). Spliced into the netlist deck at simulation time. Empty
         * when no {@link com.circuitsim.block.SubcircuitBlock} carries a chip.
         */
        public final List<String>                            subcktDefs;
        /**
         * Per user-subcircuit-instance internal device map: anchor block pos →
         * the chip's stored {@link com.circuitsim.subcircuit.SubcircuitChip.DeviceMapEntry}
         * list. Powers the floating mini-circuit OP projection. Mutable + carried
         * forward by {@link #withSubcircuitDevices} so the derived extractions
         * built during parametric/DC sweeps keep it without a constructor change.
         */
        public Map<BlockPos, List<com.circuitsim.subcircuit.SubcircuitChip.DeviceMapEntry>>
                subcircuitDevices = new java.util.HashMap<>();

        /**
         * Position of the Simulate block encountered during the BFS, or null
         * when the circuit has none (or extraction failed early). Lets features
         * anchored on other blocks — the Commands block's measurement Test —
         * reuse the analysis configuration stored in the Simulate block's BE.
         * Mutable + set by {@link CircuitExtractor#extract} like
         * {@link #subcircuitDevices}, to avoid another constructor change.
         */
        public BlockPos simulatePos = null;

        /** Sets {@link #subcircuitDevices} and returns {@code this} for chaining. */
        public ExtractionResult withSubcircuitDevices(
                Map<BlockPos, List<com.circuitsim.subcircuit.SubcircuitChip.DeviceMapEntry>> m) {
            this.subcircuitDevices = m == null ? new java.util.HashMap<>() : m;
            return this;
        }

        public ExtractionResult(boolean success, String errorMessage,
                                List<NetlistBuilder.CircuitComponent> components,
                                List<NetlistBuilder.ProbeInfo>        probes,
                                List<NetlistBuilder.CurrentProbeInfo> currentProbes,
                                Map<Integer, String>                  probeLabels,
                                List<ParametricInfo>                  parametricBlocks,
                                List<String>                          userCommands,
                                List<NetlistBuilder.UserPlot>         userPlots) {
            this(success, errorMessage, components, probes, currentProbes, probeLabels,
                    parametricBlocks, userCommands, userPlots, Collections.emptyList());
        }

        public ExtractionResult(boolean success, String errorMessage,
                                List<NetlistBuilder.CircuitComponent> components,
                                List<NetlistBuilder.ProbeInfo>        probes,
                                List<NetlistBuilder.CurrentProbeInfo> currentProbes,
                                Map<Integer, String>                  probeLabels,
                                List<ParametricInfo>                  parametricBlocks,
                                List<String>                          userCommands,
                                List<NetlistBuilder.UserPlot>         userPlots,
                                List<String>                          subcktDefs) {
            this.success          = success;
            this.errorMessage     = errorMessage;
            this.components       = components;
            this.probes           = probes;
            this.currentProbes    = currentProbes;
            this.probeLabels      = probeLabels;
            this.parametricBlocks = parametricBlocks;
            this.userCommands     = userCommands;
            this.userPlots        = userPlots;
            this.subcktDefs       = subcktDefs == null ? Collections.emptyList() : subcktDefs;
        }
    }
}