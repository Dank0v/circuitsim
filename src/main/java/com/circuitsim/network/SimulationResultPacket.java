package com.circuitsim.network;

import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class SimulationResultPacket {

    private final List<String> results;

    public SimulationResultPacket(List<String> results) {
        this.results = results;
    }

    public SimulationResultPacket(FriendlyByteBuf buf) {
        int count = buf.readInt();
        this.results = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            this.results.add(buf.readUtf(1024));
        }
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeInt(results.size());
        for (String s : results) {
            buf.writeUtf(s, 1024);
        }
    }

    public static SimulationResultPacket decode(FriendlyByteBuf buf) {
        return new SimulationResultPacket(buf);
    }

    public void handle() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            for (String line : results) {
                mc.player.displayClientMessage(Component.literal(line), false);
            }
        }
    }
}