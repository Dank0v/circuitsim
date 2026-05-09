package com.circuitsim.simulation;

import com.circuitsim.block.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

public class CircuitExtractor {

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
        List<NetlistBuilder.CurrentProbeInfo> currentProbes = new ArrayList<>();
        List<ParametricInfo> parametricBlocks = new ArrayList<>();
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
                parametricBlocks.add(new ParametricInfo(pos, targetPos, sweepString));

            } else if (block instanceof ProbeBlock) {
                Direction facing = state.getValue(BaseComponentBlock.FACING);
                BlockPos probeTarget = pos.relative(facing);
                int node = resolveNode(probeTarget, visited, nodeMap, nextNode);

                String label = "Probe_" + pos.toShortString();
                if (level.getBlockEntity(pos) instanceof com.circuitsim.blockentity.ComponentBlockEntity be) {
                    String beLabel = be.getProbeLabel();
                    if (beLabel != null && !beLabel.isEmpty()) label = beLabel;
                }

                probes.add(new NetlistBuilder.ProbeInfo(node, label));
                probeLabels.put(node, label);

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
                components.add(new NetlistBuilder.CircuitComponent(block, pos, nodeA, nodeB, 0, "DC", 0));

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

                if (level.getBlockEntity(pos) instanceof com.circuitsim.blockentity.ComponentBlockEntity be) {
                    modelName = be.getModelName();
                    wParam    = be.getWParam();
                    lParam    = be.getLParam();
                    multParam = be.getMultParam();
                }

                components.add(new NetlistBuilder.CircuitComponent(
                        block, pos, nodeA, nodeB, nodeC, 0, "DC", 0,
                        modelName, wParam, lParam, multParam));

            } else if (block instanceof IcCapacitorBlock) {
                Direction facing = state.getValue(BaseComponentBlock.FACING);
                int nodeA = resolveNode(pos.relative(facing),               visited, nodeMap, nextNode);
                int nodeB = resolveNode(pos.relative(facing.getOpposite()), visited, nodeMap, nextNode);

                String modelName = "";
                double wParam    = 1.0;
                double lParam    = 1.0;
                double multParam = 1.0;

                if (level.getBlockEntity(pos) instanceof com.circuitsim.blockentity.ComponentBlockEntity be) {
                    modelName = be.getModelName();
                    wParam    = be.getWParam();
                    lParam    = be.getLParam();
                    multParam = be.getMultParam();
                }

                components.add(new NetlistBuilder.CircuitComponent(
                        block, pos, nodeA, nodeB, 0, "DC", 0,
                        modelName, wParam, lParam, multParam));

            } else if (block instanceof BaseComponentBlock) {
                Direction facing = state.getValue(BaseComponentBlock.FACING);
                int nodeA = resolveNode(pos.relative(facing),               visited, nodeMap, nextNode);
                int nodeB = resolveNode(pos.relative(facing.getOpposite()), visited, nodeMap, nextNode);

                double value = 0;
                String sourceType = "DC";
                double frequency  = 0;

                if (level.getBlockEntity(pos) instanceof com.circuitsim.blockentity.ComponentBlockEntity be) {
                    value      = be.getValue();
                    sourceType = be.getSourceType();
                    frequency  = be.getFrequency();
                }

                components.add(new NetlistBuilder.CircuitComponent(
                        block, pos, nodeA, nodeB, value, sourceType, frequency));
            }
        }

        return new ExtractionResult(true, "", components, probes, currentProbes, probeLabels, parametricBlocks);
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
                || block instanceof ParametricBlock;
    }

    public static class ParametricInfo {
        public final BlockPos pos;
        public final BlockPos targetPos;
        public final String   sweepString;

        public ParametricInfo(BlockPos pos, BlockPos targetPos, String sweepString) {
            this.pos         = pos;
            this.targetPos   = targetPos;
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

        public ExtractionResult(boolean success, String errorMessage,
                                List<NetlistBuilder.CircuitComponent> components,
                                List<NetlistBuilder.ProbeInfo>        probes,
                                List<NetlistBuilder.CurrentProbeInfo> currentProbes,
                                Map<Integer, String>                  probeLabels,
                                List<ParametricInfo>                  parametricBlocks) {
            this.success          = success;
            this.errorMessage     = errorMessage;
            this.components       = components;
            this.probes           = probes;
            this.currentProbes    = currentProbes;
            this.probeLabels      = probeLabels;
            this.parametricBlocks = parametricBlocks;
        }
    }
}