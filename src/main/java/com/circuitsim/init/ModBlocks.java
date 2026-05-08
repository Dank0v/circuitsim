package com.circuitsim.init;

import com.circuitsim.CircuitSimMod;
import com.circuitsim.block.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModBlocks {

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, CircuitSimMod.MODID);

    public static final RegistryObject<Block> RESISTOR = BLOCKS.register("resistor",
            () -> new ResistorBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_RED).strength(2.0f).pushReaction(PushReaction.BLOCK)));

    public static final RegistryObject<Block> CAPACITOR = BLOCKS.register("capacitor",
            () -> new CapacitorBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_BLUE).strength(2.0f).pushReaction(PushReaction.BLOCK)));

    public static final RegistryObject<Block> INDUCTOR = BLOCKS.register("inductor",
            () -> new InductorBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_GREEN).strength(2.0f).pushReaction(PushReaction.BLOCK)));

    public static final RegistryObject<Block> VOLTAGE_SOURCE = BLOCKS.register("voltage_source",
            () -> new VoltageSourceBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_YELLOW).strength(2.0f).pushReaction(PushReaction.BLOCK)));

    public static final RegistryObject<Block> VOLTAGE_SOURCE_SIN = BLOCKS.register("voltage_source_sin",
            () -> new VoltageSourceSinBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_CYAN).strength(2.0f).pushReaction(PushReaction.BLOCK)));

    public static final RegistryObject<Block> CURRENT_SOURCE = BLOCKS.register("current_source",
            () -> new CurrentSourceBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_PURPLE).strength(2.0f).pushReaction(PushReaction.BLOCK)));

    public static final RegistryObject<Block> DIODE = BLOCKS.register("diode",
            () -> new DiodeBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_ORANGE).strength(2.0f).pushReaction(PushReaction.BLOCK)));

    public static final RegistryObject<Block> WIRE = BLOCKS.register("wire",
            () -> new WireBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_GRAY).strength(1.0f).pushReaction(PushReaction.BLOCK)));

    public static final RegistryObject<Block> GROUND = BLOCKS.register("ground",
            () -> new GroundBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_BLACK).strength(2.0f).pushReaction(PushReaction.BLOCK)));

    public static final RegistryObject<Block> PROBE = BLOCKS.register("probe",
            () -> new ProbeBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_CYAN).strength(2.0f).pushReaction(PushReaction.BLOCK)));

    public static final RegistryObject<Block> CURRENT_PROBE = BLOCKS.register("current_probe",
            () -> new CurrentProbeBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_LIGHT_BLUE).strength(2.0f).pushReaction(PushReaction.BLOCK)));

    public static final RegistryObject<Block> SIMULATE = BLOCKS.register("simulate",
            () -> new SimulateBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_LIGHT_GREEN).strength(2.0f).pushReaction(PushReaction.BLOCK)));

    public static final RegistryObject<Block> PARAMETRIC = BLOCKS.register("parametric",
            () -> new ParametricBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_PINK).strength(2.0f).pushReaction(PushReaction.BLOCK)));

    public static final RegistryObject<Block> IC_RESISTOR = BLOCKS.register("ic_resistor",
            () -> new IcResistorBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.TERRACOTTA_ORANGE).strength(2.0f).pushReaction(PushReaction.BLOCK)));

    public static void register(IEventBus eventBus) {
        BLOCKS.register(eventBus);
    }
}