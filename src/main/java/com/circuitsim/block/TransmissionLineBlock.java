package com.circuitsim.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Transmission line (lossless T element or lossy O/LTRA element). 2×3
 * multi-block reusing the {@link Controlled2x3Block} footprint: port 1 pins
 * on the west side (CTRL_P = port1 +, CTRL_N = port1 −), port 2 pins on the
 * east side (OUT_P = port2 +, OUT_N = port2 −).
 *
 * <p>Two modes, selected in the edit screen and carried in the BE's
 * modelName slot ({@code ""}/{@code "lossless"} vs {@code "ltra"}):
 * <pre>
 *   T1 p1+ p1- p2+ p2- Z0=50 TD=10NS            (lossless)
 *   O1 p1+ p1- p2+ p2- LTRAMOD1                 (lossy)
 *   .model LTRAMOD1 LTRA(R=... L=... G=... C=... LEN=...)
 * </pre>
 * Slot mapping (lossless | lossy): value = Z0 | R, wParam = TD | L,
 * lParam = F | G, multParam = NL | C, nfParam = — | LEN (all with the
 * matching expression slots for Parametric sweeps).
 */
public class TransmissionLineBlock extends Controlled2x3Block {

    /** modelName value selecting the lossy O/LTRA emission. */
    public static final String MODE_LTRA = "ltra";

    public TransmissionLineBlock(Properties properties) {
        super(properties);
    }

    @Override public String displayName() { return "Transmission Line"; }
    @Override public String valueLabel()  { return "Z0 (Ω)"; }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                  InteractionHand hand, BlockHitResult hit) {
        BlockPos anchor = findAnchor(level, pos);
        if (anchor == null) return InteractionResult.PASS;

        if (level.isClientSide) {
            net.minecraft.client.Minecraft.getInstance().setScreen(
                    new com.circuitsim.screen.TransmissionLineEditScreen(anchor));
        }
        return InteractionResult.SUCCESS;
    }
}
