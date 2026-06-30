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
 *   <li>{@code devmap} — internal device → blueprint-local position map, so an
 *       {@code .OP} run can float each internal device's operating point over
 *       the right spot in the floating mini-circuit projection</li>
 * </ul>
 */
public final class SubcircuitChip {

    public static final String ROOT = "Subckt";
    private static final String KEY_NAME = "name";
    private static final String KEY_DEF = "def";
    private static final String KEY_PINS = "pins";
    private static final String KEY_BLUEPRINT = "blueprint";
    private static final String KEY_DEVICEMAP = "devmap";
    // devmap entry keys
    private static final String DM_DX = "dx", DM_DY = "dy", DM_DZ = "dz";
    private static final String DM_SPICE = "spice", DM_CLS = "cls";
    private static final String DM_TYPE = "type", DM_LABEL = "label";

    private SubcircuitChip() {}

    /**
     * One internal device's identity for the OP projection: its position
     * relative to the blueprint min corner, the SPICE instance name it was
     * assigned inside the {@code .subckt} body (e.g. {@code "R1"}), the
     * {@code show} class letter, and the UI typeKey/label used to group it and
     * choose which params to annotate.
     */
    public record DeviceMapEntry(int dx, int dy, int dz, String spice, char cls,
                                 String typeKey, String label) {}

    /** Writes a fresh payload compound onto a copy of {@code stack}. */
    public static void write(ItemStack stack, String name, String def,
                             List<String> pins, CompoundTag blueprint,
                             List<DeviceMapEntry> devices) {
        CompoundTag root = new CompoundTag();
        root.putString(KEY_NAME, name == null ? "" : name);
        root.putString(KEY_DEF, def == null ? "" : def);
        ListTag pinList = new ListTag();
        if (pins != null) for (String p : pins) pinList.add(StringTag.valueOf(p));
        root.put(KEY_PINS, pinList);
        if (blueprint != null) root.put(KEY_BLUEPRINT, blueprint);
        if (devices != null && !devices.isEmpty()) {
            ListTag dm = new ListTag();
            for (DeviceMapEntry d : devices) {
                CompoundTag t = new CompoundTag();
                t.putInt(DM_DX, d.dx());
                t.putInt(DM_DY, d.dy());
                t.putInt(DM_DZ, d.dz());
                t.putString(DM_SPICE, d.spice() == null ? "" : d.spice());
                t.putString(DM_CLS, String.valueOf(d.cls()));
                t.putString(DM_TYPE, d.typeKey() == null ? "" : d.typeKey());
                t.putString(DM_LABEL, d.label() == null ? "" : d.label());
                dm.add(t);
            }
            root.put(KEY_DEVICEMAP, dm);
        }
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

    /**
     * Reads the internal device → position map. Empty for chips made before the
     * map was captured (they simply show no OP numbers until re-converted).
     */
    public static List<DeviceMapEntry> getDeviceMap(ItemStack stack) {
        List<DeviceMapEntry> out = new ArrayList<>();
        ListTag list = get(stack).getList(KEY_DEVICEMAP, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompound(i);
            String cls = t.getString(DM_CLS);
            out.add(new DeviceMapEntry(
                    t.getInt(DM_DX), t.getInt(DM_DY), t.getInt(DM_DZ),
                    t.getString(DM_SPICE), cls.isEmpty() ? '?' : cls.charAt(0),
                    t.getString(DM_TYPE), t.getString(DM_LABEL)));
        }
        return out;
    }
}
