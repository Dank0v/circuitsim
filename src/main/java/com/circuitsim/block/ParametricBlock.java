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
 * Param block. Right-clicking opens a client-side screen with one multi-line
 * text box; each non-empty line declares a variable as {@code name = value}.
 * Scalars become {@code .param} netlist lines (and substitute into any
 * component slot referencing the name); a {@code start:stop:step} range or
 * comma list makes that variable a parametric sweep — at most one variable
 * may sweep per circuit. The registry key stays {@code "parametric"} so
 * already-saved worlds keep loading. The block extends
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
