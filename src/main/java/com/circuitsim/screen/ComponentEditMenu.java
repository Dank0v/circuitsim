package com.circuitsim.screen;

import com.circuitsim.init.ModMenuTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

public class ComponentEditMenu extends AbstractContainerMenu {

    private BlockPos blockPos;

    /**
     * Client-side constructor used by MenuType factory.
     * Matches MenuType.MenuSupplier&lt;ComponentEditMenu&gt; signature: (int, Inventory) -&gt; T
     */
    public ComponentEditMenu(int containerId, Inventory playerInventory) {
        super(ModMenuTypes.COMPONENT_EDIT.get(), containerId);
        this.blockPos = BlockPos.ZERO;
    }

    /**
     * Server-side constructor used by MenuProvider.createMenu().
     * Carries the actual BlockPos of the block entity being edited.
     */
    public ComponentEditMenu(int containerId, Inventory playerInventory, BlockPos pos) {
        super(ModMenuTypes.COMPONENT_EDIT.get(), containerId);
        this.blockPos = pos;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    public BlockPos getBlockPos() {
        return blockPos;
    }

    public void setBlockPos(BlockPos pos) {
        this.blockPos = pos;
    }
}