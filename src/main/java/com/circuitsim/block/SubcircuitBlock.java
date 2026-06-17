package com.circuitsim.block;

import com.circuitsim.blockentity.SubcircuitBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.network.NetworkHooks;

import javax.annotation.Nullable;

/**
 * A user-defined subcircuit instance. A 5×5 footprint (like the Amplifier) whose
 * anchor cell carries a {@link SubcircuitBlockEntity} holding an inserted
 * Subcircuit Chip. Exposes 12 pins around its perimeter — two on each corner
 * cell (on its two outer faces) and one in the middle of each edge — numbered
 * clockwise from the top-left corner's top face. Only the first <i>N</i> pins
 * (where <i>N</i> is the loaded subcircuit's terminal count) are electrically
 * live; the rest are inert.
 *
 * <p>Reuses the Amplifier's local↔world coordinate machinery. The anchor is the
 * top-left corner (local 0,0); placement centres the footprint on the clicked
 * block (local 2,2). Pin membership is computed positionally from {@link #PINS},
 * independent of {@link CellKind} (which only marks the anchor).
 */
public class SubcircuitBlock extends Block implements EntityBlock {

    public enum CellKind implements StringRepresentable {
        ANCHOR("anchor"),
        BODY("body");

        private final String name;
        CellKind(String name) { this.name = name; }
        @Override public String getSerializedName() { return name; }
    }

    public static final EnumProperty<CellKind> CELL_KIND = EnumProperty.create("cell_kind", CellKind.class);
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final IntegerProperty LOCAL_X = IntegerProperty.create("local_x", 0, 4);
    public static final IntegerProperty LOCAL_Z = IntegerProperty.create("local_z", 0, 4);
    /**
     * Pin count of the inserted chip (0 = empty). Stamped on every one of the 25
     * cells so the blockstate can pick the matching slice of the 5×5 top texture
     * variant (subcircuit_top_0 … subcircuit_top_12). Re-stamped by
     * {@link #applyPinCount} whenever the chip changes.
     */
    public static final IntegerProperty PIN_COUNT = IntegerProperty.create("pins", 0, 12);

    /**
     * The 12 pins, in clockwise order from the top-left corner's top face. Each
     * entry is {col, row} plus a local (FACING=NORTH) outward direction. Pin
     * number = index + 1.
     */
    public record Pin(int col, int row, Direction outward) {}

    public static final Pin[] PINS = {
            new Pin(0, 0, Direction.NORTH), // 1
            new Pin(2, 0, Direction.NORTH), // 2
            new Pin(4, 0, Direction.NORTH), // 3
            new Pin(4, 0, Direction.EAST),  // 4
            new Pin(4, 2, Direction.EAST),  // 5
            new Pin(4, 4, Direction.EAST),  // 6
            new Pin(4, 4, Direction.SOUTH), // 7
            new Pin(2, 4, Direction.SOUTH), // 8
            new Pin(0, 4, Direction.SOUTH), // 9
            new Pin(0, 4, Direction.WEST),  // 10
            new Pin(0, 2, Direction.WEST),  // 11
            new Pin(0, 0, Direction.WEST),  // 12
    };

    private static final ThreadLocal<Boolean> BREAKING = ThreadLocal.withInitial(() -> Boolean.FALSE);

    public SubcircuitBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(CELL_KIND, CellKind.BODY)
                .setValue(LOCAL_X, 0)
                .setValue(LOCAL_Z, 0)
                .setValue(PIN_COUNT, 0));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, CELL_KIND, LOCAL_X, LOCAL_Z, PIN_COUNT);
    }

    // -------------------------------------------------------------------------
    // Local ↔ world transform (same convention as AmplifierBlock)
    // -------------------------------------------------------------------------

    public static int[] worldDelta(int col, int row, Direction facing) {
        return switch (facing) {
            case NORTH -> new int[]{ col,  row};
            case EAST  -> new int[]{-row,  col};
            case SOUTH -> new int[]{-col, -row};
            case WEST  -> new int[]{ row, -col};
            default    -> new int[]{ col,  row};
        };
    }

    public static Direction rotateDir(Direction localDir, Direction facing) {
        int rot = switch (facing) {
            case NORTH -> 0;
            case EAST  -> 1;
            case SOUTH -> 2;
            case WEST  -> 3;
            default    -> 0;
        };
        Direction d = localDir;
        for (int i = 0; i < rot; i++) d = d.getClockWise();
        return d;
    }

    public static BlockPos cellAt(BlockPos anchor, int col, int row, Direction facing) {
        int[] delta = worldDelta(col, row, facing);
        return anchor.offset(delta[0], 0, delta[1]);
    }

    private static BlockPos subtractDelta(BlockPos cellPos, int col, int row, Direction facing) {
        int[] delta = worldDelta(col, row, facing);
        return cellPos.offset(-delta[0], 0, -delta[1]);
    }

    @Nullable
    public static BlockPos findAnchor(BlockGetter level, BlockPos cellPos) {
        BlockState state = level.getBlockState(cellPos);
        if (!(state.getBlock() instanceof SubcircuitBlock)) return null;
        return findAnchorFromState(cellPos, state);
    }

    @Nullable
    public static BlockPos findAnchorFromState(BlockPos cellPos, BlockState state) {
        if (!state.hasProperty(FACING) || !state.hasProperty(LOCAL_X) || !state.hasProperty(LOCAL_Z)) {
            return null;
        }
        return subtractDelta(cellPos, state.getValue(LOCAL_X), state.getValue(LOCAL_Z), state.getValue(FACING));
    }

    // -------------------------------------------------------------------------
    // Pin geometry helpers
    // -------------------------------------------------------------------------

    /**
     * If the cell at local {@code (col, row)} has a pin on world face
     * {@code worldFace} (given {@code facing}), returns that pin's 1-based index;
     * otherwise 0.
     */
    public static int pinIndexAt(int col, int row, Direction worldFace, Direction facing) {
        for (int i = 0; i < PINS.length; i++) {
            Pin p = PINS[i];
            if (p.col == col && p.row == row && rotateDir(p.outward, facing) == worldFace) {
                return i + 1;
            }
        }
        return 0;
    }

    /** Number of live pins of the instance whose any cell is {@code cellPos} (0 if no chip). */
    public static int activePinCount(BlockGetter level, BlockPos cellPos) {
        BlockPos anchor = findAnchor(level, cellPos);
        if (anchor == null) return 0;
        if (level.getBlockEntity(anchor) instanceof SubcircuitBlockEntity be) {
            return be.getActivePinCount();
        }
        return 0;
    }

    /**
     * Stamps {@code pinCount} (clamped 0..12) into the {@link #PINS} property of
     * all 25 cells so the blockstate selects the matching top-texture variant.
     * Same-block setBlock, so it never triggers the break cascade and the anchor
     * block entity (and its chip) is preserved.
     */
    public static void applyPinCount(Level level, BlockPos anchor, Direction facing, int pinCount) {
        int p = Math.max(0, Math.min(12, pinCount));
        for (int col = 0; col < 5; col++) {
            for (int row = 0; row < 5; row++) {
                BlockPos cellPos = cellAt(anchor, col, row, facing);
                BlockState cur = level.getBlockState(cellPos);
                if (!(cur.getBlock() instanceof SubcircuitBlock)) continue;
                if (cur.getValue(PIN_COUNT) != p) {
                    level.setBlock(cellPos, cur.setValue(PIN_COUNT, p), Block.UPDATE_CLIENTS);
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Placement
    // -------------------------------------------------------------------------

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction facing = context.getHorizontalDirection();
        BlockPos clicked = context.getClickedPos();
        BlockPos anchor = subtractDelta(clicked, 2, 2, facing);

        for (int col = 0; col < 5; col++) {
            for (int row = 0; row < 5; row++) {
                BlockPos target = cellAt(anchor, col, row, facing);
                if (target.equals(clicked)) continue;
                if (!context.getLevel().getBlockState(target).canBeReplaced()) return null;
            }
        }

        return defaultBlockState()
                .setValue(FACING, facing)
                .setValue(CELL_KIND, CellKind.BODY)
                .setValue(LOCAL_X, 2)
                .setValue(LOCAL_Z, 2)
                .setValue(PIN_COUNT, 0);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state,
                            @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide) return;

        Direction facing = state.getValue(FACING);
        BlockPos anchor = subtractDelta(pos, 2, 2, facing);

        BREAKING.set(Boolean.TRUE);
        try {
            for (int col = 0; col < 5; col++) {
                for (int row = 0; row < 5; row++) {
                    BlockPos target = cellAt(anchor, col, row, facing);
                    CellKind kind = (col == 0 && row == 0) ? CellKind.ANCHOR : CellKind.BODY;
                    BlockState cellState = defaultBlockState()
                            .setValue(FACING, facing)
                            .setValue(CELL_KIND, kind)
                            .setValue(LOCAL_X, col)
                            .setValue(LOCAL_Z, row)
                            .setValue(PIN_COUNT, 0);
                    level.setBlock(target, cellState, Block.UPDATE_CLIENTS);
                }
            }
        } finally {
            BREAKING.set(Boolean.FALSE);
        }
    }

    // -------------------------------------------------------------------------
    // BlockEntity (only the ANCHOR cell carries one)
    // -------------------------------------------------------------------------

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return state.getValue(CELL_KIND) == CellKind.ANCHOR
                ? new SubcircuitBlockEntity(pos, state)
                : null;
    }

    // -------------------------------------------------------------------------
    // Atomic break — destroying ANY cell removes all 25 and drops the chip
    // -------------------------------------------------------------------------

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!state.is(newState.getBlock()) && !BREAKING.get()) {
            BlockPos anchor = findAnchorFromState(pos, state);
            Direction facing = state.getValue(FACING);
            if (anchor != null) {
                // Drop the chip (held by the anchor BE) before tearing down.
                if (level.getBlockEntity(anchor) instanceof SubcircuitBlockEntity be) {
                    net.minecraft.world.Containers.dropContents(level, anchor, be);
                }
                BREAKING.set(Boolean.TRUE);
                try {
                    for (int col = 0; col < 5; col++) {
                        for (int row = 0; row < 5; row++) {
                            BlockPos target = cellAt(anchor, col, row, facing);
                            if (target.equals(pos)) continue;
                            if (level.getBlockState(target).getBlock() instanceof SubcircuitBlock) {
                                level.setBlock(target, Blocks.AIR.defaultBlockState(),
                                        Block.UPDATE_CLIENTS | Block.UPDATE_NEIGHBORS);
                            }
                        }
                    }
                } finally {
                    BREAKING.set(Boolean.FALSE);
                }
            }
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }

    // -------------------------------------------------------------------------
    // Right-click → chip menu
    // -------------------------------------------------------------------------

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                  InteractionHand hand, BlockHitResult hit) {
        BlockPos anchor = findAnchor(level, pos);
        if (anchor == null) return InteractionResult.PASS;
        if (!level.isClientSide && player instanceof ServerPlayer sp
                && level.getBlockEntity(anchor) instanceof SubcircuitBlockEntity be) {
            NetworkHooks.openScreen(sp, be, anchor);
        }
        return InteractionResult.SUCCESS;
    }
}
