package com.circuitsim.network;

import com.circuitsim.blockentity.ComponentBlockEntity;
import com.circuitsim.simulation.CircuitExtractor;
import com.circuitsim.simulation.NetlistBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Server-bound: the measurement builder asking "which vectors exist in the
 * circuit around this Commands block?". The reply ({@link MeasSignalsPacket})
 * carries every referencable vector — probe voltages by their resolved net
 * name, current-probe branch currents, voltage-source branch currents, and the
 * names of existing {@code plot NAME = EXPR} directives — plus the analysis
 * the circuit's Simulate block is configured to run.
 */
public class MeasSignalsRequestPacket {

    private final BlockPos pos;

    public MeasSignalsRequestPacket(BlockPos pos) {
        this.pos = pos;
    }

    public MeasSignalsRequestPacket(FriendlyByteBuf buf) {
        this.pos = buf.readBlockPos();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
    }

    public static MeasSignalsRequestPacket decode(FriendlyByteBuf buf) {
        return new MeasSignalsRequestPacket(buf);
    }

    public void handle(NetworkEvent.Context ctx) {
        ServerPlayer player = ctx.getSender();
        if (player == null) return;
        Level level = player.level();
        if (!level.isLoaded(pos)) return;

        CircuitExtractor.ExtractionResult ex = CircuitExtractor.extract(level, pos);
        if (!ex.success) {
            ModMessages.sendToPlayer(player,
                    new MeasSignalsPacket(List.of(), List.of(), "", ex.errorMessage));
            return;
        }

        List<String> vectors = new ArrayList<>();
        List<String> labels  = new ArrayList<>();

        for (NetlistBuilder.ProbeInfo p : ex.probes) {
            vectors.add("v(" + p.netName + ")");
            labels.add(p.label);
        }
        // Current probes are netlisted as 0 V meters VM1, VM2, … in list order.
        for (int k = 0; k < ex.currentProbes.size(); k++) {
            vectors.add("i(vm" + (k + 1) + ")");
            labels.add(ex.currentProbes.get(k).label);
        }
        // Independent voltage sources carry a branch-current vector for free.
        for (NetlistBuilder.DeviceRef ref : NetlistBuilder.describeDevices(ex.components)) {
            if (ref.showClass() == 'v' && !ref.subckt()) {
                vectors.add("i(" + ref.spiceName().toLowerCase(Locale.ROOT) + ")");
                labels.add(ref.label() + " current");
            }
        }
        // Vectors already defined by `plot NAME = EXPR` directives.
        for (NetlistBuilder.UserPlot up : ex.userPlots) {
            vectors.add(up.name);
            labels.add("plot: " + up.label);
        }

        String simAnalysis = "";
        if (ex.simulatePos != null) {
            BlockEntity be = level.getBlockEntity(ex.simulatePos);
            if (be instanceof ComponentBlockEntity cbe) {
                simAnalysis = cbe.getSimAnalysis();
            }
        }

        ModMessages.sendToPlayer(player,
                new MeasSignalsPacket(vectors, labels, simAnalysis, ""));
    }
}
