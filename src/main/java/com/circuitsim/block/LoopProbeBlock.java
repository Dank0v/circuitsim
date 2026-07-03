package com.circuitsim.block;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * A Loop Probe: a 2-pin, in-series marker dropped into a feedback loop. In every
 * normal analysis (OP/AC/DC/TRAN/NOISE) it is a transparent 0 V short, so it
 * doesn't perturb the circuit. The {@code .STB} (stability) analysis detects it,
 * breaks the loop at that point, and injects the Tian dual-injection sources to
 * measure loop gain, phase margin and gain margin. It has no editable
 * parameters, so right-clicking just shows a usage hint instead of an edit menu.
 */
public class LoopProbeBlock extends BaseComponentBlock {

    public LoopProbeBlock(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                  InteractionHand hand, BlockHitResult hit) {
        if (!level.isClientSide) {
            player.displayClientMessage(Component.literal(
                    "Loop Probe — place in series in the feedback loop, front face toward the amplifier"
                    + " output, then run .STB. If the loop gain looks inverted, rotate it 180°."),
                    true);
        }
        return InteractionResult.SUCCESS;
    }
}
