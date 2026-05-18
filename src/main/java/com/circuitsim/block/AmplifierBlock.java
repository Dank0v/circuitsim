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
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;

import javax.annotation.Nullable;

/**
 * Op-amp / amplifier subcircuit instance. Placed as a 5×5 footprint on the
 * horizontal grid. Each of the 25 cells is its own AmplifierBlock with a
 * {@link CellKind} blockstate distinguishing the anchor, body, and the 5
 * (or 7) pin cells. Only the ANCHOR cell carries a {@link ComponentBlockEntity}
 * with the configuration (model name, X-index, offset-pin toggle).
 *
 * <p>Layout uses local (col, row) coordinates 0..4, with anchor at (0,0).
 * Pin positions, default (FACING=NORTH):
 * <ul>
 *   <li>VINP at (0,1), outward WEST — non-inverting input
 *   <li>VINN at (0,3), outward WEST — inverting input
 *   <li>VCC  at (1,0), outward NORTH — positive supply
 *   <li>VEE  at (1,4), outward SOUTH — negative supply
 *   <li>VOUT at (4,2), outward EAST  — output
 *   <li>OFF1 at (3,0), outward NORTH — offset null pin 1 (only when offset enabled)
 *   <li>OFF2 at (3,4), outward SOUTH — offset null pin 2 (only when offset enabled)
 * </ul>
 * Cells rotate with FACING. Pin "outward" directions rotate accordingly.
 */
public class AmplifierBlock extends Block implements EntityBlock {

    public enum CellKind implements StringRepresentable {
        ANCHOR("anchor"),
        BODY("body"),
        VINP("vinp"),
        VINN("vinn"),
        VCC("vcc"),
        VEE("vee"),
        VOUT("vout"),
        OFF1("off1"),
        OFF2("off2");

        private final String name;
        CellKind(String name) { this.name = name; }
        @Override public String getSerializedName() { return name; }

        /** True iff this cell is a wire-connecting pin (not anchor and not body). */
        public boolean isPin() {
            return this != ANCHOR && this != BODY;
        }

        /** Default outward direction (in FACING=NORTH local frame). */
        public Direction defaultOutward() {
            return switch (this) {
                case VINP, VINN -> Direction.WEST;
                case VCC, OFF1  -> Direction.NORTH;
                case VEE, OFF2  -> Direction.SOUTH;
                case VOUT       -> Direction.EAST;
                default         -> Direction.NORTH;
            };
        }
    }

    public static final EnumProperty<CellKind> CELL_KIND = EnumProperty.create("cell_kind", CellKind.class);
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    /** Local column (0..4) within the 5×5 footprint. Driven by placement. */
    public static final IntegerProperty LOCAL_X = IntegerProperty.create("local_x", 0, 4);
    /** Local row (0..4) within the 5×5 footprint. Driven by placement. */
    public static final IntegerProperty LOCAL_Z = IntegerProperty.create("local_z", 0, 4);
    /**
     * Whether the amp is in 7-pin (offset) mode. Selects the top-face texture
     * variant for every cell in the 5×5; stamped on every cell so the model
     * lookup is uniform.
     */
    public static final BooleanProperty OFFSET_PIN = BooleanProperty.create("offset_pin");

    /** Guards against recursive break propagation when removing all 25 cells. */
    private static final ThreadLocal<Boolean> BREAKING = ThreadLocal.withInitial(() -> Boolean.FALSE);

    public AmplifierBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(CELL_KIND, CellKind.BODY)
                .setValue(LOCAL_X, 0)
                .setValue(LOCAL_Z, 0)
                .setValue(OFFSET_PIN, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, CELL_KIND, LOCAL_X, LOCAL_Z, OFFSET_PIN);
    }

    // -------------------------------------------------------------------------
    // Local ↔ world coordinate transform
    // -------------------------------------------------------------------------

    /**
     * Computes the world-space (dx, dz) delta from the anchor for a cell at
     * local {@code (col, row)} given the amp's facing. Default FACING=NORTH
     * uses col→+X, row→+Z; rotations follow horizontal CW order
     * (NORTH→EAST→SOUTH→WEST).
     */
    public static int[] worldDelta(int col, int row, Direction facing) {
        return switch (facing) {
            case NORTH -> new int[]{ col,  row};
            case EAST  -> new int[]{-row,  col};
            case SOUTH -> new int[]{-col, -row};
            case WEST  -> new int[]{ row, -col};
            default    -> new int[]{ col,  row};
        };
    }

    /** Rotates a direction vector that's expressed in default (NORTH) local space. */
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

    /** Local (col, row) of each pin kind. Anchor is (0,0). */
    public static int[] localPosOf(CellKind kind) {
        return switch (kind) {
            case ANCHOR -> new int[]{0, 0};
            case VINP   -> new int[]{0, 1};
            case VINN   -> new int[]{0, 3};
            case VCC    -> new int[]{1, 0};
            case OFF1   -> new int[]{3, 0};
            case VEE    -> new int[]{1, 4};
            case OFF2   -> new int[]{3, 4};
            case VOUT   -> new int[]{4, 2};
            default     -> null; // BODY has many possible positions
        };
    }

    /** What kind should the cell at local (col, row) be, given the offset-pin toggle? */
    public static CellKind kindFor(int col, int row, boolean offsetEnabled) {
        if (col == 0 && row == 0) return CellKind.ANCHOR;
        if (col == 0 && row == 1) return CellKind.VINP;
        if (col == 0 && row == 3) return CellKind.VINN;
        if (col == 1 && row == 0) return CellKind.VCC;
        if (col == 1 && row == 4) return CellKind.VEE;
        if (col == 4 && row == 2) return CellKind.VOUT;
        if (col == 3 && row == 0) return offsetEnabled ? CellKind.OFF1 : CellKind.BODY;
        if (col == 3 && row == 4) return offsetEnabled ? CellKind.OFF2 : CellKind.BODY;
        return CellKind.BODY;
    }

    // -------------------------------------------------------------------------
    // Anchor lookup — every cell can find its 5×5 group's anchor
    // -------------------------------------------------------------------------

    /**
     * Walks the world from {@code cellPos} to the anchor of this amp. Returns
     * {@code null} if the cell is not part of an amp or the structure is
     * corrupted.
     *
     * <p>For pin kinds the offset is exact. For BODY cells we iterate the 25
     * possible local positions a body cell might occupy and pick the
     * candidate whose anchor matches.
     */
    @Nullable
    public static BlockPos findAnchor(LevelAccessor level, BlockPos cellPos) {
        BlockState state = level.getBlockState(cellPos);
        if (!(state.getBlock() instanceof AmplifierBlock)) return null;
        return findAnchorFromState(level, cellPos, state);
    }

    /**
     * Like {@link #findAnchor} but accepts the cell's state explicitly. Used
     * from {@link #onRemove}, where {@code level.getBlockState(cellPos)} has
     * already returned AIR by the time we run.
     */
    @Nullable
    public static BlockPos findAnchorFromState(LevelAccessor level, BlockPos cellPos, BlockState state) {
        if (!state.hasProperty(FACING) || !state.hasProperty(LOCAL_X) || !state.hasProperty(LOCAL_Z)) {
            return null;
        }
        Direction facing = state.getValue(FACING);
        int col = state.getValue(LOCAL_X);
        int row = state.getValue(LOCAL_Z);
        return subtractDelta(cellPos, col, row, facing);
    }

    private static BlockPos subtractDelta(BlockPos cellPos, int col, int row, Direction facing) {
        int[] delta = worldDelta(col, row, facing);
        return cellPos.offset(-delta[0], 0, -delta[1]);
    }

    /** World position of a cell at local (col, row) given anchor + facing. */
    public static BlockPos cellAt(BlockPos anchor, int col, int row, Direction facing) {
        int[] delta = worldDelta(col, row, facing);
        return anchor.offset(delta[0], 0, delta[1]);
    }

    // -------------------------------------------------------------------------
    // Placement
    // -------------------------------------------------------------------------

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        // Match BaseComponentBlock convention: FACING = direction the player is
        // looking, so the schematic's "top" (local row 0) points the same way.
        Direction facing = context.getHorizontalDirection();
        BlockPos clicked = context.getClickedPos();
        // Click position becomes the center of the 5×5 (local cell (2,2)).
        BlockPos anchor = subtractDelta(clicked, 2, 2, facing);

        // Reject placement if any of the 25 target cells is occupied (the
        // clicked cell itself is allowed — it's where the item will be placed).
        for (int col = 0; col < 5; col++) {
            for (int row = 0; row < 5; row++) {
                BlockPos target = cellAt(anchor, col, row, facing);
                if (target.equals(clicked)) continue;
                BlockState existing = context.getLevel().getBlockState(target);
                if (!existing.canBeReplaced()) return null;
            }
        }

        // Place a BODY cell at the click position; setPlacedBy fills in the other 24.
        // The click position is local (2,2), the centre of the 5×5 footprint.
        return defaultBlockState()
                .setValue(FACING, facing)
                .setValue(CELL_KIND, CellKind.BODY)
                .setValue(LOCAL_X, 2)
                .setValue(LOCAL_Z, 2)
                .setValue(OFFSET_PIN, false);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state,
                            @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide) return;

        Direction facing = state.getValue(FACING);
        BlockPos anchor = subtractDelta(pos, 2, 2, facing);

        // Use the breaking guard so the temporary block replacements below don't
        // each cascade through onRemove and try to break the whole structure.
        BREAKING.set(Boolean.TRUE);
        try {
            for (int col = 0; col < 5; col++) {
                for (int row = 0; row < 5; row++) {
                    BlockPos target = cellAt(anchor, col, row, facing);
                    CellKind kind = kindFor(col, row, /*offsetEnabled=*/false);
                    BlockState cellState = defaultBlockState()
                            .setValue(FACING, facing)
                            .setValue(CELL_KIND, kind)
                            .setValue(LOCAL_X, col)
                            .setValue(LOCAL_Z, row)
                            .setValue(OFFSET_PIN, false);
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
                ? new ComponentBlockEntity(pos, state)
                : null;
    }

    // -------------------------------------------------------------------------
    // Atomic break — destroying ANY cell removes all 25
    // -------------------------------------------------------------------------

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!state.is(newState.getBlock()) && !BREAKING.get()) {
            // Use the old state explicitly: level.getBlockState(pos) is already
            // the replacement (typically AIR) at the time onRemove runs.
            BlockPos anchor = findAnchorFromState(level, pos, state);
            Direction facing = state.getValue(FACING);
            if (anchor != null) {
                BREAKING.set(Boolean.TRUE);
                try {
                    for (int col = 0; col < 5; col++) {
                        for (int row = 0; row < 5; row++) {
                            BlockPos target = cellAt(anchor, col, row, facing);
                            if (target.equals(pos)) continue; // current cell will be handled by super.onRemove
                            BlockState other = level.getBlockState(target);
                            if (other.getBlock() instanceof AmplifierBlock) {
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
                    new com.circuitsim.screen.AmplifierEditScreen(anchor));
        }
        return InteractionResult.SUCCESS;
    }

    // -------------------------------------------------------------------------
    // Helpers for the update packet — toggles the OFF1/OFF2 cells in the world
    // -------------------------------------------------------------------------

    /**
     * Applies the offset-pin toggle to the entire 5×5:
     * <ul>
     *   <li>The (3,0) and (3,4) cells flip between OFF1/OFF2 (pin) and BODY,
     *       which the wire/extractor code uses to decide pin participation.
     *   <li>Every cell's {@code OFFSET_PIN} state flips, which selects the
     *       5pin vs 7pin top-texture model variant — that's what makes the
     *       whole top face change appearance.
     * </ul>
     */
    public static void applyOffsetToggle(Level level, BlockPos anchor, Direction facing, boolean offsetEnabled) {
        BREAKING.set(Boolean.TRUE);
        try {
            for (int col = 0; col < 5; col++) {
                for (int row = 0; row < 5; row++) {
                    BlockPos cellPos = cellAt(anchor, col, row, facing);
                    BlockState cur = level.getBlockState(cellPos);
                    if (!(cur.getBlock() instanceof AmplifierBlock)) continue;
                    BlockState next = cur.setValue(OFFSET_PIN, offsetEnabled);
                    if ((col == 3 && row == 0) || (col == 3 && row == 4)) {
                        next = next.setValue(CELL_KIND, kindFor(col, row, offsetEnabled));
                    }
                    if (!next.equals(cur)) {
                        level.setBlock(cellPos, next, Block.UPDATE_CLIENTS);
                    }
                }
            }
        } finally {
            BREAKING.set(Boolean.FALSE);
        }
    }
}
