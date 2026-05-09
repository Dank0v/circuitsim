package com.circuitsim.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

public class WireBlock extends Block {

    public static final BooleanProperty NORTH = BooleanProperty.create("north");
    public static final BooleanProperty SOUTH = BooleanProperty.create("south");
    public static final BooleanProperty EAST  = BooleanProperty.create("east");
    public static final BooleanProperty WEST  = BooleanProperty.create("west");

    public WireBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
            .setValue(NORTH, true).setValue(SOUTH, true)
            .setValue(EAST,  true).setValue(WEST,  true));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(NORTH, SOUTH, EAST, WEST);
    }

    // -------------------------------------------------------------------------
    // Placement and neighbour-driven state updates
    // -------------------------------------------------------------------------

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return computeState(ctx.getLevel(), ctx.getClickedPos());
    }

    @Override
    public BlockState updateShape(BlockState state, Direction dir, BlockState neighborState,
                                  net.minecraft.world.level.LevelAccessor level,
                                  BlockPos pos, BlockPos neighborPos) {
        if (dir.getAxis() == Direction.Axis.Y) return state;
        return computeState(level, pos);
    }

    @Override
    public void neighborChanged(BlockState state, Level level, BlockPos pos,
                                Block sourceBlock, BlockPos fromPos, boolean isMoving) {
        if (level.isClientSide) return;
        BlockState newState = computeState(level, pos);
        if (!newState.equals(state)) {
            level.setBlock(pos, newState, 3);
        }
    }

    // -------------------------------------------------------------------------
    // State computation
    // -------------------------------------------------------------------------

    private BlockState computeState(LevelReader level, BlockPos pos) {
        boolean n = canConnect(level, pos, Direction.NORTH);
        boolean s = canConnect(level, pos, Direction.SOUTH);
        boolean e = canConnect(level, pos, Direction.EAST);
        boolean w = canConnect(level, pos, Direction.WEST);

        int count = (n ? 1 : 0) + (s ? 1 : 0) + (e ? 1 : 0) + (w ? 1 : 0);

        // 0, 1, or 4 connections → show the full cross
        if (count == 0 || count == 1 || count == 4) {
            return defaultBlockState()
                .setValue(NORTH, true).setValue(SOUTH, true)
                .setValue(EAST,  true).setValue(WEST,  true);
        }

        return defaultBlockState()
            .setValue(NORTH, n).setValue(SOUTH, s)
            .setValue(EAST,  e).setValue(WEST,  w);
    }

    private boolean canConnect(LevelReader level, BlockPos pos, Direction dir) {
        BlockPos neighborPos = pos.relative(dir);
        BlockState neighborState = level.getBlockState(neighborPos);
        Block neighbor = neighborState.getBlock();

        // Wire-to-wire: always connect horizontally
        if (neighbor instanceof WireBlock) return true;

        // Ground node: always connect
        if (neighbor instanceof GroundBlock) return true;

        // Non-directional circuit blocks: connect from any horizontal side
        if (neighbor instanceof SimulateBlock
                || neighbor instanceof ProbeBlock
                || neighbor instanceof CurrentProbeBlock
                || neighbor instanceof ParametricBlock) {
            return true;
        }

        // Directional component blocks: connect only at valid pin faces
        if (neighbor instanceof BaseComponentBlock) {
            Direction facing = neighborState.getValue(BaseComponentBlock.FACING);
            // The direction pointing from this neighbour back toward the wire
            Direction toWire = dir.getOpposite();

            // Front pin (block faces toward wire) or back pin (block faces away)
            if (facing == toWire || facing.getOpposite() == toWire) return true;

            // IC resistor has an additional right-side (bulk) pin
            if (neighbor instanceof IcResistorBlock && facing.getClockWise() == toWire) return true;

            // 4-pin MOSFETs: also connect on left (gate) and right (bulk) sides
            if ((neighbor instanceof IcNmos4Block || neighbor instanceof IcPmos4Block)) {
                if (facing.getClockWise() == toWire) return true;        // right = bulk
                if (facing.getCounterClockWise() == toWire) return true; // left  = gate
            }
        }

        return false;
    }
}
