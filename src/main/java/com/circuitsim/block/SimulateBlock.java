package com.circuitsim.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The Simulate Block.
 *
 * Right-clicking it no longer runs the simulation directly.  Instead it opens
 * {@link com.circuitsim.screen.SimulateEditScreen} on the client, where the
 * player chooses an analysis type (OP / AC) and its parameters.  When the
 * player clicks "Simulate" the screen sends a
 * {@link com.circuitsim.network.SimulatePacket} to the server, which performs
 * the actual extraction and ngspice call.
 *
 * All simulation logic that previously lived here has been moved to
 * {@link com.circuitsim.network.SimulatePacket#handle}.
 */
public class SimulateBlock extends Block {

    public SimulateBlock(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos,
                                  Player player, InteractionHand hand, BlockHitResult hit) {
        if (!level.isClientSide) return InteractionResult.SUCCESS;

        // Open the analysis-selector screen on the client side
        net.minecraft.client.Minecraft.getInstance().setScreen(
                new com.circuitsim.screen.SimulateEditScreen(pos));

        return InteractionResult.SUCCESS;
    }
}