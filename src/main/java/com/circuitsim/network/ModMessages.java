package com.circuitsim.network;

import com.circuitsim.CircuitSimMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.function.Supplier;

public class ModMessages {

    private static final String PROTOCOL_VERSION = "1";

    public static final SimpleChannel INSTANCE = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(CircuitSimMod.MODID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    private static int packetId = 0;

    public static void register() {
        INSTANCE.messageBuilder(ComponentUpdatePacket.class, packetId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(ComponentUpdatePacket::encode)
                .decoder(ComponentUpdatePacket::decode)
                .consumerMainThread(ModMessages::handleComponentUpdate)
                .add();

        INSTANCE.messageBuilder(SimulationResultPacket.class, packetId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(SimulationResultPacket::encode)
                .decoder(SimulationResultPacket::decode)
                .consumerMainThread(ModMessages::handleSimulationResult)
                .add();
    }

    private static void handleComponentUpdate(ComponentUpdatePacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> msg.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }

    private static void handleSimulationResult(SimulationResultPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> msg.handle());
        ctx.get().setPacketHandled(true);
    }

    public static <MSG> void sendToServer(MSG message) {
        INSTANCE.sendToServer(message);
    }
}