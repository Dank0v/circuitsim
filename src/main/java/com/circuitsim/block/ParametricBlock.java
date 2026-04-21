package com.circuitsim.block;

import com.circuitsim.blockentity.ComponentBlockEntity;
import com.circuitsim.client.ClientSetup;
import com.circuitsim.screen.ParametricEditMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

public class ParametricBlock extends BaseComponentBlock {

    public ParametricBlock(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos,
                                  Player player, InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) {
            ClientSetup.setLastInteractedPos(pos);
            return InteractionResult.SUCCESS;
        }
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof ComponentBlockEntity) {
            player.openMenu(new SimpleMenuProvider(
                    (id, inv, p) -> new ParametricEditMenu(id, inv, pos),
                    Component.literal("Parametric Analysis")
            ));
        }
        return InteractionResult.CONSUME;
    }
}