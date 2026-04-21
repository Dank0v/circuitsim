package com.circuitsim.screen;

import com.circuitsim.init.ModMenuTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

public class ParametricEditMenu extends AbstractContainerMenu {

    private final BlockPos blockPos;

    /** Client-side factory constructor (no pos). */
    public ParametricEditMenu(int containerId, Inventory playerInventory) {
        super(ModMenuTypes.PARAMETRIC_EDIT.get(), containerId);
        this.blockPos = BlockPos.ZERO;
    }

    /** Server-side constructor carrying the real pos. */
    public ParametricEditMenu(int containerId, Inventory playerInventory, BlockPos pos) {
        super(ModMenuTypes.PARAMETRIC_EDIT.get(), containerId);
        this.blockPos = pos;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }

    @Override
    public boolean stillValid(Player player) { return true; }

    public BlockPos getBlockPos() { return blockPos; }
}