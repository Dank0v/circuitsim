package com.circuitsim.network;

import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * Client-bound reply to {@link MeasTestPacket}: the harvested
 * {@code name = value} lines (or an error), displayed in the measurement
 * builder's results area.
 */
public class MeasTestResultPacket {

    public final List<String> lines;

    public MeasTestResultPacket(List<String> lines) {
        this.lines = lines;
    }

    public MeasTestResultPacket(FriendlyByteBuf buf) {
        int n = Math.min(buf.readVarInt(), 32);
        lines = new ArrayList<>(n);
        for (int i = 0; i < n; i++) lines.add(buf.readUtf(512));
    }

    public void encode(FriendlyByteBuf buf) {
        int n = Math.min(lines.size(), 32);
        buf.writeVarInt(n);
        for (int i = 0; i < n; i++) buf.writeUtf(lines.get(i), 512);
    }

    public static MeasTestResultPacket decode(FriendlyByteBuf buf) {
        return new MeasTestResultPacket(buf);
    }

    public void handle() {
        com.circuitsim.screen.MeasBuilderScreen.onTestResult(lines);
    }
}
