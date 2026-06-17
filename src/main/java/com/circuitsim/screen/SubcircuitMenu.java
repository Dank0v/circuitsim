package com.circuitsim.screen;

import com.circuitsim.blockentity.SubcircuitBlockEntity;
import com.circuitsim.init.ModMenuTypes;
import com.circuitsim.item.SubcircuitItem;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;

/**
 * Menu for the {@link com.circuitsim.block.SubcircuitBlock}: one chip-input slot
 * backed by the anchor {@link SubcircuitBlockEntity}, plus the player inventory.
 * The read-only netlist view and the (deferred) render slot are drawn by
 * {@link SubcircuitScreen} from the chip stack itself; they are not real slots.
 */
public class SubcircuitMenu extends AbstractContainerMenu {

    /** GUI position of the chip slot (top-left), matching the screen background. */
    public static final int CHIP_SLOT_X = 16;
    public static final int CHIP_SLOT_Y = 34;

    private final Container chip;
    @Nullable private final SubcircuitBlockEntity be;

    public SubcircuitMenu(int id, Inventory inv, SubcircuitBlockEntity be) {
        super(ModMenuTypes.SUBCIRCUIT.get(), id);
        this.be = be;
        this.chip = be != null ? be : new SimpleContainer(1);
        addChipAndInventory(inv);
    }

    /** Client factory (from network buffer carrying the anchor pos). */
    public SubcircuitMenu(int id, Inventory inv, FriendlyByteBuf buf) {
        super(ModMenuTypes.SUBCIRCUIT.get(), id);
        BlockPos pos = buf.readBlockPos();
        SubcircuitBlockEntity resolved =
                inv.player.level().getBlockEntity(pos) instanceof SubcircuitBlockEntity sbe ? sbe : null;
        this.be = resolved;
        this.chip = resolved != null ? resolved : new SimpleContainer(1);
        addChipAndInventory(inv);
    }

    private void addChipAndInventory(Inventory inv) {
        addSlot(new Slot(chip, 0, CHIP_SLOT_X, CHIP_SLOT_Y) {
            @Override public boolean mayPlace(ItemStack s) { return s.getItem() instanceof SubcircuitItem; }
            @Override public int getMaxStackSize() { return 1; }
        });

        // Player inventory (3×9) + hotbar, below the enlarged top panel.
        int invX = 8, invY = 152;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inv, col + row * 9 + 9, invX + col * 18, invY + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inv, col, invX + col * 18, invY + 58));
        }
    }

    @Nullable
    public SubcircuitBlockEntity getBlockEntity() {
        return be;
    }

    /** The currently inserted chip stack (for the screen's read-only views). */
    public ItemStack getChipStack() {
        return getSlot(0).getItem();
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack result = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot == null || !slot.hasItem()) return result;

        ItemStack stack = slot.getItem();
        result = stack.copy();

        final int chipSlot = 0;
        final int invStart = 1;
        final int invEnd = this.slots.size();

        if (index == chipSlot) {
            // chip -> player inventory
            if (!moveItemStackTo(stack, invStart, invEnd, true)) return ItemStack.EMPTY;
        } else {
            // player inventory -> chip slot (only subcircuit items)
            if (stack.getItem() instanceof SubcircuitItem) {
                if (!moveItemStackTo(stack, chipSlot, chipSlot + 1, false)) return ItemStack.EMPTY;
            } else {
                return ItemStack.EMPTY;
            }
        }

        if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
        else slot.setChanged();
        return result;
    }

    @Override
    public boolean stillValid(Player player) {
        return chip.stillValid(player);
    }
}
