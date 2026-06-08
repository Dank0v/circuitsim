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

        // Wire-to-wire: always connect
        if (neighbor instanceof WireBlock) return true;

        // Simulate block: connect from any horizontal side
        if (neighbor instanceof SimulateBlock) return true;

        // Amplifier: pin cells connect only on their outward face; body and
        // anchor cells never connect to wires.
        if (neighbor instanceof AmplifierBlock) {
            AmplifierBlock.CellKind kind = neighborState.getValue(AmplifierBlock.CELL_KIND);
            if (!kind.isPin()) return false;
            Direction facing = neighborState.getValue(AmplifierBlock.FACING);
            Direction outward = AmplifierBlock.rotateDir(kind.defaultOutward(), facing);
            // dir = from this wire toward the neighbor; outward = from the
            // neighbor pin toward where its wire should be. They match when
            // outward == -dir.
            return outward == dir.getOpposite();
        }

        // Discrete 3-pin NMOS: front (drain), back (source), counter-clockwise (gate).
        // Clockwise face is insulated.
        if (neighbor instanceof DiscreteNmosBlock) {
            Direction facing = neighborState.getValue(DiscreteNmosBlock.FACING);
            Direction toWire = dir.getOpposite();
            return facing == toWire
                    || facing.getOpposite() == toWire
                    || facing.getCounterClockWise() == toWire;
        }

        // Discrete 3-pin PMOS: same connectable sides as NMOS — front, back,
        // and counter-clockwise — only the pin semantics differ.
        if (neighbor instanceof DiscretePmosBlock) {
            Direction facing = neighborState.getValue(DiscretePmosBlock.FACING);
            Direction toWire = dir.getOpposite();
            return facing == toWire
                    || facing.getOpposite() == toWire
                    || facing.getCounterClockWise() == toWire;
        }

        // Discrete 3-pin NPN: front (collector), back (emitter),
        // counter-clockwise (base). Clockwise face is insulated.
        if (neighbor instanceof DiscreteNpnBlock) {
            Direction facing = neighborState.getValue(DiscreteNpnBlock.FACING);
            Direction toWire = dir.getOpposite();
            return facing == toWire
                    || facing.getOpposite() == toWire
                    || facing.getCounterClockWise() == toWire;
        }

        // Discrete 3-pin PNP: same connectable sides as NPN — front, back,
        // and counter-clockwise — only the pin semantics differ.
        if (neighbor instanceof DiscretePnpBlock) {
            Direction facing = neighborState.getValue(DiscretePnpBlock.FACING);
            Direction toWire = dir.getOpposite();
            return facing == toWire
                    || facing.getOpposite() == toWire
                    || facing.getCounterClockWise() == toWire;
        }

        // VCVS / VCCS 2×3 multi-block: same rule as the amplifier.
        if (neighbor instanceof Controlled2x3Block) {
            Controlled2x3Block.CellKind kind = neighborState.getValue(Controlled2x3Block.CELL_KIND);
            if (!kind.isPin()) return false;
            Direction facing = neighborState.getValue(Controlled2x3Block.FACING);
            Direction outward = Controlled2x3Block.rotateDir(kind.defaultOutward(), facing);
            return outward == dir.getOpposite();
        }

        // All remaining connectable blocks are directional (have FACING)
        if (neighbor instanceof BaseComponentBlock) {
            Direction facing = neighborState.getValue(BaseComponentBlock.FACING);
            // Direction pointing from the neighbour back toward this wire
            Direction toWire = dir.getOpposite();

            // Parametric block: never connects to wires
            if (neighbor instanceof ParametricBlock) return false;

            // Ground: front only
            if (neighbor instanceof GroundBlock) return facing == toWire;

            // Probe: front only
            if (neighbor instanceof ProbeBlock) return facing == toWire;

            // Current probe: front and back
            if (neighbor instanceof CurrentProbeBlock) {
                return facing == toWire || facing.getOpposite() == toWire;
            }

            // IC resistor: front, back, and right (bulk)
            if (neighbor instanceof IcResistorBlock) {
                return facing == toWire
                        || facing.getOpposite() == toWire
                        || facing.getClockWise() == toWire;
            }

            // 4-pin MOSFETs: front, back, left (gate), right (bulk)
            if (neighbor instanceof IcNmos4Block || neighbor instanceof IcPmos4Block) {
                return facing == toWire
                        || facing.getOpposite() == toWire
                        || facing.getClockWise() == toWire
                        || facing.getCounterClockWise() == toWire;
            }

            // All other directional components (resistor, capacitor, voltage source, etc.): front and back
            return facing == toWire || facing.getOpposite() == toWire;
        }

        return false;
    }
}
