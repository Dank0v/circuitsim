package com.circuitsim.item;

import com.circuitsim.subcircuit.SubcircuitBlueprint;
import com.circuitsim.subcircuit.SubcircuitChip;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.List;

/**
 * A Subcircuit Chip: holds a generated {@code .subckt} netlist, its ordered pin
 * names, and a blueprint of the original schematic. Right-clicking the ground
 * rebuilds the schematic and consumes the chip; the chip can also be inserted
 * into a {@link com.circuitsim.block.SubcircuitBlock} to instantiate it.
 */
public class SubcircuitItem extends Item {

    public SubcircuitItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        ItemStack stack = ctx.getItemInHand();
        if (!SubcircuitChip.isPresent(stack)) return InteractionResult.PASS;

        CompoundTag blueprint = SubcircuitChip.getBlueprint(stack);
        if (blueprint == null || SubcircuitBlueprint.blockCount(blueprint) == 0) {
            return InteractionResult.PASS;
        }

        Level level = ctx.getLevel();
        BlockPos origin = ctx.getClickedPos().relative(ctx.getClickedFace());
        if (level.isClientSide) return InteractionResult.SUCCESS;

        Player player = ctx.getPlayer();
        if (!SubcircuitBlueprint.canPlace(level, origin, blueprint)) {
            if (player != null) {
                player.displayClientMessage(
                        Component.literal("Not enough room to rebuild the subcircuit here.")
                                .withStyle(ChatFormatting.RED), true);
            }
            return InteractionResult.FAIL;
        }

        SubcircuitBlueprint.place(level, origin, blueprint);
        if (player == null || !player.getAbilities().instabuild) stack.shrink(1);
        return InteractionResult.CONSUME;
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level,
                                List<Component> tooltip, TooltipFlag flag) {
        if (!SubcircuitChip.isPresent(stack)) {
            tooltip.add(Component.literal("Empty chip").withStyle(ChatFormatting.DARK_GRAY));
            return;
        }
        tooltip.add(Component.literal("Subcircuit: " + SubcircuitChip.getName(stack))
                .withStyle(ChatFormatting.AQUA));
        List<String> pins = SubcircuitChip.getPins(stack);
        tooltip.add(Component.literal("Pins: " + pins.size() + " (" + String.join(", ", pins) + ")")
                .withStyle(ChatFormatting.GRAY));
        CompoundTag bp = SubcircuitChip.getBlueprint(stack);
        tooltip.add(Component.literal("Blocks: " + SubcircuitBlueprint.blockCount(bp))
                .withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.literal("Right-click ground to rebuild, or insert into a Subcircuit block.")
                .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
    }
}
