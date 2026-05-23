package com.circuitsim.block;

import com.circuitsim.blockentity.ComponentBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Parametric (variable) block. Right-clicking opens a client-side screen where
 * the player sets a variable name and one or more values. At simulation time
 * every component whose value field references that variable gets its value
 * substituted; multiple values trigger a parametric sweep. The block extends
 * {@link BaseComponentBlock} only so it inherits {@code FACING} for visual
 * model rotation — facing is otherwise ignored.
 */
public class ParametricBlock extends BaseComponentBlock {

    public ParametricBlock(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos,
                                  Player player, InteractionHand hand, BlockHitResult hit) {
        if (!level.isClientSide) return InteractionResult.SUCCESS;
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof ComponentBlockEntity) {
            Minecraft.getInstance().setScreen(
                    new com.circuitsim.screen.ParametricEditScreen(pos));
        }
        return InteractionResult.SUCCESS;
    }
}
