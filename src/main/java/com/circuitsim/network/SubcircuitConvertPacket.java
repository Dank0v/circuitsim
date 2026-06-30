package com.circuitsim.network;

import com.circuitsim.block.ProbeBlock;
import com.circuitsim.block.SubcircuitConverterBlock;
import com.circuitsim.blockentity.ComponentBlockEntity;
import com.circuitsim.init.ModItems;
import com.circuitsim.simulation.CircuitExtractor;
import com.circuitsim.simulation.NetlistBuilder;
import com.circuitsim.subcircuit.SubcircuitBlueprint;
import com.circuitsim.subcircuit.SubcircuitChip;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Sent when the player clicks "Convert to Subcircuit" in the
 * {@link com.circuitsim.screen.SubcircuitConverterScreen}. The server extracts
 * the connected circuit, validates the probe-marked pins, builds the
 * {@code .subckt} definition + a rebuild blueprint, hands the player a
 * Subcircuit Chip, and removes the schematic (and the converter) from the world.
 */
public class SubcircuitConvertPacket {

    private final BlockPos pos;
    private final String   name;

    public SubcircuitConvertPacket(BlockPos pos, String name) {
        this.pos = pos;
        this.name = name == null ? "" : name;
    }

    public SubcircuitConvertPacket(FriendlyByteBuf buf) {
        this.pos = buf.readBlockPos();
        this.name = buf.readUtf(64);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeUtf(name, 64);
    }

    public static SubcircuitConvertPacket decode(FriendlyByteBuf buf) {
        return new SubcircuitConvertPacket(buf);
    }

    /** Holds a marked pin's order, sanitized net name, and the probe position. */
    private record MarkedPin(int order, String netName, BlockPos pos) {}

    public void handle(NetworkEvent.Context ctx) {
        ServerPlayer player = ctx.getSender();
        if (player == null) return;
        if (!(player.level() instanceof ServerLevel level)) return;
        if (!level.isLoaded(pos)) return;
        if (!(level.getBlockState(pos).getBlock() instanceof SubcircuitConverterBlock)) return;

        // 1. Collect the connected schematic (everything but the converter).
        Set<BlockPos> positions = CircuitExtractor.connectedCircuitBlocks(level, pos);
        if (positions.isEmpty()) {
            fail(player, "No connected circuit found next to the converter.");
            return;
        }

        // 2. Extract for the netlist.
        CircuitExtractor.ExtractionResult ex = CircuitExtractor.extract(level, pos);
        if (!ex.success) {
            fail(player, ex.errorMessage);
            return;
        }

        // 3. Gather probe-marked pins. A pin's net name is the sanitized probe
        //    label; the set of named (non-ground) nets validates each pin.
        Set<String> namedNets = new HashSet<>();
        for (NetlistBuilder.ProbeInfo p : ex.probes) {
            if (!p.netName.equals(Integer.toString(p.node))) namedNets.add(p.netName);
        }

        Map<String, MarkedPin> pins = new LinkedHashMap<>();
        for (BlockPos p : positions) {
            if (!(level.getBlockState(p).getBlock() instanceof ProbeBlock)) continue;
            if (!(level.getBlockEntity(p) instanceof ComponentBlockEntity be) || !be.isSubcktPin()) continue;
            String pinName = NetlistBuilder.sanitizeNodeName(be.getProbeLabel());
            if (pinName.isEmpty()) {
                fail(player, "A probe marked as a subcircuit pin has no usable label.");
                return;
            }
            if (!namedNets.contains(pinName)) {
                fail(player, "Subcircuit pin '" + pinName + "' is on ground or not connected.");
                return;
            }
            pins.putIfAbsent(pinName, new MarkedPin(be.getSubcktPinOrder(), pinName, p));
        }

        if (pins.isEmpty()) {
            fail(player, "Mark at least one probe as a subcircuit pin (in the probe's edit screen).");
            return;
        }
        if (pins.size() > 12) {
            fail(player, "Too many subcircuit pins (" + pins.size() + "); the block supports up to 12.");
            return;
        }

        // 4. Order pins: numbered first (ascending), unordered last, ties by position.
        List<MarkedPin> ordered = new ArrayList<>(pins.values());
        ordered.sort(Comparator
                .comparingInt((MarkedPin m) -> m.order() > 0 ? m.order() : Integer.MAX_VALUE)
                .thenComparingInt(m -> m.pos().getY())
                .thenComparingInt(m -> m.pos().getX())
                .thenComparingInt(m -> m.pos().getZ()));
        List<String> pinNames = new ArrayList<>();
        for (MarkedPin m : ordered) pinNames.add(m.netName());

        // 5. Name + definition.
        String subName = NetlistBuilder.sanitizeNodeName(name);
        if (subName.isEmpty()) subName = "subckt1";
        String def = NetlistBuilder.buildSubcktDefinition(subName, pinNames, ex.components, ex.probes);

        // 6. Internal device map for the OP projection. describeDevices assigns
        //    the exact same SPICE names buildSubcktDefinition just used, so each
        //    ref maps an internal device name to its block; we store it in the
        //    blueprint's local frame so the client can float the device's OP at
        //    the right cell of the mini-circuit.
        BlockPos min = SubcircuitBlueprint.minCorner(positions);
        List<SubcircuitChip.DeviceMapEntry> devMap = new ArrayList<>();
        for (NetlistBuilder.DeviceRef ref : NetlistBuilder.describeDevices(ex.components)) {
            BlockPos dp = ref.pos();
            devMap.add(new SubcircuitChip.DeviceMapEntry(
                    dp.getX() - min.getX(), dp.getY() - min.getY(), dp.getZ() - min.getZ(),
                    ref.spiceName(), ref.showClass(), ref.typeKey(), ref.label()));
        }

        // 7. Blueprint + chip.
        CompoundTag blueprint = SubcircuitBlueprint.capture(level, positions);
        ItemStack chip = new ItemStack(ModItems.SUBCIRCUIT_CHIP.get());
        SubcircuitChip.write(chip, subName, def, pinNames, blueprint, devMap);

        // 8. Remove the schematic and the converter.
        for (BlockPos p : positions) {
            if (!level.getBlockState(p).isAir()) {
                level.setBlock(p, Blocks.AIR.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_ALL);
            }
        }
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_ALL);

        // 9. Hand the chip to the player.
        if (!player.addItem(chip)) {
            player.drop(chip, false);
        }
        player.displayClientMessage(
                Component.literal("Converted to subcircuit '" + subName + "' ("
                        + pinNames.size() + " pins, " + SubcircuitBlueprint.blockCount(blueprint) + " blocks).")
                        .withStyle(ChatFormatting.GREEN), false);
    }

    private static void fail(ServerPlayer player, String message) {
        player.displayClientMessage(
                Component.literal("Conversion failed: " + message).withStyle(ChatFormatting.RED), false);
    }
}
