package com.circuitsim.network;

import com.circuitsim.screen.SimulationOutputScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * PLAY_TO_CLIENT: ships the full ngspice simulation output (the same lines that
 * are written to the result book) to the player and opens
 * {@link SimulationOutputScreen} so the player can browse, scroll and search
 * the results in a dedicated GUI instead of reading them in chat.
 */
public class SimulationOutputPacket {

    private final String       title;
    private final List<String> lines;

    public SimulationOutputPacket(String title, List<String> lines) {
        this.title = title;
        this.lines = lines;
    }

    public SimulationOutputPacket(FriendlyByteBuf buf) {
        this.title = buf.readUtf(128);
        int n = buf.readInt();
        this.lines = new ArrayList<>(n);
        for (int i = 0; i < n; i++) this.lines.add(buf.readUtf(2048));
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(title, 128);
        buf.writeInt(lines.size());
        for (String l : lines) buf.writeUtf(l, 2048);
    }

    public static SimulationOutputPacket decode(FriendlyByteBuf buf) {
        return new SimulationOutputPacket(buf);
    }

    public void handle() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        mc.setScreen(new SimulationOutputScreen(title, lines));
    }
}
