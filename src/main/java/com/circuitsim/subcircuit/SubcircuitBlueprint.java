package com.circuitsim.subcircuit;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Serializes a set of in-world blocks (a schematic) into an NBT "blueprint" and
 * restores them elsewhere. Each block records its position relative to the
 * bounding-box minimum corner, its full {@link BlockState} (via
 * {@link NbtUtils#writeBlockState}), and its block-entity data (without
 * position/id metadata). Used by the Subcircuit Converter (capture) and the
 * Subcircuit Chip item (restore).
 *
 * <p>Multiblocks (e.g. the Amplifier's 25 cells) survive a round trip because
 * every cell is an independent block carrying its own complete blockstate, and
 * the anchor cell's block-entity data is captured alongside it. Restore uses
 * {@link Block#UPDATE_CLIENTS} so it never triggers a multiblock's break
 * cascade, then a second pass issues neighbour updates so wires re-link.
 */
public final class SubcircuitBlueprint {

    private static final String KEY_BLOCKS = "blocks";
    private static final String KEY_DX = "dx";
    private static final String KEY_DY = "dy";
    private static final String KEY_DZ = "dz";
    private static final String KEY_STATE = "state";
    private static final String KEY_BE = "be";

    private SubcircuitBlueprint() {}

    /**
     * Captures the given positions into a blueprint tag. Offsets are stored
     * relative to the minimum (x, y, z) corner of the set, so restore places the
     * whole structure in the +X/+Y/+Z octant from the chosen origin.
     */
    /**
     * The minimum (x, y, z) corner of {@code positions} — the same origin
     * {@link #capture} stores offsets against. Callers that need positions in
     * the blueprint's local frame (e.g. the OP device map) subtract this so
     * their offsets line up with {@link PreviewBlock#dx()} etc.
     */
    public static BlockPos minCorner(Collection<BlockPos> positions) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        for (BlockPos p : positions) {
            minX = Math.min(minX, p.getX());
            minY = Math.min(minY, p.getY());
            minZ = Math.min(minZ, p.getZ());
        }
        return new BlockPos(minX, minY, minZ);
    }

    public static CompoundTag capture(Level level, Collection<BlockPos> positions) {
        BlockPos min = minCorner(positions);
        int minX = min.getX(), minY = min.getY(), minZ = min.getZ();

        ListTag blocks = new ListTag();
        for (BlockPos p : positions) {
            BlockState state = level.getBlockState(p);
            if (state.isAir()) continue;
            CompoundTag b = new CompoundTag();
            b.putInt(KEY_DX, p.getX() - minX);
            b.putInt(KEY_DY, p.getY() - minY);
            b.putInt(KEY_DZ, p.getZ() - minZ);
            b.put(KEY_STATE, NbtUtils.writeBlockState(state));
            BlockEntity be = level.getBlockEntity(p);
            if (be != null) {
                b.put(KEY_BE, be.saveWithoutMetadata());
            }
            blocks.add(b);
        }

        CompoundTag root = new CompoundTag();
        root.put(KEY_BLOCKS, blocks);
        return root;
    }

    /** Number of blocks stored in a blueprint. */
    public static int blockCount(CompoundTag blueprint) {
        if (blueprint == null) return 0;
        return blueprint.getList(KEY_BLOCKS, Tag.TAG_COMPOUND).size();
    }

    /** One block of the top-down preview: grid position + its block state. */
    public record PreviewBlock(int dx, int dy, int dz, BlockState state) {}

    /**
     * Reads every block of the blueprint as a {@link PreviewBlock} (relative
     * position + {@link BlockState}) for the Subcircuit block GUI's 3D preview.
     */
    public static List<PreviewBlock> previewBlocks(CompoundTag blueprint, HolderGetter<Block> blocks) {
        List<PreviewBlock> out = new ArrayList<>();
        if (blueprint == null) return out;
        ListTag list = blueprint.getList(KEY_BLOCKS, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag b = list.getCompound(i);
            BlockState state = NbtUtils.readBlockState(blocks, b.getCompound(KEY_STATE));
            if (state.isAir()) continue;
            out.add(new PreviewBlock(b.getInt(KEY_DX), b.getInt(KEY_DY), b.getInt(KEY_DZ), state));
        }
        return out;
    }

    /**
     * True if every block in the blueprint can be placed at {@code origin}
     * (each target cell is currently replaceable — air, water, grass, etc.).
     */
    public static boolean canPlace(Level level, BlockPos origin, CompoundTag blueprint) {
        if (blueprint == null) return false;
        ListTag blocks = blueprint.getList(KEY_BLOCKS, Tag.TAG_COMPOUND);
        if (blocks.isEmpty()) return false;
        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag b = blocks.getCompound(i);
            BlockPos target = origin.offset(b.getInt(KEY_DX), b.getInt(KEY_DY), b.getInt(KEY_DZ));
            if (!level.getBlockState(target).canBeReplaced()) return false;
        }
        return true;
    }

    /**
     * Restores all blocks of the blueprint with their min corner at
     * {@code origin}. Assumes {@link #canPlace} has already passed. Returns the
     * list of placed positions (so the caller can run extra updates if needed).
     */
    public static List<BlockPos> place(Level level, BlockPos origin, CompoundTag blueprint) {
        List<BlockPos> placed = new ArrayList<>();
        if (blueprint == null) return placed;
        HolderGetter<Block> blocks = level.holderLookup(Registries.BLOCK);
        ListTag list = blueprint.getList(KEY_BLOCKS, Tag.TAG_COMPOUND);

        // First pass: set blockstates + load block-entity data, no neighbour
        // updates (flag 2) so multiblocks don't cascade and wires don't churn.
        for (int i = 0; i < list.size(); i++) {
            CompoundTag b = list.getCompound(i);
            BlockPos target = origin.offset(b.getInt(KEY_DX), b.getInt(KEY_DY), b.getInt(KEY_DZ));
            BlockState state = NbtUtils.readBlockState(blocks, b.getCompound(KEY_STATE));
            level.setBlock(target, state, Block.UPDATE_CLIENTS);
            if (b.contains(KEY_BE, Tag.TAG_COMPOUND)) {
                BlockEntity be = level.getBlockEntity(target);
                if (be != null) {
                    be.load(b.getCompound(KEY_BE));
                    be.setChanged();
                }
            }
            placed.add(target);
        }

        // Second pass: issue full neighbour updates so wires recompute their
        // connection shape and clients get the final state.
        for (BlockPos p : placed) {
            BlockState state = level.getBlockState(p);
            level.sendBlockUpdated(p, state, state, Block.UPDATE_ALL);
            level.updateNeighborsAt(p, state.getBlock());
        }
        return placed;
    }
}
