package com.circuitsim.network;

import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * Client-bound reply to {@link MeasSignalsRequestPacket}: the pickable vectors
 * ({@code vectors[i]} / {@code labels[i]} are parallel), the analysis the
 * circuit's Simulate block is set to (empty when there is none), and an error
 * message when extraction failed.
 */
public class MeasSignalsPacket {

    public final List<String> vectors;
    public final List<String> labels;
    public final String simAnalysis;
    public final String error;

    public MeasSignalsPacket(List<String> vectors, List<String> labels,
                             String simAnalysis, String error) {
        this.vectors     = vectors;
        this.labels      = labels;
        this.simAnalysis = simAnalysis == null ? "" : simAnalysis;
        this.error       = error == null ? "" : error;
    }

    public MeasSignalsPacket(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        vectors = new ArrayList<>(n);
        labels  = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            vectors.add(buf.readUtf(256));
            labels.add(buf.readUtf(256));
        }
        simAnalysis = buf.readUtf(32);
        error       = buf.readUtf(512);
    }

    public void encode(FriendlyByteBuf buf) {
        int n = Math.min(vectors.size(), labels.size());
        buf.writeVarInt(n);
        for (int i = 0; i < n; i++) {
            buf.writeUtf(vectors.get(i), 256);
            buf.writeUtf(labels.get(i), 256);
        }
        buf.writeUtf(simAnalysis, 32);
        buf.writeUtf(error, 512);
    }

    public static MeasSignalsPacket decode(FriendlyByteBuf buf) {
        return new MeasSignalsPacket(buf);
    }

    public void handle() {
        com.circuitsim.screen.MeasBuilderScreen.onSignals(this);
    }
}
