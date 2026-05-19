package com.circuitsim.network;

import com.circuitsim.screen.GraphScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Carries the full set of probe series for one simulation session to the
 * client so {@link GraphScreen} can let the player flip between plots (and
 * stack two at once) without round-tripping to the server for each change.
 *
 * <p>The X axis ({@code sweepValues}) is shared by every series — that's why
 * it's sent once. Each probe contributes its own Y-value list plus an
 * optional unit override (used by {@code plot NAME = EXPR} directives for
 * dB/phase/ratio plots).
 */
public class GraphDataPacket {

    private final String              sweepComponentName;
    private final String              sweepUnit;
    private final boolean             isLogFrequency;
    private final List<Double>        sweepValues;
    private final List<String>        probeNames;
    private final List<List<Double>>  probeData;
    private final List<String>        probeUnits;
    /** Index into {@code probeNames} of the probe to preselect in slot 1. */
    private final int                 initialIndex;

    public GraphDataPacket(String sweepComponentName, String sweepUnit, boolean isLogFrequency,
                           List<Double> sweepValues,
                           List<String> probeNames, List<List<Double>> probeData,
                           List<String> probeUnits, int initialIndex) {
        this.sweepComponentName = sweepComponentName;
        this.sweepUnit          = sweepUnit;
        this.isLogFrequency     = isLogFrequency;
        this.sweepValues        = sweepValues;
        this.probeNames         = probeNames;
        this.probeData          = probeData;
        this.probeUnits         = probeUnits;
        this.initialIndex       = initialIndex;
    }

    public GraphDataPacket(FriendlyByteBuf buf) {
        this.sweepComponentName = buf.readUtf(64);
        this.sweepUnit          = buf.readUtf(16);
        this.isLogFrequency     = buf.readBoolean();

        int xCount = buf.readInt();
        this.sweepValues = new ArrayList<>(xCount);
        for (int i = 0; i < xCount; i++) sweepValues.add(buf.readDouble());

        int nProbes = buf.readInt();
        this.probeNames = new ArrayList<>(nProbes);
        for (int i = 0; i < nProbes; i++) probeNames.add(buf.readUtf(256));
        this.probeData = new ArrayList<>(nProbes);
        for (int i = 0; i < nProbes; i++) {
            int len = buf.readInt();
            List<Double> series = new ArrayList<>(len);
            for (int j = 0; j < len; j++) series.add(buf.readDouble());
            probeData.add(series);
        }
        this.probeUnits = new ArrayList<>(nProbes);
        for (int i = 0; i < nProbes; i++) probeUnits.add(buf.readUtf(16));

        this.initialIndex = buf.readInt();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(sweepComponentName, 64);
        buf.writeUtf(sweepUnit,          16);
        buf.writeBoolean(isLogFrequency);

        buf.writeInt(sweepValues.size());
        for (double v : sweepValues) buf.writeDouble(v);

        buf.writeInt(probeNames.size());
        for (String n : probeNames) buf.writeUtf(n, 256);
        for (List<Double> series : probeData) {
            buf.writeInt(series.size());
            for (double v : series) buf.writeDouble(v);
        }
        for (String u : probeUnits) buf.writeUtf(u, 16);

        buf.writeInt(initialIndex);
    }

    public static GraphDataPacket decode(FriendlyByteBuf buf) {
        return new GraphDataPacket(buf);
    }

    public void handle(NetworkEvent.Context ctx) {
        ctx.enqueueWork(() ->
                Minecraft.getInstance().setScreen(
                        new GraphScreen(sweepComponentName, sweepUnit, isLogFrequency,
                                sweepValues, probeNames, probeData, probeUnits, initialIndex))
        );
        ctx.setPacketHandled(true);
    }
}
