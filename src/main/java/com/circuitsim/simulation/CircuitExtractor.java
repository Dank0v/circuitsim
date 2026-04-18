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
                    Collections.emptyList(), Collections.emptyMap());
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
        Map<Integer, String> probeLabels = new HashMap<>();

        for (BlockPos pos : visited) {
            Block block = level.getBlockState(pos).getBlock();
            BlockState state = level.getBlockState(pos);

            if (block instanceof ProbeBlock) {
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
                // Current probe sits in series — front and back faces are its two terminals
                Direction facing = state.getValue(BaseComponentBlock.FACING);
                BlockPos frontPos = pos.relative(facing);
                BlockPos backPos = pos.relative(facing.getOpposite());

                int nodeA = resolveNode(frontPos, visited, nodeMap, nextNode);
                int nodeB = resolveNode(backPos, visited, nodeMap, nextNode);

                String label = "IProbe_" + pos.toShortString();
                if (level.getBlockEntity(pos) instanceof com.circuitsim.blockentity.ComponentBlockEntity be) {
                    String beLabel = be.getProbeLabel();
                    if (beLabel != null && !beLabel.isEmpty()) label = beLabel;
                }

                currentProbes.add(new NetlistBuilder.CurrentProbeInfo(nodeA, nodeB, label));

            } else if (block instanceof DiodeBlock) {
                Direction facing = state.getValue(BaseComponentBlock.FACING);
                int nodeA = resolveNode(pos.relative(facing), visited, nodeMap, nextNode);
                int nodeB = resolveNode(pos.relative(facing.getOpposite()), visited, nodeMap, nextNode);
                components.add(new NetlistBuilder.CircuitComponent(block, pos, nodeA, nodeB, 0, "DC", 0));

            } else if (block instanceof BaseComponentBlock) {
                Direction facing = state.getValue(BaseComponentBlock.FACING);
                int nodeA = resolveNode(pos.relative(facing), visited, nodeMap, nextNode);
                int nodeB = resolveNode(pos.relative(facing.getOpposite()), visited, nodeMap, nextNode);

                double value = 0;
                String sourceType = "DC";
                double frequency = 0;

                if (level.getBlockEntity(pos) instanceof com.circuitsim.blockentity.ComponentBlockEntity be) {
                    value = be.getValue();
                    sourceType = be.getSourceType();
                    frequency = be.getFrequency();
                }

                components.add(new NetlistBuilder.CircuitComponent(
                        block, pos, nodeA, nodeB, value, sourceType, frequency));
            }
        }

        return new ExtractionResult(true, "", components, probes, currentProbes, probeLabels);
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
                || block instanceof CapacitorBlock
                || block instanceof InductorBlock
                || block instanceof VoltageSourceBlock
                || block instanceof CurrentSourceBlock
                || block instanceof DiodeBlock
                || block instanceof WireBlock
                || block instanceof GroundBlock
                || block instanceof ProbeBlock
                || block instanceof CurrentProbeBlock
                || block instanceof SimulateBlock;
    }

    public static class ExtractionResult {
        public final boolean success;
        public final String errorMessage;
        public final List<NetlistBuilder.CircuitComponent> components;
        public final List<NetlistBuilder.ProbeInfo> probes;
        public final List<NetlistBuilder.CurrentProbeInfo> currentProbes;
        public final Map<Integer, String> probeLabels;

        public ExtractionResult(boolean success, String errorMessage,
                                List<NetlistBuilder.CircuitComponent> components,
                                List<NetlistBuilder.ProbeInfo> probes,
                                List<NetlistBuilder.CurrentProbeInfo> currentProbes,
                                Map<Integer, String> probeLabels) {
            this.success = success;
            this.errorMessage = errorMessage;
            this.components = components;
            this.probes = probes;
            this.currentProbes = currentProbes;
            this.probeLabels = probeLabels;
        }
    }
}