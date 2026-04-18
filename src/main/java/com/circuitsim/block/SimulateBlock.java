package com.circuitsim.block;

import com.circuitsim.simulation.CircuitExtractor;
import com.circuitsim.simulation.NetlistBuilder;
import com.circuitsim.simulation.NgSpiceRunner;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

public class SimulateBlock extends Block {

    public SimulateBlock(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                  InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;

        player.displayClientMessage(
                Component.literal("=== Circuit Simulation ===").withStyle(ChatFormatting.GOLD), false);

        CircuitExtractor.ExtractionResult extraction = CircuitExtractor.extract(level, pos);

        if (!extraction.success) {
            player.displayClientMessage(
                    Component.literal("Error: " + extraction.errorMessage).withStyle(ChatFormatting.RED), false);
            return InteractionResult.CONSUME;
        }

        String netlist = NetlistBuilder.buildNetlist(
                extraction.components, extraction.probes, extraction.currentProbes);

        if (netlist == null || netlist.isEmpty()) {
            player.displayClientMessage(
                    Component.literal("No circuit found!").withStyle(ChatFormatting.RED), false);
            return InteractionResult.CONSUME;
        }

        player.displayClientMessage(Component.literal("Netlist:").withStyle(ChatFormatting.YELLOW), false);
        for (String line : netlist.split("\n")) {
            player.displayClientMessage(
                    Component.literal("  " + line).withStyle(ChatFormatting.WHITE), false);
        }

        NgSpiceRunner.Result result = NgSpiceRunner.run(netlist);

        if (result.error != null) {
            player.displayClientMessage(
                    Component.literal("Simulation Error: " + result.error).withStyle(ChatFormatting.RED), false);
            return InteractionResult.CONSUME;
        }

        player.displayClientMessage(
                Component.literal("--- Results ---").withStyle(ChatFormatting.GREEN), false);
        for (String line : result.output) {
            player.displayClientMessage(
                    Component.literal(line).withStyle(ChatFormatting.GREEN), false);
        }

        // Voltage probes
        for (NetlistBuilder.ProbeInfo probe : extraction.probes) {
            String voltage = result.getNodeVoltage(probe.node);
            player.displayClientMessage(
                    Component.literal("Probe [" + probe.label + "] Node " + probe.node + ": " + voltage)
                            .withStyle(ChatFormatting.AQUA), false);
        }

        // Current probes
        int vmIdx = 1;
        for (NetlistBuilder.CurrentProbeInfo cp : extraction.currentProbes) {
            String current = result.getBranchCurrent("vm" + vmIdx);
            player.displayClientMessage(
                    Component.literal("Current Probe [" + cp.label + "]: " + current)
                            .withStyle(ChatFormatting.LIGHT_PURPLE), false);
            vmIdx++;
        }

        return InteractionResult.CONSUME;
    }
}