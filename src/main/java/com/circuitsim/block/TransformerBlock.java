package com.circuitsim.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Coupled inductors / transformer (Kxxxx). 2×3 multi-block reusing the
 * {@link Controlled2x3Block} footprint: primary winding pins on the west side
 * (CTRL_P = pri+, CTRL_N = pri−), secondary winding pins on the east side
 * (OUT_P = sec+, OUT_N = sec−).
 *
 * <p>Netlist: two inductor lines from the shared L index family plus one
 * coupling line, e.g.
 * <pre>
 *   L3 pri+ pri- {Lp}
 *   L4 sec+ sec- {Ls}
 *   K1 L3 L4 {k}
 * </pre>
 * The anchor BE carries k in the value slot, Lp in wParam, Ls in lParam
 * (with the matching expression slots for Parametric sweeps).
 */
public class TransformerBlock extends Controlled2x3Block {

    public TransformerBlock(Properties properties) {
        super(properties);
    }

    @Override public String displayName() { return "Transformer (K)"; }
    @Override public String valueLabel()  { return "Coupling k"; }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                  InteractionHand hand, BlockHitResult hit) {
        BlockPos anchor = findAnchor(level, pos);
        if (anchor == null) return InteractionResult.PASS;

        if (level.isClientSide) {
            net.minecraft.client.Minecraft.getInstance().setScreen(
                    new com.circuitsim.screen.TransformerEditScreen(anchor));
        }
        return InteractionResult.SUCCESS;
    }
}
