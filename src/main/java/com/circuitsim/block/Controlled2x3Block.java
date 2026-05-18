package com.circuitsim.block;

import com.circuitsim.blockentity.ComponentBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
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

import javax.annotation.Nullable;

/**
 * Voltage-controlled controlled-source instance (VCVS or VCCS). 2×3 horizontal
 * footprint: 2 columns × 3 rows. One top texture (64×96, i.e. 32px per cell)
 * is split across the 6 cells via per-cell UV submodels.
 *
 * <p>Layout, FACING=NORTH (col→+X, row→+Z), anchor at (0,1) (left middle):
 * <pre>
 *   col 0 (west)        col 1 (east)
 *   (0,0) CTRL_P        (1,0) OUT_P    &lt;- row 0
 *   (0,1) ANCHOR        (1,1) BODY     &lt;- row 1
 *   (0,2) CTRL_N        (1,2) OUT_N    &lt;- row 2
 * </pre>
 * Pin outward directions (NORTH frame):
 * <ul>
 *   <li>CTRL_P at (0,0) — outward WEST  (control + input)
 *   <li>CTRL_N at (0,2) — outward WEST  (control − input)
 *   <li>OUT_P  at (1,0) — outward EAST  (output +)
 *   <li>OUT_N  at (1,2) — outward EAST  (output −)
 * </ul>
 * Only the ANCHOR cell carries a {@link ComponentBlockEntity} (gain/transconductance
 * value and netlist index).
 */
public abstract class Controlled2x3Block extends Block implements EntityBlock {

    public enum CellKind implements StringRepresentable {
        ANCHOR("anchor"),
        BODY("body"),
        CTRL_P("ctrl_p"),
        CTRL_N("ctrl_n"),
        OUT_P("out_p"),
        OUT_N("out_n");

        private final String name;
        CellKind(String name) { this.name = name; }
        @Override public String getSerializedName() { return name; }

        /** True iff this cell wires up to an external node. */
        public boolean isPin() {
            return this == CTRL_P || this == CTRL_N || this == OUT_P || this == OUT_N;
        }

        /** Default outward direction (in FACING=NORTH local frame). */
        public Direction defaultOutward() {
            return switch (this) {
                case CTRL_P, CTRL_N -> Direction.WEST;
                case OUT_P,  OUT_N  -> Direction.EAST;
                default             -> Direction.NORTH;
            };
        }
    }

    public static final EnumProperty<CellKind> CELL_KIND = EnumProperty.create("cell_kind", CellKind.class);
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    /** Local column (0..1) within the 2×3 footprint. */
    public static final IntegerProperty LOCAL_X = IntegerProperty.create("local_x", 0, 1);
    /** Local row (0..2) within the 2×3 footprint. */
    public static final IntegerProperty LOCAL_Z = IntegerProperty.create("local_z", 0, 2);

    public static final int COLS = 2;
    public static final int ROWS = 3;

    /** Cell coords for each pin kind (and the anchor). */
    private static final int[] LOC_ANCHOR = {0, 1};
    private static final int[] LOC_CTRL_P = {0, 0};
    private static final int[] LOC_CTRL_N = {0, 2};
    private static final int[] LOC_OUT_P  = {1, 0};
    private static final int[] LOC_OUT_N  = {1, 2};

    /** Click position becomes this local cell. */
    private static final int CLICK_COL = 0;
    private static final int CLICK_ROW = 1;

    /** Guards against recursive break propagation when removing all 6 cells. */
    private static final ThreadLocal<Boolean> BREAKING = ThreadLocal.withInitial(() -> Boolean.FALSE);

    protected Controlled2x3Block(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(CELL_KIND, CellKind.BODY)
                .setValue(LOCAL_X, 0)
                .setValue(LOCAL_Z, 0));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, CELL_KIND, LOCAL_X, LOCAL_Z);
    }

    // -------------------------------------------------------------------------
    // Local ↔ world coordinate transform (same convention as AmplifierBlock)
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

    public static int[] localPosOf(CellKind kind) {
        return switch (kind) {
            case ANCHOR -> LOC_ANCHOR;
            case CTRL_P -> LOC_CTRL_P;
            case CTRL_N -> LOC_CTRL_N;
            case OUT_P  -> LOC_OUT_P;
            case OUT_N  -> LOC_OUT_N;
            default     -> null;
        };
    }

    public static CellKind kindFor(int col, int row) {
        if (col == LOC_ANCHOR[0] && row == LOC_ANCHOR[1]) return CellKind.ANCHOR;
        if (col == LOC_CTRL_P[0] && row == LOC_CTRL_P[1]) return CellKind.CTRL_P;
        if (col == LOC_CTRL_N[0] && row == LOC_CTRL_N[1]) return CellKind.CTRL_N;
        if (col == LOC_OUT_P[0]  && row == LOC_OUT_P[1])  return CellKind.OUT_P;
        if (col == LOC_OUT_N[0]  && row == LOC_OUT_N[1])  return CellKind.OUT_N;
        return CellKind.BODY;
    }

    // -------------------------------------------------------------------------
    // Anchor lookup
    // -------------------------------------------------------------------------

    @Nullable
    public static BlockPos findAnchor(LevelAccessor level, BlockPos cellPos) {
        BlockState state = level.getBlockState(cellPos);
        if (!(state.getBlock() instanceof Controlled2x3Block)) return null;
        return findAnchorFromState(cellPos, state);
    }

    @Nullable
    public static BlockPos findAnchorFromState(BlockPos cellPos, BlockState state) {
        if (!state.hasProperty(FACING) || !state.hasProperty(LOCAL_X) || !state.hasProperty(LOCAL_Z)) {
            return null;
        }
        Direction facing = state.getValue(FACING);
        int col = state.getValue(LOCAL_X);
        int row = state.getValue(LOCAL_Z);
        // anchor is at (LOC_ANCHOR[0], LOC_ANCHOR[1]); cell at (col, row) is at
        // anchor + delta(col-LOC_ANCHOR[0], row-LOC_ANCHOR[1]).
        int[] delta = worldDelta(col - LOC_ANCHOR[0], row - LOC_ANCHOR[1], facing);
        return cellPos.offset(-delta[0], 0, -delta[1]);
    }

    public static BlockPos cellAt(BlockPos anchor, int col, int row, Direction facing) {
        int[] delta = worldDelta(col - LOC_ANCHOR[0], row - LOC_ANCHOR[1], facing);
        return anchor.offset(delta[0], 0, delta[1]);
    }

    // -------------------------------------------------------------------------
    // Placement
    // -------------------------------------------------------------------------

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction facing = context.getHorizontalDirection();
        BlockPos clicked = context.getClickedPos();
        // Click position becomes local (CLICK_COL, CLICK_ROW). Compute anchor.
        int[] clickDelta = worldDelta(CLICK_COL - LOC_ANCHOR[0], CLICK_ROW - LOC_ANCHOR[1], facing);
        BlockPos anchor  = clicked.offset(-clickDelta[0], 0, -clickDelta[1]);

        for (int col = 0; col < COLS; col++) {
            for (int row = 0; row < ROWS; row++) {
                BlockPos target = cellAt(anchor, col, row, facing);
                if (target.equals(clicked)) continue;
                BlockState existing = context.getLevel().getBlockState(target);
                if (!existing.canBeReplaced()) return null;
            }
        }

        // Place a tentative state at the click position; setPlacedBy fills in the rest.
        return defaultBlockState()
                .setValue(FACING, facing)
                .setValue(CELL_KIND, kindFor(CLICK_COL, CLICK_ROW))
                .setValue(LOCAL_X, CLICK_COL)
                .setValue(LOCAL_Z, CLICK_ROW);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state,
                            @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide) return;

        Direction facing = state.getValue(FACING);
        int[] clickDelta = worldDelta(CLICK_COL - LOC_ANCHOR[0], CLICK_ROW - LOC_ANCHOR[1], facing);
        BlockPos anchor  = pos.offset(-clickDelta[0], 0, -clickDelta[1]);

        BREAKING.set(Boolean.TRUE);
        try {
            for (int col = 0; col < COLS; col++) {
                for (int row = 0; row < ROWS; row++) {
                    BlockPos target = cellAt(anchor, col, row, facing);
                    CellKind kind = kindFor(col, row);
                    BlockState cellState = defaultBlockState()
                            .setValue(FACING, facing)
                            .setValue(CELL_KIND, kind)
                            .setValue(LOCAL_X, col)
                            .setValue(LOCAL_Z, row);
                    level.setBlock(target, cellState, Block.UPDATE_CLIENTS);
                }
            }
        } finally {
            BREAKING.set(Boolean.FALSE);
        }
    }

    // -------------------------------------------------------------------------
    // BlockEntity — only the ANCHOR cell carries one
    // -------------------------------------------------------------------------

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return state.getValue(CELL_KIND) == CellKind.ANCHOR
                ? new ComponentBlockEntity(pos, state)
                : null;
    }

    // -------------------------------------------------------------------------
    // Atomic break — destroying ANY cell removes all 6
    // -------------------------------------------------------------------------

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!state.is(newState.getBlock()) && !BREAKING.get()) {
            BlockPos anchor = findAnchorFromState(pos, state);
            Direction facing = state.getValue(FACING);
            if (anchor != null) {
                BREAKING.set(Boolean.TRUE);
                try {
                    for (int col = 0; col < COLS; col++) {
                        for (int row = 0; row < ROWS; row++) {
                            BlockPos target = cellAt(anchor, col, row, facing);
                            if (target.equals(pos)) continue;
                            BlockState other = level.getBlockState(target);
                            if (other.getBlock() instanceof Controlled2x3Block) {
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
    // Right-click → edit screen
    // -------------------------------------------------------------------------

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                  InteractionHand hand, BlockHitResult hit) {
        BlockPos anchor = findAnchor(level, pos);
        if (anchor == null) return InteractionResult.PASS;

        if (level.isClientSide) {
            net.minecraft.client.Minecraft.getInstance().setScreen(
                    new com.circuitsim.screen.ControlledSourceEditScreen(anchor, displayName(), valueLabel()));
        }
        return InteractionResult.SUCCESS;
    }

    /** Human-readable name shown in the edit dialog title. */
    public abstract String displayName();

    /** Label for the "value" field — e.g. "Voltage gain" or "Transconductance (S)". */
    public abstract String valueLabel();
}
