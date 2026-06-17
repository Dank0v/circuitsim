package com.circuitsim.blockentity;

import com.circuitsim.block.SubcircuitBlock;
import com.circuitsim.init.ModBlockEntities;
import com.circuitsim.item.SubcircuitItem;
import com.circuitsim.subcircuit.SubcircuitChip;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Anchor block entity of a {@link SubcircuitBlock}. Holds the single inserted
 * Subcircuit Chip and exposes its subcircuit data (name / netlist / pin count)
 * to the extractor and the GUI. Implements {@link Container} (1 slot) and
 * {@link MenuProvider} so it can back a slot-bearing menu.
 */
public class SubcircuitBlockEntity extends BlockEntity implements Container, MenuProvider {

    private final NonNullList<ItemStack> items = NonNullList.withSize(1, ItemStack.EMPTY);

    public SubcircuitBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.SUBCIRCUIT_BE.get(), pos, state);
    }

    // -------------------------------------------------------------------------
    // Subcircuit data accessors (derived from the inserted chip)
    // -------------------------------------------------------------------------

    public ItemStack getChip()        { return items.get(0); }
    public boolean hasChip()          { return SubcircuitChip.isPresent(items.get(0)); }
    public String getSubcktName()     { return SubcircuitChip.getName(items.get(0)); }
    public String getSubcktDef()      { return SubcircuitChip.getDef(items.get(0)); }
    public List<String> getPins()     { return SubcircuitChip.getPins(items.get(0)); }

    /** Live pin count (0..12), clamped to the block's physical pin budget. */
    public int getActivePinCount() {
        return Math.min(12, SubcircuitChip.getPinCount(items.get(0)));
    }

    // -------------------------------------------------------------------------
    // Change propagation — relink pin wires + sync to clients
    // -------------------------------------------------------------------------

    private void contentsChanged() {
        setChanged();
        if (level == null || level.isClientSide) return;
        if (!(getBlockState().getBlock() instanceof SubcircuitBlock)) return;
        Direction facing = getBlockState().getValue(SubcircuitBlock.FACING);
        // Update the visible pin-count texture across all 25 cells.
        SubcircuitBlock.applyPinCount(level, worldPosition, facing, getActivePinCount());
        level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        // Relink the adjacent wires (their connectivity depends on the live pin count).
        for (SubcircuitBlock.Pin p : SubcircuitBlock.PINS) {
            BlockPos cell = SubcircuitBlock.cellAt(worldPosition, p.col(), p.row(), facing);
            level.updateNeighborsAt(cell, getBlockState().getBlock());
        }
    }

    // -------------------------------------------------------------------------
    // Container (single chip slot)
    // -------------------------------------------------------------------------

    @Override public int getContainerSize() { return 1; }

    @Override
    public boolean isEmpty() {
        return items.get(0).isEmpty();
    }

    @Override public ItemStack getItem(int slot) { return items.get(slot); }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        ItemStack result = ContainerHelper.removeItem(items, slot, amount);
        if (!result.isEmpty()) contentsChanged();
        return result;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        ItemStack result = ContainerHelper.takeItem(items, slot);
        contentsChanged();
        return result;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        items.set(slot, stack);
        if (stack.getCount() > getMaxStackSize()) stack.setCount(getMaxStackSize());
        contentsChanged();
    }

    @Override public int getMaxStackSize() { return 1; }

    @Override
    public boolean stillValid(Player player) {
        if (level == null || level.getBlockEntity(worldPosition) != this) return false;
        return player.distanceToSqr(worldPosition.getX() + 0.5,
                worldPosition.getY() + 0.5, worldPosition.getZ() + 0.5) <= 64.0;
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return stack.getItem() instanceof SubcircuitItem;
    }

    @Override
    public void clearContent() {
        items.clear();
        contentsChanged();
    }

    // -------------------------------------------------------------------------
    // MenuProvider
    // -------------------------------------------------------------------------

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.circuitsim.subcircuit");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inv, Player player) {
        return new com.circuitsim.screen.SubcircuitMenu(id, inv, this);
    }

    // -------------------------------------------------------------------------
    // NBT
    // -------------------------------------------------------------------------

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        ContainerHelper.saveAllItems(tag, items);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        items.clear();
        ContainerHelper.loadAllItems(tag, items);
    }

    @Override
    public CompoundTag getUpdateTag() {
        CompoundTag tag = super.getUpdateTag();
        ContainerHelper.saveAllItems(tag, items);
        return tag;
    }

    @Override
    public void handleUpdateTag(CompoundTag tag) {
        load(tag);
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
