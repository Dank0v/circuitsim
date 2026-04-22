package com.circuitsim.network;

import com.circuitsim.screen.GraphScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;

public class GraphDataPacket {

    private final String probeLabel;
    private final String sweepComponentName;
    private final String sweepUnit;
    private final List<Double> sweepValues;
    private final List<Double> probeValues;
    private final boolean isVoltage;

    public GraphDataPacket(String probeLabel, String sweepComponentName, String sweepUnit,
                           List<Double> sweepValues, List<Double> probeValues, boolean isVoltage) {
        this.probeLabel          = probeLabel;
        this.sweepComponentName  = sweepComponentName;
        this.sweepUnit           = sweepUnit;
        this.sweepValues         = sweepValues;
        this.probeValues         = probeValues;
        this.isVoltage           = isVoltage;
    }

    public GraphDataPacket(FriendlyByteBuf buf) {
        this.probeLabel         = buf.readUtf(256);
        this.sweepComponentName = buf.readUtf(64);
        this.sweepUnit          = buf.readUtf(16);
        int count = buf.readInt();
        this.sweepValues = new ArrayList<>(count);
        for (int i = 0; i < count; i++) sweepValues.add(buf.readDouble());
        this.probeValues = new ArrayList<>(count);
        for (int i = 0; i < count; i++) probeValues.add(buf.readDouble());
        this.isVoltage = buf.readBoolean();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(probeLabel, 256);
        buf.writeUtf(sweepComponentName, 64);
        buf.writeUtf(sweepUnit, 16);
        buf.writeInt(sweepValues.size());
        for (double v : sweepValues) buf.writeDouble(v);
        for (double v : probeValues) buf.writeDouble(v);
        buf.writeBoolean(isVoltage);
    }

    public static GraphDataPacket decode(FriendlyByteBuf buf) {
        return new GraphDataPacket(buf);
    }

    public void handle(NetworkEvent.Context ctx) {
        ctx.enqueueWork(() ->
                Minecraft.getInstance().setScreen(
                        new GraphScreen(probeLabel, sweepComponentName, sweepUnit,
                                sweepValues, probeValues, isVoltage))
        );
        ctx.setPacketHandled(true);
    }
}