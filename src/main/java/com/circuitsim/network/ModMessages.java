package com.circuitsim.network;

import com.circuitsim.CircuitSimMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.function.Supplier;

public class ModMessages {

    private static final String PROTOCOL_VERSION = "5";   // bumped for CommandsUpdatePacket

    public static final SimpleChannel INSTANCE = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(CircuitSimMod.MODID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    private static int packetId = 0;

    public static void register() {
        // ── server-bound ──────────────────────────────────────────────────────
        INSTANCE.messageBuilder(ComponentUpdatePacket.class, packetId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(ComponentUpdatePacket::encode)
                .decoder(ComponentUpdatePacket::decode)
                .consumerMainThread(ModMessages::handleComponentUpdate)
                .add();

        INSTANCE.messageBuilder(ParametricSimulatePacket.class, packetId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(ParametricSimulatePacket::encode)
                .decoder(ParametricSimulatePacket::decode)
                .consumerMainThread(ModMessages::handleParametricSimulate)
                .add();

        INSTANCE.messageBuilder(SimulatePacket.class, packetId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(SimulatePacket::encode)
                .decoder(SimulatePacket::decode)
                .consumerMainThread(ModMessages::handleSimulate)
                .add();

        INSTANCE.messageBuilder(CommandsUpdatePacket.class, packetId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(CommandsUpdatePacket::encode)
                .decoder(CommandsUpdatePacket::decode)
                .consumerMainThread(ModMessages::handleCommandsUpdate)
                .add();

        // ── client-bound ──────────────────────────────────────────────────────
        INSTANCE.messageBuilder(SimulationResultPacket.class, packetId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(SimulationResultPacket::encode)
                .decoder(SimulationResultPacket::decode)
                .consumerMainThread(ModMessages::handleSimulationResult)
                .add();

        INSTANCE.messageBuilder(GraphDataPacket.class, packetId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(GraphDataPacket::encode)
                .decoder(GraphDataPacket::decode)
                .consumerMainThread(ModMessages::handleGraphData)
                .add();

        INSTANCE.messageBuilder(SimulationOutputPacket.class, packetId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(SimulationOutputPacket::encode)
                .decoder(SimulationOutputPacket::decode)
                .consumerMainThread(ModMessages::handleSimulationOutput)
                .add();
    }

    // ── handlers ──────────────────────────────────────────────────────────────

    private static void handleComponentUpdate(ComponentUpdatePacket msg,
                                               Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> msg.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }

    private static void handleParametricSimulate(ParametricSimulatePacket msg,
                                                  Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> msg.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }

    private static void handleSimulate(SimulatePacket msg,
                                        Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> msg.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }

    private static void handleCommandsUpdate(CommandsUpdatePacket msg,
                                              Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> msg.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }

    private static void handleSimulationResult(SimulationResultPacket msg,
                                                Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> msg.handle());
        ctx.get().setPacketHandled(true);
    }

    private static void handleGraphData(GraphDataPacket msg,
                                         Supplier<NetworkEvent.Context> ctx) {
        msg.handle(ctx.get());
    }

    private static void handleSimulationOutput(SimulationOutputPacket msg,
                                                Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(msg::handle);
        ctx.get().setPacketHandled(true);
    }

    // ── send helpers ──────────────────────────────────────────────────────────

    public static <MSG> void sendToServer(MSG message) {
        INSTANCE.sendToServer(message);
    }

    public static <MSG> void sendToPlayer(ServerPlayer player, MSG message) {
        INSTANCE.send(PacketDistributor.PLAYER.with(() -> player), message);
    }
}