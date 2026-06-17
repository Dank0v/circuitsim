package com.circuitsim.subcircuit;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads/writes the subcircuit payload carried by a Subcircuit Chip item stack.
 * The payload lives under the {@value #ROOT} compound:
 * <ul>
 *   <li>{@code name} — sanitized SPICE subckt name</li>
 *   <li>{@code def} — full {@code .subckt … .ends} netlist text</li>
 *   <li>{@code pins} — ordered list of terminal (net) names</li>
 *   <li>{@code blueprint} — block layout for rebuilding the schematic
 *       (see {@link SubcircuitBlueprint})</li>
 * </ul>
 */
public final class SubcircuitChip {

    public static final String ROOT = "Subckt";
    private static final String KEY_NAME = "name";
    private static final String KEY_DEF = "def";
    private static final String KEY_PINS = "pins";
    private static final String KEY_BLUEPRINT = "blueprint";

    private SubcircuitChip() {}

    /** Writes a fresh payload compound onto a copy of {@code stack}. */
    public static void write(ItemStack stack, String name, String def,
                             List<String> pins, CompoundTag blueprint) {
        CompoundTag root = new CompoundTag();
        root.putString(KEY_NAME, name == null ? "" : name);
        root.putString(KEY_DEF, def == null ? "" : def);
        ListTag pinList = new ListTag();
        if (pins != null) for (String p : pins) pinList.add(StringTag.valueOf(p));
        root.put(KEY_PINS, pinList);
        if (blueprint != null) root.put(KEY_BLUEPRINT, blueprint);
        stack.getOrCreateTag().put(ROOT, root);
    }

    public static boolean isPresent(ItemStack stack) {
        return stack != null && stack.hasTag()
                && stack.getTag().contains(ROOT, Tag.TAG_COMPOUND)
                && !get(stack).getString(KEY_NAME).isEmpty();
    }

    private static CompoundTag get(ItemStack stack) {
        if (stack == null || !stack.hasTag()) return new CompoundTag();
        return stack.getTag().getCompound(ROOT);
    }

    public static String getName(ItemStack stack) {
        return get(stack).getString(KEY_NAME);
    }

    public static String getDef(ItemStack stack) {
        return get(stack).getString(KEY_DEF);
    }

    public static List<String> getPins(ItemStack stack) {
        List<String> out = new ArrayList<>();
        ListTag list = get(stack).getList(KEY_PINS, Tag.TAG_STRING);
        for (int i = 0; i < list.size(); i++) out.add(list.getString(i));
        return out;
    }

    public static int getPinCount(ItemStack stack) {
        return get(stack).getList(KEY_PINS, Tag.TAG_STRING).size();
    }

    /** Returns a copy of the blueprint tag, or {@code null} if absent. */
    public static CompoundTag getBlueprint(ItemStack stack) {
        CompoundTag root = get(stack);
        if (!root.contains(KEY_BLUEPRINT, Tag.TAG_COMPOUND)) return null;
        return root.getCompound(KEY_BLUEPRINT).copy();
    }
}
