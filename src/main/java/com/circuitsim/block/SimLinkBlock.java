package com.circuitsim.block;

import net.minecraft.world.level.block.Block;

/**
 * Simulation-only link. Pure traversal marker: the circuit extractor's BFS
 * walks across this block so two physically-disconnected sub-circuits end up
 * in the same netlist (use case: a CCVS/CCCS in one region whose controlling
 * voltage source lives in a different region the player doesn't want wired
 * together). Does NOT union nodes (unlike a wire) and does NOT emit a netlist
 * line. Wires don't visually connect to it either — only BFS does.
 */
public class SimLinkBlock extends Block {
    public SimLinkBlock(Properties properties) {
        super(properties);
    }
}
