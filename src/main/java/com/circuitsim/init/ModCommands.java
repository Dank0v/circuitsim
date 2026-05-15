package com.circuitsim.init;

import com.circuitsim.CircuitSimMod;
import com.circuitsim.network.GraphDataPacket;
import com.circuitsim.network.ModMessages;
import com.circuitsim.network.SimulationOutputPacket;
import com.circuitsim.simulation.ParametricResultCache;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;

@Mod.EventBusSubscriber(modid = CircuitSimMod.MODID)
public class ModCommands {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
            Commands.literal("circuitsim")
                .then(Commands.literal("output")
                    .then(Commands.argument("sessionId", IntegerArgumentType.integer(0))
                        .executes(ctx -> {
                            int sessionId = IntegerArgumentType.getInteger(ctx, "sessionId");
                            ServerPlayer player;
                            try { player = ctx.getSource().getPlayerOrException(); }
                            catch (Exception e) { return 0; }
                            ParametricResultCache.ResultSet rs =
                                    ParametricResultCache.get(sessionId);
                            if (rs == null) {
                                player.displayClientMessage(
                                    Component.literal(
                                        "[CircuitSim] Session expired — run the simulation again.")
                                            .withStyle(net.minecraft.ChatFormatting.RED),
                                    false);
                                return 0;
                            }
                            if (rs.outputLines == null || rs.outputLines.isEmpty()) {
                                player.displayClientMessage(
                                    Component.literal(
                                        "[CircuitSim] No output text stored for this session.")
                                            .withStyle(net.minecraft.ChatFormatting.GRAY),
                                    false);
                                return 0;
                            }
                            ModMessages.sendToPlayer(player, new SimulationOutputPacket(
                                    rs.outputTitle == null || rs.outputTitle.isEmpty()
                                            ? "CircuitSim Output"
                                            : rs.outputTitle,
                                    rs.outputLines));
                            return 1;
                        })
                    )
                )
                .then(Commands.literal("graph")
                    .then(Commands.argument("sessionId", IntegerArgumentType.integer(0))
                        .then(Commands.argument("probeIndex", IntegerArgumentType.integer(0))
                            .executes(ctx -> {
                                int sessionId  = IntegerArgumentType.getInteger(ctx, "sessionId");
                                int probeIndex = IntegerArgumentType.getInteger(ctx, "probeIndex");

                                ServerPlayer player;
                                try { player = ctx.getSource().getPlayerOrException(); }
                                catch (Exception e) { return 0; }

                                ParametricResultCache.ResultSet rs =
                                        ParametricResultCache.get(sessionId);
                                if (rs == null) {
                                    player.displayClientMessage(
                                        Component.literal(
                                            "[CircuitSim] Session expired — run the simulation again.")
                                                .withStyle(net.minecraft.ChatFormatting.RED),
                                        false);
                                    return 0;
                                }

                                List<String> names = rs.getAllProbeNames();
                                if (probeIndex < 0 || probeIndex >= names.size()) {
                                    player.displayClientMessage(
                                        Component.literal("[CircuitSim] Invalid probe index.")
                                                .withStyle(net.minecraft.ChatFormatting.RED),
                                        false);
                                    return 0;
                                }

                                String       probeName   = names.get(probeIndex);
                                List<Double> probeValues = rs.getValues(probeName);
                                String       yUnit       = rs.getUnit(probeName);

                                ModMessages.sendToPlayer(player, new GraphDataPacket(
                                        probeName,
                                        rs.sweepComponentName,
                                        rs.sweepUnit,
                                        rs.sweepValues,
                                        probeValues,
                                        yUnit,
                                        rs.isLogFrequency));
                                return 1;
                            })
                        )
                    )
                )
        );
    }
}